/** Only the server's explicitly published waypoint list; never reads a player's private store. */
export class WebMarkers {
  constructor(api,feed) {
    this.api=api;this.feed=feed;this.abort=new AbortController();this.rows=[];this.buttons=new Map();
    this.container=document.createElement('div');this.container.className='diorama-public-waypoints';
    this.container.style.cssText='position:absolute;inset:0;pointer-events:none;overflow:hidden;z-index:4';
    this.overlay=api.registerOverlay('public-waypoints',{element:this.container});
    this.style=document.createElement('style');this.style.textContent=`.diorama-public-pin{position:absolute;border:0;background:none;padding:0;pointer-events:auto;cursor:pointer;color:white}.diorama-public-pin img{position:absolute;inset:0;image-rendering:pixelated;width:100%;height:100%}.diorama-public-pin strong{position:absolute;left:20%;top:10%;width:60%;height:52%;display:grid;place-items:center;font:bold 11px monospace;text-shadow:1px 1px #101418,-1px -1px #101418}.diorama-public-pin span{position:absolute;left:100%;top:4px;padding:3px 5px;border-radius:3px;background:#15171ce8;color:white;white-space:nowrap;font:12px sans-serif;visibility:hidden}.diorama-public-pin:hover span,.diorama-public-pin:focus span{visibility:visible}.diorama-public-pin:focus-visible{outline:2px solid white}`;
    this.container.append(this.style);
    api.events.addEventListener('dispose',()=>this.dispose(),{once:true,signal:this.abort.signal});
    api.events.addEventListener('visibility',()=>this.draw(),{signal:this.abort.signal});
    api.events.addEventListener('change',()=>{this.draw();if(!this.started&&feed()){this.started=true;void this.poll();}},{signal:this.abort.signal});
    if(feed()){this.started=true;void this.poll();}
  }
  async poll() {
    if(this.abort.signal.aborted)return;
    try {
      if(document.hidden)return;
      const path=this.feed();if(!path)return;
      const url=new URL(path,document.baseURI);if(url.origin!==location.origin)throw Error('External marker feed');
      const response=await fetch(url,{cache:'no-store',signal:AbortSignal.any([this.abort.signal,AbortSignal.timeout(5000)])});
      if(!response.ok)throw Error('Marker feed unavailable');
      const reader=response.body.getReader(),chunks=[];let size=0;
      try {while(true){const {value,done}=await reader.read();if(done)break;size+=value.byteLength;if(size>4*1024*1024)throw Error('Marker feed too large');chunks.push(value);}}finally{await reader.cancel();}
      const bytes=new Uint8Array(size);let at=0;for(const chunk of chunks){bytes.set(chunk,at);at+=chunk.length;}
      const data=JSON.parse(new TextDecoder().decode(bytes));
      if(!Array.isArray(data.markers)||data.markers.length>4096)throw Error('Invalid marker feed');
      this.rows=data.markers.filter(row=>row&&typeof row.id==='string'&&typeof row.name==='string'&&row.name.length<=64&&typeof row.dimension==='string'&&[row.x,row.y,row.z,row.width,row.height,row.anchorX,row.anchorY].every(Number.isFinite)&&row.width>0&&row.width<=64&&row.height>0&&row.height<=64&&typeof row.icon==='string'&&typeof row.initials==='string'&&row.initials.length<=8).filter(row=>{const image=new URL(row.icon,document.baseURI);return image.origin===location.origin&&image.pathname.startsWith(new URL('assets/diorama-markers/',document.baseURI).pathname);});
      this.draw();
    }catch{ /* Retain the last approved marker snapshot across a temporary network failure. */ }
    finally{if(!this.abort.signal.aborted)this.timer=setTimeout(()=>void this.poll(),5000);}
  }
  draw() {
    const state=this.api.getState();if(!state)return;
    this.container.dataset.distant=String(state.scale<1);const keep=new Set();
    for(const row of this.api.visible?.("sharedMarkers")===false?[]:this.rows){
      const point=this.api.screen(row);if(!point||point.x< -64||point.y< -64||point.x>state.width+64||point.y>state.height+64)continue;
      keep.add(row.id);let button=this.buttons.get(row.id);
      if(!button){button=document.createElement('button');button.className='diorama-public-pin';button.type='button';button.append(document.createElement('img'),document.createElement('strong'),document.createElement('span'));this.container.append(button);this.buttons.set(row.id,button);}
      const image=button.querySelector('img');image.src=new URL(row.icon,document.baseURI).href;image.alt='';
      button.querySelector('strong').textContent=row.initials;button.querySelector('span').textContent=row.name;
      button.title=row.name;button.setAttribute('aria-label',row.name);button.onclick=()=>{button.focus();this.api.navigate(row);};
      button.style.width=row.width*2+'px';button.style.height=row.height*2+'px';button.style.transform=`translate(${point.x-row.anchorX*2}px,${point.y-row.anchorY*2}px)`;
    }
    for(const [id,button]of this.buttons)if(!keep.has(id)){button.remove();this.buttons.delete(id);}
  }
  dispose(){this.abort.abort();clearTimeout(this.timer);this.overlay.remove();this.buttons.clear();}
}
