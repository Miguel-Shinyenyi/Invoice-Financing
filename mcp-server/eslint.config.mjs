import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist/**", "node_modules/**", "evals/results/**"] },
  ...tseslint.configs.recommended,
  {
    rules: {
      "@typescript-eslint/no-unused-vars": ["error", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
      "no-console": "error",
    },
  },
  // Tests read JSON straight off the wire, so they use `any` for it. Source code does not.
  { files: ["test/**/*.ts", "support/**/*.ts", "evals/**/*.ts", "scripts/**/*.ts"], rules: { "@typescript-eslint/no-explicit-any": "off" } },
  // The evals runner and the build scripts are CLIs: they print on purpose.
  { files: ["evals/**/*.ts", "scripts/**/*.ts"], rules: { "no-console": "off" } },
);
