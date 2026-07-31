import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./portal", import.meta.url)),
    },
  },
  server: {
    proxy: {
      "/actuator": "http://localhost:4242",
      "/v1": "http://localhost:4242",
    },
  },
});
