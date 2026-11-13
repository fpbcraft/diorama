import { mkdtemp, mkdir, copyFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const root=fileURLToPath(new URL("../",import.meta.url));
const temp=await mkdtemp(join(tmpdir(),"diorama-vehicles-"));
try {
  const source=join(temp,"dev/diorama/web/vehicles"),bake=join(temp,"dev/diorama/bake");
  await mkdir(source,{recursive:true});await mkdir(bake,{recursive:true});
  const stub=join(bake,"CraftBaker.java");
  await writeFile(stub,"package dev.diorama.bake; public final class CraftBaker { public record Image(int edge,int width,int height,double offsetU,double offsetV,int[][] mips,float[] depth,double[] linear,double pitchSin,int[] sunless){} }");
  const projection=join(temp,"dev/diorama/Projection.java");
  await writeFile(projection,"package dev.diorama; public final class Projection { public static final int PIXELS_PER_UNIT=4; public static Facing facing(){return new Facing();} public static class Facing { public final int turns=0; } }");
  const files=[stub,projection];
  const compat=join(temp,"dev/diorama/compat");await mkdir(compat,{recursive:true});
  for(const [name,from] of [["OptionalCall.java","src/main/java/dev/diorama/compat/OptionalCall.java"],["OptionalCallTest.java","tests/web/OptionalCallTest.java"]]){const out=join(compat,name);await copyFile(resolve(root,from),out);files.push(out);}
  for(const [name,from] of [["VehicleSprites.java","src/main/java/dev/diorama/web/vehicles/VehicleSprites.java"],["VehiclePublicationTest.java","tests/web/VehiclePublicationTest.java"]]){const out=join(source,name);await copyFile(resolve(root,from),out);files.push(out);}
  const bin=process.env.JAVA_HOME?join(process.env.JAVA_HOME,"bin"):"/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home/bin";
  execFileSync(join(bin,"javac"),["-d",temp,...files],{stdio:"inherit"});
  execFileSync(join(bin,"java"),["-cp",temp,"dev.diorama.web.vehicles.VehiclePublicationTest"],{stdio:"inherit"});
  await rm(join(compat,"OptionalCallTest$ClientOnly.class"));
  execFileSync(join(bin,"java"),["-cp",temp,"dev.diorama.compat.OptionalCallTest"],{stdio:"inherit"});
}finally{await rm(temp,{recursive:true,force:true});}
