# Global frontiers (FPBCraft Diorama fork)

A JourneyMap-free, server-authoritative territory overlay integrated directly into Diorama.
This is intentionally separate from **OPAC claims**, which are real protection/claim
permissions managed and enforced by the Open Parties and Claims mod.

## Multiplayer setup

- Install the **same built Diorama fork JAR** on the NeoForge 1.21.1 dedicated
  server and on each player's client. No JourneyMap or MapFrontiers installation.
- The server saves all global territories at:
  `<world>/data/diorama-frontiers.json`.
- Every player with Diorama receives the same global frontier list on joining,
  even if `share-map=false` in Diorama's shared-terrain configuration.
- A player with vanilla **OP level 2 or higher** can create, change, rename
  or delete frontiers. The server checks that permission on every request;
  client-only code or forged packets cannot bypass it.
- Other players can view frontiers and the current territory name on the
  fullscreen map/minimap, but cannot use the edit toggle.
- On integrated worlds or LAN games, the player also needs operator-equivalent permissions (for example, cheats enabled for the host). LAN guests are not implicitly authorized.
- If the connected server doesn't run the new Diorama implementation, no global
  frontier data is available and server-authoritative editing stays off.

## Map editor

1. Open the map with `M`.
2. Choose **Edit global frontiers** in the right-side toolbar. This button
   requires server operator permissions.
3. Choose **New frontier**, then click map positions to add polygon vertices.
   **Save** opens the name and colour editor once the polygon has at least
   three vertices. Set the colour with the RGB sliders or the hex field.
   **Use held banner** copies a banner from the main hand.
4. Select an existing frontier to edit it. Drag a vertex handle to move it,
   or choose **Add Point** and click an edge to insert a vertex.
5. Use the contextual toolbar or the frontier edit right-click menu to
   rename, save, discard or delete a draft. On an existing area drawing,
   the frontier edit menu can convert it to a global frontier. The original
   drawing is removed after the server acknowledges the conversion.

The **Territory Directory** lists global frontiers and known OPAC regions.
Select a result to centre the map there. **Settings → Overlays → Frontiers**
controls shape visibility, and **Minimap Info** controls the frontier and
claim names under the minimap. Labels are drawn inside shapes when there is
room at the current zoom. Boundaries are stored as world X/Z coordinates
and stay in place as the camera turns.

## Protocol and limits

- Separate versioned frontier messages reuse Diorama's optional share play
  channel, but do not depend on the shared terrain feature being enabled.
- Full snapshots send a begin frame, a frame per territory, optional banner
  frames, and an end frame. Authorized changes are broadcast as revisioned
  deltas with banner updates as needed.
- Stale edit requests trigger a fresh server snapshot rather than silently
  overwriting another operator's update.
- Maximum **512 global frontiers** per world, each containing **3–256**
  polygon vertices. Invalid/oversized geometry is rejected.
- Global data is saved atomically and invalid JSON entries are backed up.
- An approximate Y=64 projection is used for frontier fills. Terrain-aware
  outlines over mountains/cliffs are a subsequent improvement.

## Roadmap

- Add collections and optional imports from existing MapFrontiers data.
- Improve validation of intersecting polygons.

## Validation

The GitHub Actions NeoForge 1.21.1 build checks compilation, the companion
addon and web-assets builds. The selected `DIORAMA_TESTS=frontiers` client
GameTest validates model geometry and server-format JSON persistence, but
the test must be run separately from the default CI build. Multi-client
operator/guest validation is also required before production deployment.

## Browser map

The web map publishes visible global frontiers only in dimensions allowed by the
server map configuration. A detached snapshot is serialized on a background
worker to `frontiers.json`; edits and removals appear on the next poll.

Frontiers are enabled for new browser profiles. Existing saved layer choices
remain in effect. Names are drawn inside each polygon at an interior label
anchor, with a dark halo; names that cannot fit at the current zoom are hidden.
The same flat reference height is used for names and boundaries.

The approved web layout puts icon-only Frontiers (flag), OPAC claims (shield,
when Player History is installed), Vehicles (boat), and Trains buttons in a
compact vertical strip below settings. Mint indicates enabled. Tooltips and
accessible names identify the buttons; keyboard activation and focus are
supported. Other settings remain in the menu. Hovering shows territory names
and their colour swatches. With Player History #102, OPAC claims also show the
owner when it differs from the displayed name.

Deploy the updated core and, when installed, the matching `diorama-web` asset
addon. Player History's matching layer-controls change also hides vehicle and
train models and replay fallback pins with their respective buttons.

Checks: `node scripts/test-web-frontiers.mjs` verifies concave label placement,
dimension filtering, and removal publication. The paired Player History browser
fixture verifies icons, order, default state, labels, saved choices, and mobile
layout against this frontend. Native game testing remains a separate gate.
