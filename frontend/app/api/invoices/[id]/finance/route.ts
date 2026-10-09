import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { ACCESS_TOKEN_COOKIE } from "@/lib/auth";
import { BACKEND_URL } from "@/lib/api";

// Client Components can't read the httpOnly access token cookie, so the finance button posts
// here instead of straight to the backend; this route attaches the token server-side.
export async function POST(request: NextRequest, { params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const token = (await cookies()).get(ACCESS_TOKEN_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ message: "Not authenticated" }, { status: 401 });
  }

  const { idempotencyKey } = await request.json();

  const response = await fetch(`${BACKEND_URL}/invoices/${id}/finance`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Idempotency-Key": idempotencyKey,
      "Content-Type": "application/json",
    },
  });

  const body = await response.text();
  return new NextResponse(body, {
    status: response.status,
    headers: { "Content-Type": response.headers.get("Content-Type") ?? "application/json" },
  });
}
