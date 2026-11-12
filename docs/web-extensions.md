# Web overlays (API version 1)

The browser exposes `window.diorama` and emits `diorama:ready` when the extension
API exists. `getState()` is null until `map.json` arrives. Subscribe to `change`
for metadata, dimension, pan, zoom and resize updates. Extensions may repaint
without calling the terrain renderer; their canvas is composited above tiles.
The terrain-only viewer has no build-time frontend dependencies.

Put optional `extensions.json` in the generated map directory:

```json
{
  "scripts": ["/player-history/diorama-history.js"],
  "styles": ["/player-history/player-history.css"]
}
```

Only same-origin URLs are accepted (at most 16 per list). This is server-owned
configuration, never a public write endpoint. It is preserved across restarts.
Missing config keeps the plain map usable. The embedded server serves JS/CSS/PNG
inside its own root, retains realpath/symlink containment checks, and does not
mount any external data folder. Use explicit reverse-proxy routes for external
public assets/data; never serve the recorder's private directory.

| API | Contract |
| --- | --- |
| `version` | `1`; consumers reject other versions |
| `getState()` | Null or `{dimension, dimensions, u,v,scale,width,height,pixelRatio,projection}` |
| `project({x,y,z})` | Minecraft **blocks** to projected `{u,v}` |
| `screen({x,y,z,dimension?})` | World blocks to CSS `{x,y}`, or null for a different dimension |
| `setDimension(key)` | Switch to an available dimension; returns false if unavailable |
| `navigate({x,y,z,dimension?})` | Centre on the position; optional dimension switch; returns success |
| `registerOverlay(id,{element?,draw?,pointer?})` | Unique ID; returns `{invalidate,remove}` |
| `events: change` | `CustomEvent.detail = {reason,state}`; reason `interaction` cancels follow |
| `events: dispose` | Release timers, listeners, requests and DOM nodes |

All public coordinates use CSS pixels; canvas drawing is already transformed for
high DPI. A drawing callback gets `(context,state)` with saved/restored canvas
state. `invalidate()` coalesces overlay-only painting into one animation frame.
An optional absolutely positioned DOM `element` may contain accessible buttons.
The overlay container has pointer events off; set `pointer-events:auto` only on
interactive children so terrain gestures still work. `remove()` is idempotent.

`pointer(event,state)` receives map-canvas pointer move/leave events for geometric
hit testing. Return true to stop dispatch to lower overlays. DOM markers receive
their own native events. Coordinate navigation and pointer handling do not imply
player-follow state: the Player History adapter owns its current target and
cancels following on manual map interaction/Escape.

The projection matches Java `Facing` and `Projection`: rotate X/Z as
north `(x,z)`, east `(z,-x)`, south `(-x,-z)`, west `(-z,x)`; then
`u=turnedX`, `v=turnedZ*sin(pitch)-y*cos(pitch)`. Do not apply the integer-block
`-1` offset to continuous player coordinates. Both dedicated and client map
metadata now include facing. Player History's protocol coordinates require
one division by 32 **before** calling this API.

An X/Z-only aggregate cannot be placed on the terrain exactly. Consumers must
label a chosen reference-height plane as approximate, or supply actual surface
heights. Screen projection does not supply underground depth/occlusion.

Run deterministic web checks without Minecraft:

```sh
node --test tests/web/*.test.mjs
JAVA_HOME=/path/to/jdk21 node scripts/test-web-server.mjs
```

The latter compiles the production HTTP handler with isolated logging/annotation
stubs and checks traversal, external symlinks, MIME types, GET/HEAD, immutable
versioned tiles/modules, conditional tiles and fresh live JSON. It never starts
a Minecraft world. Build the mod with Java 25:
`./gradlew :1.21.1-neoforge:build`.
