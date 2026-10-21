import { createHash, verify } from "node:crypto";
import { describe, expect, it } from "vitest";
import { importPrivateKey, isoDate, modify, p1363ToDer, sign, signatureMessage } from "../src/cloudkit";
import { throwawayKey } from "./keys";

const env = { CK_KEY_ID: "test-key-id", CK_CONTAINER: "iCloud.com.movieroulettetuesday.app", CK_ENVIRONMENT: "production" };

describe("server-to-server signing", () => {
  it("message is date:base64(sha256(body)):subpath", async () => {
    const body = '{"a":1}';
    const hash = createHash("sha256").update(body).digest("base64");
    expect(await signatureMessage("2026-10-21T23:00:00Z", body, "/database/1/c/production/public/records/modify"))
      .toBe(`2026-10-21T23:00:00Z:${hash}:/database/1/c/production/public/records/modify`);
    expect(isoDate(new Date(Date.UTC(2026, 9, 21, 23, 0, 0, 123)))).toBe("2026-10-21T23:00:00Z");
  });

  it("signs with SEC1 and PKCS#8 PEMs; signature is DER and verifies", async () => {
    const k = throwawayKey();
    for (const pem of [k.sec1Pem, k.pkcs8Pem, k.sec1Pem.replace(/\n/g, "\\n")]) {
      const key = await importPrivateKey(pem);
      const message = "2026-10-21T23:00:00Z:abc=:/database/1/x/production/public/records/modify";
      const sig = Buffer.from(await sign(key, message), "base64");
      expect(sig[0]).toBe(0x30); // ASN.1 SEQUENCE
      expect(verify("sha256", Buffer.from(message), { key: k.publicKey, dsaEncoding: "der" }, sig)).toBe(true);
    }
  });

  it("DER conversion handles high-bit and leading-zero integers", () => {
    const raw = new Uint8Array(64);
    raw[0] = 0x80; // r needs a 0x00 pad
    raw[63] = 0x01; // s is 1 after stripping zeros
    const derSig = p1363ToDer(raw);
    expect([...derSig.slice(0, 4)]).toEqual([0x30, 2 + 33 + 3, 0x02, 33]);
    expect([...derSig.slice(-3)]).toEqual([0x02, 0x01, 0x01]);
  });

  it("records/modify request carries the three CloudKit headers", async () => {
    const k = throwawayKey();
    let seen: { url: string; init: RequestInit } | undefined;
    const fake = (async (url: string, init: RequestInit) => {
      seen = { url, init };
      return new Response('{"records":[]}', { status: 200 });
    }) as unknown as typeof fetch;
    const record = { recordName: "vote-p.good-bad-" + "a".repeat(64), recordType: "PollVote" as const, fields: { option: { value: "good", type: "STRING" as const } } };
    await modify({ ...env, CK_PRIVATE_KEY: k.sec1Pem }, record, new Date(Date.UTC(2026, 9, 21, 23)), fake);
    expect(seen!.url).toBe("https://api.apple-cloudkit.com/database/1/iCloud.com.movieroulettetuesday.app/production/public/records/modify");
    const h = seen!.init.headers as Record<string, string>;
    expect(h["X-Apple-CloudKit-Request-KeyID"]).toBe("test-key-id");
    expect(h["X-Apple-CloudKit-Request-ISO8601Date"]).toBe("2026-10-21T23:00:00Z");
    const body = seen!.init.body as string;
    expect(JSON.parse(body)).toEqual({ operations: [{ operationType: "forceReplace", record }], atomic: true });
    const message = await signatureMessage("2026-10-21T23:00:00Z", body, "/database/1/iCloud.com.movieroulettetuesday.app/production/public/records/modify");
    expect(verify("sha256", Buffer.from(message), { key: k.publicKey, dsaEncoding: "der" }, Buffer.from(h["X-Apple-CloudKit-Request-SignatureV1"]!, "base64"))).toBe(true);
  });
});
