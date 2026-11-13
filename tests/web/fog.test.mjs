import test from 'node:test';
import assert from 'node:assert/strict';
import { fogLayout,roundKnown,fogMask,MASK_SIZE } from '../../src/main/resources/diorama/web/fog-mask.js';
import { revealProgress,revealFrom } from '../../src/main/resources/diorama/web/fog-reveal.js';

test('rounded coverage never reveals missing ground and preserves the interior',()=>{
  const known=new Float32Array(64*64);for(let y=8;y<56;y++)for(let x=8;x<56;x++)known[y*64+x]=1;
  const rounded=roundKnown(known,5,64,64);
  for(let i=0;i<known.length;i++)assert.ok(rounded[i]<=known[i]);
  assert.equal(rounded[32*64+32],1);assert.ok(rounded[8*64+8]<1);
});
test('fog layout remains world anchored across negative coordinates, zooms and sizes',()=>{
  for(const scale of [.002,.1,1,8])for(const [width,height] of [[390,844],[1920,1080],[3200,900]]) {
    const view={u:-12345,v:-6789,scale},l=fogLayout(view,width,height);
    assert.ok(l.u<=view.u-width/2/scale);assert.ok(l.v<=view.v-height/2/scale);
    assert.ok(l.u+l.units>=view.u+width/2/scale);assert.ok(l.v+l.units>=view.v+height/2/scale);
  }
});
test('fully known and unknown masks receive safe shader skip flags',()=>{
  const full=fogMask(new Float32Array(MASK_SIZE*MASK_SIZE).fill(1),8),empty=fogMask(new Float32Array(MASK_SIZE*MASK_SIZE),8);
  assert.equal(full.rgba[1],255);assert.equal(full.detail[2],255);
  assert.equal(empty.rgba[2],255);assert.equal(empty.detail[2],0);
});
test('new coverage reveals smoothly, overlapping arrivals continue, and missing ground hides immediately',()=>{
  const mask=value=>({rgba:new Uint8Array([value,0,0,255]),detail:new Uint8Array([0,0,value,255])});
  const previous={from:mask(0),to:mask(255)};
  assert.equal(revealProgress(0,0),0);assert.equal(revealProgress(0,400),.5);assert.equal(revealProgress(0,800),1);
  assert.equal(revealProgress(0,100,true),1);
  assert.equal(revealFrom(previous,mask(255),.5).rgba[0],128);
  assert.equal(revealFrom(previous,mask(0),.5).rgba[0],0);
});

test('coarse zoom levels keep rounded cloud borders',()=>{
  const sharp=new Float32Array(MASK_SIZE*MASK_SIZE);
  for(let y=64;y<192;y++)for(let x=64;x<192;x++)sharp[y*MASK_SIZE+x]=1;
  for(const texel of [16,64,256]){
    const mask=fogMask(sharp,texel,true);
    assert.ok(mask.known[64*MASK_SIZE+64]<1);
    assert.equal(mask.known[128*MASK_SIZE+128],1);
    for(let i=0;i<sharp.length;i++)assert.ok(mask.known[i]<=sharp[i]);
  }
});
