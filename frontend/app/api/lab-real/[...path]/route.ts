import { NextRequest, NextResponse } from "next/server";
import { cookies } from "next/headers";
import { labBackendUrl } from "@/lib/config";
import { LAB_PERSONA_COOKIE } from "@/lib/lab/cookies";
import { allowed, realGet, realPost } from "@/lib/lab/proxyPaths";

// Calls the sandbox's REAL endpoints (/accounts, /settlements, /reconciliation, /invoices) with the chosen
// persona's token, so the 200s, 403s and row-level filtering the Access screen shows are genuine. Only the persona
// cookie is read here; the real login's access_token cookie is never looked at and never forwarded.

async function forward(request: NextRequest, ctx: RouteContext<"/api/lab-real/[...path]">, method: "GET" | "POST") {
  const base = labBackendUrl();
  if (!base) {
    return NextResponse.json({ message: "The Lab is not connected (LAB_BACKEND_URL is not set)." }, { status: 503 });
  }
  const { path } = await ctx.params;
  const joined = path.join("/");
  if (!allowed(method === "GET" ? realGet : realPost, joined)) {
    return NextResponse.json({ message: "Not an endpoint the Lab calls." }, { status: 404 });
  }
  const token = (await cookies()).get(LAB_PERSONA_COOKIE)?.value;
  if (!token) {
    return NextResponse.json({ message: "Choose a persona first." }, { status: 401 });
  }

  const headers = new Headers({ Authorization: `Bearer ${token}` });
  for (const h of ["content-type", "idempotency-key"]) {
    const v = request.headers.get(h);
    if (v) headers.set(h, v);
  }
  const requestId = request.headers.get("x-request-id");
  if (requestId && /^[A-Za-z0-9._-]{1,64}$/.test(requestId)) headers.set("X-Request-Id", requestId);
  try {
    const upstream = await fetch(`${base}/${joined}${request.nextUrl.search}`, {
      method,
      headers,
      body: method === "POST" ? await request.text() : undefined,
      cache: "no-store",
      signal: AbortSignal.timeout(30_000),
    });
    return new Response(await upstream.text(), {
      status: upstream.status,
      headers: {
        "Content-Type": upstream.headers.get("content-type") ?? "application/json",
        "X-Request-Id": upstream.headers.get("x-request-id") ?? "",
      },
    });
  } catch {
    return NextResponse.json({ message: "The sandbox did not answer." }, { status: 503 });
  }
}

export async function GET(request: NextRequest, ctx: RouteContext<"/api/lab-real/[...path]">) {
  return forward(request, ctx, "GET");
}

export async function POST(request: NextRequest, ctx: RouteContext<"/api/lab-real/[...path]">) {
  return forward(request, ctx, "POST");
}
