# Custom waypoint icons

Resource packs can add `assets/<namespace>/textures/waypoint_icons/<name>.png`. Diorama discovers these icons in the in-game waypoint picker automatically. The image is cropped to its opaque pixels and centred on an 11×11 tile, with integer downsampling for larger images.

Try [the example pack](waypoint-icons/diorama-example-waypoint-icons.zip), which adds `example:station` and `example:harbour`. Put the ZIP in the client's `resourcepacks/` folder, enable it, and select an icon when editing a waypoint. The included original pixel art is CC0.

For identical web pins, run `/diorama-export-web-appearance` in the client with the packs enabled. Copy the resulting `diorama-web-appearance/` folder to the server's `config/`, including `waypoint-icons.json`, then restart the server. Select **Show on web map** for the waypoint.

The export stores the same resolved 11×11 pixels that the in-game pins use, including replacements for built-in icons. The server composes them through the shared pin renderer. It accepts at most 512 icons in a 2 MiB local export; a player's waypoint only references an icon ID and cannot upload pixels or select a file path. Icons missing from the server export use built-in icons or initials. Re-export after changing resource packs; content-addressed pin images avoid stale browser images after an update.
