import { fogMask } from './fog-mask.js';

/** One active calculation and one latest pending mask; stale zoom results never reach the view. */
export class FogMaskTask {
  constructor(done,createWorker=()=>new Worker(new URL('fog-mask-worker.js',import.meta.url),{type:'module'})) {
    this.done=done;this.serial=0;this.busy=false;this.disposed=false;
    try {
      this.worker=createWorker();
      this.worker.onmessage=({data})=>{
        const job=this.current;this.current=null;this.busy=false;
        if(!this.disposed&&job&&data.id===job.id&&job.id===this.latest?.id)this.done(data.mask,job.context);
        this.next();
      };
      this.worker.onerror=event=>{
        event?.preventDefault?.();
        this.worker?.terminate();this.worker=null;this.busy=false;this.current=null;this.queued=null;
        if(!this.disposed&&this.latest)this.run(this.latest);
      };
    }catch{this.worker=null;}
  }
  submit(input,context) {
    const job={id:++this.serial,input,context};this.latest=job;
    if(this.busy)this.queued=job;else this.run(job);
  }
  run(job) {
    if(this.disposed)return;this.busy=true;this.current=job;
    if(this.worker){
      try{this.worker.postMessage({id:job.id,input:job.input});return;}
      catch{this.worker.terminate();this.worker=null;}
    }
    queueMicrotask(()=>{
      this.busy=false;this.current=null;
      if(!this.disposed&&job.id===this.latest?.id)this.done(fogMask(job.input.sharp,job.input.texel,job.input.rounded,job.input.steps),job.context);
      this.next();
    });
  }
  next(){const job=this.queued;this.queued=null;if(!this.disposed&&job&&job.id===this.latest?.id)this.run(job);}
  cancel(){this.latest=null;this.queued=null;}
  dispose(){this.disposed=true;this.cancel();this.worker?.terminate();}
}
