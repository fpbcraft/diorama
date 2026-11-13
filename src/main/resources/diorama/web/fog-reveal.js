export const REVEAL_MS = 800;
export const revealProgress = (started,now,reduced=false) => {
  if(reduced)return 1;
  const t=Math.max(0,Math.min(1,(now-started)/REVEAL_MS));return t*t*(3-2*t);
};
/** Continue an interrupted reveal from its current coverage; never expose newly unknown ground. */
export const revealFrom = (previous,target,amount) => {
  if(!previous)return {rgba:new Uint8Array(target.rgba),detail:new Uint8Array(target.detail)};
  const rgba=new Uint8Array(target.rgba.length),detail=new Uint8Array(target.detail.length);
  for(let i=0;i<rgba.length;i+=4) {
    rgba[i]=Math.min(target.rgba[i],Math.round(previous.from.rgba[i]*(1-amount)+previous.to.rgba[i]*amount));
    rgba[i+1]=Math.min(previous.from.rgba[i+1],previous.to.rgba[i+1],target.rgba[i+1]);
    rgba[i+2]=Math.min(previous.from.rgba[i+2],previous.to.rgba[i+2],target.rgba[i+2]);rgba[i+3]=255;
    detail[i+2]=Math.min(target.detail[i+2],Math.round(previous.from.detail[i+2]*(1-amount)+previous.to.detail[i+2]*amount));detail[i+3]=255;
  }
  return {rgba,detail};
};
