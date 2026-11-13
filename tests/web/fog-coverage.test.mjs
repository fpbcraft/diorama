import test from 'node:test';
import assert from 'node:assert/strict';
import { FogCoverage,coverageSources } from '../../src/main/resources/diorama/web/fog-coverage.js';
import { cloudsEnabled } from '../../src/main/resources/diorama/web/web-fog.js';

const bounds={u:-256,v:-256,units:1024,texel:4};
const source=(mask,units=128)=>({mask,units,u:0,v:0,key:'0,0',zoom:0,version:1,dimension:'minecraft:overworld'});
const fakeImages=t=>{
  const previous=globalThis.Image,images=[];
  globalThis.Image=class {constructor(){images.push(this);}complete(){this.naturalWidth=this.naturalHeight=64;this.onload();}fail(){this.onerror();}};
  t.after(()=>{globalThis.Image=previous;});return images;
};
test('coverage requests come from the manifest rather than loaded JPEGs',()=>{
  const levels=[new Map([['0,0',1]]),new Map([['0,0',2]]),new Map([['0,0',3]])];
  for(const level of levels)level[Symbol.iterator]=()=>{throw Error('Coverage must only look up visible coordinates');};
  const sources=coverageSources(levels,bounds,(z,u,v,extension,version)=>`${z}/${u}_${v}${extension}?v=${version}`);
  assert.deepEqual(sources.map(s=>s.zoom),[2,1]);
  assert.equal(sources[1].mask,'1/0_0.fog.png?v=2');
});
test('zooming retains ready and overlapping in-flight masks',t=>{
  const images=fakeImages(t),coverage=new FogCoverage(()=>{});t.after(()=>coverage.dispose());
  const fine=source('fine'),coarse=source('coarse',512);
  coverage.begin([fine],bounds);assert.equal(coverage.get(fine),null);
  coverage.begin([coarse],bounds);assert.equal(coverage.active,1);
  images[0].complete();assert.equal(coverage.ready(bounds,'minecraft:overworld')[0].source.mask,'fine');
  coverage.get(coarse);assert.equal(coverage.ready(bounds,'minecraft:overworld').length,1);
  images[1].complete();assert.deepEqual(coverage.ready(bounds,'minecraft:overworld').map(e=>e.source.mask),['coarse','fine']);
  coverage.begin([coarse],bounds,s=>s.mask!=='fine');assert.deepEqual(coverage.ready(bounds,'minecraft:overworld').map(e=>e.source.mask),['coarse']);
});
test('failed masks stay unknown rather than revealing whole black JPEG tiles',t=>{
  const images=fakeImages(t),coverage=new FogCoverage(()=>{});t.after(()=>coverage.dispose());
  const tile=source('missing');coverage.begin([tile],bounds);coverage.get(tile);images[0].fail();
  assert.equal(coverage.get(tile),null);assert.equal(coverage.ready(bounds,'minecraft:overworld').length,0);
});
test('Nether and smoke maps never show clouds',()=>{
  assert.equal(cloudsEnabled({dimension:'minecraft:the_nether'}),false);
  assert.equal(cloudsEnabled({dimension:'mod:roofed',smoke:true}),false);
  assert.equal(cloudsEnabled({dimension:'minecraft:overworld',enabled:false}),false);
  assert.equal(cloudsEnabled({dimension:'minecraft:overworld'}),true);
});
