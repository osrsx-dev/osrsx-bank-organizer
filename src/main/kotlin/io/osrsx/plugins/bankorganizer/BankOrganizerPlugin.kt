package io.osrsx.plugins.bankorganizer

import io.osrsx.api.ui.Overlays
import io.osrsx.plugin.PluginSettings
import io.osrsx.plugin.eq
import io.osrsx.plugin.Gfx2D
import io.osrsx.script.ScriptPlugin
import io.osrsx.script.stagedScript
import io.osrsx.api.ui.Widget

/**
 * Bank organiser. Files the whole bank into smart category tabs (or insertion-sorts it), by
 * humanised drag-and-drop. Categories are authoritative — `ctx.items().category(id)` reads the OSRS
 * Wiki-sourced `item_cats` table baked into osrsx.db, not name guesswork.
 *
 * Two rules make bank reorganising reliable (both via the engine's bank primitives):
 *  1. Operate from "View all items" — a tab view shows a SUBSET, so a global slot no longer maps to the
 *     on-screen widget and every move misfires.
 *  2. Use INSERT rearrange mode for ordering — a swap can bounce a just-placed item back out (thrash).
 *
 * Every move is deterministic and self-checking: it locates the item by id, brings it to the viewport
 * centre via the bank's own scrollbar math ([io.osrsx.api.items.Bank.bringItemIntoView]), confirms it's
 * actually visible, THEN drags. It never grabs a scrolled-out / wrong widget, so it can't do a stray
 * drag or spawn a stray tab. "Auto tabs" is self-correcting: each loop it works out where every item
 * IS (tabs occupy the leading slots, read via tab sizes) vs where it BELONGS, and fixes one item — so
 * a stray item is just re-filed next pass. Run "Continuous" to keep the bank tidy as it changes.
 */
class BankOrganizerPlugin : ScriptPlugin() {

    object Config : PluginSettings("bankorganizer") {
        var mode by enumItem(
            "mode", "Mode", "Auto tabs", listOf("Auto tabs", "Sort all", "Clear tabs"),
            "'Auto tabs' files items into a bank tab per category. 'Sort all' reorders every item by the " +
                    "sort key. 'Clear tabs' empties every tab back into the main view."
        )
        // The sort key/direction only apply to 'Sort all'; min-items-per-tab only to 'Auto tabs' — so each
        // is shown only in the mode it affects (the panel reacts live as Mode changes).
        var sortBy by enumItem(
            "sortBy", "Sort by", "Category",
            listOf("Category", "Value (total)", "Value (each)", "Quantity", "Name (A-Z)", "Item ID"),
            "Key for 'Sort all'. 'Category' groups by smart category, then by value within each group.",
            visibleIf = eq("mode", "Sort all")
        )
        var order by enumItem(
            "order", "Direction", "Descending", listOf("Descending", "Ascending"),
            "Within the key (and within categories): high-to-low / Z-A, or the reverse.",
            visibleIf = eq("mode", "Sort all")
        )
        var minTabItems by intItem(
            "minTabItems", "Min items per tab", 3, min = 1, max = 20, section = "Auto tabs",
            description = "Categories with fewer than this many items stay in the main view instead of getting a tab.",
            visibleIf = eq("mode", "Auto tabs")
        )
        var autoOpen by boolItem(
            "autoOpen", "Auto-open bank", true, section = "Antiban",
            description = "Walk to and open the nearest bank if it isn't already open."
        )
        var minDelay by intItem(
            "minDelay", "Min delay (ms)", 260, min = 60, max = 4000, section = "Antiban",
            description = "Lower bound of the randomised pause between drags."
        )
        var maxDelay by intItem(
            "maxDelay", "Max delay (ms)", 620, min = 60, max = 6000, section = "Antiban",
            description = "Upper bound of the randomised pause between drags."
        )
        var maxOps by intItem(
            "maxOps", "Max drags / run", 0, min = 0, max = 5000, section = "Antiban",
            description = "Safety cap on total drags this run. 0 = unlimited."
        )
        var lockInput by boolItem(
            "lockInput", "Lock user input", true, section = "Antiban",
            description = "Ignore physical mouse/keyboard while organising. Released automatically on stop."
        )
        var dryRun by boolItem(
            "dryRun", "Preview only (no drags)", false, section = "Scope",
            description = "Log the planned tab layout / order without moving anything."
        )
        var runMode by enumItem(
            "runMode", "Run mode", "Once", listOf("Once", "Continuous"), section = "Scope",
            description = "'Once' stops when done; 'Continuous' keeps the bank organised as it changes."
        )
    }

