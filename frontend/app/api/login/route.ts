import { NextRequest, NextResponse } from "next/server";
import { ACCESS_TOKEN_COOKIE, REFRESH_TOKEN_COOKIE, decodeAccessToken } from "@/lib/auth";
import { BACKEND_URL } from "@/lib/api";

// The one proxy route: calls the backend's /auth/login and sets the resulting tokens as
// httpOnly cookies, so the access token is never readable by client-side JS (immune to
// XSS-based token theft). Server Components read the cookie directly for authenticated fetches.
export async function POST(request: NextRequest) {
  const body = await request.json();

  const backendResponse = await fetch(`${BACKEND_URL}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });

  if (!backendResponse.ok) {
    const errorBody = await backendResponse.text();
    return new NextResponse(errorBody, {
      status: backendResponse.status,
      headers: { "Content-Type": "application/json" },
    });
  }

  const { accessToken, refreshToken } = (await backendResponse.json()) as {
    accessToken: string;
    refreshToken: string;
  };
  const claims = decodeAccessToken(accessToken);

  const secure = process.env.NODE_ENV === "production";
  const response = NextResponse.json({ role: claims.role });
  response.cookies.set(ACCESS_TOKEN_COOKIE, accessToken, {
    httpOnly: true,
    secure,
    sameSite: "lax",
    path: "/",
    expires: new Date(claims.expiresAt * 1000),
  });
  response.cookies.set(REFRESH_TOKEN_COOKIE, refreshToken, {
    httpOnly: true,
    secure,
    sameSite: "lax",
    path: "/",
    maxAge: 60 * 60 * 24 * 30,
  });
  return response;
}
