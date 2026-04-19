import { NextResponse } from "next/server";

const DEFAULT_BACKEND = "http://localhost:8091";

export async function POST(request: Request) {
  const payload = await request.json();
  const backendBase = process.env.BACKEND_BASE_URL ?? DEFAULT_BACKEND;

  const path = normalizePath(payload.path);
  const apiKey = String(payload.apiKey ?? "").trim();
  const method = String(payload.method ?? "GET").toUpperCase();

  const upstream = await fetch(`${backendBase}/gateway${path}`, {
    method,
    headers: {
      "X-API-Key": apiKey,
    },
    cache: "no-store",
  });

  const rawBody = await upstream.text();
  const body = rawBody ? (safeParseJson(rawBody) ?? rawBody) : null;

  return NextResponse.json(
    {
      status: upstream.status,
      headers: {
        rateLimitLimit: upstream.headers.get("x-ratelimit-limit"),
        rateLimitRemaining: upstream.headers.get("x-ratelimit-remaining"),
        rateLimitReset: upstream.headers.get("x-ratelimit-reset"),
        retryAfter: upstream.headers.get("retry-after"),
      },
      body,
    },
    { status: 200 },
  );
}

function normalizePath(value: unknown) {
  const input = String(value ?? "/get").trim();
  if (!input || input === "/") {
    return "/";
  }
  return input.startsWith("/") ? input : `/${input}`;
}

function safeParseJson(value: string) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}