    override fun settings() = Config

    // The broad buckets, in display/tab order; "Misc" (last) is the catch-all. Matches the item_cats table:
    // "Weapons & Tools" is one weapon-slot tab (weapons + pickaxes/axes/hammers), seeds live with Herblore.
    private val cats = listOf(
        "Magic & Ranged",
        "Jewellery",
        "Weapons & Tools",
        "Armour",
        "Consumables",
        "Herblore",
        "Resources",
        "Misc"
    )
    private val catIx = cats.withIndex().associate { (i, n) -> n to i }

    // An item's category and name never change, but `items.category(id)` is a per-item catalogue lookup
    // (~20 ms each). readBank() runs EVERY loop over the whole bank, so re-resolving all ~640 items each
    // time cost ~13 s per move — the real reason a move took 20 s. Cache both by item id (computed once).
    private val catByItem = HashMap<Int, Int>()
    private val nameByItem = HashMap<Int, String>()

    private fun catOf(id: Int): Int = catByItem.getOrPut(id) { catIx[items.category(id)] ?: cats.lastIndex }
    private fun nameOf(id: Int): String = nameByItem.getOrPut(id) { items.name(id) ?: "?" }

    private class BankItem(val slot: Int, val id: Int, val qty: Int, val name: String, val cat: Int, val value: Long) {
        var tab = 0   // the tab this item is currently IN (0 = main view)
        var want = 0  // the tab this item BELONGS in
    }

    // Named orgStatus (not `status`): ScriptPlugin publishes a `status` member the old name would hide.
    @Volatile
    private var orgStatus = "idle"

    @Volatile
    private var ops = 0

    @Volatile
    private var planned = 0

    @Volatile
    private var scanned = 0
    private var done = false

    override fun onScriptStart() {
        ops = 0; planned = 0; scanned = 0; done = false; orgStatus = "started"
    }

    override fun onScriptStop() {
        input.unlock(); orgStatus = "stopped"
    }

    // ONE plugin model, coarse-intent conversion: this plugin is action-dominated (a stateful drag
    // machine whose reads follow its own writes with settle waits), so there is no exactness win in
    // splitting its planner out — the stage gate decides on the client thread (the script pump) and the
    // whole organize pass runs as ONE act on the actuator drain, its returned delay (park) pacing
    // decisions exactly as the old loop did. Note: the op-cap's negative return maps to requestStop() —
    // the plugin visibly disables at the cap instead of idling enabled (park would coerce it to 0).
    override fun script() = stagedScript<Unit>("bank-organizer") {
        readState { }
        isComplete { false } // cyclic — 'Once' mode / the op cap end the run via requestStop()
        stage("organize", { login.isLoggedIn() }) {
            val d = act("organize") { organizePass() }
            if (d < 0) { this@BankOrganizerPlugin.requestStop(); park(600) } else park(d)
        }
    }.toScript()

    private fun organizePass(): Long {
        if (Config.lockInput) input.lock() else input.unlock()
        if (!ensureBank()) return 800
        if (!bank.viewAllItems()) {
            orgStatus = "switching to View all"; return 600
        }
        if (capped()) {
            orgStatus = "op cap reached"; return -1
        }
        return when (Config.mode) {
            "Clear tabs" -> loopClearTabs()
            "Sort all" -> loopSortAll()
            else -> loopAutoTabs()
        }
    }

    // ---- shared helpers ----

