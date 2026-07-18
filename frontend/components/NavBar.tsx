"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { BASE_PATH } from "@/lib/basePath";

export function NavBar({ role }: { role: string | null }) {
  const router = useRouter();

  async function logout() {
    await fetch(`${BASE_PATH}/api/logout`, { method: "POST" });
    router.push("/login");
    router.refresh();
  }

  return (
    <nav className="flex items-center justify-between border-b-2 border-black bg-white px-6 py-3">
      <div className="flex items-center gap-6">
        <span className="font-black uppercase tracking-tight text-black">Invoice Financing</span>
        {role && (
          <>
            <Link href="/settlements" className="neo-chip bg-white hover:bg-[var(--color-neo-yellow)]">
              Settlements
            </Link>
            <Link href="/invoices" className="neo-chip bg-white hover:bg-[var(--color-neo-yellow)]">
              Invoices
            </Link>
            {(role === "ADMIN" || role === "SUPPORT") && (
              <Link href="/reconciliation" className="neo-chip bg-white hover:bg-[var(--color-neo-yellow)]">
                Reconciliation
              </Link>
            )}
          </>
        )}
        <Link href="/about" className="neo-chip bg-white hover:bg-[var(--color-neo-pink)]">
          Build Story
        </Link>
      </div>
      <div className="flex items-center gap-4">
        {role ? (
          <>
            <span className="neo-badge bg-[var(--color-neo-blue)] text-white">{role}</span>
            <button onClick={logout} className="neo-btn">
              Sign out
            </button>
          </>
        ) : (
          <Link href="/login" className="neo-btn bg-[var(--color-neo-yellow)]">
            Log in
          </Link>
        )}
      </div>
    </nav>
  );
}
