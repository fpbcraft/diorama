import { mkdtemp, mkdir, copyFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../", import.meta.url));
const temp = await mkdtemp(join(tmpdir(), "diorama-terrain-test-"));
try {
  const source = join(temp,"dev/diorama/web"), bake = join(temp,"dev/diorama/bake");
  await mkdir(source,{recursive:true}); await mkdir(bake,{recursive:true});
  await writeFile(join(bake,"TileSurface.java"), "package dev.diorama.bake; public record TileSurface(int width, int height, float[] near) {}");
  const files = [join(bake,"TileSurface.java")];
  for (const name of ["DirtyChunks", "WebDepth"]) { const out=join(source,`${name}.java`); await copyFile(resolve(root,`src/main/java/dev/diorama/web/${name}.java`),out); files.push(out); }
  const test=join(source,"HybridTerrainTest.java"); await copyFile(resolve(root,"tests/web/HybridTerrainTest.java"),test); files.push(test);
  const java=process.env.JAVA_HOME ? join(process.env.JAVA_HOME,"bin") : "/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home/bin";
  execFileSync(join(java,"javac"),["-d",temp,...files],{stdio:"inherit"});
  execFileSync(join(java,"java"),["-cp",temp,"dev.diorama.web.HybridTerrainTest"],{stdio:"inherit"});
} finally { await rm(temp,{recursive:true,force:true}); }
