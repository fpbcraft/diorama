import {mkdtemp,mkdir,writeFile,copyFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';import {join} from 'node:path';import {execFileSync} from 'node:child_process';
const root=new URL('../',import.meta.url),temp=await mkdtemp(join(tmpdir(),'web-tiles-'));
try{
 const web=join(temp,'dev/diorama/web'),ann=join(temp,'org/jspecify/annotations');await mkdir(web,{recursive:true});await mkdir(ann,{recursive:true});
 const files=[];
 const projection=join(temp,'dev/diorama/Projection.java');await writeFile(projection,'package dev.diorama;public class Projection{public static final int TILE_PIXELS=512;}');files.push(projection);
 const annotation=join(ann,'Nullable.java');await writeFile(annotation,'package org.jspecify.annotations;@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE})public @interface Nullable{}');files.push(annotation);
 for(const [name,path]of [['WebTiles','src/main/java/dev/diorama/web/'],['WebCoverage','src/main/java/dev/diorama/web/'],['WebTilesTest','tests/web/']]){const out=join(web,name+'.java');await copyFile(new URL(path+name+'.java',root),out);files.push(out);}
 const bin=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin'):'/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin';
 execFileSync(join(bin,'javac'),['-d',temp,...files],{stdio:'inherit'});execFileSync(join(bin,'java'),['-cp',temp,'dev.diorama.web.WebTilesTest'],{stdio:'inherit'});
}finally{await rm(temp,{recursive:true,force:true});}
