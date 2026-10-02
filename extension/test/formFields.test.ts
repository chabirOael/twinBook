import { describe, expect, it } from "vitest";
import { readFormFields } from "../src/lib/formFields";

describe("readFormFields", () => {
  it("reads parsed form data", () => {
    const r = readFormFields({ formData: { av: ["1"], fb_api_req_friendly_name: ["FeedQuery"], doc_id: ["123"], variables: ['{"a":1}'], __req: ["1b"] } });
    expect(r.source).toBe("formData");
    expect(r.fieldNames).toEqual(["av", "fb_api_req_friendly_name", "doc_id", "variables", "__req"]);
    expect(r.fields).toEqual({ fb_api_req_friendly_name: "FeedQuery", doc_id: "123", variables: '{"a":1}', __req: "1b" });
  });

  it("parses a raw urlencoded body", () => {
    const body = new TextEncoder().encode("fb_dtsg=AB%3Acd&lsd=x&jazoest=2&__rev=1000&variables=%7B%22q%22%3A%22%C3%A9%22%7D");
    const r = readFormFields({ raw: [{ bytes: body.buffer }] });
    expect(r.source).toBe("raw");
    expect(r.fields).toEqual({ fb_dtsg: "AB:cd", lsd: "x", jazoest: "2", __rev: "1000", variables: '{"q":"é"}' });
  });

  it("copes with no body", () => {
    expect(readFormFields(undefined)).toEqual({ fieldNames: [], fields: {}, source: "none" });
  });
});
