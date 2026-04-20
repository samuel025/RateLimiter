import { NextResponse } from "next/server";

const DEFAULT_BACKEND = "http://localhost:8091";

export async function POST(request: Request) {
  const payload = await request.json();
  const backendBase = process.env.BACKEND_BASE_URL ?? DEFAULT_BACKEND;

  const apiKeyId = String(payload.apiKeyId ?? "").trim();
  const rateLimit = Number(payload.rateLimit ?? 0);

  if (!apiKeyId) {
    return NextResponse.json(
      { error: "apiKeyId is required" },
      { status: 400 },
    );
  }
  if (!Number.isFinite(rateLimit) || rateLimit <= 0) {
    return NextResponse.json(
      { error: "rateLimit must be > 0" },
      { status: 400 },
    );
  }

  const upstream = await fetch(
    `${backendBase}/admin/keys/${encodeURIComponent(apiKeyId)}/rate-limit?value=${encodeURIComponent(String(rateLimit))}`,
    {
      method: "PATCH",
      cache: "no-store",
    },
  );

  const text = await upstream.text();
  const data = text ? safeParseJson(text) : null;
  return NextResponse.json(data ?? { raw: text }, { status: upstream.status });
}

function safeParseJson(value: string) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}
