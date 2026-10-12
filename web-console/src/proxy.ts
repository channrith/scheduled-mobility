import { type NextRequest, NextResponse } from "next/server";

/** Pages need a session; the login page is only for signed-out users. API routes handle auth themselves. */
export function proxy(request: NextRequest) {
  const signedIn = request.cookies.has("wc_session");
  const isLogin = request.nextUrl.pathname === "/login";
  if (!signedIn && !isLogin) {
    const url = new URL("/login", request.url);
    if (request.nextUrl.pathname !== "/") url.searchParams.set("next", request.nextUrl.pathname);
    return NextResponse.redirect(url);
  }
  if (signedIn && isLogin) {
    return NextResponse.redirect(new URL("/drivers", request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico|.*\\.(?:png|svg|jpg|ico)$).*)"],
};
