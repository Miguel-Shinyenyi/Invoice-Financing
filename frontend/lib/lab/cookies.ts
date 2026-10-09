// The Lab's persona token is a different cookie from the real login's access_token on purpose.
export const LAB_PERSONA_COOKIE = "lab_persona_token";
export const LAB_ROLES = ["ADMIN", "SUPPORT", "READ_ONLY"] as const;
export type LabRole = (typeof LAB_ROLES)[number];
