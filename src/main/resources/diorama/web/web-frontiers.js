export function contains(vertices, x, z) {
  let inside = false;
  for (let i = 0, j = vertices.length - 2; i < vertices.length; j = i, i += 2) {
    const zi = vertices[i + 1], zj = vertices[j + 1];
    if ((zi > z) !== (zj > z) && x < (vertices[j] - vertices[i]) * (z - zi) / (zj - zi) + vertices[i]) {
      inside = !inside;
    }
  }
  return inside;
}

export class WebFrontiers {
  constructor(api, feed) {
    this.api = api;
    this.feed = feed;
    this.entries = [];
    this.abort = new AbortController();
    this.overlay = api.registerOverlay("frontiers", {
      draw: (context, view) => this.draw(context, view),
      hover: (x, y, view) => this.hover(x, y, view),
    });
    const start = () => {
      if (this.feed() && !this.available) {
        this.available = true;
        api.registerVisibility("frontiers", "Frontiers", "flag");
        void this.poll();
      }
    };
    api.events.addEventListener("change", start, { signal: this.abort.signal });
    start();
    api.events.addEventListener("dispose", () => this.dispose(), { once: true, signal: this.abort.signal });
    api.events.addEventListener("visibility", event => {
      if (event.detail.key === "frontiers" && event.detail.value) void this.poll();
    }, { signal: this.abort.signal });
  }

  async poll() {
    const path = this.feed();
    const url = path ? new URL(path, document.baseURI) : null;
    if (!url || url.origin !== location.origin || this.loading || this.abort.signal.aborted) return;
    clearTimeout(this.timer);
    this.loading = true;
    try {
      if (this.api.visible("frontiers") && !document.hidden) {
        const signal = AbortSignal.any([this.abort.signal, AbortSignal.timeout(8000)]);
        const response = await fetch(url, { cache: "no-cache", signal });
        const data = response.ok ? await response.json() : [];
        this.entries = Array.isArray(data) && data.length <= 512 ? data.filter(entry =>
          typeof entry?.dimension === "string" && typeof entry.name === "string" && entry.name.length <= 48 &&
          Number.isInteger(entry.color) && Array.isArray(entry.vertices) &&
          entry.vertices.length >= 6 && entry.vertices.length <= 512 && entry.vertices.length % 2 === 0 &&
          entry.vertices.every(Number.isFinite)) : [];
      }
    } catch {
      this.entries = [];
    } finally {
      this.loading = false;
      this.overlay.invalidate();
      if (!this.abort.signal.aborted) this.timer = setTimeout(() => void this.poll(), 3000);
    }
  }

  draw(context, view) {
    if (!this.api.visible("frontiers")) return;
    context.lineWidth = 1.5;
    const y = view.referenceY ?? 64;
    const visible = this.entries.filter(entry => entry.dimension === view.dimension);
    for (const entry of visible) {
      const color = (entry.color & 0xffffff).toString(16).padStart(6, "0");
      context.beginPath();
      for (let i = 0; i < entry.vertices.length; i += 2) {
        const point = this.api.screen({ x: entry.vertices[i], y, z: entry.vertices[i + 1] });
        if (i) context.lineTo(point.x, point.y);
        else context.moveTo(point.x, point.y);
      }
      context.closePath();
      context.fillStyle = "#" + color + "38";
      context.strokeStyle = "#" + color;
      context.fill();
      context.stroke();
    }
    context.textAlign = "center";
    context.textBaseline = "middle";
    context.font = "600 13px system-ui, sans-serif";
    context.lineWidth = 3;
    context.lineJoin = "round";
    for (const entry of visible) {
      const label = entry.label;
      if (!label || ![label.x, label.z, label.clearance].every(Number.isFinite)) continue;
      const point = this.api.screen({ x: label.x, y, z: label.z });
      const radius = label.clearance * view.projection.sin * view.scale;
      if (Math.hypot(context.measureText(entry.name).width / 2 + 2, 8) > radius) continue;
      context.strokeStyle = "#101722";
      context.fillStyle = "#f3f6fa";
      context.strokeText(entry.name, point.x, point.y);
      context.fillText(entry.name, point.x, point.y);
    }
  }

  hover(x, y, view) {
    if (!this.api.visible("frontiers")) return;
    const point = this.api.worldAt(x, y, view.referenceY ?? 64);
    if (!point) return;
    const entry = this.entries.findLast(entry => entry.dimension === view.dimension &&
      contains(entry.vertices, point.x, point.z));
    return entry ? { name: entry.name, color: entry.color } : undefined;
  }

  dispose() {
    this.abort.abort();
    clearTimeout(this.timer);
    this.overlay.remove();
  }
}
