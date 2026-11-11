# FPBCraft Diorama add-on (NeoForge 1.21.1)

A **separate client-only JAR** using Diorama's new `MapAnnotations` API (PR #2), without mixing
MineColonies, Create Railways Navigator, or Sable classes into Diorama's core.

This add-on registers three optional, read-only providers:
- **MineColonies:** known colony centres, building huts and levels, construction work orders,
  and outstanding human-actionable requests aggregated at requesting buildings. Follows the
  player-facing requests exposed to MineColonies' Clipboard.
- **Create railways:** synced station names, docked trains, moving train positions and any
  navigation destination already present in Create's client train state. Works with CRN but
  currently does not query CRN's timetable/delay calculations.
- **Create Aeronautics/Sable:** actual moving block imagery for all client-visible Sable
  sub-levels (including unnamed ones), rendered by Diorama's existing craft baker with
  textures, rotation, shadows and occlusion. Optional name pins remain available but are
  disabled by default.

Live pins are **never persisted** as Diorama waypoints; they appear/disappear as source state
changes. Sable craft imagery is also transient and is not saved as a parked craft.
All reflection bridges are client-side and optional. A dedicated server installation
of the add-on is **not** required; MineColonies, Create and Sable must be present where their
own gameplay already requires them.

## Build

1. Check out the branches for PR #1, PR #2 and this add-on in the same repository.
2. From the repository root, build both projects in dependency order with **JDK 25**:

   `JAVA_HOME=$(/usr/libexec/java_home -v 25) bash scripts/build-fpbcraft-addon.sh`

   This builds Diorama first, then compiles the separate add-on against that exact JAR.
   You can alternatively run the two Gradle builds manually, in that order.

3. Place `build/libs/fpbcraft-diorama-addon-0.1.0.jar` into the **client** mods folder
   alongside the matching Diorama JAR. This add-on must not be installed on a dedicated
   server. The three optional source mods are not bundled.

The add-on uses a `compileOnly` dependency on the exact, freshly built NeoForge 1.21.1
Diorama jar, rather than bundling Diorama or resolving an older jar. It targets Java 21 bytecode.

## Known limitations / required tests

- **Unverified draft:** Gradle compilation and a real modded client run have not been performed
  for this code yet.
- Source APIs can differ across installed MineColonies, CRN, Create, Aeronautics and Sable
  versions. A failing provider disables only itself via Diorama's annotation registry.
- MineColonies' own translation keys are localized when known to the client.
- Request pins aggregate active player-resolver requests per source position; clicking uses
  Diorama's normal pin navigation/copy menu. A dedicated request detail/action screen,
  request item stacks/quantities, filters and clustering are planned.
- **Colony territory borders** are not implemented by this add-on yet. They require accurate
  chunk-boundary snapshots and terrain-aware rendering rather than assuming a circular radius.
- **CRN departure times, delay data, route selection and line colours** are not yet displayed.
  The initial train and station positions come from Create's synced graph.
- The moving block imagery reads all **client-visible** Sable sub-levels, including unnamed
  vehicles, and uses their already synchronized plot blocks and interpolated poses. It does
  not show distant ships without client data. Larger ships may require expensive first-time
  snapshots; sub-levels exceeding Diorama's cell limit are skipped.
- Compact icon badges replace the always-on labels for live markers. Full details appear
  on hover. Separate toggles under Diorama Settings > Overlays control each integration;
  ships render as blocks by default, not as name pins.
- Create/Steam 'n' Rails **straight and curved graph edges** are rendered through
  Diorama PR #2's shared 4-pixels-per-block map raster with the same material palette,
  lighting/grade and mipmaps. Reversed graph edges are deduplicated; only curved
  connections with `BezierConnection.hasGirder` gain the additional metal support.
  Unlike the older direct-texture overlay, the same map-styled geometry now applies
  across both rail types. Diorama's existing straight blocks still remain baked in
  underlying saved map tiles, so visually perfect replacement will need selective
  removal of graph-owned track blocks from the terrain baker (without hiding detached
  tracks or invalidating player maps).
- Individual marker groups can be enabled/disabled via Settings > Overlays; full clustering
  and request-detail screens remain to be implemented. The existing global Diorama
  waypoint setting also controls whether any live pins appear.

## Roadmap

1. Compile and smoke-test the integration providers against the exact installed mod JARs.
   Specifically verify that Create's `BezierConnection.primary` field resolves, and that
   Sable's plot blocks are drawn at the correct position/orientation across motion and world reloads.
2. Add collapsible filter groups for colony, job and supply request pins.
3. Add a safe, scoped request-detail popup that cannot fulfil/cancel requests without
   server-side permission checks.
4. Add territory chunk outlines, station timetable details, train route selection and
   selected-ship route/destination labels.
5. Move the curved-track provider into the add-on, refine custom material/girder geometry and test bridge/tunnel occlusion.