    // `bank` comes from PluginApi (== ctx.bank()).
    private fun capped() = Config.maxOps > 0 && ops >= Config.maxOps
    private fun delay(): Long = io.osrsx.util.Rng.uniform(Config.minDelay, maxOf(Config.minDelay, Config.maxDelay))

    private fun ensureBank(): Boolean {
        if (bank.isOpen()) return true
        if (!Config.autoOpen) {
            orgStatus = "open the bank"; return false
        }
        orgStatus = "opening bank"
        bank.open()
        io.osrsx.util.Wait.until(3000) { bank.isOpen() }
        return bank.isOpen()
    }

    private fun valueOf(id: Int, qty: Int): Long = when (Config.sortBy) {
        "Value (each)" -> prices.price(id).toLong()
        "Quantity" -> qty.toLong()
        "Item ID" -> id.toLong()
        else -> prices.price(id).toLong() * qty
    }

    /** The bank's non-empty stacks (compacted, in display order) — the SDK bank container, whose order
     *  lines up with the View-all item grid's [Widget.dynamicChildren] used by [slotWidget]. */
    private fun readBank(): List<BankItem> = bank.items().mapIndexed { i, it ->
        BankItem(i, it.id, it.quantity, nameOf(it.id), catOf(it.id), valueOf(it.id, it.quantity))
    }

    // ---- bank tab-bar widget reads (group 12, child 10) — via the SDK Widget tree ----

    private fun tabBar(): Widget? = widgets.get(GRP, TABBAR)

    private fun tabCount(): Int =
        tabBar()?.dynamicChildren?.count { k -> k.actions.any { it == "View tab" } } ?: 0

    /** The p-th tab icon (1-based) left-to-right. */
    private fun tabIconAt(p: Int): Widget? = tabBar()?.dynamicChildren
        ?.filter { k -> k.actions.any { it == "View tab" } }
        ?.sortedBy { it.bounds?.x ?: 0 }?.getOrNull(p - 1)

    /** The tab-bar button offering [action] ("New tab" / "View all items"). */
    private fun tabButton(action: String): Widget? =
        tabBar()?.dynamicChildren?.firstOrNull { k -> k.actions.any { it == action } }

    /**
     * THE move primitive — drag the item with bank id [id] onto [dest] (a tab icon / "New tab" / "View
     * all items" button, all permanently on-screen in the tab bar). Deterministic: switch to View all,
     * bring the item to the viewport centre by the bank's scrollbar math, confirm it's visible, re-locate
     * it by id, THEN drag. If it can't be surfaced we return WITHOUT dragging — so no stray drag is ever
     * made and no stray tab is ever spawned.
     */
    private fun moveItem(id: Int, dest: Widget?): Boolean {
        if (dest == null) return false
        if (!bank.viewAllItems()) return false
        if (!bank.bringItemIntoView(id)) return false
        if (!bank.isItemVisible(id)) return false
        val w = bank.itemWidget(id) ?: return false
        // Snapshot the tab layout BEFORE the drag, then wait only until it CHANGES (the move has registered)
        // — not until it stops changing. The old settle() slept in coarse 250 ms steps and, because the tab
        // varbits lag the drag by a tick, usually saw "no change yet" on its first poll and returned early,
        // BEFORE the move registered — leaving the next readBank() to act on stale tab sizes (thrash). Waiting
        // for the change is both faster (early-exit the instant it lands) and correct (next loop sees it).
        val before = tabSnapshot()
        val ok = widgets.drag(w, dest)
        if (ok) io.osrsx.util.Wait.until(SETTLE_TIMEOUT_MS) { tabSnapshot() != before }
        return ok
    }

    private fun tabSnapshot(): Long {
        val n = tabCount()
        var s = n.toLong()
        for (t in 1..n) s = s * 263 + bank.tabSize(t)
        return s
    }

    // ---- Auto tabs (self-correcting) ----

