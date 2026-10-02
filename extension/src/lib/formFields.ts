// Extracts the form fields a site's GraphQL POST carries, from webRequest request details.

/** The fields the app wants from every recorded GraphQL request. */
export const RECORDED_FIELDS = [
  "fb_api_req_friendly_name",
  "doc_id",
  "variables",
  "fb_dtsg",
  "lsd",
  "jazoest",
  "__rev",
  "__req",
] as const;

export interface RecordedForm {
  /** Every field name in body order, duplicates kept. */
  fieldNames: string[];
  /** The RECORDED_FIELDS that were present (first value of each). */
  fields: Partial<Record<(typeof RECORDED_FIELDS)[number], string>>;
  /** How the body was read: parsed form data, raw bytes, or not available. */
  source: "formData" | "raw" | "none";
}

interface RequestBodyLike {
  formData?: Record<string, string[]> | undefined;
  raw?: { bytes?: ArrayBuffer | undefined }[] | undefined;
}

export function readFormFields(body: RequestBodyLike | undefined | null): RecordedForm {
  let entries: [string, string][] = [];
  let source: RecordedForm["source"] = "none";
  if (body?.formData !== undefined) {
    source = "formData";
    for (const [name, values] of Object.entries(body.formData)) {
      for (const v of values) entries.push([name, v]);
    }
  } else if (body?.raw !== undefined) {
    source = "raw";
    const parts = body.raw.flatMap((p) => (p.bytes === undefined ? [] : [new Uint8Array(p.bytes)]));
    const total = parts.reduce((n, p) => n + p.length, 0);
    const all = new Uint8Array(total);
    let offset = 0;
    for (const p of parts) {
      all.set(p, offset);
      offset += p.length;
    }
    entries = [...new URLSearchParams(new TextDecoder().decode(all)).entries()];
  }
  const fields: RecordedForm["fields"] = {};
  for (const name of RECORDED_FIELDS) {
    const hit = entries.find(([n]) => n === name);
    if (hit !== undefined) fields[name] = hit[1];
  }
  return { fieldNames: entries.map(([n]) => n), fields, source };
}
