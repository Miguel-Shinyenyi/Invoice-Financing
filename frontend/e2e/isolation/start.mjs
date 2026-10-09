// Two recording stub backends (the "real" one and the "lab" one) and a production Next server wired to them.
// Used only by the cookie-isolation test. Run `npm run build` first.
import http from "node:http";
import { spawn } from "node:child_process";

const REAL = 4101;
const LAB = 4102;
const NEXT = 4100;

function jwt(payload) {
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
  return `${b64({ alg: "none" })}.${b64(payload)}.sig`;
}

function stub(port, name, handler) {
  const log = [];
  const server = http.createServer((req, res) => {
    if (req.url === "/__log") {
      res.setHeader("Content-Type", "application/json");
      return res.end(JSON.stringify(log));
    }
    if (req.url === "/__reset") {
      log.length = 0;
      return res.end("ok");
    }
    let body = "";
    req.on("data", (c) => (body += c));
    req.on("end", () => {
      log.push({ method: req.method, url: req.url, headers: req.headers, body });
      const out = handler(req) ?? { status: 200, json: {} };
      res.statusCode = out.status;
      res.setHeader("Content-Type", "application/json");
      res.end(JSON.stringify(out.json));
    });
  });
  server.listen(port, "127.0.0.1", () => console.log(`${name} stub on ${port}`));
}

stub(REAL, "real", (req) => {
  if (req.url.startsWith("/settlements")) {
    return { status: 200, json: { content: [], page: { size: 20, number: 0, totalElements: 0, totalPages: 0 } } };
  }
  return { status: 200, json: {} };
});

stub(LAB, "lab", (req) => {
  if (req.url.startsWith("/lab/personas/")) {
    const token = jwt({ sub: "11111111-1111-1111-1111-111111111111", role: "ADMIN", exp: Math.floor(Date.now() / 1000) + 900 });
    return { status: 200, json: { persona: { role: "ADMIN" }, token, expiresInSeconds: 900 } };
  }
  if (req.url.startsWith("/settlements")) {
    return { status: 200, json: { content: [], page: { size: 20, number: 0, totalElements: 0, totalPages: 0 } } };
  }
  return { status: 200, json: { ok: true } };
});

const next = spawn("npx", ["next", "start", "-p", String(NEXT)], {
  stdio: "inherit",
  env: { ...process.env, BACKEND_URL: `http://127.0.0.1:${REAL}`, LAB_BACKEND_URL: `http://127.0.0.1:${LAB}` },
});
process.on("SIGTERM", () => next.kill());
process.on("SIGINT", () => next.kill());
