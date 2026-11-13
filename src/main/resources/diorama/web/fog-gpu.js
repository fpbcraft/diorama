import { revealProgress, revealFrom } from './fog-reveal.js';
/** The game's fog.fsh, with only its host uniforms adapted for WebGL2. Half-resolution, as FogLayer. */
export class FogGpu {
  constructor(canvas) {
    this.gl=canvas.getContext('webgl2',{alpha:true,premultipliedAlpha:false,antialias:false,powerPreference:'low-power'});
    if(!this.gl)throw Error('WebGL2 unavailable');
    this.canvas=canvas;this.ready=false;this.disposed=false;
  }
  async load(signal) {
    const [response,noiseResponse]=await Promise.all([fetch(new URL('fog.fsh',import.meta.url),{signal}),fetch(new URL('fog-noise.png',import.meta.url),{signal})]);
    if(!response.ok||!noiseResponse.ok)throw Error('Fog resources unavailable');
    const shader=await response.text(),noise=await createImageBitmap(await noiseResponse.blob(),{premultiplyAlpha:'none',colorSpaceConversion:'none'});
    if(signal.aborted){noise.close();return;}
    const gl=this.gl;
    const compile=(type,source)=>{const s=gl.createShader(type);gl.shaderSource(s,source);gl.compileShader(s);if(!gl.getShaderParameter(s,gl.COMPILE_STATUS)){const message=gl.getShaderInfoLog(s);gl.deleteShader(s);throw Error(message);}return s;};
    const vertex=compile(gl.VERTEX_SHADER,'#version 300 es\nprecision highp float;uniform vec4 viewUv;out vec2 maskUv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);maskUv=mix(viewUv.xy,viewUv.zw,vec2(p.x,1.0-p.y));gl_Position=vec4(p*2.0-1.0,0.0,1.0);}');
    const fragment=compile(gl.FRAGMENT_SHADER,shader);this.program=gl.createProgram();gl.attachShader(this.program,vertex);gl.attachShader(this.program,fragment);gl.linkProgram(this.program);gl.deleteShader(vertex);gl.deleteShader(fragment);
    if(!gl.getProgramParameter(this.program,gl.LINK_STATUS))throw Error(gl.getProgramInfoLog(this.program));
    gl.useProgram(this.program);this.uniforms={};for(const name of ['viewUv','params','GameTime','ColorModulator','Reveal'])this.uniforms[name]=gl.getUniformLocation(this.program,name);
    this.textures=[0,1,2,3,4].map(index=>{const texture=gl.createTexture();gl.activeTexture(gl.TEXTURE0+index);gl.bindTexture(gl.TEXTURE_2D,texture);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MIN_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_MAG_FILTER,gl.LINEAR);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_S,index===1?gl.REPEAT:gl.CLAMP_TO_EDGE);gl.texParameteri(gl.TEXTURE_2D,gl.TEXTURE_WRAP_T,index===1?gl.REPEAT:gl.CLAMP_TO_EDGE);gl.uniform1i(gl.getUniformLocation(this.program,index===3?'Previous0':index===4?'Previous2':'Sampler'+index),index);return texture;});
    gl.activeTexture(gl.TEXTURE1);gl.bindTexture(gl.TEXTURE_2D,this.textures[1]);gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL,gl.NONE);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,gl.RGBA,gl.UNSIGNED_BYTE,noise);noise.close();
    gl.uniform4f(this.uniforms.ColorModulator,1,1,1,1);this.ready=true;
  }
  update(mask,animate=true) {
    if(!this.ready)return;const gl=this.gl,now=performance.now();
    const previous=animate?this.transition:null;
    const amount=previous?revealProgress(previous.started,now):1;
    let from=revealFrom(previous,mask,amount);
    if(animate&&!previous){from={rgba:new Uint8Array(mask.rgba.length),detail:new Uint8Array(mask.detail.length)};for(let i=3;i<from.rgba.length;i+=4){from.rgba[i]=255;from.detail[i]=255;}}
    this.transition={from,to:mask,started:animate?now:now-800};
    for(const [unit,data] of [[0,mask.rgba],[2,mask.detail],[3,from.rgba],[4,from.detail]]){gl.activeTexture(gl.TEXTURE0+unit);gl.bindTexture(gl.TEXTURE_2D,this.textures[unit]);gl.texImage2D(gl.TEXTURE_2D,0,gl.RGBA,256,256,0,gl.RGBA,gl.UNSIGNED_BYTE,data);}
  }
  draw(state,layout,time) {
    if(!this.ready||this.disposed)return;
    const gl=this.gl,ratio=Math.min(.5,1280/Math.max(state.width,state.height));
    const w=Math.max(1,Math.ceil(state.width*ratio)),h=Math.max(1,Math.ceil(state.height*ratio));
    if(this.canvas.width!==w||this.canvas.height!==h){this.canvas.width=w;this.canvas.height=h;}
    gl.viewport(0,0,w,h);gl.useProgram(this.program);
    const left=state.u-state.width/2/state.scale,top=state.v-state.height/2/state.scale;
    gl.uniform4f(this.uniforms.viewUv,(left-layout.u)/layout.units,(top-layout.v)/layout.units,(left+state.width/state.scale-layout.u)/layout.units,(top+state.height/state.scale-layout.v)/layout.units);
    const period=512*2**(layout.steps+1),offset=value=>Math.floor(((value/period%1+1)%1)*8)*32;
    const alpha=(Math.round(Math.log2(layout.texel/2))*16)+(layout.steps>=8?8:0)+2+(state.smoke?1:0);
    gl.uniform4f(this.uniforms.params,offset(layout.u)/255,offset(layout.v)/255,((layout.steps%8)*16+Math.round(layout.fraction*16))/255,alpha/255);
    const progress=this.transition?revealProgress(this.transition.started,performance.now(),state.reduced):1;
    this.canvas.dataset.fogRevealProgress=progress.toFixed(3);
    gl.uniform1f(this.uniforms.Reveal,progress);
    gl.uniform1f(this.uniforms.GameTime,(time%1200000)/1200000);gl.drawArrays(gl.TRIANGLES,0,3);
  }
  dispose(){this.disposed=true;if(this.textures)for(const texture of this.textures)this.gl.deleteTexture(texture);if(this.program)this.gl.deleteProgram(this.program);}
}
