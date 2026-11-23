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

/** Intersect a screen point with the same horizontal plane an overlay was drawn on. */
export function worldAtScreen(x, y, height, state) {
  if (!state || !state.projection.sin) return null;
  const u = state.u + (x - state.width / 2) / state.scale;
  const v = state.v + (y - state.height / 2) / state.scale;
  const z = (v + height * state.projection.cos) / state.projection.sin;
  switch (state.projection.facing) {
    case 1: return { x: -z, z: u };
    case 2: return { x: -u, z: -z };
    case 3: return { x: z, z: -u };
    default: return { x: u, z };
  }
}

/** A composited overlay frame never calls the terrain renderer or its tile loader. */
export const createOverlayApi = ({ container, getState, navigate, setDimension, depthTiles = () => [], vehicleFeed = () => undefined, queueFeed = () => undefined }) => {
  const events = new EventTarget();
  let visibility={clouds:true,sablePins:true,trainPins:true,sharedMarkers:true,queuedTiles:false,allVehicles:false,loaded:false,claims:false,frontiers:true,entities:false,heatmap:false};
  try {const saved=JSON.parse(localStorage.getItem('diorama-map-visibility')||'{}');for(const key of Object.keys(visibility))if(typeof saved[key]==='boolean')visibility[key]=saved[key];}catch{}

  const overlays = new Map();
  const canvas = document.createElement("canvas");
  canvas.setAttribute("aria-hidden", "true");
  canvas.style.pointerEvents = "none";
  container.append(canvas);
  const context = canvas.getContext("2d");
  const tooltip = document.createElement("div");
  tooltip.className = "panel territory-tooltip";
  tooltip.hidden = true;
  container.append(tooltip);
  let pointer = null;
  const updateTooltip = state => {
    const labels = [];
    if (pointer && state) {
      for (const overlay of overlays.values()) {
        const label = overlay.hover?.(pointer.x, pointer.y, state);
        if (label) labels.push(label);
      }
    }
    tooltip.hidden = labels.length === 0;
    if (!labels.length) return;
    tooltip.replaceChildren();
    for (const label of labels) {
      const row = document.createElement("div");
      row.className = "territory-hover-entry";
      if (typeof label === "string") {
        row.textContent = label;
      } else {
        if (Number.isInteger(label.color)) {
          const swatch = document.createElement("span");
          swatch.className = "territory-swatch";
          swatch.style.backgroundColor = "#" + (label.color & 0xffffff).toString(16).padStart(6, "0");
          swatch.setAttribute("aria-hidden", "true");
          row.append(swatch);
        }
        const text = document.createElement("span");
        text.textContent = label.name;
        if (label.owner && label.owner !== label.name) {
          const owner = document.createElement("small");
          owner.textContent = "Owner: " + label.owner;
          text.append(owner);
        }
        row.append(text);
      }
      tooltip.append(row);
    }
    tooltip.style.left = Math.max(4, Math.min(pointer.x + 14, state.width - tooltip.offsetWidth - 4)) + "px";
    tooltip.style.top = Math.max(4, Math.min(pointer.y + 16, state.height - tooltip.offsetHeight - 4)) + "px";
  };
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
    updateTooltip(state);
  };
  const invalidate = () => { if (!frame && !disposed) frame = requestAnimationFrame(repaint); };
  return Object.freeze({
    version: 1,
    events,
    getState,
    worldAt: (x, y, height) => worldAtScreen(x, y, height, getState()),
    project: (point) => { const state = getState(); return state ? projectWorld(point, state.projection) : null; },
    screen: (point) => screenPosition(point, getState()),
    navigate,
    setDimension,
    depthTiles,
    vehicleFeed,
    queueFeed,
    registerVisibility(key,label,icon='layers') {
      if(!Object.hasOwn(visibility,key))return;
      events.dispatchEvent(new CustomEvent('visibility-option',{detail:{key,label,icon}}));
    },
    visible: key => visibility[key] !== false,
    setVisible(key,value) {
      if(!Object.hasOwn(visibility,key)||typeof value!=='boolean'||visibility[key]===value)return;
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
      pointer = event.type === "pointermove" && !event.buttons && event.pointerType !== "touch"
        ? { x: event.clientX, y: event.clientY } : null;
      updateTooltip(getState());
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
      tooltip.remove();
      events.dispatchEvent(new Event("dispose"));
    },
  });
};
