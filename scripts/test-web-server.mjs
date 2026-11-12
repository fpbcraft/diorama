import { mkdtemp, mkdir, copyFile, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../", import.meta.url));
const temp = await mkdtemp(join(tmpdir(), "diorama-java-test-"));
try {
  const source = join(temp, "dev/diorama/web");
  await mkdir(source, { recursive: true });
  await mkdir(join(temp, "org/jspecify/annotations"), { recursive: true });
  // Stubs isolate the HTTP handler from Minecraft/logging, not its security behavior.
  await writeFile(join(temp, "dev/diorama/Diorama.java"), 'package dev.diorama; public class Diorama { public static final Log LOGGER = new Log(); public static class Log { public void warn(String text, Object... values) { System.err.println(text); } } }');
  await writeFile(join(temp, "org/jspecify/annotations/Nullable.java"), 'package org.jspecify.annotations; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE}) public @interface Nullable {}');
  await copyFile(resolve(root, "src/main/java/dev/diorama/web/WebServer.java"), join(source, "WebServer.java"));
  await copyFile(resolve(root, "tests/web/WebServerSecurityTest.java"), join(source, "WebServerSecurityTest.java"));
  const java = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin") : "/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home/bin";
  execFileSync(join(java, "javac"), ["-d", temp, join(temp, "dev/diorama/Diorama.java"), join(temp, "org/jspecify/annotations/Nullable.java"), join(source, "WebServer.java"), join(source, "WebServerSecurityTest.java")], { stdio: "inherit" });
  execFileSync(join(java, "java"), ["-cp", temp, "dev.diorama.web.WebServerSecurityTest"], { stdio: "inherit", timeout: 30000 });
} finally { await rm(temp, { recursive: true, force: true }); }
