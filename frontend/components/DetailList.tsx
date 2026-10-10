import type { ReactNode } from "react";

/** A responsive definition list used by the detail pages: one column on a phone, more as space allows. */
export function DetailList({ items, columns = 2 }: { items: Array<[string, ReactNode]>; columns?: 2 | 3 }) {
  return (
    <dl className={`neo-card grid grid-cols-1 gap-4 p-4 sm:p-6 ${columns === 3 ? "sm:grid-cols-3" : "sm:grid-cols-2"}`}>
      {items.map(([label, value]) => (
        <div key={label} className="min-w-0">
          <dt className="text-xs font-semibold text-ink">{label}</dt>
          <dd className="mt-1 break-words font-medium text-ink">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function PageContainer({ children, narrow = false }: { children: ReactNode; narrow?: boolean }) {
  return <div className={`mx-auto w-full px-4 py-6 sm:px-6 ${narrow ? "max-w-3xl" : "max-w-6xl"}`}>{children}</div>;
}
