/** Small versioned coverage images; they share the JPEG's revision but never delay terrain requests. */
export class FogCoverage {
  constructor(changed) {this.changed=changed;this.cache=new Map();this.wanted=new Set();this.active=0;this.disposed=false;}
  begin(sources) {
    this.wanted=new Set(sources.map(source=>source.mask));
    for(const [key,entry] of this.cache) {
      if(entry.state==='loading'&&!this.wanted.has(key)) {entry.image.onload=entry.image.onerror=null;entry.image.src='';this.active--;this.cache.delete(key);}
    }
    if(this.cache.size>256)for(const [key,entry] of this.cache) {if(this.cache.size<=192)break;if(!this.wanted.has(key)&&entry.state!=='loading')this.cache.delete(key);}
  }
  get(source,enabled) {
    if(!enabled)return true;
    let entry=this.cache.get(source.mask);
    if(!entry&&this.active<4&&!this.disposed) {
      const image=new Image();entry={image,state:'loading'};this.cache.set(source.mask,entry);this.active++;
      const finish=state=>{if(this.disposed)return;this.active--;entry.state=state;this.changed();};
      image.onload=()=>finish(image.naturalWidth===64&&image.naturalHeight===64?'ready':'legacy');
      image.onerror=()=>finish('legacy'); // Old generated JPEGs have no mask until their next render.
      image.src=source.mask;
    }
    return entry?.state==='ready'?entry.image:entry?.state==='legacy'?true:null;
  }
  dispose() {this.disposed=true;for(const entry of this.cache.values()){entry.image.onload=entry.image.onerror=null;entry.image.src='';}this.cache.clear();}
}
