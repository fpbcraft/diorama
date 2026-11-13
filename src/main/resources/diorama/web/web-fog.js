import { MASK_SIZE, fogLayout, fogMask } from './fog-mask.js';
import { FogCoverage } from './fog-coverage.js';
import { FogGpu } from './fog-gpu.js';
import { revealProgress, revealFrom } from './fog-reveal.js';

/** Cloud layer independent of terrain painting, downloads, player feeds and pointer handling. */
export class WebFog {
  constructor(canvas) {
    this.canvas=canvas;this.disposed=false;this.abort=new AbortController();this.frame=0;this.lastPaint=0;this.serial=0;
    this.maskCanvas=document.createElement('canvas');this.maskCanvas.width=this.maskCanvas.height=MASK_SIZE;
    this.maskContext=this.maskCanvas.getContext('2d',{willReadFrequently:true});
    this.coverage=new FogCoverage(()=>{this.serial++;if(this.state)this.update(this.state,this.sources);});
    this.reduced=matchMedia('(prefers-reduced-motion: reduce)');
    const wake=()=>{if(this.state)this.paint(performance.now());this.start();};
    document.addEventListener('visibilitychange',wake,{signal:this.abort.signal});
    this.reduced.addEventListener('change',wake,{signal:this.abort.signal});
    canvas.addEventListener('webglcontextlost',event=>{event.preventDefault();this.fallback();},{signal:this.abort.signal});
    try {
      this.gpu=new FogGpu(canvas);
      const gpu=this.gpu;
      void gpu.load(this.abort.signal).then(()=>{if(this.disposed||this.gpu!==gpu)return;this.maskKey='';if(this.state)this.update(this.state,this.sources);}).catch(error=>{if(!this.disposed&&this.gpu===gpu){console.warn('Cloud renderer fallback:',error);this.fallback();}});
    }catch{this.fallback();}
  }
  fallback() {
    this.gpu?.dispose();this.gpu=null;
    const replacement=this.canvas.cloneNode(false);this.canvas.replaceWith(replacement);this.canvas=replacement;
    this.context=replacement.getContext('2d');this.maskKey='';this.canvas.dataset.fogRenderer='canvas';
    if(this.state)this.update(this.state,this.sources);
  }
  update(state,sources) {
    if(this.disposed)return;this.state=state;this.sources=sources;
    const previousLayout=this.layout;
    this.layout=fogLayout(state,state.width,state.height);this.coverage.begin(sources);
    const l=this.layout,key=[state.dimension,l.u,l.v,l.texel,state.rounded,this.serial,...sources.map(source=>source.mask)].join('|');
    if(key!==this.maskKey) {
      this.maskKey=key;const ctx=this.maskContext;
      ctx.fillStyle='#000';ctx.fillRect(0,0,MASK_SIZE,MASK_SIZE);ctx.imageSmoothingEnabled=true;
      for(const source of sources) {
        const image=this.coverage.get(source,state.coverage);if(!image)continue;
        const x=(source.u-l.u)/l.texel,y=(source.v-l.v)/l.texel,size=source.units/l.texel;
        if(image===true){ctx.fillStyle='#fff';ctx.fillRect(x,y,size,size);}else ctx.drawImage(image,x,y,size,size);
      }
      const pixels=ctx.getImageData(0,0,MASK_SIZE,MASK_SIZE).data;
      const sharp=Float32Array.from({length:MASK_SIZE*MASK_SIZE},(_,i)=>pixels[i*4]/255);
      this.mask=fogMask(sharp,l.texel,state.rounded,l.steps);
      const sameGeometry=previousLayout&&previousLayout.u===l.u&&previousLayout.v===l.v&&previousLayout.texel===l.texel&&this.dimension===state.dimension;
      const now=performance.now(),animate=!!sameGeometry&&!this.reduced.matches;
      const previous=animate?this.transition:null;
      const amount=previous?revealProgress(previous.started,now):1;
      this.transition={from:revealFrom(previous,this.mask,amount),to:this.mask,started:animate?now:now-800};
      this.dimension=state.dimension;
      this.gpu?.update(this.mask,animate);
      const output=ctx.createImageData(MASK_SIZE,MASK_SIZE);
      for(let i=0;i<sharp.length;i++){output.data[i*4]=output.data[i*4+1]=output.data[i*4+2]=255;output.data[i*4+3]=Math.round(this.mask.known[i]*255);}
      this.fallbackMask=document.createElement('canvas');this.fallbackMask.width=this.fallbackMask.height=MASK_SIZE;this.fallbackMask.getContext('2d').putImageData(output,0,0);
    }
    this.paint(performance.now());this.start();
  }
  paint(time) {
    if(!this.state||this.disposed||document.hidden)return;
    const state={...this.state,reduced:this.reduced.matches};
    if(this.gpu) {
      this.gpu.draw(state,this.layout,this.reduced.matches?0:time);
      if(this.gpu.ready)this.canvas.dataset.fogRenderer='webgl';
    }else if(this.context) {
      this.canvas.width=Math.ceil(state.width);this.canvas.height=Math.ceil(state.height);
      const ctx=this.context,l=this.layout;
      ctx.globalCompositeOperation='source-over';ctx.fillStyle=state.smoke?'#493e40':'#dce2ed';ctx.fillRect(0,0,state.width,state.height);
      const progress=revealProgress(this.transition.started,time,this.reduced.matches);
      const output=this.maskContext.createImageData(MASK_SIZE,MASK_SIZE);
      for(let i=0;i<this.mask.known.length;i++){output.data[i*4]=output.data[i*4+1]=output.data[i*4+2]=255;output.data[i*4+3]=Math.round(this.transition.from.rgba[i*4]*(1-progress)+this.transition.to.rgba[i*4]*progress);}
      this.fallbackMask.getContext('2d').putImageData(output,0,0);
      ctx.globalCompositeOperation='destination-out';ctx.imageSmoothingEnabled=true;
      const left=state.u-state.width/2/state.scale,top=state.v-state.height/2/state.scale;
      ctx.drawImage(this.fallbackMask,(l.u-left)*state.scale,(l.v-top)*state.scale,l.units*state.scale,l.units*state.scale);
      ctx.globalCompositeOperation='source-over';
    }
  }
  start() {
    cancelAnimationFrame(this.frame);this.frame=0;
    if(this.disposed||document.hidden||!this.state||this.reduced.matches)return;
    const tick=time=>{
      if(this.disposed||document.hidden)return;
      if(time-this.lastPaint>=1000/15){this.lastPaint=time;this.paint(time);}
      if(this.gpu || revealProgress(this.transition.started,time)<1)this.frame=requestAnimationFrame(tick);
    };
    this.frame=requestAnimationFrame(tick);
  }
  dispose() {this.disposed=true;this.abort.abort();cancelAnimationFrame(this.frame);this.coverage.dispose();this.gpu?.dispose();}
}
