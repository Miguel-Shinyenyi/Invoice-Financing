export const ACCESS_TOKEN_COOKIE = "access_token";
export const REFRESH_TOKEN_COOKIE = "refresh_token";

export interface AccessTokenClaims {
  userId: string;
  role: "ADMIN" | "SUPPORT" | "READ_ONLY";
  ownerId: string | null;
  expiresAt: number;
}

/**
 * Decodes (does not verify) the JWT payload -- signature verification already happened on the
 * backend for every real API call this token is used against. This is purely for reading the
 * role claim server-side to gate which buttons/links render; it is never trusted as an
 * authorization decision by itself.
 */
export function decodeAccessToken(token: string): AccessTokenClaims {
  const payload = token.split(".")[1];
  const json = Buffer.from(payload, "base64url").toString("utf-8");
  const parsed = JSON.parse(json) as { sub: string; role: AccessTokenClaims["role"]; ownerId?: string; exp: number };
  return {
    userId: parsed.sub,
    role: parsed.role,
    ownerId: parsed.ownerId ?? null,
    expiresAt: parsed.exp,
  };
}
