# Diorama's client map add-on API

Diorama's public `dev.diorama.api.MapAnnotations` entry point allows a **separate client-side mod**
to register transient map layers and live waypoint pins. Add-ons do not need mixins into
`MapScreen`, `MinimapHud`, `WaypointStore`, or Diorama's tile baker.

This is a deliberately small API; its source compatibility with future Diorama versions is
not yet guaranteed. Build an add-on against the same Minecraft/NeoForge and Diorama versions.

## Registering an add-on

From the companion mod's **client-only** setup or class initialization:

```java
MapAnnotations.register(new MapAnnotations.Source() {
    @Override
    public List<Waypoint> markers(String dimension) {
        return List.of(); // Return a cached immutable snapshot, not a new world scan.
    }

    @Override
    public void draw(GuiGraphics graphics, MapViewport.View view, String dimension, boolean fullscreen) {
        // 1.21.1 uses GuiGraphics. Newer Diorama versions use GuiGraphicsExtractor.
        // Prefer MapCanvas.of(graphics) for smooth map-space geometry.
    }

    @Override
    public void tick(Minecraft minecraft) {
        // Only examine already-synchronized client data.
    }
});
```

A live marker is a regular `Waypoint` with `kind = Waypoint.Kind.LIVE`. Set its stable
UUID, name, position, dimension, colour and initials. Live markers cannot be edited or
hidden via `WaypointStore`; when an add-on stops returning a marker it disappears
without leaving files behind. The normal waypoint visibility preference applies.

Both the fullscreen map and minimap render provider geometry via `Source.draw`.
The `fullscreen` argument allows a provider to avoid drawing dense details at minimap
size; the `view` includes bounds, orientation-neutral map projection, scale and a
`round` flag. Respect `dimension`, `view.layer()`, and clipping. To place 3D world
positions, use `Projection.u(worldX, worldZ)` and
`Projection.v(worldX, worldY, worldZ)`, then `view.screenX`/`screenY`.

All callbacks run on the client thread and may execute every frame. Update expensive
snapshots from `tick` at a controlled rate, do not query server worlds from render
callbacks, and never do blocking I/O in `markers` or `draw`. Runtime failures of one
source disable only that source for the session.

## Compatibility roadmap

- **Create railway tracks:** `compat/CreateCurves` reads synchronized Create graph edges,
  including ordinary straight sections and Bézier curves, deduplicating their mirrored edges.
  Both use `compat/CurveRaster`, which draws rails and sleepers on Diorama's 4-pixels-per-block
  map grid with the same material colours, grade, mipmaps and terrain-depth masking. Curves
  with `BezierConnection.hasGirder` add a metal support; plain curves and straight sections
  do not gain artificial girders. Other mods' connected railway networks use their registered
  Create track materials too. This provider can move to a separate add-on after testing.
- **MineColonies:** source markers for colony centres, buildings, construction orders
  and human-actionable requests. Requests are not persisted as ordinary waypoints.
- **Create Railways Navigator:** source markers for stations/trains and optional departure
  or route details, using the installed version's synchronized state.
- **Create Aeronautics / Sable:** moving named ship markers, altitude and destination
  where those values are exposed to the viewer.

Next API steps: add a rich-marker record with structured tooltip lines and click
actions, an explicit provider-visibility settings model, and a terrain-aware geometry
or claims-outline hook. Those are needed before the more elaborate integrations can
live entirely in separate JARs.

## Compatibility caveats

Create railway geometry remains a **transient overlay** and does not replace
Diorama's saved terrain pixels. Normal straight-track blocks therefore still exist in
the underlying baked terrain image; the same map-styled sleeper/rail geometry is
superimposed on the two types to reduce their difference. To avoid permanently hiding
unconnected track blocks, this first pass does not remove the original straight-block
rendering from tiles. The renderer prevents that already-baked geometry from depth-
occluding the new pixels. Terrain whose depth is not yet known also hides graph rails with fog of war off; the overlay reveals them only after the asynchronous surface trace confirms they are in front of the ground. Straight and curved tracks now share the same 0.3-block depth tolerance. Geometry
refreshes every 80 ticks, with bounded sampling and texture caches. For a perfect
replacement, the terrain baker would need selective masking of graph-owned straight
track blocks and safe invalidation of existing saved tiles; do not blanket-hide every
Create track block or unrelated/disconnected pieces disappear.
