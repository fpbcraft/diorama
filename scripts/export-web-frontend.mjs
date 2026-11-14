import { cp, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
const output=process.argv[2];
if(!output)throw Error('Usage: node scripts/export-web-frontend.mjs OUTPUT');
const source=fileURLToPath(new URL('../src/main/resources/diorama/web/',import.meta.url));
await mkdir(resolve(output),{recursive:true});
await cp(source,resolve(output),{recursive:true});
console.log('Exported standalone Diorama web frontend');
