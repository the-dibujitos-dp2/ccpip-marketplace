import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";

describe("favicon oficial", () => {
  it("index apunta al PNG versionado con tamaños y conserva título", () => {
    const html = readFileSync("index.html", "utf8");
    expect(html).toContain("<title>Mercado CCPIP</title>");
    expect(html).toMatch(/<link[^>]*rel="icon"[^>]*type="image\/png"[^>]*sizes="64x64"[^>]*href="\/favicon-ccpip.png"/);
    expect(html).not.toContain("/favicon.svg");
  });

  it("el archivo oficial existe como PNG cuadrado y con transparencia", () => {
    const bytes = readFileSync("public/favicon-ccpip.png");
    expect(bytes.subarray(0, 8)).toEqual(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]));
    expect(bytes.readUInt32BE(16)).toBe(64);
    expect(bytes.readUInt32BE(20)).toBe(64);
    expect(bytes[25]).toBe(6);
  });
});
