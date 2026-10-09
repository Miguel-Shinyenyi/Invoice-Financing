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
    <div className="flex items-center justify-between border-t-2 border-black px-4 py-3">
      <Link
        href={hrefFor(Math.max(0, page - 1))}
        aria-disabled={page === 0}
        className={page === 0 ? "neo-btn pointer-events-none opacity-30" : "neo-btn"}
      >
        Previous
      </Link>
      <span className="text-sm font-bold text-black">
        Page {page + 1} of {totalPages}
      </span>
      <Link
        href={hrefFor(Math.min(totalPages - 1, page + 1))}
        aria-disabled={page >= totalPages - 1}
        className={page >= totalPages - 1 ? "neo-btn pointer-events-none opacity-30" : "neo-btn"}
      >
        Next
      </Link>
    </div>
  );
}
