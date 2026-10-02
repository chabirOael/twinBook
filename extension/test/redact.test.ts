import { describe, expect, it } from "vitest";
import { findStringEnd, isPlaceholder, l1Placeholder, l2Placeholder, Redactor } from "../src/lib/redact";

const TOKEN = "NAcMx7Q2sZ_kT:17:1696000000";
const LSD = "AVq9xKpW3bQ";

function secretsOf(r: Redactor): Record<string, string> {
  return Object.fromEntries(r.secrets.entries().map((s) => [s.value, s.label]));
}

describe("placeholders", () => {
  it("layer 1 keeps the length", () => {
    for (let n = 0; n < 40; n++) expect(l1Placeholder(n)).toHaveLength(n);
    expect(l1Placeholder(8)).toBe("!R*****!");
    expect(isPlaceholder(l1Placeholder(8))).toBe(true);
    expect(isPlaceholder("!Rabc!")).toBe(false);
  });

  it("layer 2 names the label", () => {
    expect(l2Placeholder("cookie:c_user")).toBe("!T:cookie:c_user!");
    expect(l2Placeholder("cookie:a b")).toBe("!T:cookie:a_b!");
  });
});

describe("Redactor headers", () => {
  it("keeps cookie names and replaces values", () => {
    const r = new Redactor();
    const out = r.cookieHeader("c_user=100012345678901; xs=12%3AabcDEF%3A2%3A1696; locale=en_US");
    expect(out).toBe(`c_user=${l1Placeholder(15)}; xs=${l1Placeholder(22)}; locale=${l1Placeholder(5)}`);
    expect(secretsOf(r)).toMatchObject({ "100012345678901": "cookie:c_user", "12%3AabcDEF%3A2%3A1696": "cookie:xs", "12:abcDEF:2:1696": "cookie:xs", en_US: "cookie:locale" });
    expect(r.counts.cookies).toBe(3);
  });

  it("keeps Set-Cookie attributes, also for several cookies joined by newlines", () => {
    const r = new Redactor();
    const v = "datr=Xy12abCD34ef; expires=Fri, 01 Oct 2027 10:00:00 GMT; Max-Age=34560000; path=/; domain=.facebook.com; secure; httponly; SameSite=None\nfr=0abc; path=/";
    const out = r.setCookie(v);
    expect(out).toBe(`datr=${l1Placeholder(12)}; expires=Fri, 01 Oct 2027 10:00:00 GMT; Max-Age=34560000; path=/; domain=.facebook.com; secure; httponly; SameSite=None\nfr=${l1Placeholder(4)}; path=/`);
    expect(out).not.toContain("Xy12abCD34ef");
    expect(r.counts.setCookies).toBe(2);
  });

  it("replaces credential headers and redacts URL headers", () => {
    const r = new Redactor();
    const out = r.headers([
      { name: "Authorization", value: "Bearer abcdef123456" },
      { name: "X-FB-LSD", value: LSD },
      { name: "Referer", value: `https://www.facebook.com/x?fb_dtsg_ag=${TOKEN}&a=1` },
      { name: "X-FB-Friendly-Name", value: "CometNewsFeedQuery" },
      { name: "Content-Type", value: "text/html" },
    ]);
    expect(out[0]!.value).toBe(l1Placeholder("Bearer abcdef123456".length));
    expect(out[1]!.value).toBe(l1Placeholder(LSD.length));
    expect(out[2]!.value).toBe(`https://www.facebook.com/x?fb_dtsg_ag=${l1Placeholder(TOKEN.length)}&a=1`);
    expect(out[3]!.value).toBe("CometNewsFeedQuery");
    expect(out[4]!.value).toBe("text/html");
    expect(secretsOf(r)).toMatchObject({ abcdef123456: "header:authorization", [LSD]: "header:x-fb-lsd", [TOKEN]: "field:fb_dtsg_ag" });
  });
});

