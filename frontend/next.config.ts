import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Docker deployment copies this self-contained build output into a minimal runtime image
  // instead of shipping node_modules -- see frontend/Dockerfile.
  output: "standalone",
  // The backend Ingress already owns /accounts, /auth, /invoices, /reconciliation, /settlements
  // as path prefixes on the one shared bare-IP TLS NodePort (30443, no hostname to route by) --
  // and this app's own pages live at those exact paths. /app namespaces the whole frontend so
  // both coexist on one origin. See infra/k8s/14-frontend.yaml.
  basePath: "/app",
};

export default nextConfig;
