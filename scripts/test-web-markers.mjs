import { mkdtemp,mkdir,copyFile,writeFile,rm,readdir,cp } from 'node:fs/promises';
import { tmpdir,homedir } from 'node:os';
import { join,resolve,delimiter } from 'node:path';
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
const temp=await mkdtemp(join(tmpdir(),'diorama-markers-'));
try{
 const source=join(temp,'dev/diorama/web'),render=join(temp,'dev/diorama/render'),annotation=join(temp,'org/jspecify/annotations/Nullable.java');
 await mkdir(source,{recursive:true});await mkdir(render,{recursive:true});await mkdir(join(temp,'org/jspecify/annotations'),{recursive:true});
 await writeFile(annotation,'package org.jspecify.annotations; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE}) public @interface Nullable {}');
 const logger=join(temp,'dev/diorama/Diorama.java');await writeFile(logger,'package dev.diorama; public class Diorama { public static final Log LOGGER=new Log(); public static class Log { public void warn(String text,Object...values){System.err.println(text);} } }');
 const files=[annotation,logger];
 for(const name of ['WebPins','WebWaypoints','WebWaypointIcons']){const out=join(source,name+'.java');await copyFile(join(root,'src/main/java/dev/diorama/web',name+'.java'),out);files.push(out);}
 for(const [input,output]of [['src/main/java/dev/diorama/render/PinArt.java',join(render,'PinArt.java')],['tests/web/WebWaypointsTest.java',join(source,'WebWaypointsTest.java')]]){await copyFile(join(root,input),output);files.push(output);}
 await cp(join(root,'src/main/resources/assets'),join(temp,'assets'),{recursive:true});
 const bin=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin'):'/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin';
 execFileSync(join(bin,'javac'),['-cp',gson,'-d',temp,...files],{stdio:'inherit'});
 execFileSync(join(bin,'java'),['-cp',temp+delimiter+gson,'dev.diorama.web.WebWaypointsTest',...(process.argv[2]==='--assets'?[resolve(process.argv[3])]:[])],{stdio:'inherit',timeout:30000});
}finally{await rm(temp,{recursive:true,force:true});}
