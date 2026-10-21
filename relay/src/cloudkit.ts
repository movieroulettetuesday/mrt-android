// CloudKit Web Services server-to-server requests. Signature (Apple "Composing Web Service Requests"):
//   message   = "<ISO8601 date>:<base64(sha256(body))>:<subpath>"
//   signature = base64(DER(ECDSA P-256 / SHA-256 over message)) with the key from the CloudKit Console.
// Headers: X-Apple-CloudKit-Request-KeyID, X-Apple-CloudKit-Request-ISO8601Date, X-Apple-CloudKit-Request-SignatureV1.

import type { CloudKitRecord } from "./records";

export interface CloudKitEnv {
  CK_KEY_ID: string;
  CK_PRIVATE_KEY: string;
  CK_CONTAINER: string;
  CK_ENVIRONMENT: string;
  /** Tests only: override https://api.apple-cloudkit.com. */
  CK_API_BASE?: string;
}

function b64(bytes: ArrayBuffer | Uint8Array): string {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = "";
  for (const b of u8) s += String.fromCharCode(b);
  return btoa(s);
}

function unb64(text: string): Uint8Array {
  const bin = atob(text);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function derLength(n: number): number[] {
  if (n < 0x80) return [n];
  const bytes: number[] = [];
  while (n > 0) { bytes.unshift(n & 0xff); n >>= 8; }
  return [0x80 | bytes.length, ...bytes];
}

function der(tag: number, content: Uint8Array | number[]): Uint8Array {
  const c = content instanceof Uint8Array ? [...content] : content;
  return new Uint8Array([tag, ...derLength(c.length), ...c]);
}

const OID_EC_PUBLIC_KEY = [0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01];
const OID_PRIME256V1 = [0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07];

/** SEC1 "EC PRIVATE KEY" (what `openssl ecparam -genkey` writes) → PKCS#8 for WebCrypto. */
export function sec1ToPkcs8(sec1: Uint8Array): Uint8Array {
  const algorithm = der(0x30, [...OID_EC_PUBLIC_KEY, ...OID_PRIME256V1]);
  return der(0x30, [0x02, 0x01, 0x00, ...algorithm, ...der(0x04, sec1)]);
}

/** Accepts PEM (SEC1 or PKCS#8, `\n` escapes allowed) and returns a signing key. */
export async function importPrivateKey(pem: string): Promise<CryptoKey> {
  const text = pem.replace(/\\n/g, "\n").trim();
  const label = /-----BEGIN ([A-Z ]+)-----/.exec(text)?.[1] ?? "";
  const body = unb64(text.replace(/-----(BEGIN|END) [A-Z ]+-----/g, "").replace(/\s+/g, ""));
  const pkcs8 = label.startsWith("EC ") ? sec1ToPkcs8(body) : body;
  return crypto.subtle.importKey("pkcs8", new Uint8Array(pkcs8), { name: "ECDSA", namedCurve: "P-256" }, false, ["sign"]);
}

/** WebCrypto returns r||s (IEEE P1363); CloudKit expects ASN.1 DER. */
export function p1363ToDer(sig: Uint8Array): Uint8Array {
  const int = (bytes: Uint8Array) => {
    let i = 0;
    while (i < bytes.length - 1 && bytes[i] === 0) i++;
    let v = [...bytes.slice(i)];
    if ((v[0] ?? 0) & 0x80) v = [0, ...v];
    return der(0x02, v);
  };
  const half = sig.length / 2;
  return der(0x30, [...int(sig.slice(0, half)), ...int(sig.slice(half))]);
}

/** "2026-10-21T23:00:00Z" (no fractional seconds). */
export function isoDate(d: Date): string {
  return d.toISOString().replace(/\.\d{3}Z$/, "Z");
}

export async function signatureMessage(date: string, body: string, subpath: string): Promise<string> {
  const hash = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(body));
  return `${date}:${b64(hash)}:${subpath}`;
}

export async function sign(key: CryptoKey, message: string): Promise<string> {
  const raw = await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, key, new TextEncoder().encode(message));
  return b64(p1363ToDer(new Uint8Array(raw)));
}

export function modifySubpath(env: CloudKitEnv): string {
  return `/database/1/${env.CK_CONTAINER}/${env.CK_ENVIRONMENT}/public/records/modify`;
}

/**
 * One records/modify call. Votes/ratings use forceReplace (deterministic name: a re-vote overwrites
 * the voter's record, like iOS's save policy .allKeys); suggestions use create (fresh UUID names).
 */
export async function modify(env: CloudKitEnv, record: CloudKitRecord, now: Date = new Date(), fetchImpl: typeof fetch = fetch): Promise<Response> {
  const subpath = modifySubpath(env);
  const operationType = record.recordType === "Suggestion" ? "create" : "forceReplace";
  const body = JSON.stringify({ operations: [{ operationType, record }], atomic: true });
  const date = isoDate(now);
  const key = await importPrivateKey(env.CK_PRIVATE_KEY);
  const signature = await sign(key, await signatureMessage(date, body, subpath));
  return fetchImpl((env.CK_API_BASE ?? "https://api.apple-cloudkit.com") + subpath, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Apple-CloudKit-Request-KeyID": env.CK_KEY_ID,
      "X-Apple-CloudKit-Request-ISO8601Date": date,
      "X-Apple-CloudKit-Request-SignatureV1": signature,
    },
    body,
  });
}
