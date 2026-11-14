import {mkdtemp,mkdir,writeFile,copyFile,rm,readdir} from 'node:fs/promises';
import {tmpdir,homedir} from 'node:os';
import {join,dirname} from 'node:path';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url)),temp=await mkdtemp(join(tmpdir(),'diorama-materials-'));
const stubs={
 "dev/diorama/Diorama.java": "package dev.diorama;public class Diorama{public static Log LOGGER=new Log();public static class Log{public void warn(String s,Object...a){}}}",
 "org/jspecify/annotations/Nullable.java": "package org.jspecify.annotations;import java.lang.annotation.*;@Target({ElementType.TYPE_USE})public @interface Nullable{}",
 "net/minecraft/resources/Identifier.java": "package net.minecraft.resources;public record Identifier(String namespace,String path){public static Identifier fromNamespaceAndPath(String n,String p){return new Identifier(n,p);}public String toString(){return namespace+\":\"+path;}}",
 "net/minecraft/world/level/block/Block.java": "package net.minecraft.world.level.block;public class Block{public final String id;public Block(String id){this.id=id;}public net.minecraft.world.level.block.state.BlockState defaultBlockState(){return new net.minecraft.world.level.block.state.BlockState(this);}}",
 "net/minecraft/core/registries/BuiltInRegistries.java": "package net.minecraft.core.registries;import net.minecraft.world.level.block.Block;import net.minecraft.resources.Identifier;import java.util.*;public class BuiltInRegistries{public static final Registry BLOCK=new Registry();public static class Registry{public Identifier getKey(Block b){String[] s=b.id.split(\":\");return new Identifier(s[0],s[1]);}public Optional<Block> getOptional(Identifier id){return Optional.of(new Block(id.toString()));}}}",
 "net/minecraft/tags/BlockTags.java": "package net.minecraft.tags;public class BlockTags{public static final Object LEAVES=new Object();}",
 "net/minecraft/world/level/block/state/properties/Property.java": "package net.minecraft.world.level.block.state.properties;public class Property<T extends Comparable<T>>{public String getName(){return \"facing\";}public String getName(T v){return v.toString();}}",
 "net/minecraft/world/level/block/state/BlockState.java": "package net.minecraft.world.level.block.state;import net.minecraft.world.level.block.Block;import net.minecraft.world.level.block.state.properties.Property;import java.util.*;public record BlockState(Block block){public Block getBlock(){return block;}public boolean is(Object tag){return false;}public boolean isAir(){return block.id.equals(\"minecraft:air\");}public Collection<Property<?>> getProperties(){return List.of();}public <T extends Comparable<T>> T getValue(Property<T> p){return null;}}",
 "net/minecraft/server/packs/resources/ResourceManager.java": "package net.minecraft.server.packs.resources;import java.io.*;import java.util.*;import net.minecraft.resources.Identifier;public interface ResourceManager{Optional<Resource> getResource(Identifier id);interface Resource{InputStream open()throws IOException;}}",
 "dev/diorama/bake/BlockLooks.java": "package dev.diorama.bake;public class BlockLooks{}",
 "dev/diorama/bake/Looks.java": "package dev.diorama.bake;import net.minecraft.world.level.block.state.BlockState;public class Looks{public static final byte TINT_FOLIAGE=1,TINT_GRASS=2;public int[] top={0xff0000,0x0000ff,0x00ff00},side=top,topTinted=top,sideTinted=top;public float[] topTintWeight={0,0,1},sideTintWeight=topTintWeight;public int lookId(BlockState s){return s.getBlock().id.contains(\"blue\")?1:s.getBlock().id.contains(\"green\")?2:0;}}"
};
async function gsonAt(dir){for(const entry of await readdir(dir,{withFileTypes:true})){const p=join(dir,entry.name);if(entry.isFile()&&/^gson-[0-9.]+\.jar$/.test(entry.name))return p;if(entry.isDirectory()){const found=await gsonAt(p);if(found)return found;}}}
try{
 const gson=await gsonAt(join(homedir(),'.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson'));if(!gson)throw new Error('Gson missing: build Diorama first');
 const files=[];
 for(const [name,text]of Object.entries(stubs)){const path=join(temp,name);await mkdir(dirname(path),{recursive:true});await writeFile(path,text);files.push(path);}
 for(const name of ['JarModels','FaceColor','VanillaParents']){const path=join(temp,'dev/diorama/bake',name+'.java');await mkdir(dirname(path),{recursive:true});await copyFile(join(root,'src/main/java/dev/diorama/bake',name+'.java'),path);files.push(path);}
 const test=join(temp,'dev/diorama/bake/MaterialModelsTest.java');await copyFile(join(root,'tests/web/MaterialModelsTest.java'),test);files.push(test);
 const bin=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin'):'/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin';
 execFileSync(join(bin,'javac'),['-cp',gson,'-d',temp,...files],{stdio:'inherit'});
 execFileSync(join(bin,'java'),['-cp',temp+':'+gson,'dev.diorama.bake.MaterialModelsTest',temp],{stdio:'inherit'});
}finally{await rm(temp,{recursive:true,force:true});}
