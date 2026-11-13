import { fogMask } from './fog-mask.js';
self.onmessage = ({data}) => {
  const mask=fogMask(data.input.sharp,data.input.texel,data.input.rounded,data.input.steps);
  self.postMessage({id:data.id,mask},[mask.rgba.buffer,mask.detail.buffer,mask.known.buffer]);
};
