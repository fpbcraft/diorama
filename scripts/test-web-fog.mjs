import { mkdtemp,mkdir,copyFile,writeFile,rm,cp } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join,resolve } from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const args=process.argv.slice(2);if(args.length&&!(args.length===2&&args[0]==='--assets'))throw Error('Usage: test-web-fog.mjs [--assets OUTPUT]');
const temp=await mkdtemp(join(tmpdir(),'diorama-fog-'));
try {
  const web=join(temp,'dev/diorama/web'),bake=join(temp,'dev/diorama/bake');await mkdir(web,{recursive:true});await mkdir(bake,{recursive:true});
  const files=[];
  for(const name of ['WebCoverage','WebFogAssets']){const out=join(web,name+'.java');await copyFile(join(root,'src/main/java/dev/diorama/web',name+'.java'),out);files.push(out);}
  const noise=join(bake,'FogNoise.java');await copyFile(join(root,'src/main/java/dev/diorama/bake/FogNoise.java'),noise);files.push(noise);
  const test=join(web,'WebFogTest.java');await copyFile(join(root,'tests/web/WebFogTest.java'),test);files.push(test);
  await cp(join(root,'src/main/resources/diorama/web'),join(temp,'diorama/web'),{recursive:true});
  const shaders=join(temp,'assets/diorama/shaders/core');await mkdir(shaders,{recursive:true});await copyFile(join(root,'src/main/resources/assets/diorama/shaders/core/fog.fsh'),join(shaders,'fog.fsh'));
  const bin=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin'):'/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin';
  execFileSync(join(bin,'javac'),['-d',temp,...files],{stdio:'inherit'});
  execFileSync(join(bin,'java'),['-cp',temp,'dev.diorama.web.WebFogTest',...(args.length?[resolve(args[1])]:[])],{stdio:'inherit'});
}finally{await rm(temp,{recursive:true,force:true});}
