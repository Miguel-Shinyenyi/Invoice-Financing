import { NextRequest, NextResponse } from "next/server";
import { labBackendUrl } from "@/lib/config";
import { allowed, labGet, labPost } from "@/lib/lab/proxyPaths";

// Browser -> this handler -> the sandbox backend's /lab/**. Fixed whitelist, no cookies forwarded (the Lab's
// public endpoints take none, and the real login cookies must never reach the sandbox), client IP passed on
// as X-Forwarded-For for the backend's per-IP limits.

function clientIp(request: NextRequest): string {
  const forwarded = request.headers.get("x-forwarded-for");
  return forwarded ? forwarded.split(",")[0].trim() : "unknown";
}

async function forward(request: NextRequest, ctx: RouteContext<"/api/lab/[...path]">, method: "GET" | "POST") {
  const base = labBackendUrl();
  if (!base) {
    return NextResponse.json({ message: "The Lab is not connected (LAB_BACKEND_URL is not set)." }, { status: 503 });
  }
  const { path } = await ctx.params;
  const joined = path.join("/");
  if (!allowed(method === "GET" ? labGet : labPost, joined)) {
    return NextResponse.json({ message: "Not a Lab endpoint." }, { status: 404 });
  }

  const headers = new Headers({ "X-Forwarded-For": clientIp(request) });
  const contentType = request.headers.get("content-type");
  if (contentType) headers.set("Content-Type", contentType);
  const requestId = request.headers.get("x-request-id");
  if (requestId && /^[A-Za-z0-9._-]{1,64}$/.test(requestId)) headers.set("X-Request-Id", requestId);

  const upstreamUrl = `${base}/lab/${joined}${request.nextUrl.search}`;
  const isStream = joined.endsWith("/stream");
  try {
    const upstream = await fetch(upstreamUrl, {
      method,
      headers,
      body: method === "POST" ? await request.text() : undefined,
      cache: "no-store",
      signal: isStream ? request.signal : AbortSignal.timeout(60_000),
    });
    const out = new Headers({
      "Content-Type": upstream.headers.get("content-type") ?? "application/json",
      "Cache-Control": "no-cache, no-transform",
    });
    for (const h of ["x-request-id", "retry-after", "content-disposition"]) {
      const v = upstream.headers.get(h);
      if (v) out.set(h, v);
    }
    if (isStream) out.set("X-Accel-Buffering", "no"); // ask nginx-style proxies not to buffer the stream
    return new Response(upstream.body, { status: upstream.status, headers: out });
  } catch {
    return NextResponse.json({ message: "The sandbox did not answer." }, { status: 503 });
  }
}

export async function GET(request: NextRequest, ctx: RouteContext<"/api/lab/[...path]">) {
  return forward(request, ctx, "GET");
}

export async function POST(request: NextRequest, ctx: RouteContext<"/api/lab/[...path]">) {
  return forward(request, ctx, "POST");
}
