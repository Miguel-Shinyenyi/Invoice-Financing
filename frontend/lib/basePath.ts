// Mirrors next.config.ts's basePath. Client-side fetch() calls and proxy.ts's manually
// constructed redirect URLs aren't covered by Next's automatic basePath rewriting (that only
// applies to next/link and next/router) so they need this prefix by hand.
export const BASE_PATH = "/app";
