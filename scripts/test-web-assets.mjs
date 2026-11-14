import {mkdtemp,mkdir,writeFile,copyFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join,dirname} from 'node:path';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url)),temp=await mkdtemp(join(tmpdir(),'diorama-assets-'));
try {
 const log=join(temp,'dev/diorama/Diorama.java'); await mkdir(dirname(log),{recursive:true});
 await writeFile(log,'package dev.diorama;public class Diorama{public static final Log LOGGER=new Log();public static class Log{public void warn(String s,Object...v){}public void info(String s,Object...v){}}}');
 const source=join(temp,'WebFrontendAssets.java'),test=join(temp,'WebAssetsTest.java');
 await copyFile(join(root,'src/main/java/dev/diorama/web/WebFrontendAssets.java'),source);
 await copyFile(join(root,'tests/web/WebAssetsTest.java'),test);
 const bin=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin'):'/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin';
 execFileSync(join(bin,'javac'),['-d',temp,log,source,test],{stdio:'inherit'});
 for(const scenario of ['good','missing','bad-api'])execFileSync(join(bin,'java'),['-cp',temp,'dev.diorama.web.WebAssetsTest',temp,scenario],{stdio:'inherit'});
} finally { await rm(temp,{recursive:true,force:true}); }
