/** Queue state is a separate public snapshot; enabling it never requests terrain tiles. */
export class WebQueue {
  constructor(api){
    this.api=api;this.abort=new AbortController();this.status=document.createElement('small');this.status.className='map-update panel';this.status.style.bottom='calc(var(--map-bottom) + 86px)';this.status.hidden=!api.visible('queuedTiles');
    document.body.append(this.status);this.overlay=api.registerOverlay('queued-tiles',{draw:(ctx,state)=>this.draw(ctx,state)});
    const update=()=>{this.status.hidden=!api.visible('queuedTiles');this.overlay.invalidate();if(api.visible('queuedTiles'))void this.poll();else{clearTimeout(this.timer);this.request?.abort();}};
    api.events.addEventListener('dispose',()=>this.dispose(),{once:true,signal:this.abort.signal});
    api.events.addEventListener('visibility',update,{signal:this.abort.signal});api.events.addEventListener('change',()=>this.overlay.invalidate(),{signal:this.abort.signal});
    if(api.visible('queuedTiles'))void this.poll();
  }
  async poll(){
    if(this.request||this.abort.signal.aborted||!this.api.visible('queuedTiles'))return;clearTimeout(this.timer);
    const controller=new AbortController();this.request=controller;
    try{
      if(document.hidden)return;const feed=this.api.queueFeed?.();if(!feed){this.status.textContent='Queue status unavailable';return;}
      const url=new URL(feed,location.href);if(url.origin!==location.origin)throw Error();
      const response=await fetch(url,{cache:'no-store',signal:AbortSignal.any([this.abort.signal,controller.signal,AbortSignal.timeout(5000)])});if(!response.ok)throw Error();
      const text=await response.text();if(text.length>2*1024*1024)throw Error();const data=JSON.parse(text);
      if(data.version!==1||!Number.isFinite(data.generatedAt)||Date.now()-data.generatedAt>15000)throw Error();
      this.data=data;this.showStatus();
    }catch{this.data=null;this.status.textContent='Queue status unavailable';}
    finally{this.request=null;this.overlay.invalidate();if(!this.abort.signal.aborted&&this.api.visible('queuedTiles'))this.timer=setTimeout(()=>void this.poll(),2000);}
  }
  showStatus(){const row=this.data?.dimensions?.[this.api.getState()?.dimension];this.status.textContent=row?`${row.queuedTiles} tiles queued · ${row.waitingChunks} chunks waiting${this.data.paused?' · Paused':''}${row.truncated?' · Partial view':''}`:'No queued tiles';}
  draw(ctx,state){
    if(!this.api.visible('queuedTiles')||!this.data||Date.now()-this.data.generatedAt>15000)return;this.showStatus();
    const cells=this.data.dimensions?.[state.dimension]?.tiles;if(!Array.isArray(cells))return;
    ctx.fillStyle='#e4aa3326';ctx.strokeStyle='#f1c35c';ctx.lineWidth=1;
    for(let i=0;i+1<Math.min(cells.length,16384);i+=2){const u=cells[i],v=cells[i+1];if(!Number.isInteger(u)||!Number.isInteger(v))continue;
      const x=(u*128-state.u)*state.scale+state.width/2,y=(v*128-state.v)*state.scale+state.height/2,size=128*state.scale;
      if(x+size<0||y+size<0||x>state.width||y>state.height)continue;ctx.fillRect(x,y,size,size);ctx.strokeRect(x+.5,y+.5,size-1,size-1);
    }
  }
  dispose(){this.abort.abort();this.request?.abort();clearTimeout(this.timer);this.overlay.remove();this.status.remove();}
}
