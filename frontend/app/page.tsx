import { redirect } from "next/navigation";
import { currentUser } from "@/lib/api";

// Signed-in users land on their data; everyone else lands on the public Lab.
export default async function Home() {
  redirect((await currentUser()) ? "/settlements" : "/lab");
}
