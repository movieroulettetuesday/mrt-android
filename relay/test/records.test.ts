import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { optionAllowed, ratingRecord, voteRecordName, roundedStars, safeComponent, sha256Hex, suggestionRecord, voteRecord } from "../src/records";

// Same pinned contract the Android app tests against (repo shared/ = mrt-shared, hash-checked by Gradle).
const ck = JSON.parse(readFileSync(new URL("../../shared/test-vectors/cloudkit.json", import.meta.url), "utf8"));

describe("cloudkit.json vectors", () => {
  it("sha256 and safeComponent", async () => {
    for (const c of ck.sha256) expect(await sha256Hex(c.input)).toBe(c.sha256hex);
    for (const c of ck.safeComponent) expect(await safeComponent(c.input)).toBe(c.expected);
  });

  it("vote and rating record names match iOS byte for byte", async () => {
    for (const c of ck.voteRecordName) {
      const voterKey = await sha256Hex(c.userRecordName + c.pollId);
      expect(await voteRecordName(c.pollId, voterKey)).toBe(c.expected);
      // The full builder agrees for every poll id the apps can produce (always safe characters).
      if (/^[A-Za-z0-9._-]+$/.test(c.pollId)) {
        const option = c.pollId.endsWith(".good-bad") ? "good" : "tie";
        expect((await voteRecord({ pollId: c.pollId, option, voterKey })).recordName).toBe(c.expected);
      }
    }
    for (const c of ck.ratingRecordName) {
      const voterKey = await sha256Hex(c.userRecordName + "rating:" + c.movieId);
      expect((await ratingRecord({ movieId: c.movieId, halfStars: 6, voterKey })).recordName).toBe(c.expected);
    }
  });

  it("legacy stars field", () => {
    for (const c of ck.ratingFields) expect(roundedStars(c.halfStars)).toBe(c.starsFieldWritten);
  });

  it("every PollFactory option is accepted, others are not", () => {
    const polls = [...ck.defaultPolls.cases, ...ck.pollOverrides.cases];
    for (const p of polls) {
      for (const o of p.options) expect(optionAllowed(p.pollId, o.id), `${p.pollId} ${o.id}`).toBe(true);
      expect(optionAllowed(p.pollId, "nope")).toBe(false);
    }
    for (const p of ck.defaultPolls.argumentHostEdgeCases) {
      for (const o of p.options) expect(optionAllowed("x.argument", o.id)).toBe(true);
    }
    expect(optionAllowed("x.good-bad", "yes")).toBe(false);
    expect(optionAllowed("x.argument", "host-0")).toBe(false);
  });

  it("suggestion record shape", () => {
    const r = suggestionRecord({ title: " Tremors ", category: " Horror ", note: " Worms ", creditName: " Laura ", appVersion: "1.0.0 (1)" }, "0a1b2c3d-0000-4000-8000-00000000abcd");
    expect(r.recordType).toBe(ck.suggestionRecord.recordType);
    expect(Object.keys(r.fields)).toEqual(ck.suggestionRecord.fields);
    expect(r.recordName).toBe("suggestion-0A1B2C3D-0000-4000-8000-00000000ABCD");
    expect(r.fields.title!.value).toBe("Tremors");
    expect(r.fields.category!.value).toBe("Horror");
  });
});

describe("validation", () => {
  const key = "a".repeat(64);
  it("rejects bad votes", async () => {
    await expect(voteRecord({ pollId: "p.good-bad", option: "good", voterKey: "short" })).rejects.toMatchObject({ code: "bad_request" });
    await expect(voteRecord({ pollId: "p q.good-bad", option: "good", voterKey: key })).rejects.toMatchObject({ code: "bad_request" });
    await expect(voteRecord({ pollId: "p.good-bad", option: "maybe", voterKey: key })).rejects.toMatchObject({ code: "invalid_option" });
    await expect(voteRecord({ pollId: "p.good-bad", option: 1, voterKey: key })).rejects.toMatchObject({ code: "bad_request" });
    await expect(voteRecord({ pollId: "p.good-bad", option: "good", voterKey: key.toUpperCase() })).rejects.toMatchObject({ code: "bad_request" });
  });
  it("rejects bad ratings", async () => {
    for (const h of [0, 11, 2.5, "6", null]) {
      await expect(ratingRecord({ movieId: "m", halfStars: h, voterKey: key })).rejects.toMatchObject({ code: "invalid_stars" });
    }
    await expect(ratingRecord({ movieId: "", halfStars: 6, voterKey: key })).rejects.toMatchObject({ code: "bad_request" });
    const r = await ratingRecord({ movieId: "the-room-2003", halfStars: 7, voterKey: key });
    expect(r.fields).toEqual({ movieId: { value: "the-room-2003", type: "STRING" }, halfStars: { value: 7, type: "INT64" }, stars: { value: 4, type: "INT64" } });
  });
  it("suggestion limits match InAppSuggestion (120 / 280 / 60)", () => {
    expect(() => suggestionRecord({ title: "   " })).toThrow();
    expect(() => suggestionRecord({ title: "x".repeat(121) })).toThrow();
    expect(() => suggestionRecord({ title: "ok", note: "n".repeat(281) })).toThrow();
    expect(() => suggestionRecord({ title: "ok", creditName: "c".repeat(61) })).toThrow();
    expect(suggestionRecord({ title: "x".repeat(120), note: "n".repeat(280), creditName: "c".repeat(60) }).recordType).toBe("Suggestion");
  });
});
