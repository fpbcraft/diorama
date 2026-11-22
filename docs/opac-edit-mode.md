# OPAC edit mode (stacked on native global frontiers)

This PR adds an **independent, explicit OPAC claim-editing toggle** to the existing
Diorama fullscreen map. It reuses the map's existing OPAC chunk selection UI;
it does not change how OPAC stores claims or decides who can claim land.

## Usage

- The top-right map buttons now include **OPAC: Edit**, immediately beside
  **Frontiers: Edit** and the existing **Claims: On/Off** visibility button.
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
