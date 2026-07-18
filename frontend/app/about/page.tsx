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
