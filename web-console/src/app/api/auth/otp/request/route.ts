import { type NextRequest, NextResponse } from "next/server";
import { LOCALE_COOKIE, toLocale } from "@/i18n/locales";
import { CORE_API_URL } from "@/lib/server/session";

export async function POST(request: NextRequest) {
  const upstream = await fetch(`${CORE_API_URL}/api/v1/auth/otp/request`, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "accept-language": toLocale(request.cookies.get(LOCALE_COOKIE)?.value),
    },
    body: await request.text(),
    cache: "no-store",
  });
  return new NextResponse(upstream.body, {
    status: upstream.status,
    headers: { "content-type": upstream.headers.get("content-type") ?? "application/json" },
  });
}
