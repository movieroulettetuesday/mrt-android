// MRT community write relay (Option A). Android reads CloudKit directly with a read-only API token;
// every write comes here, is validated like the iOS app does, and is saved with the CloudKit
// server-to-server key held as Worker secrets (CK_KEY_ID, CK_PRIVATE_KEY).
//
//   POST /v1/vote        {pollId, option, voterKey}
//   POST /v1/rating      {movieId, halfStars, voterKey}
//   POST /v1/suggestion  {title, category, note, creditName, appVersion}
//
// voterKey = sha256(installId + pollId) or sha256(installId + "rating:" + movieId), computed on the
// phone; the install id itself never reaches this service. Responses pass CloudKit's records/modify
// JSON through ({"records":[…]}); errors are {"error": code, "message": …, "retryAfter"?}.

import { modify, type CloudKitEnv } from "./cloudkit";
import { MemoryLimiter, type RateLimitBinding } from "./ratelimit";
import { ratingRecord, suggestionRecord, ValidationError, voteRecord, type CloudKitRecord } from "./records";

export interface Env extends CloudKitEnv {
  WRITE_LIMITER?: RateLimitBinding;
  SUGGEST_LIMITER?: RateLimitBinding;
}

const MAX_BODY = 4096;
const fallbackWrite = new MemoryLimiter(20, 60_000);
const fallbackSuggest = new MemoryLimiter(2, 60_000);

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json", ...headers } });
}

function error(status: number, code: string, message: string, retryAfter?: number): Response {
  const body: Record<string, unknown> = { error: code, message };
  if (retryAfter !== undefined) body["retryAfter"] = retryAfter;
  return json(status, body, retryAfter !== undefined ? { "Retry-After": String(retryAfter) } : {});
}

async function readBody(request: Request): Promise<Record<string, unknown>> {
  const type = request.headers.get("Content-Type") ?? "";
  if (!type.toLowerCase().startsWith("application/json")) throw new ValidationError("bad_request", "Content-Type must be application/json");
  const text = await request.text();
  if (text.length > MAX_BODY) throw new ValidationError("bad_request", "body too large");
  let parsed: unknown;
  try { parsed = JSON.parse(text); } catch { throw new ValidationError("bad_request", "invalid JSON"); }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) throw new ValidationError("bad_request", "body must be an object");
  return parsed as Record<string, unknown>;
}

/** Maps a CloudKit records/modify answer to the relay's reply. */
async function forward(ck: Response): Promise<Response> {
  const text = await ck.text();
  let body: Record<string, unknown> = {};
  try { body = JSON.parse(text) as Record<string, unknown>; } catch { /* non-JSON error page */ }
  if (ck.ok) {
    const first = (body["records"] as Array<Record<string, unknown>> | undefined)?.[0];
    const code = first?.["serverErrorCode"];
    if (typeof code === "string") {
      const retry = typeof first?.["retryAfter"] === "number" ? (first["retryAfter"] as number) : undefined;
      if (code === "THROTTLED" || code === "ZONE_BUSY" || code === "TRY_AGAIN_LATER" || code === "SERVICE_UNAVAILABLE") return error(503, "busy", code, retry);
      if (code === "QUOTA_EXCEEDED") return error(503, "quota", code);
      return error(502, "cloudkit", code);
    }
    return json(200, body);
  }
  const code = typeof body["serverErrorCode"] === "string" ? (body["serverErrorCode"] as string) : `HTTP_${ck.status}`;
  const retry = typeof body["retryAfter"] === "number" ? (body["retryAfter"] as number) : undefined;
  if (ck.status === 429 || ck.status === 503 || code === "THROTTLED" || code === "ZONE_BUSY" || code === "TRY_AGAIN_LATER") return error(503, "busy", code, retry);
  if (code === "QUOTA_EXCEEDED") return error(503, "quota", code);
  if (ck.status === 401 || ck.status === 403 || code.startsWith("AUTHENTICATION")) return error(503, "not_configured", "server key rejected");
  return error(502, "cloudkit", code);
}

export async function handle(request: Request, env: Env, now: () => Date = () => new Date(), fetchImpl: typeof fetch = fetch): Promise<Response> {
  const url = new URL(request.url);
  const routes: Record<string, (b: Record<string, unknown>) => Promise<CloudKitRecord> | CloudKitRecord> = {
    "/v1/vote": voteRecord,
    "/v1/rating": ratingRecord,
    "/v1/suggestion": (b) => suggestionRecord(b),
  };
  const build = routes[url.pathname];
  if (!build) return error(404, "not_found", "unknown endpoint");
  if (request.method !== "POST") return error(405, "method_not_allowed", "POST only");
  if (!env.CK_KEY_ID || !env.CK_PRIVATE_KEY) return error(503, "not_configured", "relay secrets are not set");

  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const suggest = url.pathname === "/v1/suggestion";
  const limiter = suggest ? (env.SUGGEST_LIMITER ?? fallbackSuggest) : (env.WRITE_LIMITER ?? fallbackWrite);
  if (!(await limiter.limit({ key: `${url.pathname}:${ip}` })).success) return error(429, "rate_limited", "too many requests", 60);

  let record: CloudKitRecord;
  try {
    record = await build(await readBody(request));
  } catch (e) {
    if (e instanceof ValidationError) return error(400, e.code, e.message);
    throw e;
  }
  try {
    return await forward(await modify(env, record, now(), fetchImpl));
  } catch {
    return error(503, "busy", "CloudKit unreachable");
  }
}

export default {
  fetch(request: Request, env: Env): Promise<Response> {
    return handle(request, env);
  },
};
