# Global frontiers (FPBCraft Diorama fork)

A JourneyMap-free, server-authoritative territory overlay integrated directly into Diorama.
This is intentionally separate from **OPAC claims**, which are real protection/claim
permissions managed and enforced by the Open Parties and Claims mod.

## Multiplayer setup

- Install the **same built Diorama fork JAR** on the NeoForge 1.21.1 dedicated
  server and on each player's client. No JourneyMap or MapFrontiers installation.
- The server saves all global territories at:
  `<world>/data/diorama-frontiers.json`.
- Every player with Diorama receives the same global frontier list on joining,
  even if `share-map=false` in Diorama's shared-terrain configuration.
- A player with vanilla **OP level 2 or higher** can create, change, rename
  or delete frontiers. The server checks that permission on every request;
  client-only code or forged packets cannot bypass it.
- Other players can view frontiers and the current territory name on the
  fullscreen map/minimap, but cannot use the edit toggle.
- On integrated worlds or LAN games, the player also needs operator-equivalent permissions (for example, cheats enabled for the host). LAN guests are not implicitly authorized.
- If the connected server doesn't run the new Diorama implementation, no global
  frontier data is available and server-authoritative editing stays off.

## Map editor

1. Open the map with `M`.
2. Toggle **Frontiers: Edit** in the existing compact top-right button row.
   This button is only enabled when the server grants operator permissions.
3. Choose **New**, then click map positions to add polygon vertices.
4. Click **Save** when the polygon contains at least three vertices. A small
   editor lets you name it and pick its colour. The server confirms and
   broadcasts the new territory to everyone.
5. Click an existing frontier while in editing mode to select it. Drag a
   square vertex handle to move it, or toggle **Add Point** to append vertices.
   Press **Save** to submit changes.
6. **Rename** opens the name/colour editor; **Delete** confirms before
   submitting the removal; **Cancel** discards the uncommitted edit.
7. Toggle **Frontiers: Done** to leave frontier editing.

Existing Diorama Draw → Area polygons can also be converted using the
right-click context menu **only for operators**. The original personal
drawing is removed only after the server acknowledges the new global frontier.

Frontier names and outlines are always displayed to players in the relevant
dimension. Editing nodes and the editing toolbar appear only for authorized
operators in the corresponding edit mode. View coordinates are stored as
world X/Z, not map-camera coordinates, so map rotation does not alter boundaries.

## Protocol and limits

- Separate versioned frontier messages reuse Diorama's optional share play
  channel, but do not depend on the shared terrain feature being enabled.
- Full snapshots send a begin frame, one frame per territory, and an end frame.
  Subsequent authorized changes are broadcast as revisioned deltas.
- Stale edit requests trigger a fresh server snapshot rather than silently
  overwriting another operator's update.
- Maximum **512 global frontiers** per world, each containing **3–256**
  polygon vertices. Invalid/oversized geometry is rejected.
- Global data is saved atomically and invalid JSON entries are backed up.
- An approximate Y=64 projection is used for frontier fills. Terrain-aware
  outlines over mountains/cliffs are a subsequent improvement.

## Roadmap

- Refine vertex insertion, polygon validation (intersections), labels and
  visibility settings without changing the server-authority model.
- Add optional admin lists, search, collection/group organization and
  visibility overrides.
- Add separate OPAC region edit mode in its own stacked PR. OPAC's existing
  client requests are validated by OPAC on the server, not by the frontier
  JSON store.
- Add a read-only global frontier overlay to the browser map and optional
  imports from existing MapFrontiers data.

## Validation

The GitHub Actions NeoForge 1.21.1 build checks compilation, the companion
addon and web-assets builds. The selected `DIORAMA_TESTS=frontiers` client
GameTest validates model geometry and server-format JSON persistence, but
the test must be run separately from the default CI build. Multi-client
operator/guest validation is also required before production deployment.
