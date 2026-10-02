// Shape of a replay result, shared by the background script and the anchor content script.

export interface ReplayRequest {
  url: string;
  method?: string;
  headers?: Record<string, string>;
  body?: string;
  credentials?: RequestCredentials;
}

export interface FetchedResponse {
  via: string;
  status: number;
  statusText: string;
  url: string;
  headers: Record<string, string>;
  body: string;
}

export async function toResult(response: Response, via: string): Promise<FetchedResponse> {
  const headers: Record<string, string> = {};
  response.headers.forEach((value, name) => {
    headers[name] = value;
  });
  return { via, status: response.status, statusText: response.statusText, url: response.url, headers, body: await response.text() };
}

export function requestInit(r: ReplayRequest): RequestInit {
  const init: RequestInit = { method: r.method ?? "GET", credentials: r.credentials ?? "include", headers: r.headers ?? {} };
  if (r.body !== undefined) init.body = r.body;
  return init;
}
