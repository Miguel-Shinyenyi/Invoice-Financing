import { NextRequest, NextResponse } from "next/server";
import { ACCESS_TOKEN_COOKIE } from "@/lib/auth";
import { BASE_PATH } from "@/lib/basePath";

// Presence-only check -- this is UI routing convenience, not the authorization boundary.
// Every real data request still carries the token to the backend, which is what actually
// enforces auth/row-level access.
export function proxy(request: NextRequest) {
  const hasToken = request.cookies.has(ACCESS_TOKEN_COOKIE);
  // request.nextUrl.pathname already has basePath stripped, so compare against bare paths --
  // but NextResponse.redirect(new URL(...)) builds a raw URL that does NOT get basePath
  // reapplied automatically (unlike next/link or next/router), so it must be added back here.
  const { pathname } = request.nextUrl;

  if (pathname === "/login") {
    if (hasToken) {
      return NextResponse.redirect(new URL(`${BASE_PATH}/settlements`, request.url));
    }
    return NextResponse.next();
  }

  if (!hasToken) {
    return NextResponse.redirect(new URL(`${BASE_PATH}/login`, request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
