import { CodeBox, Notice } from "@/components/ui";
import { LAB_START_COMMAND } from "@/lib/config";

/** Shown when LAB_BACKEND_URL is unset. Nothing else in the app depends on the Lab being up. */
export function NotConnected() {
  return (
    <div className="mx-auto w-full max-w-3xl px-4 py-8 sm:px-6">
      <h1 className="mb-3 text-2xl font-semibold tracking-tight">Lab is not connected</h1>
      <Notice tone="yellow" title="No sandbox is configured.">
        The Lab runs the real settlement engine in its own sandbox stack, separate from staging. This site has no
        <code className="mx-1 font-mono">LAB_BACKEND_URL</code> set, so there is nothing to talk to. The rest of the site is unaffected.
      </Notice>
      <p className="mb-2 mt-5 text-sm font-semibold">Start the sandbox from the repository root:</p>
      <CodeBox>{LAB_START_COMMAND}</CodeBox>
      <p className="mt-3 text-sm font-medium">
        The compose file starts Postgres (<code className="font-mono">settlement_engine_lab</code>), Kafka, the fraud service, the backend
        (profile <code className="font-mono">demo</code>) and this frontend with <code className="font-mono">LAB_BACKEND_URL</code> set.
        Then open <code className="font-mono">/app/lab</code>.
      </p>
    </div>
  );
}
