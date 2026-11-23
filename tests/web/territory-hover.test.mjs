import assert from "node:assert/strict";
import test from "node:test";
import { createOverlayApi } from "../../src/main/resources/diorama/web/overlay-api.js";

test("pointer hover combines overlay names, swatches and owner details, and clears on leave", () => {
  function element() {
    return {
      children: [], style: {}, hidden: false, offsetWidth: 100, offsetHeight: 45,
      append(...children) { this.children.push(...children); },
      replaceChildren() { this.children = []; },
      setAttribute() {},
      getContext() { return {}; },
    };
  }
  globalThis.document = { createElement: element };
  globalThis.requestAnimationFrame = () => 1;
  const container = element();
  const view = { width: 640, height: 480 };
  const api = createOverlayApi({ container, getState: () => view });
  const tooltip = container.children.find(e => e.className === "panel territory-tooltip");
  api.registerOverlay("frontiers", {
    hover: () => ({ name: "Harbour", color: 0x55aa77 }),
  });
  api.registerOverlay("claims", {
    hover(x, y, state) {
      assert.deepEqual([x, y, state], [42, 54, view]);
      return { name: "Dock", color: 0x336699, owner: "Alex" };
    },
  });
  api.pointer({ type: "pointermove", pointerType: "mouse", buttons: 0, clientX: 42, clientY: 54 });
  assert.equal(tooltip.hidden, false);
  assert.deepEqual(tooltip.children.map(row => row.children[1].textContent), ["Harbour", "Dock"]);
  assert.deepEqual(tooltip.children.map(row => row.children[0].style.backgroundColor), ["#55aa77", "#336699"]);
  assert.equal(tooltip.children[1].children[1].children[0].textContent, "Owner: Alex");

  api.pointer({ type: "pointerleave", pointerType: "mouse" });
  assert.equal(tooltip.hidden, true);
});
