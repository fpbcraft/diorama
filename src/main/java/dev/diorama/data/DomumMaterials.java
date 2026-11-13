package dev.diorama.data;

import dev.diorama.Diorama;
import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}

/** Optional material-slot snapshots. No Domum classes, live worlds or block entities reach a bake. */
public final class DomumMaterials {
    //? if fabric {
    public static final boolean INSTALLED=FabricLoader.getInstance().isModLoaded("domum_ornamentum");
    //?} else {
    /*public static final boolean INSTALLED=ModList.get().isLoaded("domum_ornamentum");
    *///?}
    private static final String API="com.ldtteam.domumornamentum.entity.block.IMateriallyTexturedBlockEntity";
    public record Cell(int x,int y,int z,Map<String,BlockState> slots){public Cell{slots=Map.copyOf(slots);}}
    private record Reader(Method data,Method slots){}
    private static final ClassValue<Optional<Reader>> READERS=new ClassValue<>(){
        @Override protected Optional<Reader> computeValue(Class<?> type){
            try{Class<?> api=supertype(type,API);if(api==null)return Optional.empty();Method data=api.getMethod("getTextureData");return Optional.of(new Reader(data,data.getReturnType().getMethod("getTexturedComponents")));}
            catch(ReflectiveOperationException|LinkageError error){return Optional.empty();}
        }
    };
    private static final ClassValue<Boolean> TEXTURED=new ClassValue<>(){
        @Override protected Boolean computeValue(Class<?> type){return supertype(type,"com.ldtteam.domumornamentum.block.IMateriallyTexturedBlock")!=null;}
    };
    private static boolean warned;
    private DomumMaterials(){}
    private static @Nullable Class<?> supertype(Class<?> type,String name){
        if(type.getName().equals(name))return type;
        for(Class<?> api:type.getInterfaces()){Class<?> found=supertype(api,name);if(found!=null)return found;}
        return type.getSuperclass()==null?null:supertype(type.getSuperclass(),name);
    }
    public static boolean textured(BlockState state){return INSTALLED&&TEXTURED.get(state.getBlock().getClass());}
    public static boolean supported(BlockEntity entity){return INSTALLED&&READERS.get(entity.getClass()).isPresent();}
    /** Owning game thread only. */
    public static Map<String,BlockState> read(@Nullable BlockEntity entity){
        if(entity==null||!INSTALLED)return Map.of();var reader=READERS.get(entity.getClass());if(reader.isEmpty())return Map.of();
        try{
            Object raw=reader.get().slots.invoke(reader.get().data.invoke(entity));
            if(!(raw instanceof Map<?,?> map)||map.size()>32)return Map.of();
            var slots=new TreeMap<String,BlockState>();
            for(var entry:map.entrySet())if(entry.getValue() instanceof Block block){String id=String.valueOf(entry.getKey());if(valid(id))slots.put(id,block.defaultBlockState());}
            return Map.copyOf(slots);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError error){if(!warned){warned=true;Diorama.LOGGER.warn("Couldn't read Domum materials; using the block's default appearance",error);}return Map.of();}
    }
    public static Map<String,BlockState> read(CompoundTag entity){
        if(!INSTALLED)return Map.of();CompoundTag data=Nbt.compound(entity,"textureData");if(Nbt.keys(data).size()>32)return Map.of();
        var slots=new TreeMap<String,BlockState>();
        for(String key:Nbt.keys(data)){
            if(!valid(key))continue;Identifier id=Identifier.tryParse(Nbt.string(data,key));if(id==null)continue;
            var block=BuiltInRegistries.BLOCK.getOptional(id);if(block.isPresent())slots.put(key,block.get().defaultBlockState());
        }return Map.copyOf(slots);
    }
    private static boolean valid(String id){return id.length()<=256&&Identifier.tryParse(id)!=null;}
    public static List<Cell> capture(LevelChunk chunk){
        if(!INSTALLED)return List.of();var cells=new ArrayList<Cell>();
        for(BlockEntity entity:chunk.getBlockEntities().values()){
            var slots=read(entity);if(slots.isEmpty())continue;var pos=entity.getBlockPos();cells.add(new Cell(pos.getX()&15,pos.getY(),pos.getZ()&15,slots));
            if(cells.size()>=4096)break;
        }return List.copyOf(cells);
    }
    public static List<Cell> saved(int chunkX,int chunkZ,CompoundTag chunk){
        if(!INSTALLED)return List.of();var cells=new ArrayList<Cell>();var entities=Nbt.list(chunk,"block_entities");
        for(int i=0;i<entities.size()&&cells.size()<4096;i++){
            CompoundTag entity=Nbt.compound(entities,i);var slots=read(entity);if(slots.isEmpty())continue;
            int x=Nbt.integer(entity,"x"),y=Nbt.integer(entity,"y"),z=Nbt.integer(entity,"z");
            if(x>>4==chunkX&&z>>4==chunkZ)cells.add(new Cell(x&15,y,z&15,slots));
        }return List.copyOf(cells);
    }
}
