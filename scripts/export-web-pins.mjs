import { mkdtemp,mkdir,copyFile,writeFile,rm,cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join,resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url)),temp=await mkdtemp(join(tmpdir(),'diorama-pins-'));
try {
 const source=join(temp,'dev/diorama/web'),render=join(temp,'dev/diorama/render');
 await mkdir(source,{recursive:true});await mkdir(render,{recursive:true});await mkdir(join(temp,'org/jspecify/annotations'),{recursive:true});
 const annotation=join(temp,'org/jspecify/annotations/Nullable.java');await writeFile(annotation,'package org.jspecify.annotations; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE}) public @interface Nullable {}');
 await copyFile(join(root,'src/main/java/dev/diorama/web/WebPins.java'),join(source,'WebPins.java'));
 await copyFile(join(root,'src/main/java/dev/diorama/render/PinArt.java'),join(render,'PinArt.java'));
 await cp(join(root,'src/main/resources/assets'),join(temp,'assets'),{recursive:true});
 const main=join(source,'ExportPins.java');await writeFile(main,'package dev.diorama.web; public class ExportPins { public static void main(String[] args)throws Exception {var root=java.nio.file.Path.of(args[0]);WebPins.install(root);WebPins.write(root.resolve("assets/diorama-markers/home.png"),WebPins.art(0xff46a758,"home"));} }');
 const bin=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin'):'/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin';
 execFileSync(join(bin,'javac'),['-d',temp,annotation,join(source,'WebPins.java'),join(render,'PinArt.java'),main],{stdio:'inherit'});
 execFileSync(join(bin,'java'),['-cp',temp,'dev.diorama.web.ExportPins',resolve(process.argv[2])],{stdio:'inherit'});
}finally{await rm(temp,{recursive:true,force:true});}
