import { type NextRequest, NextResponse } from "next/server";
import { LOCALE_COOKIE, toLocale } from "@/i18n/locales";
import { CORE_API_URL, setSessionCookies, type TokenResponse } from "@/lib/server/session";
import { STAFF_ROLES } from "@/lib/auth/roles";

/** Verifies the OTP, checks the account is staff, then stores the tokens in httpOnly cookies. */
export async function POST(request: NextRequest) {
  const locale = toLocale(request.cookies.get(LOCALE_COOKIE)?.value);
  const upstream = await fetch(`${CORE_API_URL}/api/v1/auth/otp/verify`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "accept-language": locale,
      "idempotency-key": request.headers.get("idempotency-key") ?? crypto.randomUUID(),
    },
    body: await request.text(),
    cache: "no-store",
  });
  if (!upstream.ok) {
    return new NextResponse(upstream.body, {
      status: upstream.status,
      headers: { "content-type": upstream.headers.get("content-type") ?? "application/problem+json" },
    });
  }
  const tokens = (await upstream.json()) as TokenResponse;
  const me = await fetch(`${CORE_API_URL}/api/v1/me`, {
    headers: { authorization: `Bearer ${tokens.accessToken}` },
    cache: "no-store",
  }).then((r) => r.json() as Promise<{ roles?: { role: string }[] }>);
  const isStaff = (me.roles ?? []).some((r) => (STAFF_ROLES as readonly string[]).includes(r.role));
  if (!isStaff) {
    await fetch(`${CORE_API_URL}/api/v1/auth/logout`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ refreshToken: tokens.refreshToken }),
    });
    return NextResponse.json({ code: "console.not-staff", status: 403 }, { status: 403 });
  }
  const response = NextResponse.json({ ok: true });
  setSessionCookies(response, tokens);
  return response;
}
