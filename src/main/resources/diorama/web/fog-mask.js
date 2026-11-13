// Mirrors MapViewport.roundOff/writeSoft. Coverage can only shrink; unknown ground is never revealed.
export const MASK_SIZE = 256;
export const fogLayout = (view, width, height) => {
  const log = Math.min(15.99, Math.log2(Math.max(1, .5 / view.scale)));
  const steps = Math.floor(log), fraction = Math.floor((log - steps) * 16) / 16;
  const snap = 128 * 2 ** steps, margin = 64 + 48 * 2 ** steps;
  const span = Math.max(width,height) / view.scale + 2 * margin;
  let texel = 2;
  while (texel * MASK_SIZE < span + snap) texel *= 2;
  // Centre the viewport in the mask, snapped in world space so panning does not move the clouds.
  const u = Math.floor((view.u - texel * MASK_SIZE / 2) / snap) * snap;
  const v = Math.floor((view.v - texel * MASK_SIZE / 2) / snap) * snap;
  return {u,v,texel,steps,fraction,units:texel*MASK_SIZE};
};
export const boxBlur = (from,to,radius,width=MASK_SIZE,height=MASK_SIZE,horizontal=true) => {
  const lines=horizontal?height:width,length=horizontal?width:height;
  const at=(line,k)=>horizontal?line*width+k:k*width+line;
  for(let line=0;line<lines;line++) {
    let sum=0;
    for(let k=-radius;k<=radius;k++)sum+=from[at(line,Math.max(0,Math.min(length-1,k)))];
    for(let k=0;k<length;k++) {
      to[at(line,k)]=sum/(2*radius+1);
      sum+=from[at(line,Math.min(length-1,k+radius+1))]-from[at(line,Math.max(0,k-radius))];
    }
  }
};
export const roundKnown = (known,radius,width=MASK_SIZE,height=MASK_SIZE) => {
  const a=Float32Array.from(known),b=new Float32Array(a.length);
  boxBlur(a,b,radius,width,height,true);boxBlur(b,a,radius,width,height,false);
  boxBlur(a,b,radius,width,height,true);boxBlur(b,a,radius,width,height,false);
  return Float32Array.from(a,(value,i)=>{
    const t=Math.max(0,Math.min(1,(value-.5)/.35));
    return Math.min(known[i],Math.round(t*t*(3-2*t)*255)/255);
  });
};
export const sampleMask = (data,x,y,size=MASK_SIZE) => {
  const ix=Math.floor(x),iy=Math.floor(y),tx=x-ix,ty=y-iy;
  const at=(a,b)=>data[Math.max(0,Math.min(size-1,b))*size+Math.max(0,Math.min(size-1,a))];
  const top=at(ix,iy)*(1-tx)+at(ix+1,iy)*tx,bottom=at(ix,iy+1)*(1-tx)+at(ix+1,iy+1)*tx;
  return top*(1-ty)+bottom*ty;
};
export const softKnown = (known,texel) => {
  const out=new Uint8Array(MASK_SIZE*MASK_SIZE);
  const blur=(x,y,units)=>{
    const r=Math.max(units/texel,1),d=r*1.9;
    return sampleMask(known,x,y)*.2+
      (sampleMask(known,x+r,y)+sampleMask(known,x-r,y)+sampleMask(known,x,y+r)+sampleMask(known,x,y-r)+
      sampleMask(known,x+d,y+d)+sampleMask(known,x-d,y+d)+sampleMask(known,x+d,y-d)+sampleMask(known,x-d,y-d))*.1;
  };
  for(let y=0;y<MASK_SIZE;y++)for(let x=0;x<MASK_SIZE;x++)out[y*MASK_SIZE+x]=Math.round((blur(x,y,7)+blur(x,y,16.8))*.5*255);
  return out;
};
export const fogMask = (sharp,texel,rounded=true,steps=0) => {
  const known=rounded&&texel<16?roundKnown(sharp,Math.max(1,Math.round(10/texel))):sharp;
  const rgba=new Uint8Array(MASK_SIZE*MASK_SIZE*4),detail=new Uint8Array(rgba.length),soft=softKnown(known,texel);
  for(let i=0;i<known.length;i++) {
    rgba[i*4]=Math.round(known[i]*255);rgba[i*4+3]=255;
    detail[i*4+2]=soft[i];detail[i*4+3]=255;
  }
  markReach(rgba,known,texel,steps);
  return {rgba,detail,known};
};

// The same conservative read reach as MapViewport.fogReach; skip noise over fully known terrain.
const markReach = (rgba,known,texel,steps) => {
  const stride=MASK_SIZE+1,notFull=new Int32Array(stride*stride),notEmpty=new Int32Array(stride*stride);
  for(let y=0;y<MASK_SIZE;y++){let full=0,empty=0;for(let x=0;x<MASK_SIZE;x++){
    const k=known[y*MASK_SIZE+x];full+=k>=1?0:1;empty+=k<=0?0:1;
    notFull[(y+1)*stride+x+1]=notFull[y*stride+x+1]+full;
    notEmpty[(y+1)*stride+x+1]=notEmpty[y*stride+x+1]+empty;
  }}
  const blur=1.9*Math.max(16.8/texel,1),sun=9*2**(steps+1)/texel;
  const west=Math.ceil(Math.max(blur,sun)+1.5),east=Math.ceil(blur+1.5);
  const north=Math.ceil(Math.max(blur,sun*.4)+1.5),south=Math.ceil(blur+10*.643/texel+1.5);
  for(let y=0;y<MASK_SIZE;y++)for(let x=0;x<MASK_SIZE;x++){
    const l=Math.max(0,x-west),r=Math.min(MASK_SIZE,x+east+1),t=Math.max(0,y-north),b=Math.min(MASK_SIZE,y+south+1);
    const sum=data=>data[b*stride+r]-data[t*stride+r]-data[b*stride+l]+data[t*stride+l];
    if(sum(notFull)===0)rgba[(y*MASK_SIZE+x)*4+1]=255;
    else if(sum(notEmpty)===0)rgba[(y*MASK_SIZE+x)*4+2]=255;
  }
};
