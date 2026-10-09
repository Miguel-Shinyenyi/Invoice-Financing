import { Card } from "@/components/ui";
import { fmtTime } from "@/lib/lab/format";
import type { LabStep } from "@/lib/lab/types";

export function StepList({ steps }: { steps: LabStep[] }) {
  if (steps.length === 0) return null;
  return (
    <Card title="What the engine did, in order">
      <ol className="space-y-2">
        {steps.map((st) => (
          <li key={st.key} data-step={st.key} className="grid grid-cols-[2rem_1fr] gap-2 border-2 border-black p-2">
            <span className="flex h-7 w-7 items-center justify-center border-2 border-black bg-[var(--color-neo-yellow)] font-mono text-sm font-black">{st.order}</span>
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2 text-sm font-black">
                {st.title}
                <span className="neo-badge bg-white">{st.source === "log" ? "log event" : "database row"}</span>
                <span className="font-mono text-xs font-medium">{fmtTime(st.at)}</span>
              </div>
              <p className="break-words text-xs font-medium">{st.detail}</p>
            </div>
          </li>
        ))}
      </ol>
      <p className="mt-2 text-xs font-medium">Assembled from the real rows and log events the request produced, not from a script.</p>
    </Card>
  );
}
