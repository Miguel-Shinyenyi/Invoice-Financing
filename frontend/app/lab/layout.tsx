import type { Metadata } from "next";
import { LabNav } from "@/components/lab/LabNav";
import { NotConnected } from "@/components/lab/NotConnected";
import { StatusStrip } from "@/components/lab/StatusStrip";
import { labBackendUrl } from "@/lib/config";

export const metadata: Metadata = {
  title: "Lab · Invoice Financing",
  description: "Run the real settlement engine in a sandbox and watch it work.",
};

// Read at request time: LAB_BACKEND_URL is runtime configuration, not a build-time constant.
export const dynamic = "force-dynamic";

export default function LabLayout({ children }: { children: React.ReactNode }) {
  if (!labBackendUrl()) {
    return <NotConnected />;
  }
  return (
    <div className="mx-auto w-full max-w-6xl px-4 py-5 sm:px-6">
      <StatusStrip />
      <LabNav />
      <div className="mt-5">{children}</div>
    </div>
  );
}
