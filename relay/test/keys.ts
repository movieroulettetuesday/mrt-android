// Throwaway P-256 key generated in memory for each test run. Never written to disk or committed.
import { generateKeyPairSync, type KeyObject } from "node:crypto";

export function throwawayKey(): { sec1Pem: string; pkcs8Pem: string; publicKey: KeyObject } {
  const { privateKey, publicKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  return {
    sec1Pem: privateKey.export({ format: "pem", type: "sec1" }).toString(),
    pkcs8Pem: privateKey.export({ format: "pem", type: "pkcs8" }).toString(),
    publicKey,
  };
}
