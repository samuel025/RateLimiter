import { NextResponse } from "next/server";

const DEFAULT_BACKEND = "http://localhost:8091";
const MAX_PAGE_SIZE = 50;

export async function GET(request: Request) {
  const backendBase = process.env.BACKEND_BASE_URL ?? DEFAULT_BACKEND;
  const incomingUrl = new URL(request.url);
  const upstreamParams = new URLSearchParams();

  const apiKeyId = incomingUrl.searchParams.get("apiKeyId");
  const method = incomingUrl.searchParams.get("method");
  const requestPathContains = incomingUrl.searchParams.get(
    "requestPathContains",
  );
  const clientIp = incomingUrl.searchParams.get("clientIp");
  const requestedFrom = incomingUrl.searchParams.get("requestedFrom");
  const requestedTo = incomingUrl.searchParams.get("requestedTo");
  const statusCode = incomingUrl.searchParams.get("statusCode");
  const minStatusCode = incomingUrl.searchParams.get("minStatusCode");
  const maxStatusCode = incomingUrl.searchParams.get("maxStatusCode");

  const page = parseInteger(incomingUrl.searchParams.get("page"), 0);
  const size = clamp(
    parseInteger(incomingUrl.searchParams.get("size"), 12),
    1,
    MAX_PAGE_SIZE,
  );

  setIfPresent(upstreamParams, "apiKeyId", apiKeyId);
  setIfPresent(upstreamParams, "method", method);
  setIfPresent(upstreamParams, "requestPathContains", requestPathContains);
  setIfPresent(upstreamParams, "clientIp", clientIp);
  setIfPresent(upstreamParams, "requestedFrom", requestedFrom);
  setIfPresent(upstreamParams, "requestedTo", requestedTo);
  setIfPresent(upstreamParams, "statusCode", statusCode);
  setIfPresent(upstreamParams, "minStatusCode", minStatusCode);
  setIfPresent(upstreamParams, "maxStatusCode", maxStatusCode);
  upstreamParams.set("page", String(page));
  upstreamParams.set("size", String(size));
  upstreamParams.set("sort", "requestedAt,desc");

  const upstream = await fetch(
    `${backendBase}/admin/usage-logs?${upstreamParams.toString()}`,
    {
      method: "GET",
      cache: "no-store",
    },
  );

  const text = await upstream.text();
  const data = text ? safeParseJson(text) : null;

  return NextResponse.json(data ?? { raw: text }, { status: upstream.status });
}

function setIfPresent(
  params: URLSearchParams,
  key: string,
  value: string | null,
) {
  if (value && value.trim()) {
    params.set(key, value.trim());
  }
}

function parseInteger(value: string | null, defaultValue: number) {
  if (!value) {
    return defaultValue;
  }

  const parsed = Number.parseInt(value, 10);
  if (!Number.isFinite(parsed)) {
    return defaultValue;
  }

  return parsed;
}

function clamp(value: number, min: number, max: number) {
  return Math.min(max, Math.max(min, value));
}

function safeParseJson(value: string) {
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}
