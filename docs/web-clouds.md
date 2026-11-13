# Web cloud borders

Unknown and pending terrain is covered by animated clouds using the in-game fog shader, shared deterministic noise, conservative rounding and surface coverage. Nether dimensions with a ceiling use its smoke treatment. The cloud canvas stays below players and overlays and does not intercept input.

A 64×64 alpha coverage PNG accompanies each terrain tile, including overview tiles. Dark blocks remain known: coverage is never inferred from terrain colour. Existing JPEGs without coverage masks fall back to their known tile extents until they are rendered again. Force-render existing terrain if precise internal holes are needed immediately; startup does not force an expensive rerender for this cosmetic change.

New coverage reveals over 800 ms with a smooth transition. Reduced motion skips the transition and freezes cloud drift. Background pages pause rendering. Clouds use half CSS resolution, a 1280-pixel maximum edge and 15 frames per second, independently of terrain downloads and map painting. WebGL2 uses the native cloud equations; unavailable/lost contexts fall back to a soft Canvas mask.

The isolated Java/JavaScript tests cover coverage export, overview holes, rounding, noise, transition handling and shader adaptation. The synthetic browser fixture checks unknown/revealed terrain pixels, an intermediate reveal frame, overlays and reduced motion. A real in-game comparison remains required for visual acceptance.