    private fun loopAutoTabs(): Long {
        val list = readBank()
        // Categories with enough items get a tab (reserve the last tab for the "Other" catch-all).
        val counts = IntArray(cats.size)
        for (it in list) counts[it.cat]++
        val plan = ArrayList<Int>()
        for (ci in 0 until cats.lastIndex) if (counts[ci] >= Config.minTabItems) plan.add(ci)
        while (plan.size > MAX_TABS - 1) plan.removeAt(plan.size - 1)
        val catTab = HashMap<Int, Int>()
        plan.forEachIndexed { p, ci -> catTab[ci] = p + 1 }
        val otherTab = plan.size + 1
        fun tabName(p: Int) = if (p <= plan.size) cats[plan[p - 1]] else "Other"

        var needOther = false
        for (it in list) {
            it.want = catTab[it.cat] ?: otherTab; if (it.want == otherTab) needOther = true
        }
        val totalTabs = if (needOther) otherTab else plan.size
        scanned = totalTabs
        if (totalTabs == 0) {
            orgStatus = "nothing to tab"; return onceOr(3000)
        }

        if (Config.dryRun) {
            log.i("bank [dry]: $totalTabs tabs:")
            for (p in 1..totalTabs) log.i("  tab $p: ${tabName(p)} = ${list.count { it.want == p }} items")
            return -1
        }

        val nTabs = tabCount()
        val sizes = IntArray(nTabs + 1) { if (it in 1..nTabs) bank.tabSize(it) else 0 }
        fun tabOf(s: Int): Int {
            var a = 0; for (t in 1..nTabs) {
                if (s in a until a + sizes[t]) return t; a += sizes[t]
            }; return 0
        }
        for (it in list) it.tab = tabOf(it.slot)

        planned = list.count { it.tab != it.want }

        // 1. Clean up any STRAY tab beyond the ones we want (a misfire could have spawned one): empty it.
        list.firstOrNull { it.tab > totalTabs }?.let { stray ->
            orgStatus = "removing stray tab"; if (moveItem(stray.id, tabButton("View all items"))) ops++
            done = false; return capOr(delay())
        }

        // 2. Build / repair tabs left-to-right, one corrective drag per loop.
        for (p in 1..totalTabs) {
            if (p > nTabs) {
                if (p == nTabs + 1) {
                    list.firstOrNull { it.want == p && it.tab == 0 }?.let { seed ->
                        orgStatus = "new tab: ${tabName(p)}"; if (moveItem(seed.id, tabButton("New tab"))) ops++
                        done = false; return capOr(delay())
                    }
                    list.firstOrNull { it.want == p && it.tab != 0 }?.let { stuck ->  // all in wrong tabs → free one
                        orgStatus = "freeing ${tabName(p)}"; if (moveItem(stuck.id, tabButton("View all items"))) ops++
                        done = false; return capOr(delay())
                    }
                }
            } else {
                list.firstOrNull { it.want == p && it.tab != p }?.let { miss ->
                    orgStatus = "filing ${tabName(p)}"; if (moveItem(miss.id, tabIconAt(p))) ops++
                    done = false; return capOr(delay())
                }
                list.firstOrNull { it.tab == p && it.want != p }?.let { bad ->
                    orgStatus = "evicting from ${tabName(p)}"; if (moveItem(bad.id, tabButton("View all items"))) ops++
                    done = false; return capOr(delay())
                }
            }
        }

        if (!done) {
            done = true; log.i("bank: tabs organised ($totalTabs tabs)")
        }
        orgStatus = "tabs tidy ($totalTabs tabs)"
        return onceOr(4000)
    }

    // ---- Sort all (insertion order over the whole bank) ----

