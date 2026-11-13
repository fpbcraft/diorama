/** Point coordinates are Minecraft blocks; screen coordinates are CSS pixels. */
export const projectWorld = (point, projection) => {
  const { x, y, z } = point;
  const facing = projection.facing || 0;
  const u = facing === 1 ? z : facing === 2 ? -x : facing === 3 ? -z : x;
  const turnedZ = facing === 1 ? -x : facing === 2 ? -z : facing === 3 ? x : z;
  return { u, v: turnedZ * projection.sin - y * projection.cos };
};

export const screenPosition = (point, state) => {
  if (!state || (point.dimension && point.dimension !== state.dimension)) return null;
  const { u, v } = projectWorld(point, state.projection);
  return { x: (u - state.u) * state.scale + state.width / 2,
    y: (v - state.v) * state.scale + state.height / 2 };
};

/** A composited overlay frame never calls the terrain renderer or its tile loader. */
export const createOverlayApi = ({ container, getState, navigate, setDimension, depthTiles = () => [], vehicleFeed = () => undefined }) => {
  const events = new EventTarget();
  let visibility={clouds:true,sablePins:true,trainPins:true,sharedMarkers:true};
  try {const saved=JSON.parse(localStorage.getItem('diorama-map-visibility')||'{}');for(const key of Object.keys(visibility))if(typeof saved[key]==='boolean')visibility[key]=saved[key];}catch{}

  const overlays = new Map();
  const canvas = document.createElement("canvas");
  canvas.setAttribute("aria-hidden", "true");
  canvas.style.pointerEvents = "none";
  container.append(canvas);
  const context = canvas.getContext("2d");
  let frame = 0;
  let disposed = false;
  const repaint = () => {
    frame = 0;
    const state = getState();
    if (!state || !context) return;
    const dpr = state.pixelRatio;
    canvas.width = Math.round(state.width * dpr);
    canvas.height = Math.round(state.height * dpr);
    context.setTransform(dpr, 0, 0, dpr, 0, 0);
    for (const overlay of overlays.values()) {
      context.save();
      try { overlay.draw?.(context, state); }
      catch (error) { console.error("Diorama overlay draw failed", error); }
      finally { context.restore(); }
    }
  };
  const invalidate = () => { if (!frame && !disposed) frame = requestAnimationFrame(repaint); };
  return Object.freeze({
    version: 1,
    events,
    getState,
    project: (point) => { const state = getState(); return state ? projectWorld(point, state.projection) : null; },
    screen: (point) => screenPosition(point, getState()),
    navigate,
    setDimension,
    depthTiles,
    vehicleFeed,
    visible: key => visibility[key] !== false,
    setVisible(key,value) {
      if(!Object.hasOwn(visibility,key)||typeof value!=='boolean')return;
      visibility[key]=value;
      try{localStorage.setItem('diorama-map-visibility',JSON.stringify(visibility));}catch{}
      events.dispatchEvent(new CustomEvent('visibility',{detail:{key,value}}));invalidate();
    },
    registerOverlay(id, overlay) {
      if (disposed || overlays.has(id)) throw new Error("Overlay is disposed or already registered: " + id);
      overlays.set(id, overlay);
      if (overlay.element) container.append(overlay.element);
      invalidate();
      return { invalidate, remove() {
        if (overlays.get(id) !== overlay) return;
        overlays.delete(id);
        overlay.element?.remove();
        invalidate();
      } };
    },
    /** Called by terrain navigation; extensions subscribe to change, and interaction cancels follow. */
    changed(reason = "viewport") {
      events.dispatchEvent(new CustomEvent("change", { detail: { reason, state: getState() } }));
      invalidate();
    },
    pointer(event) {
      for (const overlay of [...overlays.values()].reverse()) {
        if (overlay.pointer?.(event, getState())) return true;
      }
      return false;
    },
    dispose() {
      disposed = true;
      cancelAnimationFrame(frame);
      for (const overlay of overlays.values()) overlay.element?.remove();
      overlays.clear();
      canvas.remove();
      events.dispatchEvent(new Event("dispose"));
    },
  });
};
