// Validation and record building; mirrors iOS RouletteCore/Community.swift + CloudKitCommunityStore.swift
// (and the Android port in app/.../community/). Record names must match byte-for-byte.

const SAFE = /^[A-Za-z0-9._-]+$/;
const HEX64 = /^[0-9a-f]{64}$/;

export const LIMITS = { title: 120, note: 280, creditName: 60, category: 120, appVersion: 40, id: 200 } as const;

export async function sha256Hex(text: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** iOS CommunityRecordNames.safeComponent: [A-Za-z0-9._-]{1,120} passes, else "h" + 32 hex of sha256. */
export async function safeComponent(id: string): Promise<string> {
  // Swift counts Characters; for the allowed ASCII set that equals the UTF-16 length.
  if (id.length > 0 && id.length <= 120 && SAFE.test(id)) return id;
  return "h" + (await sha256Hex(id)).slice(0, 32);
}

export class ValidationError extends Error {
  constructor(public code: string, message: string) {
    super(message);
  }
}

export interface CloudKitRecord {
  recordName: string;
  recordType: "PollVote" | "ListenerRating" | "Suggestion";
  fields: Record<string, { value: string | number; type?: "STRING" | "INT64" }>;
}

function str(body: Record<string, unknown>, key: string): string {
  const v = body[key];
  if (typeof v !== "string") throw new ValidationError("bad_request", `${key} must be a string`);
  return v;
}

function isPlainId(v: string): boolean {
  // Catalog/movie ids and poll ids: no control characters, sane length.
  // eslint-disable-next-line no-control-regex
  return v.length > 0 && v.length <= LIMITS.id && !/[\u0000-\u001f\u007f]/.test(v);
}

/**
 * Options allowed for a poll id, from PollFactory: `<guid>.good-bad` → good|bad, `.recommend` → yes|no,
 * `.argument` → host-N|tie; anything else is a polls.json override whose options are opt-N.
 */
export function optionAllowed(pollId: string, option: string): boolean {
  if (pollId.endsWith(".good-bad")) return option === "good" || option === "bad";
  if (pollId.endsWith(".recommend")) return option === "yes" || option === "no";
  if (pollId.endsWith(".argument")) return option === "tie" || /^host-[1-9][0-9]?$/.test(option);
  return /^opt-[1-9][0-9]?$/.test(option);
}

export async function voteRecordName(pollId: string, voterKey: string): Promise<string> {
  return `vote-${await safeComponent(pollId)}-${voterKey}`;
}

export async function ratingRecordName(movieId: string, voterKey: string): Promise<string> {
  return `rating-${await safeComponent(movieId)}-${voterKey}`;
}

export async function voteRecord(body: Record<string, unknown>): Promise<CloudKitRecord> {
  const pollId = str(body, "pollId");
  const option = str(body, "option");
  const voterKey = str(body, "voterKey");
  // Poll ids are built from safeComponent(guid) + a suffix, so they only use the safe set.
  if (!isPlainId(pollId) || !SAFE.test(pollId)) throw new ValidationError("bad_request", "invalid pollId");
  if (!HEX64.test(voterKey)) throw new ValidationError("bad_request", "invalid voterKey");
  if (!optionAllowed(pollId, option)) throw new ValidationError("invalid_option", "invalid option");
  return {
    recordName: await voteRecordName(pollId, voterKey),
    recordType: "PollVote",
    fields: { pollId: { value: pollId, type: "STRING" }, option: { value: option, type: "STRING" } },
  };
}

/** HalfStars.roundedStars: whole stars written to the legacy `stars` field (iOS 1.0 readers). */
export function roundedStars(halfStars: number): number {
  return Math.min(5, Math.max(1, Math.floor((halfStars + 1) / 2)));
}

export async function ratingRecord(body: Record<string, unknown>): Promise<CloudKitRecord> {
  const movieId = str(body, "movieId");
  const voterKey = str(body, "voterKey");
  const half = body["halfStars"];
  if (!isPlainId(movieId)) throw new ValidationError("bad_request", "invalid movieId");
  if (!HEX64.test(voterKey)) throw new ValidationError("bad_request", "invalid voterKey");
  if (typeof half !== "number" || !Number.isInteger(half) || half < 1 || half > 10) {
    throw new ValidationError("invalid_stars", "halfStars must be an integer 1...10");
  }
  return {
    recordName: await ratingRecordName(movieId, voterKey),
    recordType: "ListenerRating",
    fields: {
      movieId: { value: movieId, type: "STRING" },
      halfStars: { value: half, type: "INT64" },
      stars: { value: roundedStars(half), type: "INT64" },
    },
  };
}

/** InAppSuggestion limits (title 1...120 after trimming, note ≤ 280, credit ≤ 60). */
export function suggestionRecord(body: Record<string, unknown>, uuid: string = crypto.randomUUID()): CloudKitRecord {
  const title = str(body, "title").trim();
  const note = (typeof body["note"] === "string" ? (body["note"] as string) : "").trim();
  const creditName = (typeof body["creditName"] === "string" ? (body["creditName"] as string) : "").trim();
  const category = (typeof body["category"] === "string" ? (body["category"] as string) : "").trim();
  const appVersion = (typeof body["appVersion"] === "string" ? (body["appVersion"] as string) : "").trim();
  // Swift String.count is grapheme clusters; code points are a close, never-stricter-by-much proxy.
  const len = (s: string) => [...s].length;
  if (len(title) === 0 || len(title) > LIMITS.title) throw new ValidationError("bad_request", "title must be 1...120 characters");
  if (len(note) > LIMITS.note) throw new ValidationError("bad_request", "note must be at most 280 characters");
  if (len(creditName) > LIMITS.creditName) throw new ValidationError("bad_request", "creditName must be at most 60 characters");
  if (len(category) > LIMITS.category || len(appVersion) > LIMITS.appVersion) throw new ValidationError("bad_request", "field too long");
  return {
    recordName: `suggestion-${uuid.toUpperCase()}`,
    recordType: "Suggestion",
    fields: {
      title: { value: title, type: "STRING" },
      category: { value: category, type: "STRING" },
      note: { value: note, type: "STRING" },
      creditName: { value: creditName, type: "STRING" },
      appVersion: { value: appVersion, type: "STRING" },
    },
  };
}