    private fun loopSortAll(): Long {
        bank.setInsertMode(true)
        val cur = readBank()
        scanned = cur.size
        val want = cur.sortedWith(Comparator(::before))

        var fix = -1; planned = 0
        for (i in cur.indices) if (cur[i].id != want[i].id) {
            planned++; if (fix < 0) fix = i
        }
        if (fix < 0) {
            if (!done) {
                done = true; log.i("bank: sorted ${scanned} items by ${Config.sortBy}")
            }
            orgStatus = "sorted ($scanned)"; return onceOr(2500)
        }
        done = false

        var target = -1
        for (k in fix + 1 until cur.size) if (cur[k].id == want[fix].id) {
            target = k; break
        }
        if (target < 0) {
            orgStatus = "plan stalled"; return 1000
        }

        if (Config.dryRun) {
            log.i("bank [dry]: $planned inserts; ${cur[target].name} -> slot ${cur[fix].slot}"); return -1
        }

        orgStatus = "sorting: $planned left"
        // INSERT the desired item (at `target`) into position `fix`; scroll-aware drag reaches off-screen
        // ends and shifts the rest along (Insert never displaces a just-placed item — no thrash).
        if (widgets.dragScroll(slotWidget(cur[target].slot), slotWidget(cur[fix].slot), viewport())) ops++
        return capOr(delay())
    }

    /** The on-screen widget for compacted bank [slot] (View-all grid), or null if not rendered. */
    private fun slotWidget(slot: Int): Widget? = widgets.get(GRP, ITEMS)?.dynamicChildren?.getOrNull(slot)

    private fun viewport(): Widget? = widgets.get(GRP, ITEMS)

    /** Comparator over the sort key: negative if [a] should come before [b]. */
    private fun before(a: BankItem, b: BankItem): Int {
        val asc = Config.order == "Ascending"
        when (Config.sortBy) {
            "Category" -> {
                if (a.cat != b.cat) return a.cat - b.cat
                if (a.value != b.value) return b.value.compareTo(a.value)       // high value first within a category
                return a.name.lowercase().compareTo(b.name.lowercase())
            }

            "Name (A-Z)" -> {
                val c = a.name.lowercase().compareTo(b.name.lowercase())
                if (c == 0) return a.slot - b.slot
                return if (asc) c else -c
            }

            else -> {
                if (a.value == b.value) return a.slot - b.slot
                return if (asc) a.value.compareTo(b.value) else b.value.compareTo(a.value)
            }
        }
    }

    // ---- Clear tabs ----

    private fun loopClearTabs(): Long {
        val n = tabCount()
        scanned = n
        if (n == 0) {
            if (!done) {
                done = true; log.i("bank: all tabs cleared")
            }
            orgStatus = "no tabs"; return onceOr(3000)
        }
        done = false; orgStatus = "$n tab(s) left"
        // The first item of the leftmost tab, dragged onto "View all items", leaves that tab; when the
        // tab empties it vanishes. We locate that item by id (from the leading slot range) and move it.
        val list = readBank()
        val firstTabSize = bank.tabSize(1)
        val victim = list.firstOrNull { it.slot < firstTabSize } ?: list.firstOrNull()
        if (victim != null) {
            if (moveItem(victim.id, tabButton("View all items"))) ops++
        }
        return capOr(delay())
    }

    // ---- small util ----

    private fun onceOr(continuousMs: Long): Long = if (Config.runMode == "Once") -1 else continuousMs
    private fun capOr(ms: Long): Long = if (capped()) {
        orgStatus = "op cap reached"; -1
    } else ms

    override fun onPanel(gfx: Gfx2D) {
        gfx.text("Mode: ${Config.mode}")
        gfx.text("Status: $orgStatus")
        gfx.text("Misplaced: $planned")
        gfx.text("Moves done: $ops")
        if (Config.dryRun) gfx.textColored(ACCENT, "PREVIEW — no drags")
    }


    private companion object {
        const val GRP = 12       // InterfaceID.Bankmain
        const val ITEMS = 12     // the item container component
        const val TABBAR = 10    // the tab bar component
        const val MAX_TABS = 9

        /** Accent colour for the preview banner (the old PanelBuilder.ACCENT, packed 0xAARRGGBB). */
        const val ACCENT = 0xFF5B8CFF.toInt()

        /** How long to wait for a drag to register in the tab varbits before proceeding — early-exits the
         *  instant the tab layout changes, so this is only the ceiling for a move that didn't take. */
        const val SETTLE_TIMEOUT_MS = 1500L
    }
}
