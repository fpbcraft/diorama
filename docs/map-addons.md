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

- **Create curved tracks:** initial reference provider in `compat/CreateCurves` reads the
  synchronized Create track graph and draws sampled Bezier track sections. This provider
  can move to a separate add-on once its visuals and performance are tested.
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

The early Create track source is an **overlay**, not baked track geometry. It uses a
colour-coded stroke instead of Create's actual rail textures; current terrain/fog
masking is not applied to that stroke. On servers with secret undiscovered railway
networks, disable the feature until a visibility mask is implemented. Curves are rebuilt
from the client graph every 80 ticks and never written to Diorama's persistent map.
This integration is conditional on Create's reflective APIs being available.
