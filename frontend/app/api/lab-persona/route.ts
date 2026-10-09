import { NextRequest, NextResponse } from "next/server";
import { labBackendUrl } from "@/lib/config";
import { LAB_PERSONA_COOKIE, LAB_ROLES } from "@/lib/lab/cookies";

// Chooses the sandbox persona: asks the sandbox backend for a real JWT and keeps it in an httpOnly cookie that is
// a different cookie from the real login's. DELETE forgets the persona.
export async function POST(request: NextRequest) {
  const base = labBackendUrl();
  if (!base) return NextResponse.json({ message: "The Lab is not connected." }, { status: 503 });
  const { role } = (await request.json().catch(() => ({}))) as { role?: string };
  if (!role || !(LAB_ROLES as readonly string[]).includes(role)) {
    return NextResponse.json({ message: "role must be ADMIN, SUPPORT or READ_ONLY" }, { status: 400 });
  }
  const forwarded = request.headers.get("x-forwarded-for");
  const upstream = await fetch(`${base}/lab/personas/${role}/token`, {
    method: "POST",
    headers: { "X-Forwarded-For": forwarded ? forwarded.split(",")[0].trim() : "unknown" },
    cache: "no-store",
  }).catch(() => null);
  if (!upstream || !upstream.ok) {
    return NextResponse.json({ message: "The sandbox did not issue a token." }, { status: upstream?.status ?? 503 });
  }
  const body = (await upstream.json()) as { persona: unknown; token: string; expiresInSeconds: number };
  const response = NextResponse.json({ persona: body.persona, expiresInSeconds: body.expiresInSeconds });
  response.cookies.set(LAB_PERSONA_COOKIE, body.token, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: Math.max(60, body.expiresInSeconds - 30),
  });
  return response;
}

export async function DELETE() {
  const response = NextResponse.json({ ok: true });
  response.cookies.delete(LAB_PERSONA_COOKIE);
  return response;
}
