import { type NextRequest, NextResponse } from "next/server";
import { LOCALE_COOKIE, toLocale } from "@/i18n/locales";
import {
  ACCESS_COOKIE,
  CORE_API_URL,
  REFRESH_COOKIE,
  clearSessionCookies,
  refreshTokens,
  setSessionCookies,
  type TokenResponse,
} from "@/lib/server/session";

/**
 * Backend-for-frontend proxy: /api/core/api/v1/... → core-app /api/v1/...
 * Adds the access token from the httpOnly cookie, the UI language and the caller's Idempotency-Key, and
 * refreshes the session once on 401. Tokens never reach browser JavaScript.
 */
const FORWARDED_REQUEST_HEADERS = ["content-type", "accept", "idempotency-key"];
const FORWARDED_RESPONSE_HEADERS = [
  "content-type",
  "content-disposition",
  "cache-control",
  "location",
  "retry-after",
  "idempotent-replayed",
  "referrer-policy",
];

async function proxy(request: NextRequest, { params }: { params: Promise<{ path: string[] }> }) {
  const { path } = await params;
  // Only the versioned API: never actuator or other internal paths.
  if (path[0] !== "api" || path[1] !== "v1" || path.some((p) => p === ".." || p === ".")) {
    return NextResponse.json({ title: "Not found", status: 404 }, { status: 404 });
  }
  const target = `${CORE_API_URL}/${path.map(encodeURIComponent).join("/")}${request.nextUrl.search}`;
  const body = ["GET", "HEAD"].includes(request.method) ? undefined : await request.arrayBuffer();

  const call = (accessToken: string | undefined) => {
    const headers = new Headers();
    for (const name of FORWARDED_REQUEST_HEADERS) {
      const value = request.headers.get(name);
      if (value) headers.set(name, value);
    }
    headers.set("accept-language", toLocale(request.cookies.get(LOCALE_COOKIE)?.value));
    if (accessToken) headers.set("authorization", `Bearer ${accessToken}`);
    return fetch(target, { method: request.method, headers, body, cache: "no-store", redirect: "manual" });
  };

  let refreshed: TokenResponse | null = null;
  let upstream = await call(request.cookies.get(ACCESS_COOKIE)?.value);
  const refreshToken = request.cookies.get(REFRESH_COOKIE)?.value;
  if (upstream.status === 401 && refreshToken) {
    refreshed = await refreshTokens(refreshToken);
    if (refreshed) {
      upstream = await call(refreshed.accessToken);
    }
  }

  const headers = new Headers();
  for (const name of FORWARDED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value) headers.set(name, value);
  }
  const response = new NextResponse(upstream.status === 204 ? null : upstream.body, {
    status: upstream.status,
    headers,
  });
  if (refreshed) {
    setSessionCookies(response, refreshed);
  } else if (upstream.status === 401 && refreshToken) {
    clearSessionCookies(response);
  }
  return response;
}

export { proxy as GET, proxy as POST, proxy as PUT, proxy as PATCH, proxy as DELETE };
