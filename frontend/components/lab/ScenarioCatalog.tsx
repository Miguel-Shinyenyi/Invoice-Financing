"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { Card, DocLink, KnownGap } from "@/components/ui";
import { lab } from "@/lib/lab/client";
import type { Scenario } from "@/lib/lab/types";

const GAP_ANCHORS: Record<number, { anchor: string; doc?: string }> = {
  1: { anchor: "open-questions" },
  2: { anchor: "open-questions" },
  3: { anchor: "settlement-state-machine" },
  5: { anchor: "the-external-system-for-now" },
  6: { anchor: "current-state", doc: "docs/kafka-events.md" },
};

/** The catalog from lab/seed/scenarios.json: what you will see, what the engine does, what to look for, the honest limit. */
export function ScenarioCatalog({ group }: { group?: string }) {
  const [scenarios, setScenarios] = useState<Scenario[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    lab.get<Scenario[]>("scenarios").then((r) => {
      if (!live) return;
      if (r.ok && r.data) setScenarios(r.data);
      else setFailed(true);
    });
    return () => {
      live = false;
    };
  }, []);

  if (failed) return <Card title="Scenarios">The scenario catalog could not be loaded.</Card>;
  if (!scenarios) return <Card title="Scenarios">Loading…</Card>;
  const shown = group ? scenarios.filter((s) => s.group === group) : scenarios;
  const groups = [...new Set(shown.map((s) => s.group))];

  return (
    <div className="space-y-5">
      {groups.map((g) => (
        <section key={g}>
          <h3 className="mb-2 text-sm font-semibold">{g}</h3>
          <div className="grid gap-3 md:grid-cols-2">
            {shown.filter((s) => s.group === g).map((s) => (
              <article key={s.id} id={s.id} className="neo-card min-w-0 p-3">
                <div className="flex flex-wrap items-start justify-between gap-2">
                  <h4 className="text-sm font-semibold">{s.title}</h4>
                  {s.knownGap && GAP_ANCHORS[s.knownGap] && (
                    <KnownGap n={s.knownGap} anchor={GAP_ANCHORS[s.knownGap].anchor} doc={GAP_ANCHORS[s.knownGap].doc} />
                  )}
                </div>
                <dl className="mt-2 space-y-1.5 text-xs font-medium">
                  <div><dt className="font-semibold">You will see</dt><dd>{s.visitorSees}</dd></div>
                  <div>
                    <dt className="font-semibold">The engine</dt>
                    <dd>
                      <ul className="list-disc pl-4">
                        {s.engineDoes.map((e) => (
                          <li key={`${e.class}.${e.method}`}><code className="font-mono font-semibold">{e.class}.{e.method}</code>: {e.what}</li>
                        ))}
                      </ul>
                    </dd>
                  </div>
                  <div><dt className="font-semibold">Look for</dt><dd>{s.lookFor}</dd></div>
                  <div><dt className="font-semibold">The honest limit</dt><dd>{s.honestLimit}</dd></div>
                </dl>
                <div className="mt-2 flex flex-wrap items-center gap-3 text-xs">
                  <DocLink path={s.doc.path} anchor={s.doc.anchor}>{s.doc.label}</DocLink>
                  <Link href={s.action.route} className="neo-btn min-h-8 px-3.5 text-[13px]">Try it</Link>
                </div>
              </article>
            ))}
          </div>
        </section>
      ))}
    </div>
  );
}