describe("Redactor fields and URLs", () => {
  it("redacts secret form fields and leaves shape fields alone", () => {
    const r = new Redactor();
    const fields: [string, string][] = [
      ["av", "100012345678901"],
      ["__user", "100012345678901"],
      ["__a", "1"],
      ["__req", "1f"],
      ["__rev", "1007000000"],
      ["__s", "abc:def:ghi"],
      ["__hsi", "7300000000000000000"],
      ["__dyn", "7xeUmwlE"],
      ["__csr", "gQ8Q"],
      ["fb_dtsg", TOKEN],
      ["jazoest", "25510"],
      ["lsd", LSD],
      ["variables", JSON.stringify({ id: "1", token: "SECRETinVariables99" })],
      ["doc_id", "1234567890"],
    ];
    const out = r.formFields(fields);
    const map = Object.fromEntries(out);
    for (const keep of ["av", "__user", "__a", "__req", "__rev", "__s", "__hsi", "__dyn", "__csr", "doc_id"]) expect(map[keep]).toBe(Object.fromEntries(fields)[keep]);
    expect(map["fb_dtsg"]).toBe(l1Placeholder(TOKEN.length));
    expect(map["jazoest"]).toBe(l1Placeholder(5));
    expect(map["lsd"]).toBe(l1Placeholder(LSD.length));
    expect(map["variables"]).toBe(JSON.stringify({ id: "1", token: l1Placeholder(19) }));
    expect(out.map(([k]) => k)).toEqual(fields.map(([k]) => k));
    expect(secretsOf(r)).toMatchObject({ [TOKEN]: "field:fb_dtsg", "25510": "field:jazoest", [LSD]: "field:lsd", SECRETinVariables99: "field:token" });
  });

  it("redacts query and fragment values of secret keys only", () => {
    const r = new Redactor();
    expect(r.url(`https://m.facebook.com/a?lsd=${LSD}&__rev=12&next=%2F#access_token=XYZ12345`)).toBe(
      `https://m.facebook.com/a?lsd=${l1Placeholder(LSD.length)}&__rev=12&next=%2F#access_token=${l1Placeholder(8)}`,
    );
    expect(r.url("https://www.facebook.com/")).toBe("https://www.facebook.com/");
  });
});

describe("Redactor text bodies", () => {
  it("finds keyed values in JSON, escaped JSON, JS literals, query strings and input tags", () => {
    const r = new Redactor();
    const html = [
      `<script>require("ServerJS").handle({"define":[["DTSGInitialData",[],{"token":"${TOKEN}"},258],["LSD",[],{"token":"${LSD}"},323]]});</script>`,
      `<script>var x = {async_get_token:'AgTokenValue1'};</script>`,
      `<form><input type="hidden" name="lsd" value="${LSD}" autocomplete="off"><input type="hidden" name="jazoest" value="21000"><input name="email" value=""></form>`,
      `<a href="/logout.php?h=1&fb_dtsg=${encodeURIComponent(TOKEN)}">`,
      `{"payload":"{\\"password\\":\\"#PWD_BROWSER:5:1:abc\\\\\\"def\\"}"}`,
      `"__rev":1007000000,"__spin_r":1007000000,"client_token":"keepme"`,
    ].join("\n");
    const out = r.text(html);
    expect(out).toHaveLength(html.length);
    for (const s of [TOKEN, LSD, "AgTokenValue1", "21000", "#PWD_BROWSER:5:1:abc", encodeURIComponent(TOKEN)]) expect(out).not.toContain(s);
    expect(out).toContain('"__rev":1007000000');
    expect(out).toContain('"client_token":"keepme"');
    expect(out).toContain('name="email" value=""');
    expect(out).toContain(`{"token":"${l1Placeholder(TOKEN.length)}"}`);
    expect(secretsOf(r)).toMatchObject({ [TOKEN]: "field:token", AgTokenValue1: "field:async_get_token", "21000": "field:jazoest" });
    // The escaped password value is remembered raw and unescaped.
    const pw = r.secrets.entries().filter((s) => s.label === "field:password").map((s) => s.value);
    expect(pw).toContain('#PWD_BROWSER:5:1:abc\\"def');
  });

  it("is idempotent", () => {
    const r = new Redactor();
    const once = r.text(`{"token":"${TOKEN}"} lsd=${LSD}`);
    expect(r.text(once)).toBe(once);
  });

  it("finds the end of strings at every escaping level", () => {
    expect(findStringEnd('abc"', 0, '"', 0)).toBe(3);
    expect(findStringEnd('a\\"b"', 0, '"', 0)).toBe(4);
    expect(findStringEnd('a\\\\"', 0, '"', 0)).toBe(3);
    expect(findStringEnd('ab\\"', 0, '"', 1)).toBe(2);
    expect(findStringEnd('a\\\\\\"b\\"', 0, '"', 1)).toBe(6);
    expect(findStringEnd("abc", 0, '"', 0)).toBe(-1);
  });
});
