import "server-only";
import { createHash } from "node:crypto";
import type { NextResponse } from "next/server";

/** Base URL of core-app, reached from the Next.js server only. */
export const CORE_API_URL = (process.env.CORE_API_URL ?? "http://localhost:8080").replace(/\/$/, "");

const secure = process.env.NODE_ENV === "production" && process.env.COOKIE_INSECURE !== "true";

/** Access and refresh tokens: httpOnly, only sent to the console's own /api routes. */
export const ACCESS_COOKIE = "wc_at";
export const REFRESH_COOKIE = "wc_rt";
/** Non-secret marker so proxy.ts (which runs for pages) knows a session exists. */
export const SESSION_COOKIE = "wc_session";

export type TokenResponse = {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
  refreshExpiresIn: number;
};

export function setSessionCookies(response: NextResponse, tokens: TokenResponse) {
  const base = { httpOnly: true, secure, sameSite: "lax" as const };
  response.cookies.set(ACCESS_COOKIE, tokens.accessToken, { ...base, path: "/api", maxAge: tokens.expiresIn });
  response.cookies.set(REFRESH_COOKIE, tokens.refreshToken, {
    ...base,
    path: "/api",
    maxAge: tokens.refreshExpiresIn,
  });
  response.cookies.set(SESSION_COOKIE, "1", { ...base, path: "/", maxAge: tokens.refreshExpiresIn });
}

export function clearSessionCookies(response: NextResponse) {
  for (const [name, path] of [
    [ACCESS_COOKIE, "/api"],
    [REFRESH_COOKIE, "/api"],
    [SESSION_COOKIE, "/"],
  ] as const) {
    response.cookies.set(name, "", { path, maxAge: 0 });
  }
}

/**
 * Rotates the refresh token. Several browser requests can find the access token expired at once; they all
 * send the same Idempotency-Key (derived from the refresh token), so core-app performs one rotation and
 * replays its result to the others instead of treating them as token reuse (theft).
 */
export async function refreshTokens(refreshToken: string): Promise<TokenResponse | null> {
  const key = "refresh-" + createHash("sha256").update(refreshToken).digest("hex").slice(0, 40);
  for (let attempt = 0; attempt < 6; attempt++) {
    const response = await fetch(`${CORE_API_URL}/api/v1/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "Idempotency-Key": key },
      body: JSON.stringify({ refreshToken }),
      cache: "no-store",
    });
    if (response.ok) {
      return (await response.json()) as TokenResponse;
    }
    if (response.status !== 409) {
      return null;
    }
    // 409 = the same refresh is still in flight: wait for its replayable result.
    await new Promise((resolve) => setTimeout(resolve, 150 * (attempt + 1)));
  }
  return null;
}
