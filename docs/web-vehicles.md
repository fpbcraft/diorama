# Independent web vehicles

Diorama publishes Create contraptions and Sable ships without BlueMap or BlueMap3D. Its browser extension consumes a small live scene feed; Player History archives those publications through the existing recorder and retention system.

## Installation

Use matching Diorama, Player History recorder and Player History public-assets builds containing this feature. The public-assets JAR includes the frontend through the JAR-managed web extension host. There is no Node build, frontend file copy or separate vehicle HTTP server required on the Minecraft server.

In `config/diorama-server.properties`:

```properties
web-map=true
web-map-vehicles=true
web-map-depth=true
```

Restart. Force-render existing terrain once after enabling depth so the map has depth and ground-lighting sidecars. Vehicle capture never loads or generates chunks. The normal web-map pause, save and busy-tick safeguards defer capture; a stale scene is hidden by the viewer.

## Matching the in-game map

The client and server share `ContraptionBodies`, `SubLevelBodies`, `CraftBlocks`, `CraftBaker` and `CraftShadow`. The web renderer displays lossless craft pixels and their per-pixel camera depth, rather than approximating the craft with a separate mesh/material system. Shadows use the same silhouette and ground-light composition, reading already-published terrain information.

A dedicated server does not have the client's baked models or resource packs. For matching block colours and map style, run this **client command** with the intended modpack and packs loaded:

```text
/diorama-export-web-appearance
```

It writes `diorama-web-appearance/looks.dat` and `style.json` in the client game folder. Install that folder at the server's `config/diorama-web-appearance/`, then restart. This is an explicit local export, not an upload endpoint. Diorama detects profile changes and refreshes terrain through the existing background render queue. Re-export after changing resource packs or the in-game map style.

Without a profile, the server uses its shipped vanilla table and mod-jar fallback colours. Do not describe that fallback as pixel-identical to a customised client. The transport tests establish lossless colour/alpha/depth export; real Create/Sable screenshot comparisons remain required to establish visual parity.

Craft images record their pitch and facing. Historical images from a different projection fall back to accessible markers rather than being placed incorrectly. Existing BlueMap3D mesh history remains readable.

## Load and storage bounds

- Up to 64 captured vehicles globally, with a 262,144-cell limit per copied grid.
- Transform snapshots every two ticks; at most one grid copy per 100 ms, shared across dimensions. Block refreshes coalesce and occur at least periodically (30 seconds when capacity permits).
- A low-priority image baker and a separate pose publisher retain the previous image while a replacement is prepared. Expensive image work cannot stall the 100 ms pose publication loop. Image tracing backs off in proportion to its work; this is a scheduling policy, not a measured server-load guarantee.
- Half-pixel rotation tolerance and four-block height tolerance match the in-game craft painter's rebake rules. Translation does not rebake the craft image.
- The browser projects the shared sun silhouette onto up to eight visible terrain tiles, using the native four coverage samples, sun contribution, shaded ground colours, and depth. Shadows never translate a pre-coloured image of the ground. Missing ground data yields no shadow. Ground textures and silhouettes load through two bounded requests, with eight ground tiles, sixteen casting vehicles and a 32 MiB silhouette texture budget.
- Superseded live images are removed after a 60-second grace period. Player History keeps its own durable geometry copies and applies its existing retention policy.
- Browser sprite loads have two concurrent requests, a 128 MiB decoded-resource budget and at most 32 cached images including replacements. Textures/materials are reused between animation frames; missing resources retain an accessible marker.

When the existing Player History BlueMap bridge is installed, it retains ownership of object recording to preserve BlueMap replay geometry and avoid duplicate recording. A Diorama-only installation records the independent craft sprites instead. Thus coexistence history can still use BlueMap3D meshes while live Diorama uses the shared craft tracer. No existing recordings are converted or deleted.

## Publication and API

`map.json` advertises a relative `vehicleFeed`; the browser API exposes `vehicleFeed()`. Resolve it relative to the map page, including behind a subpath proxy. The feed is `assets/diorama-vehicles/scene.json`, with `generatedAt`, `intervalMs`, `format` and bounded `objects`. An optional `caster` URL supplies the native sun silhouette. The viewer ignores out-of-order publications, preserves its interpolation clock for duplicate captures, and starts new transitions from the displayed position. Teleports and dimension changes snap, and reduced motion disables interpolation. Mesh and atlas URLs are relative to the map root. It is public map information and contains no chat/session credentials.

Each craft uses a content-addressed `.dspr` plus a PNG atlas. DSPR v2 starts with a 64-byte little-endian header: ASCII `DSPR`, uint32 version, width and height; float64 projected U/V offsets, pitch sine/cosine; uint32 facing and pixels per unit; eight reserved zero bytes. The remainder is zlib-compressed Float32 camera depth relative to the craft centre, row-major, with NaN for no opaque hit. The reader also accepts the initial 32-byte v1 header. Atlas pixels retain the tracer's RGBA values.

