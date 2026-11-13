import test from 'node:test';
import assert from 'node:assert/strict';
import {WebQueue} from '../../src/main/resources/diorama/web/web-queue.js';

test('queue overlay projects negative cells, clips offscreen cells and cleans up',()=>{
  const before=globalThis.document;
  const status={style:{},remove(){this.removed=true;}};
  globalThis.document={createElement:()=>status,body:{append(){}}};
  try{
    let visible=false,removed=false,invalidated=0;
    const events=new EventTarget(),state={dimension:'minecraft:overworld',u:0,v:0,width:512,height:512,scale:2};
    const api={events,visible:()=>visible,getState:()=>state,registerOverlay:()=>({invalidate(){invalidated++;},remove(){removed=true;}})};
    const queue=new WebQueue(api);
    assert.equal(status.hidden,true);
    queue.data={generatedAt:Date.now(),dimensions:{'minecraft:overworld':{queuedTiles:3,waitingChunks:1,tiles:[-1,-1,0,0,100,100,NaN,0]}}};
    const rectangles=[],ctx={fillRect:(...args)=>rectangles.push(args),strokeRect(){}};
    queue.draw(ctx,state);assert.equal(rectangles.length,0);
    visible=true;queue.draw(ctx,state);
    assert.deepEqual(rectangles,[[0,0,256,256],[256,256,256,256]]);
    assert.equal(status.textContent,'3 tiles queued · 1 chunks waiting');
    queue.data.generatedAt=Date.now()-16000;queue.draw(ctx,state);assert.equal(rectangles.length,2);
    events.dispatchEvent(new Event('dispose'));
    assert.ok(removed&&status.removed&&queue.abort.signal.aborted);
    assert.ok(invalidated===0);
  }finally{globalThis.document=before;}
});
