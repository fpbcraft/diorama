import { mkdtemp, mkdir, writeFile, rm, readdir } from 'node:fs/promises';
import { tmpdir, homedir } from 'node:os';
import { join, delimiter } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
const root=fileURLToPath(new URL('../',import.meta.url));
const cache=join(process.env.GRADLE_USER_HOME??join(homedir(),'.gradle'),'caches/modules-2/files-2.1/com.google.code.gson/gson');
let gson=process.env.GSON_JAR;
if(!gson){
 for(const version of (await readdir(cache)).sort((a,b)=>a==='2.10.1'?-1:b==='2.10.1'?1:b.localeCompare(a,undefined,{numeric:true}))){
  if(version.localeCompare('2.10',undefined,{numeric:true})<0)continue;
  for(const hash of await readdir(join(cache,version)))for(const name of await readdir(join(cache,version,hash)))if(name.endsWith('.jar')&&!name.includes('sources')&&!name.includes('javadoc')){gson=join(cache,version,hash,name);break;}
  if(gson)break;
 }
}
if(!gson)throw Error('Run the Gradle build first, or set GSON_JAR to Gson 2.10 or newer');
const temp=await mkdtemp(join(tmpdir(),'diorama-frontiers-'));
try {
 await mkdir(join(temp,'dev/diorama'),{recursive:true});
 const log=join(temp,'dev/diorama/Diorama.java');
 await writeFile(log,'package dev.diorama; public class Diorama {public static final Log LOGGER=new Log();public static class Log {public void warn(String s,Object...v){}}}');
 const bin=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin'):'/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin';
 const annotations=join(temp,'org/jspecify/annotations');
 await mkdir(annotations,{recursive:true});
 const nullable=join(annotations,'Nullable.java');
 await writeFile(nullable,'package org.jspecify.annotations; public @interface Nullable {}');
 const files=['src/main/java/dev/diorama/frontier/FrontierBanner.java','src/main/java/dev/diorama/frontier/Frontier.java','src/main/java/dev/diorama/frontier/LabelAnchor.java','src/main/java/dev/diorama/web/WebFrontiers.java','tests/web/WebFrontiersTest.java'];
 execFileSync(join(bin,'javac'),['-cp',gson,'-d',temp,log,nullable,...files.map(f=>join(root,f))],{stdio:'inherit'});
 execFileSync(join(bin,'java'),['-cp',temp+delimiter+gson,'dev.diorama.web.WebFrontiersTest',temp],{stdio:'inherit',timeout:12000});
} finally {await rm(temp,{recursive:true,force:true});}
