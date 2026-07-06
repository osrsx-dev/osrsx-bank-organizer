# osrsx-bank-organizer

A bank organiser plugin for [osrsx](https://github.com/osrsx/osrsx-client). Files your whole bank into
smart category tabs — or insertion-sorts it — by humanised drag-and-drop, then keeps it tidy as it changes.

This is also the reference example of an **extracted osrsx plugin**: it started life as a built-in and now
lives in its own repo, built against the published `io.osrsx:osrsx-api` SDK with nothing but the
`io.osrsx.plugin` Gradle plugin.

## What it does

Three modes (set in the plugin's config):

- **Auto tabs** — files items into one bank tab per smart category (Magic & Ranged, Jewellery, Weapons &
  Tools, Armour, Consumables, Herblore, Resources, Misc). Categories are authoritative — they come from
  the OSRS-Wiki-sourced `item_cats` table baked into `osrsx.db`, not name guesswork. Self-correcting: each
  loop it works out where every item *is* vs where it *belongs* and fixes one, so a stray item is just
  re-filed next pass.
- **Sort all** — insertion-sorts the entire bank by a chosen key (category, total/each value, quantity,
  name, or item id), ascending or descending.
- **Clear tabs** — empties every tab back into the main view.

Every move is deterministic and self-checking: it locates the item by id, brings it to the viewport centre
via the bank's own scrollbar math, confirms it's actually visible, *then* drags — so it never grabs a
scrolled-out or wrong widget, and can't make a stray drag or spawn a stray tab. Run **Continuous** to keep
the bank organised as it changes; **Preview only** logs the planned layout without moving anything.

Antiban knobs: randomised inter-drag delay, an optional per-run drag cap, and input-lock while organising.

## Install (in-game marketplace)

Open the **Marketplace** panel in the client, search **Bank Organizer**, and click **Install**. The client
only offers versions whose SDK range (`Osrsx-Api-Range`) supports your build.

## Build it yourself

```
./gradlew build          # produces build/libs/osrsx-bank-organizer-<version>.jar
./gradlew installPlugin  # copies it into ~/.osrsx/plugins (the client hot-reloads it)
```

The entire build is `apply plugin: 'io.osrsx.plugin'` + the `osrsxPlugin { }` block in
[`build.gradle`](build.gradle). Applying the plugin pins the JDK-11 toolchain (auto-provisioned by foojay —
you don't need JDK 11 installed), wires the anonymous osrsx-maven SDK repo, stamps the jar manifest, and
generates `plugin.yaml` from that block.

## Dev loop (edit → save → live reload)

1. Launch the client once (from an osrsx checkout: `./gradlew :osrsx-core:runClient`).
2. Here: `./gradlew -t installPlugin` — rebuilds + reinstalls on every save; the client's directory watcher
   hot-reloads it live. Enable it from the in-game Plugin Manager.

## Publish an update

```
./gradlew publishPlugin
```

Collects the version + changelog, pushes/tags the repo, and opens the submission issue on
`osrsx/osrsx-central`. The registry CI builds and publishes it; the new version then appears in the
marketplace with its changelog.

## License

GPL-3.0 (matching the osrsx client).
