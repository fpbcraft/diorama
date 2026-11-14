const paths={layers:'<path d="m3 8 9-5 9 5-9 5-9-5Zm0 5 9 5 9-5M3 18l9 5 9-5"/>',settings:'<path d="M3 6h18M3 18h18"/><circle cx="8" cy="6" r="3"/><circle cx="16" cy="18" r="3"/>',cloud:'<path d="M6 18a4 4 0 0 1-1-8 7 7 0 0 1 13-1 4.5 4.5 0 0 1 0 9Z"/>',ship:'<path d="M3 14h18l-4 6H7l-4-6Zm9-11v11M12 4l7 7h-7"/>',train:'<rect x="5" y="3" width="14" height="15" rx="3"/><path d="M5 11h14M8 18l-3 4m11-4 3 4M8 15h1m6 0h1"/>',pin:'<path d="M12 22S4 14 4 9a8 8 0 0 1 16 0c0 5-8 13-8 13Z"/><circle cx="12" cy="9" r="3"/>'};
export const mapIcon=name=>`<svg viewBox="0 0 24 26" width="22" height="22" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">${paths[name]||paths.pin}</svg>`;
/** Core visibility controls also work without a Player History installation. */
export class MapControls {
  constructor(api){
    this.api=api;this.abort=new AbortController();this.root=document.createElement('details');this.root.className='map-visibility map-menu';
    const summary=document.createElement('summary');summary.className='map-icon-button panel';summary.setAttribute('aria-label','Map visibility');summary.title='Map visibility';summary.innerHTML=mapIcon('settings');
    const body=document.createElement('div');body.className='map-menu-body panel';
    const title=document.createElement('strong');title.textContent='Map visibility';body.append(title);
    const note=document.createElement('small');note.textContent='Show or hide map elements';body.append(note);
    const add=(key,label,icon)=>{
      if(body.querySelector(`input[data-visibility="${key}"]`))return;
      const row=document.createElement('label'),picture=document.createElement('span'),text=document.createElement('span'),input=document.createElement('input');picture.className='map-setting-icon';picture.innerHTML=mapIcon(icon);text.textContent=label;input.type='checkbox';input.setAttribute('role','switch');input.checked=api.visible(key);input.dataset.visibility=key;input.onchange=()=>api.setVisible(key,input.checked);row.append(picture,text,input);body.append(row);
    };
    for(const [key,label,icon]of [['clouds','Clouds','cloud'],['sablePins','Sable vehicle pins','ship'],['trainPins','Train pins','train'],['allVehicles','Include unnamed vehicles','ship'],['sharedMarkers','Shared waypoint sets','pin'],['queuedTiles','Queued tile updates','layers']])add(key,label,icon);
    api.events.addEventListener('visibility-option',event=>{const {key,label,icon}=event.detail;add(key,label,icon);},{signal:this.abort.signal});
    this.root.append(summary,body);document.getElementById('map-tools').append(this.root);
    document.addEventListener('pointerdown',event=>{if(!this.root.contains(event.target))this.root.open=false;},{signal:this.abort.signal});
    document.addEventListener('keydown',event=>{if(event.key==='Escape'&&this.root.open){this.root.open=false;summary.focus();}},{signal:this.abort.signal});
    api.events.addEventListener('dispose',()=>this.dispose(),{once:true,signal:this.abort.signal});
    api.events.addEventListener('visibility',()=>{for(const input of body.querySelectorAll('input'))input.checked=api.visible(input.dataset.visibility);},{signal:this.abort.signal});
  }
  dispose(){this.abort.abort();this.root.remove();}
}

/** One bounded terrain snapshot per zoom gesture; sharp tiles replace it after settling. */
export class ZoomPreview {
  constructor(settled,createCanvas=()=>document.createElement('canvas')) {
    this.settled=settled;this.createCanvas=createCanvas;this.active=false;
  }
  begin(canvas,state) {
    if(!state||!canvas.width||!canvas.height)return;
    if(!this.active) {
      this.clear();this.image=this.createCanvas();
      const ratio=Math.min(1,Math.sqrt(2000000/(state.width*state.height)));
      this.image.width=Math.ceil(state.width*ratio);this.image.height=Math.ceil(state.height*ratio);
      const context=this.image.getContext('2d');
      if(!context){this.clear();return;}
      context.drawImage(canvas,0,0,this.image.width,this.image.height);this.anchor={...state};
    }
    this.active=true;clearTimeout(this.timer);
    this.timer=setTimeout(()=>{this.active=false;this.settled();},160);
  }
  render(context,state) {
    const before=this.anchor;
    if(!before||before.dimension!==state.dimension||before.width!==state.width||before.height!==state.height||before.dpr!==state.dpr){this.clear();return false;}
    const scale=state.scale/before.scale;
    const x=(before.u-state.u)*state.scale+(state.width-before.width*scale)/2;
    const y=(before.v-state.v)*state.scale+(state.height-before.height*scale)/2;
    context.imageSmoothingEnabled=true;context.imageSmoothingQuality='low';
    context.drawImage(this.image,x*state.dpr,y*state.dpr,before.width*scale*state.dpr,before.height*scale*state.dpr);
    return true;
  }
  clear(){clearTimeout(this.timer);this.active=false;this.anchor=null;if(this.image){this.image.width=this.image.height=0;this.image=null;}}
}
