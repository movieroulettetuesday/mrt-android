// Rate limiting (documented choice, see ../README.md):
// 1. Cloudflare's Workers Rate Limiting API (bindings in wrangler.toml), keyed by client IP + route.
//    Free, no storage to manage; counters are per Cloudflare location and eventually consistent,
//    which is fine for "stop a script from flooding the poll".
// 2. When a binding is missing (tests, `wrangler dev` without it) an in-memory fixed window per
//    isolate is used instead, so behaviour is the same shape everywhere.
// Votes and ratings can't be duplicated anyway: one record per (poll|movie, voterKey) is overwritten.

export interface RateLimitBinding {
  limit(options: { key: string }): Promise<{ success: boolean }>;
}

export class MemoryLimiter implements RateLimitBinding {
  private hits = new Map<string, { start: number; count: number }>();
  constructor(private max: number, private periodMs: number, private now: () => number = Date.now) {}

  async limit({ key }: { key: string }): Promise<{ success: boolean }> {
    const t = this.now();
    const cur = this.hits.get(key);
    if (!cur || t - cur.start >= this.periodMs) {
      this.hits.set(key, { start: t, count: 1 });
      if (this.hits.size > 10_000) this.hits.clear(); // bounded memory
      return { success: true };
    }
    cur.count++;
    return { success: cur.count <= this.max };
  }
}
