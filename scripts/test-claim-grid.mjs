import { mkdtemp, mkdir, copyFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const temp = await mkdtemp(join(tmpdir(), "diorama-claim-grid-"));
const stubs = {
  "org/jspecify/annotations/Nullable.java": "package org.jspecify.annotations; import java.lang.annotation.*; @Target(ElementType.TYPE_USE) public @interface Nullable {}",
  "net/minecraft/core/BlockPos.java": "package net.minecraft.core; public record BlockPos(int x,int y,int z) {public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;}}",
  "net/minecraft/world/level/block/Rotation.java": "package net.minecraft.world.level.block; public enum Rotation {NONE,COUNTERCLOCKWISE_90,CLOCKWISE_180,CLOCKWISE_90}",
  "net/minecraft/world/level/block/state/BlockState.java": "package net.minecraft.world.level.block.state; public class BlockState {public BlockState rotate(net.minecraft.world.level.block.Rotation r){return this;}}",
  "dev/diorama/MapStyle.java": "package dev.diorama; public record MapStyle(double pitchDegrees) {public static final MapStyle DEFAULT=new MapStyle(40);}",
  "dev/diorama/Claims.java": "package dev.diorama; public class Claims {public record Claim(int rgb, boolean forceLoaded,String name){} public record Snapshot(java.util.function.BiFunction<Integer,Integer,Claim> lookup){public Claim at(int x,int z){return lookup.apply(x,z);}}}",
};
try {
  const files = [];
  for (const [name, text] of Object.entries(stubs)) {
    const file = join(temp, name);
    await mkdir(join(file, ".."), { recursive: true });
    await writeFile(file, text);
    files.push(file);
  }
  for (const name of ["dev/diorama/Projection.java", "dev/diorama/Facing.java", "dev/diorama/ClaimProjection.java", "dev/diorama/bake/TileKey.java", "dev/diorama/render/ClaimPaint.java"]) {
    const file = join(temp, name);
    await mkdir(join(file, ".."), { recursive: true });
    await copyFile(join(root, "src/main/java", name), file);
    files.push(file);
  }
  const test = join(temp, "dev/diorama/render/ClaimGridTest.java");
  await copyFile(join(root, "tests/web/ClaimGridTest.java"), test);
  files.push(test);
  const bin = (name) => process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", name) : name;
  execFileSync(bin("javac"), ["-d", temp, ...files], { stdio: "inherit" });
  execFileSync(bin("java"), ["-Djava.awt.headless=true", "-cp", temp, "dev.diorama.render.ClaimGridTest", ...(process.argv[2] ? [resolve(process.argv[2])] : [])], { stdio: "inherit" });
} finally {
  await rm(temp, { recursive: true, force: true });
}
