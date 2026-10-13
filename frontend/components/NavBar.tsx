"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { BASE_PATH } from "@/lib/basePath";

/** Global nav: a Liquid Glass bar pinned to the top. Links are plain text with the current
 *  section in ink and the rest muted, as on apple.com; on phone they scroll sideways. */
export function NavBar({ role }: { role: string | null }) {
  const router = useRouter();
  const pathname = usePathname() ?? "";

  async function logout() {
    await fetch(`${BASE_PATH}/api/logout`, { method: "POST" });
    router.push("/login");
    router.refresh();
  }

  const links: Array<[string, string]> = [["/lab", "Lab"]];
  if (role) {
    links.push(["/settlements", "Settlements"], ["/invoices", "Invoices"]);
    if (role === "ADMIN" || role === "SUPPORT") links.push(["/reconciliation", "Reconciliation"]);
  }
  links.push(["/about", "Build Story"]);

  return (
    <nav aria-label="Main" className="glass-bar sticky top-0 z-40">
      <div className="mx-auto flex min-h-[52px] w-full max-w-6xl flex-wrap items-center gap-x-5 gap-y-1 px-4 py-1.5 sm:px-6">
        <Link href={role ? "/settlements" : "/lab"} className="flex shrink-0 items-center gap-2 text-[15px] font-semibold tracking-tight text-ink">
          <span aria-hidden className="flex size-7 items-center justify-center rounded-[8px] bg-tint text-[13px] font-semibold text-white">IF</span>
          Invoice Financing
        </Link>
        <ul className="order-3 -mx-1 flex w-full min-w-0 gap-1 overflow-x-auto sm:order-none sm:mx-0 sm:w-auto sm:flex-1">
          {links.map(([href, label]) => {
            const active = pathname === href || pathname.startsWith(`${href}/`);
            return (
              <li key={href}>
                <Link
                  href={href}
                  aria-current={active ? "page" : undefined}
                  className={`inline-flex min-h-9 items-center whitespace-nowrap rounded-full px-3 text-[13px] transition-colors duration-150 hover:text-ink ${active ? "font-semibold text-ink" : "text-muted"}`}
                >
                  {label}
                </Link>
              </li>
            );
          })}
        </ul>
        <div className="ml-auto flex items-center gap-2 sm:ml-0">
          {role ? (
            <>
              <span className="neo-badge tone-blue" title="Signed-in role">{role}</span>
              <button onClick={logout} className="neo-btn min-h-8 px-3 text-[13px]">Sign out</button>
            </>
          ) : (
            <Link href="/login" className="neo-btn tone-yellow min-h-8 px-3.5 text-[13px]">Log in</Link>
          )}
        </div>
      </div>
    </nav>
  );
}
