import test from 'node:test';
import assert from 'node:assert/strict';
import { FogMaskTask } from '../../src/main/resources/diorama/web/fog-mask-task.js';
import { fogBufferSize,fogViewportTransform,FOG_GUTTER } from '../../src/main/resources/diorama/web/fog-gpu.js';
import { cloudFrameInterval } from '../../src/main/resources/diorama/web/web-fog.js';

test('large touch displays use a bounded cloud pixel budget independent of DPR',()=>{
  for(const [width,height]of [[400,900],[1366,1024],[2732,2048],[5120,2880]])for(const coarse of [false,true]){
    const size=fogBufferSize(width,height,coarse);
    assert.ok(size.width*size.height<=(coarse?90000:180000)*1.02);
    assert.ok(size.width<=960&&size.height<=960);
  }
  assert.equal(cloudFrameInterval(true),125);assert.equal(cloudFrameInterval(false),1000/15);
});
test('cached fog reprojects to the same world points during pan, zoom and resize',()=>{
  const before={u:-100,v:200,scale:2,width:1000,height:800},pad=FOG_GUTTER;
  for(const after of [{...before,u:-40,v:170},{...before,scale:.5},{...before,width:600,height:900,scale:4}]){
    const t=fogViewportTransform(before,after);
    for(const [u,v]of [[-100,200],[-200,250],[30,10]]){
      const oldX=(u-before.u)*before.scale+(before.width+2*pad)/2,oldY=(v-before.v)*before.scale+(before.height+2*pad)/2;
      const cssX=oldX*(after.width+2*pad)/(before.width+2*pad),cssY=oldY*(after.height+2*pad)/(before.height+2*pad);
      assert.ok(Math.abs(-pad+t.x+cssX*t.scaleX-((u-after.u)*after.scale+after.width/2))<1e-8);
      assert.ok(Math.abs(-pad+t.y+cssY*t.scaleY-((v-after.v)*after.scale+after.height/2))<1e-8);
    }
  }
});
test('mask worker coalesces queued views and discards old zoom results',()=>{
  const sent=[],applied=[];
  const worker={postMessage:job=>sent.push(job),terminate(){this.terminated=true;}};
  const task=new FogMaskTask((mask,context)=>applied.push(context),()=>worker);
  task.submit({sharp:new Float32Array(1)},{view:1});
  task.submit({sharp:new Float32Array(1)},{view:2});task.submit({sharp:new Float32Array(1)},{view:3});
  assert.equal(sent.length,1);
  worker.onmessage({data:{id:sent[0].id,mask:{}}});assert.equal(sent.length,2);assert.deepEqual(applied,[]);
  worker.onmessage({data:{id:sent[1].id,mask:{}}});assert.deepEqual(applied,[{view:3}]);
  task.submit({sharp:new Float32Array(1)},{view:4});task.cancel();worker.onmessage({data:{id:sent[2].id,mask:{}}});
  assert.equal(applied.length,1);task.dispose();assert.equal(worker.terminated,true);
});
