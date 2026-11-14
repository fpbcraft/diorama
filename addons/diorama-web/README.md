# Diorama Web Assets (NeoForge 1.21.1)

An optional **dedicated-server-only** JAR containing the Diorama browser frontend. Players do not install it. It registers no network channel and changes no world data.

Install a compatible core containing `WebFrontendAssets` once, then put `diorama-web-<version>.jar` in the server's `mods/` directory. Restart to install the bundled HTML, scripts, shader and noise texture automatically. Replacing this small JAR updates the browser frontend without changing the client pack. Core still supplies the renderer, HTTP/data API, generated terrain, pins and extension registration. Player History keeps supplying its own extension assets.

The web frontend has its own version in `build.gradle`. Keep its API registration version compatible with the core. An incompatible or incomplete bundle logs a warning and retains the core frontend. With an older core lacking this API the addon logs a warning and does nothing. A missing or removed addon restores the core's bundled frontend on startup.

This is a frontend split, not a full extraction of the web renderer/backend. New server data, rendering or protocol capabilities still need a server-core update. Native map changes still need a client-core update. The core's client second-screen map always keeps its bundled, matching frontend.

## Build

From the repository root:

```sh
./gradlew :1.21.1-neoforge:build
./gradlew -p addons/diorama-web build
node scripts/test-web-assets.mjs
```

Output: `addons/diorama-web/build/libs/diorama-web-0.1.0.jar`. The PR workflow uploads this artifact. No Vercel setup is required for the addon; existing frontend previews continue to use the static exporter and live backend.

## Local acceptance

The NeoForge 1.21.1 lab started with this addon installed on the dedicated server. All 14 served frontend resources matched the addon JAR byte for byte, and automatic extension, map, vehicle and queue feeds remained available. A Prism client without this addon successfully joined the lab on November 13. Its existing Diorama client core did not need an update for this frontend bundle.
