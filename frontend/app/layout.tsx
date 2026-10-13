import type { Metadata, Viewport } from "next";
import "./globals.css";
import { currentUser } from "@/lib/api";
import { NavBar } from "@/components/NavBar";

// No web font: the system stack in globals.css renders San Francisco on Apple devices and the
// platform UI font elsewhere, and the build no longer needs to reach Google Fonts.

export const metadata: Metadata = {
  title: "Invoice Financing",
  description: "Idempotent settlement and reconciliation engine -- admin dashboard",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f5f5f7" },
    { media: "(prefers-color-scheme: dark)", color: "#000000" },
  ],
};

export default async function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  const user = await currentUser();

  return (
    <html lang="en" className="h-full antialiased">
      <body className="flex min-h-full flex-col bg-canvas text-ink">
        <NavBar role={user?.role ?? null} />
        <main className="flex-1">{children}</main>
      </body>
    </html>
  );
}
