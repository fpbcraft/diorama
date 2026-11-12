# The web map

A server with Diorama can draw its map for a browser: a page anyone can open on a second screen or a phone, with no mod or game. It looks like Diorama's own map, in vanilla colours whatever resource pack players use, and it fills in by itself as players explore and build.

It's off until you turn it on.

This is the server's map, and it only runs on a dedicated server. For your own map in a browser while you play, in singleplayer too, see [Your map on a second screen](second-screen.md).

## Turn it on

1. Put Diorama in the server's `mods` folder (Fabric needs Fabric API there too).
2. Start the server once. It writes `config/diorama-server.properties`.
3. In that file, set `web-map=true` and restart.
4. Open `http://<your server's address>:8100/` in a browser. If the server is behind a firewall, open port 8100 (TCP), or pick another with `web-map-port`.

The first time, the server draws every part of the world players have spent time in, nearest the middle of the world first, on one background thread. On a 4-core machine that took 25 minutes for Hermitcraft Season 10's world (about 2,200 tiles over three dimensions), with no change to the server's tick time. The page shows tiles as they're drawn.

After that, it redraws only what changed. Each region file records when each of its chunks was last saved, and the web map looks at those times every 5 minutes, the same as vanilla's autosave. A build shows within about 5 minutes of being built, with nothing to press. A chunk saved without any change to its blocks isn't drawn again.

## Settings

All in `config/diorama-server.properties`:

| Setting | What it does |
| --- | --- |
| `web-map` | `true` to draw the web map. Off by default. |
| `web-map-port` | The port of Diorama's own small web server. `0` turns it off, for when you serve the files yourself. Default 8100. |
| `web-map-address` | The address it listens on. Empty for every address; `127.0.0.1` to only reach it through a web server of your own on the same machine. |
| `web-map-folder` | Where the map's files go. Empty: `diorama-web` in the world folder. |
| `share-land` | Which land the map shows, the same as the shared map: `visited` (land players have spent time near, as the game counts it in every chunk) or `generated` (every finished chunk, for worlds pre-generated with Chunky). |

## Serving it yourself

The folder is a plain static site: `index.html`, `map.json` and the pictures, one JPEG per tile in `<dimension>/<zoom>/<x>_<y>.jpg`. Point nginx, Caddy or any web server at it and set `web-map-port=0`. For example with nginx:

```nginx
location /map/ {
    alias /srv/minecraft/world/diorama-web/;
}
```

## Commands

For operators:

- `/diorama-web status` says what it's doing.
- `/diorama-web redraw` draws everything again.

Changing `share-land` redraws the whole map by itself the next time the server starts.

Land taken out of the world stays on the web map until `/diorama-web redraw`: a dimension reset by deleting its folder, region files deleted, or chunks trimmed with a tool such as MCA Selector.


## FPBCRAFT web-map worker diagnostics

The server console command `diorama-web status` reports the worker's current stage:

- **scanning saved region headers / scanning saved chunks** — loading region files; includes region count, saved chunks examined, average tick time in milliseconds.
- **waiting** — shows the current dimension, tile number, mean MSPT, whether world saving is active, and accumulated wait duration. If the average server tick exceeds 40 ms, tile generation pauses to preserve TPS. If it remains between 40 and 50 ms for 30 seconds without a world save, one tile can proceed at a conservative rate; at more than 50 ms or during a save, generation remains paused.
- **drawing** — current tile count, percentage, and mean MSPT.
- **incomplete / retrying** — a scan/render failure or a redraw interruption; a full pass is scheduled after 30 seconds (an operator-requested redraw begins as soon as possible).
- **up to date** — a complete pass has finished; the status also reports the current number of base JPEG tiles. A previous, interrupted pass no longer produces this status.

The server log adds:

- Pass start, region count, mean MSPT and saving state.
- Periodic scan and tile progress (every 30 seconds).
- Pauses exceeding 30 seconds, including reasons and elapsed time.
- Slow tiles (10 seconds or more), with coordinates and execution time.
- Completed scan summary, chunks considered, number of tiles queued and read failures.
- Explicit interruption cause (server shutdown or another redraw), completed count and retry schedule.
- Detection of stale progress records when no base JPEG tiles exist; one full rescan is triggered automatically.

Use `diorama-web status` to capture the current stage, then look for `Web map` in `logs/latest.log` to find timing, cause, and progress. **Do not repeatedly run `diorama-web redraw` while the first pass is building**: every invocation interrupts the current pass. This worker uses the saved region files and writes into `<world>/diorama-web/`; no manual deletion of `progress.properties` or the JPEG folders is required.

## Disk

About 59 KB a tile of land, plus half as much again for the zoomed-out views. Hermitcraft Season 10's world took 173 MB: 125 MB for the overworld, 30 MB for the Nether, 19 MB for the End.

## Modded blocks

Mods ship their block models and textures in the same jar on the server, and the web map reads them from the jars in the `mods` folder, as BlueMap does. Two things only a game client knows can't be read there:

- **Models made in code** (some machines, connected textures) have no files to read. Those blocks show in their colour on vanilla maps.
- **Tints.** A block whose model asks to be tinted shows in the biome's foliage colour if it's leaves, and its grass colour otherwise. A mod that tints its blocks some other way, or not at all, can come out a different colour from the game.
