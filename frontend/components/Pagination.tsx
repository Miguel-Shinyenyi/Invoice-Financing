import Link from "next/link";

export function Pagination({
  basePath,
  page,
  totalPages,
  extraParams = {},
}: {
  basePath: string;
  page: number;
  totalPages: number;
  extraParams?: Record<string, string | undefined>;
}) {
  if (totalPages <= 1) return null;

  function hrefFor(targetPage: number) {
    const params = new URLSearchParams();
    for (const [key, value] of Object.entries(extraParams)) {
      if (value) params.set(key, value);
    }
    params.set("page", String(targetPage));
    return `${basePath}?${params.toString()}`;
  }

  return (
    <div className="flex items-center justify-between border-t border-slate-200 px-4 py-3">
      <Link
        href={hrefFor(Math.max(0, page - 1))}
        aria-disabled={page === 0}
        className={`text-sm ${page === 0 ? "pointer-events-none text-slate-300" : "text-slate-600 hover:text-slate-900"}`}
      >
        Previous
      </Link>
      <span className="text-sm text-slate-500">
        Page {page + 1} of {totalPages}
      </span>
      <Link
        href={hrefFor(Math.min(totalPages - 1, page + 1))}
        aria-disabled={page >= totalPages - 1}
        className={`text-sm ${page >= totalPages - 1 ? "pointer-events-none text-slate-300" : "text-slate-600 hover:text-slate-900"}`}
      >
        Next
      </Link>
    </div>
  );
}
