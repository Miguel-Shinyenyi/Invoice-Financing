import { Card, DocLink, PageHeader, Table } from "@/components/ui";

// A server component: LAB_SWAGGER_URL is runtime configuration, not baked into the build.
export const dynamic = "force-dynamic";

const ENDPOINTS: Array<[string, string, string]> = [
  ["GET", "/lab/status", "caps, compressed timings, reset times"],
  ["GET", "/lab/health", "status, latency and last check of every dependency"],
  ["POST", "/lab/settlements", "create a settlement through SettlementService, optional gateway fault"],
  ["POST", "/lab/settlements/retry", "re-send a key and body"],
  ["POST", "/lab/settlements/orphan", "leave a PENDING settlement, as a crash would"],
  ["GET", "/lab/settlements/{id}/inspect", "every row the engine holds for one settlement"],
  ["POST", "/lab/external/{ref}/forget | /corrupt", "make the external system disagree"],
  ["POST", "/lab/accounts/{id}/hand-edit-balance | /repair-balance", "the row a human would edit"],
  ["POST", "/lab/reconciliation/run · /lab/sweep/run", "run the engine's own jobs now"],
  ["GET", "/lab/reconciliation/runs | mismatches | ledger-mismatches | stranded | summary", "what the real API does not list"],
  ["POST", "/lab/personas/{role}/token", "a real JWT for the sandbox ADMIN, SUPPORT or READ_ONLY user"],
  ["POST", "/lab/invoices/demo · /{id}/mark-paid · /repayment-run", "invoice and fraud scenarios"],
  ["POST", "/lab/load/start · /{id}/cancel", "start or cancel a load run (server-side caps)"],
  ["GET", "/lab/load/{id} · /{id}/stream · /history", "a run, its live stream, the last runs"],
  ["GET", "/lab/logs · /lab/logs/stream", "both services' logs, filterable, live"],
  ["GET", "/lab/metrics · /lab/events · /lab/kafka", "curated metrics, outbox and topics, offsets and lag"],
  ["GET", "/lab/traces · /lab/traces/{traceId}", "Jaeger traces"],
  ["GET", "/lab/alerts · POST /lab/ml/fault", "alert board, take the fraud service down"],
  ["GET", "/lab/audit · /lab/data/{table} · /lab/state-machines", "audit rows, whitelisted tables, state machines"],
  ["GET", "/lab/correlate?requestId=", "everything for one X-Request-Id"],
  ["POST", "/lab/reset", "return to the seed (once a minute)"],
];

export default function ApiPage() {
  const swagger = process.env.LAB_SWAGGER_URL?.trim() || null;
  return (
    <div className="space-y-5">
      <PageHeader title="API" subtitle="The sandbox's real endpoints, and the lab endpoints this UI drives. Every action in the Lab has a “Show the request” disclosure with the real method, path, headers, body and an equivalent curl." />
      <Card title="Swagger UI">
        {swagger ? (
          <p className="text-sm font-medium">The sandbox backend serves springdoc&apos;s Swagger UI: <a className="font-medium text-link underline underline-offset-2" href={swagger} target="_blank" rel="noreferrer">{swagger}</a>. Use “Authorize” with a token from <code className="font-mono">POST /lab/personas/ADMIN/token</code> to call the real endpoints.</p>
        ) : (
          <p className="text-sm font-medium">No <code className="font-mono">LAB_SWAGGER_URL</code> is set for this deployment, so there is no link to the sandbox&apos;s Swagger UI. The compose file sets it to <code className="font-mono">http://localhost:8080/swagger-ui/index.html</code>.</p>
        )}
        <p className="mt-2 text-xs"><DocLink path="docs/backend.md" anchor="lab-endpoints-demo-profile-only">Every /lab endpoint is documented in backend.md</DocLink></p>
      </Card>
      <Card title="Lab endpoints (public, no login)">
        <Table head={["Method", "Path", "What it does"]}>
          {ENDPOINTS.map(([m, p, d]) => (<tr key={p}><td className="px-3 py-1.5 font-mono text-xs font-semibold">{m}</td><td className="px-3 py-1.5 font-mono text-xs">{p}</td><td className="px-3 py-1.5 text-xs">{d}</td></tr>))}
        </Table>
        <p className="mt-2 text-xs font-medium">From a terminal, reach them through the frontend at <code className="font-mono">/app/api/lab/&lt;path without /lab&gt;</code>; real endpoints go through <code className="font-mono">/app/api/lab-real/…</code> with the persona cookie.</p>
      </Card>
    </div>
  );
}
