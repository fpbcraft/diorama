import test from 'node:test';
import assert from 'node:assert/strict';
import { ZoomPreview } from '../../src/main/resources/diorama/web/map-controls.js';
import { WebFog } from '../../src/main/resources/diorama/web/web-fog.js';

const view={u:-2940,v:-259,scale:.136,width:1200,height:800,dpr:2,dimension:'minecraft:overworld'};
test('rapid zoom reuses one bounded image, preserves world anchors and settles once',t=>{
  t.mock.timers.enable({apis:['setTimeout']});
  const copies=[],draws=[];let settled=0,created=0;
  const image={getContext:()=>({drawImage:(...args)=>copies.push(args)})};
  const preview=new ZoomPreview(()=>settled++,()=>{created++;return image;});
  const canvas={width:2400,height:1600};
  preview.begin(canvas,view);
  for(let i=0;i<20;i++){t.mock.timers.tick(15);preview.begin(canvas,view);}
  assert.equal(created,1);assert.equal(copies.length,1);assert.equal(settled,0);
  assert.ok(image.width*image.height<=2000000*1.01);
  const after={...view,u:-2920,v:-260,scale:.3};
  const ctx={drawImage:(...args)=>draws.push(args)};
  assert.equal(preview.render(ctx,after),true);
  const [,x,y,w,h]=draws[0];
  for(const [u,v]of [[view.u,view.v],[-3000,-200]]){
    const oldX=(u-view.u)*view.scale+view.width/2,oldY=(v-view.v)*view.scale+view.height/2;
    assert.ok(Math.abs(x+oldX/view.width*w-((u-after.u)*after.scale+after.width/2)*after.dpr)<1e-8);
    assert.ok(Math.abs(y+oldY/view.height*h-((v-after.v)*after.scale+after.height/2)*after.dpr)<1e-8);
  }
  t.mock.timers.tick(159);assert.equal(settled,0);
  t.mock.timers.tick(1);assert.equal(settled,1);assert.equal(preview.active,false);
  assert.equal(preview.render(ctx,after),true,'retain backdrop while sharp replacement loads');
  assert.equal(preview.render(ctx,{...after,dimension:'minecraft:the_nether'}),false);
  assert.equal(image.width,0,'release snapshot on dimension change');
});
test('resize invalidates preview and large DPR does not allocate an oversized copy',()=>{
  const image={getContext:()=>({drawImage(){}})};
  const preview=new ZoomPreview(()=>{},()=>image);
  preview.begin({width:12000,height:8000},{...view,width:6000,height:4000,dpr:2});
  assert.ok(image.width*image.height<2020000);
  assert.equal(preview.render({drawImage(){}},{...view,width:5000,height:4000,dpr:2}),false);
  assert.equal(preview.active,false);
});
test('cloud zoom preview transforms existing coverage without readback, requests or shader redraw',t=>{
  const previous=globalThis.document;globalThis.document={hidden:false};t.after(()=>{if(previous===undefined)delete globalThis.document;else globalThis.document=previous;});
  const fog=Object.create(WebFog.prototype);let cancelled=0,positioned=0;
  Object.assign(fog,{canvas:{style:{},dataset:{}},mask:{known:'preserved'},dimension:view.dimension,
    layout:{},renderer(){},position(){positioned++;},maskTask:{cancel(){cancelled++;}},
    coverage:{begin(){throw Error('coverage rebuilt during zoom');}},gpu:{draw(){throw Error('shader rendered during zoom');}}});
  fog.update({...view,interactive:true},()=>{throw Error('coverage requested during zoom');});
  fog.paint(10,true);
  assert.equal(fog.mask.known,'preserved');assert.equal(cancelled,1);assert.equal(positioned,2);
  assert.equal(fog.maskKey,'','settling must rebuild even if its old layout key matches');
  fog.mask=null;fog.update({...view,interactive:true},[]);
  assert.equal(cancelled,2,'initial pending mask is safely cancelled too');
});
