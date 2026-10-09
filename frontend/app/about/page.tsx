import Link from "next/link";
import { buildHistory } from "@/lib/buildHistory";

// Tailwind statically scans source for full class-name strings, so these must be spelled out
// literally here -- a template-literal-built class name (e.g. `bg-[var(--color-${accent})]`)
// would never get its CSS generated.
const ACCENT_BADGE_CLASSES = [
  "neo-badge bg-[var(--color-neo-yellow)]",
  "neo-badge bg-[var(--color-neo-pink)] text-white",
  "neo-badge bg-[var(--color-neo-blue)] text-white",
  "neo-badge bg-[var(--color-neo-green)] text-white",
  "neo-badge bg-[var(--color-neo-orange)]",
];

export default function AboutPage() {
  return (
    <div className="mx-auto max-w-3xl px-6 py-12">
      <p className="neo-badge bg-[var(--color-neo-green)] text-white">Public — no login required</p>
      <h1 className="mt-4 text-4xl font-black uppercase leading-tight tracking-tight text-black">
        How this was built
      </h1>
      <p className="mt-3 max-w-2xl text-lg font-medium text-black">
        An idempotent settlement and reconciliation engine, built phase by phase with TDD as a
        hard rule. Below is the real record — what got built, and the real bugs hit and fixed
        along the way, not just a feature list.
      </p>

      <div className="neo-card mt-8 bg-[var(--color-neo-yellow)] p-6">
        <h2 className="text-xl font-black uppercase tracking-tight text-black">Try it yourself</h2>
        <p className="mt-2 font-medium text-black">
          This is a live, working deployment — not a mockup. Log in with the demo account below
          (a seeded test credential for this portfolio project, not a real secret) and click
          around.
        </p>
        <div className="mt-4 flex flex-wrap items-center gap-3">
          <code className="neo-badge bg-white text-black">admin1 / password123</code>
          <Link href="/login" className="neo-btn bg-white">
            Log in →
          </Link>
        </div>
        <ol className="mt-4 list-decimal space-y-1 pl-5 font-medium text-black">
          <li>Browse Settlements, Invoices, and Accounts — all live, real data.</li>
          <li>
            Open an <span className="font-mono font-bold">ISSUED</span> invoice and click
            &quot;Finance this invoice&quot; to watch the actual idempotent settlement flow run
            end to end.
          </li>
          <li>Check Reconciliation for open mismatches, or trigger a fresh run.</li>
        </ol>
      </div>

      <div className="mt-10 space-y-8">
        {buildHistory.map((entry, index) => {
          const badgeClass = ACCENT_BADGE_CLASSES[index % ACCENT_BADGE_CLASSES.length];
          return (
            <div key={entry.phase} className="neo-card p-6">
              <div className="flex flex-wrap items-baseline gap-3">
                <span className={badgeClass}>{entry.phase}</span>
                <h2 className="text-2xl font-black text-black">{entry.title}</h2>
              </div>
              <p className="mt-3 font-medium text-black">{entry.summary}</p>

              {entry.challenges.length > 0 && (
                <div className="mt-5 border-t-2 border-black pt-4">
                  <p className="text-xs font-black uppercase tracking-wide text-black">
                    Challenges hit
                  </p>
                  <ul className="mt-3 space-y-4">
                    {entry.challenges.map((challenge, i) => (
                      <li key={i} className="border-l-4 border-black pl-4">
                        <p className="font-bold text-black">{challenge.problem}</p>
                        <p className="mt-1 text-black">
                          <span className="font-black">Fix: </span>
                          {challenge.fix}
                        </p>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
