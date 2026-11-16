# Straight claim boundaries

Claims use a flat X/Z chunk grid at Y=64 on the surface map. Each chunk appears
as a rectangle in Diorama's angled projection, matching Xaero's square chunk
boundaries without following hills, roofs or tree canopies. Adjacent chunks of
the same claim merge visually; irregular areas retain their steps and unclaimed
gaps. Force-loaded chunks keep their stronger fill and separate boundary.

The minimap and fullscreen map share this projection. Claims do not depend on
saved terrain-picking metadata, and terrain rebakes no longer repaint the grid.
Visibility settings and the existing cave-map policy are unchanged.

Claim hover details, context-menu actions, Shift-drag selection and the claim
distance outline all use the same grid. Tooltips include the claim's chunk
coordinates. Ordinary terrain coordinates, biome information, teleport actions
and waypoints continue to use the actual terrain pick.

Because terrain is rendered in 3D, a high building or hill can appear displaced
from the flat claim rectangle covering its X/Z position. The grid intentionally
stays stable as the player changes height; following the player's height would
make stationary claims slide across the map.

Run the renderer/projection regression without starting Minecraft:

```sh
node scripts/test-claim-grid.mjs build/reports/claim-grid.png
```

The generated image is the actual claim raster on a synthetic background, not an
in-game screenshot. Checks cover rectangles, negative coordinates, four facings,
three pitches, picking agreement, tile seams, overviews, gaps and force-load
borders. The opt-in `ftb-claims`, `opac-claims` and MineColonies game scenarios
remain the integration checks; run them through the repository's boat workflow.
