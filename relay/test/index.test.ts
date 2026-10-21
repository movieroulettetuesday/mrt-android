import { describe, expect, it } from "vitest";
import { handle, type Env } from "../src/index";
import { MemoryLimiter } from "../src/ratelimit";
import { sha256Hex } from "../src/records";
import { throwawayKey } from "./keys";

const key = throwawayKey();

function env(overrides: Partial<Env> = {}): Env {
  return {
    CK_KEY_ID: "kid",
    CK_PRIVATE_KEY: key.pkcs8Pem,
    CK_CONTAINER: "iCloud.com.movieroulettetuesday.app",
    CK_ENVIRONMENT: "production",
    WRITE_LIMITER: new MemoryLimiter(100, 60_000),
    SUGGEST_LIMITER: new MemoryLimiter(2, 60_000),
    ...overrides,
  };
}

function post(path: string, body: unknown, ip = "203.0.113.7", type = "application/json"): Request {
  return new Request("https://relay.example" + path, {
    method: "POST",
    headers: { "Content-Type": type, "CF-Connecting-IP": ip },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

/** Fake CloudKit: echoes the saved record in the records/modify response shape. */
function cloudKit(reply?: (sent: any) => Response) {
  const calls: any[] = [];
  const f = (async (_url: string, init: RequestInit) => {
    const sent = JSON.parse(init.body as string);
    calls.push(sent);
    return reply ? reply(sent) : new Response(JSON.stringify({ records: [sent.operations[0].record] }), { status: 200 });
  }) as unknown as typeof fetch;
  return { calls, f };
}

describe("relay endpoints", () => {
  it("vote → forceReplace PollVote with the deterministic name", async () => {
    const ck = cloudKit();
    const voterKey = await sha256Hex("6f1c2a3b-0000-4000-8000-1234567890ab" + "Buzzsprout-1.good-bad");
    const res = await handle(post("/v1/vote", { pollId: "Buzzsprout-1.good-bad", option: "bad", voterKey }), env(), undefined, ck.f);
    expect(res.status).toBe(200);
    const out = (await res.json()) as any;
    expect(out.records[0].recordName).toBe(`vote-Buzzsprout-1.good-bad-${voterKey}`);
    expect(ck.calls[0].operations[0].operationType).toBe("forceReplace");
    expect(ck.calls[0].operations[0].record.fields).toEqual({ pollId: { value: "Buzzsprout-1.good-bad", type: "STRING" }, option: { value: "bad", type: "STRING" } });
  });

  it("rating and suggestion", async () => {
    const ck = cloudKit();
    const r = await handle(post("/v1/rating", { movieId: "laura-1944", halfStars: 9, voterKey: "b".repeat(64) }), env(), undefined, ck.f);
    expect(r.status).toBe(200);
    expect(ck.calls[0].operations[0].record.fields.stars).toEqual({ value: 5, type: "INT64" });
    const s = await handle(post("/v1/suggestion", { title: "Tremors", category: "", note: "", creditName: "", appVersion: "1.0.0 (1)" }), env(), undefined, ck.f);
    expect(s.status).toBe(200);
    expect(ck.calls[1].operations[0].operationType).toBe("create");
    expect(ck.calls[1].operations[0].record.recordName).toMatch(/^suggestion-[0-9A-F-]{36}$/);
  });

  it("validation errors are 400 with a code the app maps", async () => {
    const ck = cloudKit();
    const bad = await handle(post("/v1/vote", { pollId: "p.recommend", option: "good", voterKey: "c".repeat(64) }), env(), undefined, ck.f);
    expect(bad.status).toBe(400);
    expect(await bad.json()).toMatchObject({ error: "invalid_option" });
    expect((await handle(post("/v1/rating", { movieId: "m", halfStars: 12, voterKey: "c".repeat(64) }), env(), undefined, ck.f)).status).toBe(400);
    expect((await handle(post("/v1/vote", "not json"), env(), undefined, ck.f)).status).toBe(400);
    expect((await handle(post("/v1/vote", "[]"), env(), undefined, ck.f)).status).toBe(400);
    expect((await handle(post("/v1/vote", { pollId: "p.good-bad" }, undefined, "text/plain"), env(), undefined, ck.f)).status).toBe(400);
    expect((await handle(post("/v1/vote", { pad: "x".repeat(5000) }), env(), undefined, ck.f)).status).toBe(400);
    expect(ck.calls.length).toBe(0); // nothing reached CloudKit
  });

  it("routing, method and missing secrets", async () => {
    const ck = cloudKit();
    expect((await handle(post("/v1/nope", {}), env(), undefined, ck.f)).status).toBe(404);
    expect((await handle(new Request("https://relay.example/v1/vote"), env(), undefined, ck.f)).status).toBe(405);
    const res = await handle(post("/v1/vote", {}), env({ CK_PRIVATE_KEY: "" }), undefined, ck.f);
    expect(res.status).toBe(503);
    expect(await res.json()).toMatchObject({ error: "not_configured" });
  });

  it("rate limits per IP and route", async () => {
    const ck = cloudKit();
    const e = env({ WRITE_LIMITER: new MemoryLimiter(2, 60_000) });
    const body = { pollId: "p.good-bad", option: "good", voterKey: "d".repeat(64) };
    expect((await handle(post("/v1/vote", body), e, undefined, ck.f)).status).toBe(200);
    expect((await handle(post("/v1/vote", body), e, undefined, ck.f)).status).toBe(200);
    const limited = await handle(post("/v1/vote", body), e, undefined, ck.f);
    expect(limited.status).toBe(429);
    expect(limited.headers.get("Retry-After")).toBe("60");
    expect(await limited.json()).toMatchObject({ error: "rate_limited", retryAfter: 60 });
    expect((await handle(post("/v1/vote", body, "198.51.100.1"), e, undefined, ck.f)).status).toBe(200); // other IP
    const s = { title: "Tremors" };
    expect((await handle(post("/v1/suggestion", s), e, undefined, ck.f)).status).toBe(200);
    expect((await handle(post("/v1/suggestion", s), e, undefined, ck.f)).status).toBe(200);
    expect((await handle(post("/v1/suggestion", s), e, undefined, ck.f)).status).toBe(429);
  });

  it("CloudKit errors map to busy / quota / not_configured", async () => {
    const body = { pollId: "p.good-bad", option: "good", voterKey: "e".repeat(64) };
    const cases: Array<[Response, number, string, number | undefined]> = [
      [new Response('{"serverErrorCode":"THROTTLED","retryAfter":15}', { status: 503 }), 503, "busy", 15],
      [new Response('{"records":[{"recordName":"x","serverErrorCode":"ZONE_BUSY","retryAfter":4}]}', { status: 200 }), 503, "busy", 4],
      [new Response('{"serverErrorCode":"QUOTA_EXCEEDED"}', { status: 400 }), 503, "quota", undefined],
      [new Response('{"serverErrorCode":"AUTHENTICATION_FAILED"}', { status: 401 }), 503, "not_configured", undefined],
      [new Response("<html>", { status: 500 }), 502, "cloudkit", undefined],
    ];
    for (const [reply, status, code, retry] of cases) {
      const ck = cloudKit(() => reply);
      const res = await handle(post("/v1/vote", body, crypto.randomUUID()), env(), undefined, ck.f);
      expect(res.status).toBe(status);
      const json = (await res.json()) as any;
      expect(json.error).toBe(code);
      expect(json.retryAfter).toBe(retry);
    }
    const down = (async () => { throw new TypeError("network"); }) as unknown as typeof fetch;
    expect((await handle(post("/v1/vote", body, "192.0.2.9"), env(), undefined, down)).status).toBe(503);
  });
});
