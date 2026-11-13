import { MASK_SIZE, fogLayout } from './fog-mask.js';
import { FogCoverage } from './fog-coverage.js';
import { FogMaskTask } from './fog-mask-task.js';
import { FogGpu, fogViewportTransform } from './fog-gpu.js';
import { revealProgress, revealFrom } from './fog-reveal.js';

export const cloudsEnabled = state => state.enabled !== false && state.dimension !== 'minecraft:the_nether' && !state.smoke;
export const cloudFrameInterval = coarse => 1000/(coarse?8:15);

/** World-anchored coverage with off-thread mask preparation and a bounded cloud-only render budget. */
export class WebFog {
  constructor(canvas) {
    this.canvas=canvas;this.disposed=false;this.abort=new AbortController();this.frame=0;this.lastPaint=0;this.serial=0;this.coverageFrame=0;this.lastPreparation=0;
    this.maskCanvas=document.createElement('canvas');this.maskCanvas.width=this.maskCanvas.height=MASK_SIZE;
    this.maskContext=this.maskCanvas.getContext('2d',{willReadFrequently:true});
    this.coverage=new FogCoverage(()=>{
      this.serial++;this.canvas.dataset.fogMaskPending='true';
      if(!this.coverageFrame)this.coverageFrame=requestAnimationFrame(()=>{this.coverageFrame=0;if(this.state&&!this.disposed)this.update(this.state,this.sources);});
    });
    this.reduced=matchMedia('(prefers-reduced-motion: reduce)');this.coarse=matchMedia('(any-pointer: coarse)');
    const wake=()=>{if(this.state)this.update(this.state,this.sources);this.paint(performance.now(),true);this.start();};
    document.addEventListener('visibilitychange',wake,{signal:this.abort.signal});
    this.reduced.addEventListener('change',wake,{signal:this.abort.signal});this.coarse.addEventListener('change',wake,{signal:this.abort.signal});
    canvas.addEventListener('webglcontextlost',event=>{event.preventDefault();this.fallback();},{signal:this.abort.signal});
  }
  renderer() {
    if(this.rendererStarted)return;this.rendererStarted=true;
    this.maskTask=new FogMaskTask((mask,job)=>this.apply(mask,job));
    try {
      this.gpu=new FogGpu(this.canvas);const gpu=this.gpu;
      void gpu.load(this.abort.signal).then(()=>{
        if(this.disposed||this.gpu!==gpu)return;
        if(this.mask)gpu.update(this.mask,false);this.paint(performance.now(),true);this.start();
      }).catch(error=>{if(!this.disposed&&this.gpu===gpu){console.warn('Cloud renderer fallback:',error);this.fallback();}});
    }catch{this.fallback();}
  }
  fallback() {
    this.gpu?.dispose();this.gpu=null;
    const replacement=this.canvas.cloneNode(false);this.canvas.replaceWith(replacement);this.canvas=replacement;
    Object.assign(replacement.style,{left:'0',top:'0',right:'0',bottom:'0',width:'100%',height:'100%',transform:''});
    this.context=replacement.getContext('2d');this.canvas.dataset.fogRenderer='canvas';
    this.paint(performance.now(),true);this.start();
  }
  update(state,sources) {
    if(this.disposed)return;this.state=state;this.sources=sources;
    const enabled=cloudsEnabled(state);this.canvas.style.display=enabled?'':'none';this.canvas.dataset.fogEnabled=String(enabled);
    if(!enabled){clearTimeout(this.maskPrepareTimer);this.maskPrepareTimer=null;clearTimeout(this.deferredPaint);this.deferredPaint=null;cancelAnimationFrame(this.frame);this.frame=0;this.coverage.pause();this.maskTask?.cancel();this.maskKey='';return;}
    if(document.hidden){clearTimeout(this.maskPrepareTimer);this.maskPrepareTimer=null;return;}
    this.renderer();
    const l=fogLayout(state,state.width,state.height),requested=typeof sources==='function'?sources(l):sources;
    if(this.coverage.begin(requested,l,state.validCoverage))this.serial++;
    for(const source of requested)this.coverage.get(source,state.coverage);
    const key=[state.dimension,l.u,l.v,l.texel,l.steps,state.rounded,this.serial,...requested.map(source=>source.mask)].join('|');
    this.requestedKey=key;
    if(key!==this.maskKey) {
      this.canvas.dataset.fogMaskPending='true';
      const now=performance.now(),gap=cloudFrameInterval(this.coarse.matches);
      if(this.maskKey&&now-this.lastPreparation<gap){
        if(!this.maskPrepareTimer)this.maskPrepareTimer=setTimeout(()=>{this.maskPrepareTimer=null;if(this.state&&!this.disposed)this.update(this.state,this.sources);},gap-(now-this.lastPreparation)+1);
        this.paint(now);this.start();return;
      }
      clearTimeout(this.maskPrepareTimer);this.maskPrepareTimer=null;this.lastPreparation=now;
      this.maskKey=key;const ctx=this.maskContext;
      ctx.fillStyle='#000';ctx.fillRect(0,0,MASK_SIZE,MASK_SIZE);ctx.imageSmoothingEnabled=true;
      for(const {source,image} of this.coverage.ready(l,state.dimension))ctx.drawImage(image,(source.u-l.u)/l.texel,(source.v-l.v)/l.texel,source.units/l.texel,source.units/l.texel);
      if(!state.coverage)for(const source of requested){ctx.fillStyle='#fff';ctx.fillRect((source.u-l.u)/l.texel,(source.v-l.v)/l.texel,source.units/l.texel,source.units/l.texel);}
      const pixels=ctx.getImageData(0,0,MASK_SIZE,MASK_SIZE).data;
      const sharp=Float32Array.from({length:MASK_SIZE*MASK_SIZE},(_,i)=>pixels[i*4]/255);
      this.canvas.dataset.fogMaskPending='true';
      this.maskTask.submit({sharp,texel:l.texel,rounded:state.rounded,steps:l.steps},{key,layout:l,dimension:state.dimension});
    }
    this.paint(performance.now());this.start();
  }
  apply(mask,job) {
    if(this.disposed||job.key!==this.maskKey||!this.state||!cloudsEnabled(this.state))return;
    const l=job.layout,old=this.layout;
    const sameGeometry=old&&old.u===l.u&&old.v===l.v&&old.texel===l.texel&&this.dimension===job.dimension;
    const now=performance.now(),animate=!!sameGeometry&&this.appliedScale===this.state.scale&&!this.reduced.matches;
    const previous=animate?this.transition:null,amount=previous?revealProgress(previous.started,now):1;
    this.mask=mask;this.layout=l;this.dimension=job.dimension;this.appliedScale=this.state.scale;
    this.transition={from:revealFrom(previous,mask,amount),to:mask,started:animate?now:now-800};
    this.canvas.dataset.fogMaskPending=String(this.requestedKey!==job.key);this.canvas.dataset.fogMaskWorker=this.maskTask.worker?'worker':'main';
    this.gpu?.update(mask,animate);this.paint(now);this.start();
  }
  position(state) {
    if(this.gpu)this.gpu.position(state);
    else if(this.lastView){const t=fogViewportTransform(this.lastView,state,0);this.canvas.style.transform=`translate(${t.x}px,${t.y}px) scale(${t.scaleX},${t.scaleY})`;}
  }
  paint(time,force=false) {
    if(!this.state||!this.mask||!this.layout||this.disposed||document.hidden||!cloudsEnabled(this.state))return;
    const state={...this.state,reduced:this.reduced.matches,coarse:this.coarse.matches};
    this.position(state);
    if(!force&&time-this.lastPaint<cloudFrameInterval(state.coarse)){
      if(this.reduced.matches&&!this.deferredPaint)this.deferredPaint=setTimeout(()=>{this.deferredPaint=null;this.paint(performance.now(),true);},cloudFrameInterval(state.coarse)-(time-this.lastPaint));
      return;
    }
    this.lastPaint=time;
    if(this.gpu) {
      this.gpu.draw(state,this.layout,this.reduced.matches?0:time);
      if(this.gpu.ready)this.canvas.dataset.fogRenderer='webgl';
    }else if(this.context) {
      this.canvas.width=Math.ceil(state.width);this.canvas.height=Math.ceil(state.height);
      const ctx=this.context,l=this.layout;
      ctx.globalCompositeOperation='source-over';ctx.fillStyle='#dce2ed';ctx.fillRect(0,0,state.width,state.height);
      const progress=revealProgress(this.transition.started,time,this.reduced.matches),output=this.maskContext.createImageData(MASK_SIZE,MASK_SIZE);
      for(let i=0;i<this.mask.known.length;i++){output.data[i*4]=output.data[i*4+1]=output.data[i*4+2]=255;output.data[i*4+3]=Math.round(this.transition.from.rgba[i*4]*(1-progress)+this.transition.to.rgba[i*4]*progress);}
      if(!this.fallbackMask){this.fallbackMask=document.createElement('canvas');this.fallbackMask.width=this.fallbackMask.height=MASK_SIZE;}
      this.fallbackMask.getContext('2d').putImageData(output,0,0);
      ctx.globalCompositeOperation='destination-out';ctx.imageSmoothingEnabled=true;
      ctx.drawImage(this.fallbackMask,(l.u-state.u+state.width/2/state.scale)*state.scale,(l.v-state.v+state.height/2/state.scale)*state.scale,l.units*state.scale,l.units*state.scale);
      ctx.globalCompositeOperation='source-over';this.lastView={...state};this.canvas.style.transform='';
    }
  }
  start() {
    if(this.disposed||document.hidden||!this.state||!cloudsEnabled(this.state)||this.reduced.matches){cancelAnimationFrame(this.frame);this.frame=0;return;}
    if(this.frame)return;
    this.frame=requestAnimationFrame(time=>{
      this.frame=0;if(this.disposed||document.hidden||!cloudsEnabled(this.state))return;
      this.paint(time);
      if(this.gpu||this.transition&&revealProgress(this.transition.started,time)<1)this.start();
    });
  }
  dispose(){this.disposed=true;clearTimeout(this.maskPrepareTimer);clearTimeout(this.deferredPaint);this.abort.abort();cancelAnimationFrame(this.frame);cancelAnimationFrame(this.coverageFrame);this.maskTask?.dispose();this.coverage.dispose();this.gpu?.dispose();}
}
