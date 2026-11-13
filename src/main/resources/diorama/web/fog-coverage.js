/** Coverage follows the world manifest, independently of terrain JPEG loading and zoom. */
export const coverageSources = (levels, layout, url, tileUnits = 128, dimension = '') => {
  const wanted = Math.max(0, Math.min(levels.length - 1, Math.round(Math.log2(layout.texel / 2))));
  const sources = [];
  for (const zoom of [...new Set([Math.min(levels.length - 1, wanted + 2), wanted])]) {
    const units = tileUnits * 2 ** zoom;
    const level=levels[zoom];if(!level)continue;
    const minU=Math.floor(layout.u/units),maxU=Math.ceil((layout.u+layout.units)/units);
    const minV=Math.floor(layout.v/units),maxV=Math.ceil((layout.v+layout.units)/units);
    for(let v=minV;v<maxV;v++)for(let u=minU;u<maxU;u++) {
      const key=u+','+v,version=level.get(key);if(version===undefined)continue;
      sources.push({ u: u * units, v: v * units, units, zoom, key, version, dimension,
        mask: url(zoom, u, v, '.fog.png', version),
        depth: zoom === 0 ? url(zoom, u, v, '.depth', version) : undefined });
    }
  }
  return sources.slice(0,256);
};
const overlaps = (source, bounds) => !bounds || source.u < bounds.u + bounds.units &&
  source.u + source.units > bounds.u && source.v < bounds.v + bounds.units && source.v + source.units > bounds.v;

/** Missing coverage is unknown. In-flight zoom requests and ready masks remain usable across LODs. */
export class FogCoverage {
  constructor(changed) { this.changed=changed;this.cache=new Map();this.active=0;this.disposed=false; }
  begin(sources, bounds, valid = () => true) {
    this.wanted=new Set(sources.map(source=>source.mask));let removed=false;
    for(const [key,entry] of this.cache) {
      if(!valid(entry.source) || entry.state==='loading'&&!this.wanted.has(key)&&!overlaps(entry.source,bounds)) {
        this.cancel(entry);this.cache.delete(key);removed=true;
      }
    }
    if(this.cache.size>256)for(const [key,entry] of this.cache) {
      if(this.cache.size<=192)break;
      if(!this.wanted.has(key)&&!overlaps(entry.source,bounds)){this.cancel(entry);this.cache.delete(key);removed=true;}
    }
    if(this.cache.size>256)for(const [key,entry]of this.cache){
      if(this.cache.size<=256)break;
      if(!this.wanted.has(key)&&entry.state!=='loading'){this.cancel(entry);this.cache.delete(key);removed=true;}
    }
    return removed;
  }
  get(source, enabled = true) {
    if(!enabled)return true;
    let entry=this.cache.get(source.mask);
    if(entry?.state==='missing'&&Date.now()>=entry.retryAt){this.cancel(entry);this.cache.delete(source.mask);entry=null;}
    if(!entry&&this.active<4&&!this.disposed) {
      const image=new Image();entry={source,image,state:'loading',abort:new AbortController()};
      this.cache.set(source.mask,entry);this.active++;
      const finish=(state,picture=image)=>{
        if(this.disposed||entry.abort.signal.aborted)return;
        this.active--;entry.state=state;entry.image=picture;entry.retryAt=Date.now()+10000;
        if(state==='missing')entry.retryTimer=setTimeout(()=>{if(!this.disposed&&this.wanted.has(source.mask))this.changed();},10010);
        this.changed();
      };
      const missing=()=>{
        if(!source.depth){finish('missing');return;}
        void depthCoverage(source.depth,entry.abort.signal).then(picture=>finish('ready',picture)).catch(()=>finish('missing'));
      };
      image.onload=()=>image.naturalWidth===64&&image.naturalHeight===64?finish('ready'):missing();
      image.onerror=missing;image.src=source.mask;
    }
    return entry?.state==='ready'?entry.image:null;
  }
  ready(bounds,dimension) {
    return [...this.cache.values()].filter(entry=>entry.state==='ready'&&overlaps(entry.source,bounds)&&
      (!entry.source.dimension||entry.source.dimension===dimension))
      .sort((a,b)=>b.source.units-a.source.units||a.source.version-b.source.version);
  }
  cancel(entry) {
    if(entry.state==='loading')this.active--;
    clearTimeout(entry.retryTimer);entry.abort.abort();entry.image.onload=entry.image.onerror=null;
    if('src' in entry.image)entry.image.src='';
  }
  pause() { for(const [key,entry]of this.cache)if(entry.state==='loading'){this.cancel(entry);this.cache.delete(key);} }
  dispose() {this.disposed=true;for(const entry of this.cache.values())this.cancel(entry);this.cache.clear();}
}

/** Recover coverage from existing camera depths; opaque black blocks remain known. */
const depthCoverage = async (url,signal) => {
  const response=await fetch(url,{signal});if(!response.ok)throw Error('No legacy depth');
  const bytes=await response.arrayBuffer();if(bytes.byteLength<16||bytes.byteLength>2*1024*1024)throw Error('Invalid legacy depth');
  const header=new DataView(bytes),version=header.getUint32(4,true);
  if(header.getUint32(0)!==0x44444550||![1,2].includes(version)||header.getUint32(8,true)!==512||header.getUint32(12,true)!==512)throw Error('Invalid legacy depth');
  let payload=bytes.slice(16);
  if(version===2){
    const reader=new Blob([payload]).stream().pipeThrough(new DecompressionStream('deflate')).getReader(),chunks=[];let size=0;
    try {while(true){const {value,done}=await reader.read();if(done)break;size+=value.byteLength;if(size>512*512*4)throw Error('Invalid legacy depth size');chunks.push(value);}}
    finally{await reader.cancel();}
    const result=new Uint8Array(size);let at=0;for(const chunk of chunks){result.set(chunk,at);at+=chunk.length;}payload=result.buffer;
  }
  if(payload.byteLength!==512*512*4||signal.aborted)throw Error('Invalid legacy depth size');
  const values=new DataView(payload),canvas=document.createElement('canvas');canvas.width=canvas.height=64;
  const context=canvas.getContext('2d'),pixels=context.createImageData(64,64);
  for(let y=0;y<64;y++)for(let x=0;x<64;x++){
    let known=0;for(let dy=0;dy<8;dy++)for(let dx=0;dx<8;dx++)if(Number.isFinite(values.getFloat32(((y*8+dy)*512+x*8+dx)*4,true)))known++;
    const i=(y*64+x)*4;pixels.data[i]=pixels.data[i+1]=pixels.data[i+2]=Math.round(known/64*255);pixels.data[i+3]=255;
  }
  context.putImageData(pixels,0,0);return canvas;
};
