"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { BASE_PATH } from "@/lib/basePath";

export function NavBar({ role }: { role: string }) {
  const router = useRouter();

  async function logout() {
    await fetch(`${BASE_PATH}/api/logout`, { method: "POST" });
    router.push("/login");
    router.refresh();
  }

  return (
    <nav className="flex items-center justify-between border-b border-slate-200 bg-white px-6 py-3">
      <div className="flex items-center gap-6">
        <span className="font-semibold text-slate-900">Invoice Financing</span>
        <Link href="/settlements" className="text-sm text-slate-600 hover:text-slate-900">
          Settlements
        </Link>
        <Link href="/invoices" className="text-sm text-slate-600 hover:text-slate-900">
          Invoices
        </Link>
        {(role === "ADMIN" || role === "SUPPORT") && (
          <Link href="/reconciliation" className="text-sm text-slate-600 hover:text-slate-900">
            Reconciliation
          </Link>
        )}
      </div>
      <div className="flex items-center gap-4">
        <span className="rounded-full bg-slate-100 px-2 py-1 text-xs font-medium text-slate-600">{role}</span>
        <button onClick={logout} className="text-sm text-slate-600 hover:text-slate-900">
          Sign out
        </button>
      </div>
    </nav>
  );
}
