import { writeFileSync } from "node:fs";
import { GENERATED_FILE, readForbiddenHosts, renderGenerated } from "./forbiddenHostsSource.ts";

const hosts = readForbiddenHosts();
if (hosts === null) {
  console.log("sync-forbidden-hosts: backend config not found, keeping the committed src/generated/forbiddenHosts.ts");
} else {
  writeFileSync(GENERATED_FILE, renderGenerated(hosts));
  console.log(`sync-forbidden-hosts: wrote ${hosts.length} host(s) to src/generated/forbiddenHosts.ts`);
}
