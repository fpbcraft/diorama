package dev.diorama.bake;

import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.packs.resources.ResourceManager;

/** Two independent asset sources must preserve frame/infill proportions and biome tint. */
public final class MaterialModelsTest {
    public static void main(String[] args) throws Exception {
        Path dir=Path.of(args[0]);
        Map<String,String> assets=Map.of(
            "assets/test/blockstates/frame.json", "{\"variants\":{\"\":{\"model\":\"test:block/frame\"}}}",
            "assets/test/models/block/frame.json", "{\"textures\":{\"frame\":\"block/oak_planks\",\"infill\":\"block/dark_oak_planks\"},\"elements\":[{\"from\":[0,0,0],\"to\":[4,16,16],\"faces\":{\"up\":{\"texture\":\"#frame\",\"cullface\":\"up\"},\"south\":{\"texture\":\"#frame\",\"cullface\":\"south\"}}},{\"from\":[4,0,0],\"to\":[16,16,16],\"faces\":{\"up\":{\"texture\":\"#infill\",\"cullface\":\"up\"},\"south\":{\"texture\":\"#infill\",\"cullface\":\"south\"}}}]}",
            "assets/test/blockstates/composite.json", "{\"variants\":{\"\":{\"model\":\"test:block/composite\"}}}",
            "assets/test/models/block/composite.json", "{\"loader\":\"neoforge:composite\",\"children\":{\"base\":{\"parent\":\"test:block/frame\"}}}",
            "assets/test/blockstates/loop.json", "{\"variants\":{\"\":{\"model\":\"test:block/loop\"}}}",
            "assets/test/models/block/loop.json", "{\"children\":{\"base\":{\"parent\":\"test:block/loop\"}}}"
        );
        try(var zip=new ZipOutputStream(Files.newOutputStream(dir.resolve("models.jar")))){
            for(var asset:assets.entrySet()){zip.putNextEntry(new ZipEntry(asset.getKey()));zip.write(asset.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
        }
        ResourceManager resources=id->{String json=assets.get("assets/"+id.namespace()+"/"+id.path());return json==null?Optional.empty():Optional.of(()->new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));};
        var slots=Map.of("minecraft:block/oak_planks",state("test:red"),"minecraft:block/dark_oak_planks",state("test:blue"));
        JarModels server=JarModels.of(dir),client=JarModels.resources(resources);Looks looks=new Looks();
        for(JarModels source:List.of(server,client)){
            for(String name:List.of("frame","composite")){
                var faces=source.faces(state("test:"+name),slots,looks);
                check(faces!=null,"missing "+name);
                for(var face:faces){check(face.untinted()==0x4000bf,"weighted frame/infill: "+Integer.toHexString(face.untinted()));check(face.tintWeight()==0,"unexpected tint");}
            }
            var tinted=source.faces(state("test:frame"),Map.of("minecraft:block/oak_planks",state("test:red"),"minecraft:block/dark_oak_planks",state("test:green")),looks);
            check(Math.abs(tinted[0].tintWeight()-.75f)<.001,"lost material tint proportion");
            check(tinted[0].untinted()==0xff0000&&tinted[0].tinted()==0x00ff00,"mixed tinted and raw RGB");
            check(source.faces(state("test:loop"),slots,looks)==null,"recursive composite didn't stop");
            source.close();
        }
        System.out.println("Material model checks passed: frame/infill, composite, client/server sources, tint, recursion limit");
    }
    private static BlockState state(String id){return new Block(id).defaultBlockState();}
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}
