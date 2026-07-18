"use client";

import { useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";
import { BASE_PATH } from "@/lib/basePath";

export default function LoginPage() {
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);

    const response = await fetch(`${BASE_PATH}/api/login`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    });

    setSubmitting(false);
    if (!response.ok) {
      setError("Invalid username or password");
      return;
    }
    router.push("/settlements");
    router.refresh();
  }

  return (
    <div className="flex min-h-[calc(100vh-57px)] items-center justify-center bg-[var(--background)] px-4">
      <form onSubmit={onSubmit} className="neo-card w-full max-w-sm space-y-4 p-8">
        <h1 className="text-2xl font-black uppercase tracking-tight text-black">Invoice Financing</h1>
        <p className="font-medium text-black">Sign in to the admin dashboard</p>

        <div className="space-y-1">
          <label htmlFor="username" className="text-sm font-bold uppercase tracking-wide text-black">
            Username
          </label>
          <input
            id="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
            className="neo-input w-full"
          />
        </div>

        <div className="space-y-1">
          <label htmlFor="password" className="text-sm font-bold uppercase tracking-wide text-black">
            Password
          </label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
            className="neo-input w-full"
          />
        </div>

        {error && <p className="font-bold text-[var(--color-neo-red)]">{error}</p>}

        <button type="submit" disabled={submitting} className="neo-btn w-full bg-[var(--color-neo-yellow)]">
          {submitting ? "Signing in..." : "Sign in"}
        </button>
      </form>
    </div>
  );
}
