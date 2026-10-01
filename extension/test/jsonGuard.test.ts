import { describe, expect, it } from "vitest";
import { stripJsonGuard } from "../src/lib/jsonGuard";

describe("stripJsonGuard", () => {
  it("removes the guard with a space", () => {
    expect(stripJsonGuard('for (;;);{"a":1}')).toBe('{"a":1}');
  });

  it("removes the guard without a space", () => {
    expect(stripJsonGuard('for(;;);{"a":1}')).toBe('{"a":1}');
  });

  it("returns text without a guard unchanged", () => {
    expect(stripJsonGuard('{"a":1}')).toBe('{"a":1}');
    expect(stripJsonGuard("  \n{\"a\":1}")).toBe("  \n{\"a\":1}");
    expect(stripJsonGuard("")).toBe("");
  });

  it("removes a guard preceded by leading whitespace", () => {
    expect(stripJsonGuard(' \r\n\tfor (;;);{"a":1}')).toBe('{"a":1}');
  });

  it("does not touch a guard that appears later in the text", () => {
    const text = '{"code":"for (;;);"}';
    expect(stripJsonGuard(text)).toBe(text);
    expect(stripJsonGuard('x for(;;);{"a":1}')).toBe('x for(;;);{"a":1}');
  });

  it("removes only one leading guard", () => {
    expect(stripJsonGuard('for (;;);for (;;);{"a":1}')).toBe('for (;;);{"a":1}');
  });

  it("keeps everything after the guard byte for byte", () => {
    const body = '\n{"a":1}\n{"b":2}\n';
    expect(stripJsonGuard(`for (;;);${body}`)).toBe(body);
  });

  it("does not treat a near-miss as a guard", () => {
    expect(stripJsonGuard("for  (;;);{}")).toBe("for  (;;);{}");
    expect(stripJsonGuard("for (;;){}")).toBe("for (;;){}");
  });
});
