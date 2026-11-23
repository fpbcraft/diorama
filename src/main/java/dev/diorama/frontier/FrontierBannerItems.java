package dev.diorama.frontier;

import dev.diorama.render.IconBatch;
import java.util.ArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;
//? if >=1.20.5 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
//?} else {
/*import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BlockEntityType;
*///?}

/** Maps a frontier's portable banner data to and from the game's held item and renderer. */
public final class FrontierBannerItems {
    private FrontierBannerItems() {
    }

    public static @Nullable FrontierBanner read(ItemStack stack) {
        if (!(stack.getItem() instanceof BannerItem item)) return null;
        var layers = new ArrayList<FrontierBanner.Layer>();
        //? if >=1.20.5 {
        for (var layer : stack.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY).layers()) {
            if (layers.size() >= FrontierBanner.MAX_LAYERS) break;
            layer.pattern().unwrapKey().ifPresent(key ->
                layers.add(new FrontierBanner.Layer(key.identifier().toString(), layer.color().getId())));
        }
        //?} else {
        /*ListTag saved = BannerBlockEntity.getItemPatterns(stack);
        if (saved != null) {
            for (int i = 0; i < Math.min(saved.size(), FrontierBanner.MAX_LAYERS); i++) {
                CompoundTag layer = saved.getCompound(i);
                var pattern = BannerPattern.byHash(layer.getString(BannerBlockEntity.TAG_PATTERN));
                if (pattern == null) continue;
                pattern.unwrapKey().ifPresent(key -> layers.add(new FrontierBanner.Layer(key.identifier().toString(),
                    layer.getInt(BannerBlockEntity.TAG_COLOR))));
            }
        }
        *///?}
        FrontierBanner banner = new FrontierBanner(item.getColor().getId(), layers);
        return banner.valid() ? banner : null;
    }

    public static ItemStack stack(@Nullable FrontierBanner banner) {
        if (banner == null || Minecraft.getInstance().level == null) return ItemStack.EMPTY;
        String name = DyeColor.byId(banner.base()).getName() + "_banner";
        ItemStack stack = BuiltInRegistries.ITEM.getOptional(Identifier.withDefaultNamespace(name))
            .map(ItemStack::new).orElse(ItemStack.EMPTY);
        //? if >=1.20.5 {
        var patterns = Minecraft.getInstance().level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        var builder = new BannerPatternLayers.Builder();
        for (FrontierBanner.Layer layer : banner.layers()) {
            Identifier id = Identifier.tryParse(layer.pattern());
            if (id != null) builder.addIfRegistered(patterns, ResourceKey.create(Registries.BANNER_PATTERN, id), DyeColor.byId(layer.dye()));
        }
        stack.set(DataComponents.BANNER_PATTERNS, builder.build());
        //?} else {
        /*ListTag patterns = new ListTag();
        for (FrontierBanner.Layer layer : banner.layers()) {
            Identifier id = Identifier.tryParse(layer.pattern());
            if (id == null) continue;
            BuiltInRegistries.BANNER_PATTERN.getOptional(id).ifPresent(pattern -> {
                CompoundTag entry = new CompoundTag();
                entry.putString(BannerBlockEntity.TAG_PATTERN, pattern.getHashname());
                entry.putInt(BannerBlockEntity.TAG_COLOR, layer.dye());
                patterns.add(entry);
            });
        }
        if (!patterns.isEmpty()) {
            CompoundTag data = new CompoundTag();
            data.put(BannerBlockEntity.TAG_PATTERNS, patterns);
            BlockItem.setBlockEntityData(stack, BlockEntityType.BANNER, data);
        }
        *///?}
        return stack;
    }

    public static void draw(GuiGraphicsExtractor graphics, ItemStack stack, float x, float y, float size) {
        if (!stack.isEmpty()) IconBatch.direct(graphics).item(stack, x, y, size / 16);
    }
}
