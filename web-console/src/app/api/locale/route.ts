import { type NextRequest, NextResponse } from "next/server";
import { LOCALE_COOKIE, toLocale } from "@/i18n/locales";

export async function POST(request: NextRequest) {
  const { locale } = (await request.json()) as { locale?: string };
  const response = NextResponse.json({ locale: toLocale(locale) });
  response.cookies.set(LOCALE_COOKIE, toLocale(locale), { path: "/", maxAge: 60 * 60 * 24 * 365, sameSite: "lax" });
  return response;
}
