# OPAC edit mode (stacked on native global frontiers)

This PR adds an **independent, explicit OPAC claim-editing toggle** to the existing
Diorama fullscreen map. It reuses the map's existing OPAC chunk selection UI;
it does not change how OPAC stores claims or decides who can claim land.

## Usage

- The right-side toolbar includes **Edit OPAC claims**, **Edit global frontiers**,
  and **Claims** visibility. Contextual editing tools appear beside it.
- Press **OPAC: Edit** to enter an explicit chunk editing mode.
- **Left-click or left-drag** to claim a chunk or rectangle of chunks.
- **Right-click or right-drag** to unclaim chunks in the selected rectangle.
- The map shows the selected area and the claimable-distance indicator.
  Out-of-range selections are indicated in red.
- **Escape** cancels an in-progress area selection; **OPAC: Done** exits the mode.
- Frontier editing and OPAC editing are mutually exclusive; entering either
  mode closes the other. The existing drawing tool also closes both.
- The existing **Shift+drag** claim/unclaim shortcut still works outside
  explicit OPAC editing, for compatibility.

## Permission and server behavior

**Frontiers:** The separate parent PR makes these global, server-owned and
editable only by vanilla level-2+ operators.

**OPAC:** These are actual Open Parties and Claims (OPAC) chunk claims.
Diorama sends requests through OPAC's API, and **OPAC** validates the
player/party/server claim permissions, limits and distance. We deliberately
do not issue raw commands, edit OPAC storage, or replace its authorization
with Diorama's frontier OP check; that would prevent ordinary players
from using claims their server configuration permits.

The edit toggle is only active while OPAC is available on the client and
the player is viewing their current dimension. It does not change OPAC's
claims data or modify it until the user submits a selection.

## Validation

Build the stack against the current NeoForge 1.21.1 fork. Test normal
client/OP/moderator permissions (depending on OPAC settings), single-chunk
clicks and rectangular drags, cancelling with Escape, and toggling Frontier,
OPAC and Draw modes. Confirm that regular players cannot use global
Frontier editing even if they can claim land through OPAC.

## Forceloading visibility and editing

- **Marked for forceloading** claims display a stronger translucent tint and
  diagonal hatching on the fullscreen map and minimap. Normal claims remain
  lightly filled. This uses OPAC's own `isForceloadable()` claim marker.
- The map tooltip explicitly says **marked for forceloading**. This is not
  an assertion that a server ticket is active at the present moment: OPAC's
  API exposes the requested mark, which can exceed an active limit.
- Enter **OPAC Edit**. The contextual **Tool: Claims** button switches to
  **Tool: Forceload** (click again to return to Claims).
- With the forceload tool selected, **left-click/drag** marks already-claimed
  chunks for forceloading and **right-click/drag** removes those marks.
  Both modes use OPAC's existing `requestAreaForceload` API and result handler;
  the server enforces its usual permissions and limits.
- Press the side toolbar’s **Open Parties and Claims menu** button to open OPAC's own party/claim
  management screen. It calls OPAC's public `openMainMenuScreen` API;
  exiting returns to the Diorama map. The button is only shown when OPAC
  is installed on the client.

### Additional QA

Check that normal claims and forceload-marked claims have visibly different
fills, including across render tile seams and after reconnecting; that
forceload toggles are reflected in overlays after the server responds; and
that OPAC Menu returns to a functional map. Test normal players, operators
and permission-denied selections on NeoForge 1.21.1.
