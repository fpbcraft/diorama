# Custom waypoint icons

Resource packs can add `assets/<namespace>/textures/waypoint_icons/<name>.png`. Diorama discovers these icons in the in-game waypoint picker automatically. The image is cropped to its opaque pixels and centred on an 11×11 tile, with integer downsampling for larger images.

Try [the example pack](waypoint-icons/diorama-example-waypoint-icons.zip), which adds `example:station` and `example:harbour`. Put the ZIP in the client's `resourcepacks/` folder, enable it, and select an icon when editing a waypoint. The included original pixel art is CC0.

For identical web pins, run `/diorama-export-web-appearance` in the client with the packs enabled. Copy the resulting `diorama-web-appearance/` folder to the server's `config/`, including `waypoint-icons.json`, then restart the server. Select **Show on web map** for the waypoint.

The export stores the same resolved 11×11 pixels that the in-game pins use, including replacements for built-in icons. The server composes them through the shared pin renderer. It accepts at most 512 icons in a 2 MiB local export; a player's waypoint only references an icon ID and cannot upload pixels or select a file path. Icons missing from the server export use built-in icons or initials. Re-export after changing resource packs; content-addressed pin images avoid stale browser images after an update.

## FPBCraft workshop pack

The FPBCraft Diorama core JAR bundles 24 original 11×11 icons for workshops, factories, cogs, trains, stations, rails, airships, ships, harbours, warehouses, mines, quarries, smelters, power, mills, bridges, markets, colonies, construction, supplies, portals, farms and forests. They appear automatically in Diorama's normal icon picker under the `fpbcraft` namespace. No separate resource-pack installation or activation is needed.

The dedicated server uses the identical bundled pixels to compose web waypoint pins. Choose an icon and **Show on web map** in the client; the matching server JAR needs no appearance export for these icons. An administrator's optional appearance export still takes priority, so resource-pack replacements and downloaded URL icons remain supported.

An optional [standalone resource-pack ZIP](waypoint-icons/fpbcraft-waypoint-icons.zip?raw=true) is also available and is embedded inside the core JAR at `diorama/packs/fpbcraft-waypoint-icons.zip`. The art is CC0; [preview the set](waypoint-icons/fpbcraft-preview.html).

Editable sources are in `resourcepacks/fpbcraft-waypoint-icons/`. Run `python3 scripts/build-waypoint-pack.py` to regenerate the pack ZIP, JAR assets, server pixel table and preview together. The generator uses only Python's standard library. Its metadata follows Diorama's multi-version example format.

## Icons from a URL

In the waypoint editor, open the icon picker, choose **From URL…**, paste a direct **HTTPS PNG** URL, and press **Download and use**. The client downloads the image once, then saves it locally under `.minecraft/diorama/waypoint-icons/`. Large sets are paged to fit the game window. Downloaded icons remain in the picker after restart and use the same cropping and 11×11 sizing as pack icons.

The host receives a request only when you explicitly import an image. Incoming/shared waypoints and rendering never trigger downloads. URLs with logins, fragments, custom ports, redirects or local/private hosts are rejected. PNGs must be at most 256×256 pixels and 1 MiB; the cache is limited to 128 icons. A total download deadline prevents a slow host from holding the importer open indefinitely. Use an original image you own or have permission to use.

Waypoints store a short content-based `url:` icon ID, not the original address. To publish the icon on the web map, export appearance as described above. The server uses those exported pixels and never contacts the image URL. Another client needs the matching cached PNG or its own import of the same PNG bytes; sharing a waypoint does not distribute image files or make someone else's client contact its host.