`.ground` files contain only published terrain pixels: surface height, camera depth, sun contribution and sunless colour. The embedded handler serves them and content-addressed `.dshd` sun silhouettes through its existing web-root containment rules. No world directory is mounted. A DSHD v1 silhouette has a 128-byte little-endian header followed by zlib-compressed float32 sun reach; the header records dimensions, texel density, sun basis, grid offset from the craft centre and relative bounds. It is independent of translation.

`dev.diorama.api.WebVehicleScene.latest()` exposes an immutable publication snapshot for integrations. It contains published asset paths and scalar transforms, with no live entity/world references. Consumers must reject stale snapshots and must not re-enumerate the source mods. Player History's archive worker consumes this snapshot and cancels obsolete session work on shutdown.

## Verification

```sh
node --test tests/web/*.test.mjs
JAVA_HOME=/path/to/jdk25 node scripts/test-web-vehicles.mjs
JAVA_HOME=/path/to/jdk25 node scripts/test-web-server.mjs
JAVA_HOME=/path/to/jdk25 ./gradlew :1.21.1-neoforge:build
```

The isolated vehicle check tests lossless pixels/depth, stable content identities, and optional API calls with client-only classes deliberately absent. It does not start Minecraft. Validate the final JARs on a dedicated modded server without BlueMap/BlueMap3D, including train assembly, Sable edits/rotation, filled copycats, unload/despawn, restart, terrain occlusion and shadows. Compare the same craft in-game and on the web with a matching appearance profile before declaring exact visual parity.

## Provider lifecycle and parity

Sable presence comes from its container, including ships with unavailable or empty plot bounds. Previously captured blocks and the last usable pose remain available while its plot chunks sleep. Disassembly/removal from the container removes the publication. A dormant ship never captured during this server session cannot be reconstructed from unloaded chunks; it must be observed once. No chunks are force-loaded.

Create trains are enumerated from `Create.RAILWAYS.trains`. Geometry is read through the same contraption/copycat reader, with a cold-start fallback to `Carriage.write()` and `Contraption.fromNBT()`. Position and orientation follow the railway manager's dimensional anchors, with stable per-dimension/train/carriage identities. Loaded carriage entities are excluded from the ordinary contraption pass. Removing a train or shortening its carriage list removes those objects. This is the same registry/anchor approach used by the FPBCRAFT BlueMap3D provider.

| Behaviour | Independent Diorama path |
| --- | --- |
| Live positions | Two-tick capture, independent 100 ms publication, continuous viewer interpolation |
| Sable dormant plots | Cached geometry remains while the container reports the ship |
| Unloaded train carriages | Railway anchors plus saved contraption geometry |
| Terrain occlusion | Per-pixel native camera depth; requires `web-map-depth=true` and rendered sidecars |
| Moving shadows | Browser projects the native silhouette onto current terrain each frame |
| Player/object replay | Existing Player History archive; Create train provider receives empty removal snapshots too |
| Vehicle appearance | Native craft tracer; matching client appearance export required for custom colours |
| Wheel/propeller subpart animation | Native craft sprites do not carry BlueMap3D's animated mesh subparts; not claimed as parity |

These implementation paths and fixtures do not replace dedicated-server acceptance. Verify unloaded/reloaded ships, cold trains, sloped/portal tracks, disassembly, copycats and changing roof/ground heights with the final JARs. In-game screenshot parity and the installed Create/Sable API bindings remain acceptance checks.

## Named vehicle pins and public waypoints

Player-assigned Sable names, Create train names and custom contraption names are published as labels. Once the vehicle's projected size is below 28 CSS pixels, a named vehicle keeps a visible pin and label. Carriages of the same train collapse to one pin at their average position, independently in each dimension. Pins use the actual in-game `PinArt` pixels; close-up models retain keyboard-accessible fallback pins. Clicking a pin centres the map there.

In the in-game waypoint editor, select **Show on web map (public)** and save. This is separate from **Show on maps** and defaults off. Only selected waypoints are sent over the authenticated Minecraft connection; no browser upload endpoint exists. Unselecting or deleting a waypoint removes it from the owner's next snapshot, normally within two seconds plus the browser's five-second refresh. Selected waypoints remain visible while their owner is offline and survive server restarts.

Each player can publish up to 64 selected waypoints; the server limits the list to 4,096 total. Snapshots replace only that authenticated player's entries, and duplicate ids from different players cannot overwrite each other. Names, coordinates, dimensions and message sizes are bounded. The private owner registry lives at `<world>/diorama-public-waypoints.json`; the web root contains only approved marker fields and pin images. Built-in waypoint icons use native pin art; custom resource-pack icons fall back to initials when the server does not have them. Both Minecraft client and server need the updated Diorama build to use the checkbox.

The isolated marker test covers ownership, rate/coordinate/count bounds, opt-out, restart persistence and lossless native pin pixels. The browser fixture covers named pins at distant zoom and the selected waypoint overlay. The in-game editor layout and real ship/train names still need dedicated-server/client acceptance.
