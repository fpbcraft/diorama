import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';
if(!process.argv[2])throw Error('Usage: export-web-pins.mjs OUTPUT');
// Keep asset generation on the same classpath and pixel checks as the waypoint publisher.
execFileSync(process.execPath,[fileURLToPath(new URL('./test-web-markers.mjs',import.meta.url)),'--assets',resolve(process.argv[2])],{stdio:'inherit'});
