# Native frontiers (FPBCraft Diorama fork)

This feature implements **local named polygon territories in Diorama**, without JourneyMap
or MapFrontiers installed. It is an initial native alternative, **not yet feature parity**
with MapFrontiers.

## Use

1. Open Diorama's fullscreen map (default `M`).
2. Select the pencil (Draw mode), then the **Area** tool (polygon icon).
3. Left-click points around the territory; finish with right-click or Enter.
4. Exit Draw mode with the pencil or Escape.
5. Right-click inside the finished area and choose **Convert to named frontier**.
6. Enter a name and a six-digit hex colour, then Save.

The area is converted to a frontier; on successful save, the original drawing is
removed so that it does not render twice. Right-click in an existing frontier
to **Edit** or **Delete** it. Deletion asks for confirmation.

The frontier renders above the fullscreen map and minimap. The fullscreen map shows
its name near its centre; the minimap's information lines display the name while
the player stands inside it.

## Data and compatibility

- Data is written locally to `.minecraft/diorama/<world>/frontiers.json`, beside
  `drawings.json` and `waypoints.json`. Each entry has a stable UUID, dimension,
  name, RGB colour, visibility flag and an array of X/Z world-space vertices.
- Unlike ordinary drawings, frontiers are independent of the selected Diorama
  sub-map. Rotating the map does not move their real-world boundaries.
- Geometry is projected using a representative height of Y=64, so filled shapes
  may not precisely follow terrain or cliffs in the angled 3D map.
- No JourneyMap dependency, MapFrontiers dependency, external service, or extra
  server installation is needed for **local** frontiers.
- There is **no synchronization** between players. Other players cannot see
  a frontier you create unless they receive the data file separately.
- Malformed frontier entries are ignored, with a backup copy of the source JSON.

## Outstanding features for MapFrontiers parity

1. **Shared server frontiers.** Provide an optional NeoForge server-side module
   with persisted global territories, versioned packets, delta synchronization,
   ownership and server-validated create/edit/delete permissions.
2. **Chunk and path frontiers.** Support unioned claimed-chunk shapes, additive and
   subtractive chunk editing, line paths with width and dashed/marker styles, and
   arbitrary vertex editing.
3. **Collections and visibility.** Tree/list management, per-player local
   visibility controls, sharing roles, labels, filters, search and sorting.
4. **HUD and world display.** Enter/exit notification, configurable banner or
   current-area HUD, world-space boundary markers and distance limits.
5. **FPBCraft integrations.** Optionally read OPAC claims and MineColonies borders
   into read-only generated frontiers, with server names and ownership colours;
   distinguish a visual annotation from the permissions actually enforced by OPAC.
6. **Migration and web display.** Optional import of the MIT-licensed
   MapFrontiers storage format after validating its current schema, plus display on
   Diorama's separate browser map.

## Tests

`DIORAMA_TESTS=frontiers` uses the existing client GameTest runner to check
world-coordinate conversion, point-in-polygon hit testing, defensive copies,
JSON persistence and cleanup. The test currently has not been run against the
fully modded FPBCraft NeoForge client.
