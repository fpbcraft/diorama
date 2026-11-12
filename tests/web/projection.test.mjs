import { readFile } from "node:fs/promises";
import assert from "node:assert/strict";
import { test } from "node:test";
const source = await readFile(new URL("../../src/main/resources/diorama/web/overlay-api.js", import.meta.url), "utf8");
const { projectWorld, screenPosition } = await import(`data:text/javascript;base64,${Buffer.from(source).toString("base64")}`);
const close = (actual, expected) => assert.ok(Math.abs(actual - expected) < 1e-9, `${actual} != ${expected}`);
for (const facing of [0, 1, 2, 3]) for (const pitch of [15, 40, 90]) {
  test(`facing ${facing}, pitch ${pitch}, Y and negative world coordinates`, () => {
    const projection = { facing, sin: Math.sin(pitch * Math.PI / 180), cos: Math.cos(pitch * Math.PI / 180) };
    const point = { x: -81.5, y: -40, z: 23.25 };
    const turned = [[-81.5, 23.25], [23.25, 81.5], [81.5, -23.25], [-23.25, -81.5]][facing];
    const result = projectWorld(point, projection);
    close(result.u, turned[0]); close(result.v, turned[1] * projection.sin + 40 * projection.cos);
    close(projectWorld({ ...point, y: 60 }, projection).v, result.v - 100 * projection.cos);
  });
}
test("pan, zoom, dimensions, resize and high DPI stay in CSS pixels", () => {
  const state = { dimension: "custom:moon", projection: { sin: 1, cos: 0, facing: 0 }, u: -50, v: -100, scale: 2, width: 800, height: 600, pixelRatio: 1 };
  const point = { x: -40, y: 72, z: -80, dimension: "custom:moon" };
  assert.deepEqual(screenPosition(point, state), { x: 420, y: 340 });
  assert.deepEqual(screenPosition(point, { ...state, pixelRatio: 3 }), { x: 420, y: 340 });
  assert.deepEqual(screenPosition(point, { ...state, width: 400, height: 300, scale: 4 }), { x: 240, y: 230 });
  assert.equal(screenPosition({ ...point, dimension: "minecraft:the_nether" }, state), null);
  assert.equal(screenPosition(point, null), null);
});
