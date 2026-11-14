import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
const root=fileURLToPath(new URL('../',import.meta.url)),temp=await mkdtemp(join(tmpdir(),'diorama-url-icons-'));
const bin=process.env.JAVA_HOME?join(process.env.JAVA_HOME,'bin'):'/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home/bin';
try {
  execFileSync(join(bin,'javac'),['-d',temp,join(root,'src/main/java/dev/diorama/render/UrlWaypointIcons.java'),join(root,'tests/icons/UrlWaypointIconsTest.java'),join(root,'src/main/java/dev/diorama/waypoint/WaypointIconGrid.java'),join(root,'tests/icons/WaypointIconGridTest.java')],{stdio:'inherit'});
  execFileSync(join(bin,'java'),['-cp',temp,'dev.diorama.render.UrlWaypointIconsTest'],{stdio:'inherit',timeout:30000});
  execFileSync(join(bin,'java'),['-cp',temp,'dev.diorama.waypoint.WaypointIconGridTest'],{stdio:'inherit'});
} finally { await rm(temp,{recursive:true,force:true}); }
