/** Problem details from core-app (RFC 7807); `detail` is already localized for the UI language. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string | undefined,
    readonly detail: string | undefined,
    readonly body: Record<string, unknown>,
  ) {
    super(detail ?? code ?? `HTTP ${status}`);
  }
}

const BASE = "/api/core";

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { accept: "application/json" };
  let payload: BodyInit | undefined;
  if (body instanceof FormData) {
    payload = body;
  } else if (body !== undefined) {
    headers["content-type"] = "application/json";
    payload = JSON.stringify(body);
  }
  if (method !== "GET") {
    // One key per user action; the BFF forwards it so retries are safe.
    headers["idempotency-key"] = crypto.randomUUID();
  }
  const response = await fetch(`${BASE}${path}`, { method, headers, body: payload, credentials: "same-origin" });
  if (response.status === 401 && typeof window !== "undefined") {
    // Session ended (refresh failed): start over at the login page.
    // eslint-disable-next-line @next/next/no-location-assign-relative-destination
    window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname)}`);
  }
  if (!response.ok) {
    const problem = (await response.json().catch(() => ({}))) as Record<string, unknown>;
    throw new ApiError(response.status, problem.code as string | undefined, problem.detail as string | undefined, problem);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return (await response.json()) as T;
}

export const api = {
  get: <T>(path: string) => request<T>("GET", path),
  post: <T>(path: string, body?: unknown) => request<T>("POST", path, body ?? {}),
  put: <T>(path: string, body: unknown) => request<T>("PUT", path, body),
  patch: <T>(path: string, body: unknown) => request<T>("PATCH", path, body),
  del: <T>(path: string) => request<T>("DELETE", path),
  upload: <T>(path: string, form: FormData) => request<T>("POST", path, form),
};

/** Signed preview links are relative to the API base; load them through the BFF. */
export function previewSrc(url: string) {
  return `${BASE}${url}`;
}

export function query(params: Record<string, string | number | boolean | undefined | null>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== "" && value !== false) {
      search.set(key, String(value));
    }
  }
  const s = search.toString();
  return s ? `?${s}` : "";
}
