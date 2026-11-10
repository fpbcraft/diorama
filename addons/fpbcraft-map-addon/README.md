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
- **Create Aeronautics/Sable:** moving live pins for **named** Sable sub-levels, using their
  synchronized pose and stable UUID. Unnamed contraptions are not shown.

Live pins are **never persisted** as Diorama waypoints; they appear/disappear as source state
changes. All reflection bridges are client-side and optional. A dedicated server installation
of the add-on is **not** required; MineColonies, Create and Sable must be present where their
own gameplay already requires them.

## Build

1. Check out the branches for PR #1, PR #2 and this add-on in the same repository.
2. Build Diorama first from the repository root with **JDK 25**:

   `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew :1.21.1-neoforge:build`

3. Build the add-on as its **independent Gradle project**:

   `cd addons/fpbcraft-map-addon`

   `JAVA_HOME=$(/usr/libexec/java_home -v 25) ../../gradlew -p . build`

4. Place `build/libs/fpbcraft-diorama-addon-0.1.0.jar` into the **client** mods folder
   alongside the matching Diorama JAR. This add-on must not be installed on a dedicated
   server. The three optional source mods are not bundled.

The add-on uses a `compileOnly` file-tree dependency on the parent's local NeoForge 1.21.1
Diorama jar, rather than re-bundling Diorama. It targets Java 21 bytecode.

## Known limitations / required tests

- **Unverified draft:** Gradle compilation and a real modded client run have not been performed
  for this code yet.
- Source APIs can differ across installed MineColonies, CRN, Create, Aeronautics and Sable
  versions. A failing provider disables only itself via Diorama's annotation registry.
- Building names can be translation keys if the source does not give the client a translated name.
- Request pins aggregate active player-resolver requests per source position; clicking uses
  Diorama's normal pin navigation/copy menu. A dedicated request detail/action screen,
  request item stacks/quantities, filters and clustering are planned.
- **Colony territory borders** are not implemented by this add-on yet. They require accurate
  chunk-boundary snapshots and terrain-aware rendering rather than assuming a circular radius.
- **CRN departure times, delay data, route selection and line colours** are not yet displayed.
  The initial train and station positions come from Create's synced graph.
- The moving vehicle provider reads only named sub-levels currently visible to the client,
  not every ship in distant unloaded regions. Sable is the authoritative source; the
  add-on does not invent player-defined names or server-wide ship positions.
- Dynamic curved-track geometry is currently implemented as a reference provider in Diorama
  PR #2, and should migrate into this add-on after its performance and fog masking are validated.
- No per-provider visibility options or clustering are present yet, and the existing global
  Diorama waypoint toggle controls marker visibility.

## Roadmap

1. Compile and smoke-test the three optional bridges against the exact installed mod JARs.
2. Add collapsible filter groups for colony, job and supply request pins.
3. Add a safe, scoped request-detail popup that cannot fulfil/cancel requests without
   server-side permission checks.
4. Add territory chunk outlines, station timetable details, train route selection and
   selected-ship route/destination labels.
5. Move the curved-track provider into the add-on and add exploration/fog masking.
