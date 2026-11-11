# Minimap information lines

Diorama's minimap renders configurable text **below** the map, aligned to its chosen corner. Open **Map settings → Info** to enable or disable individual lines. The minimap's map/compass/zoom settings remain in the **Minimap** tab.

| Row | Default | Source |
| --- | --- | --- |
| Coordinates | On | Player's current block position |
| Biome | On | Biome at the player's feet |
| OPAC claim | On | Current chunk's synchronized OPAC owner/party/server name |
| Season | On | Ecliptic Seasons public API's global season (localized) |
| Local time | On | Device's local time, independently of Minecraft's clock |
| Weather | On | Ecliptic Seasons' local rain/snow/thunder APIs, or vanilla weather without it |
| Game time | Off | Existing Xaero-style in-game day/clock setting |
| Facing | Off | Cardinal facing direction |
| Dimension | Off | Minecraft dimension identifier |

The OPAC row appears only for an actual claim on an OPAC-enabled server; it is independent of the **Overlays → Claims** preference. Ecliptic Seasons is entirely optional: no hard dependency or server-side add-on is required. Season disappears when Ecliptic Seasons is absent or disabled in the dimension. Weather describes Minecraft weather—not the weather at the player's physical location—and is omitted in dimensions without skylight.

Names wider than the available screen space are ellipsized. The lowest-priority info rows are omitted when the window is too short to keep the minimap visible.

## Manual smoke tests

- Join an OPAC server; cross player, party and server claims as well as unclaimed land. Hide claim polygons and confirm the text still updates, then join a server without OPAC.
- With Ecliptic Seasons installed, change season and local precipitation (including snow and thunder) and verify the HUD follows it; check the Nether and a game without Ecliptic Seasons.
- Toggle every Info option independently and verify the minimap preview and JSON settings persist.
- Test top/bottom, left/right corners, small GUI sizes and long claim names; confirm no text leaves the screen.
- Check local time updates with the system clock and does not track Minecraft day changes.

No game runtime or NeoForge build has been run in the authoring environment.
