# Domum Ornamentum materials

Diorama reads the material slots of Domum Ornamentum blocks on both the native map and the server web map. Slots preserve frame and infill colours separately, weighted by the model's face areas. Client resource packs are used by the native renderer; the dedicated server reads models from installed mod JARs and material colours from its appearance profile.

Live chunks, saved chunks, Sable bodies and Create contraptions carry immutable material-only snapshots into the renderer. Block entity inventories and other private NBT are not published. Material edits invalidate web terrain even when the block state stays the same. Saved-region change detection includes material data. Installing this support refreshes existing web terrain through the normal bounded queue; deleting tiles is unnecessary.

This follows Diorama's existing voxel-shape and directional-colour renderer. It does not introduce a full textured mesh renderer or reproduce arbitrary custom model-loader code. Resource packs that replace model geometry need matching server model assets to produce the same geometry.

## Validation

- `node scripts/test-material-models.mjs`: frame/infill proportions, composite models, client-resource and server-JAR paths, tint separation, recursive-model limit.
- Core NeoForge 1.21.1 build and existing terrain, track and vehicle checks.
- Local modded server: Domum 1.0.237-snapshot, gold/red and iron/blue timber-frame floors at (160,-60,160) and (168,-60,160); both render with different palettes. Changing (160,-60,160) to emerald/lime using `data merge block` republished its terrain within the configured update cycle without replacing the block.

Interactive native rendering and material-bearing moving vehicles still require visual acceptance testing.
