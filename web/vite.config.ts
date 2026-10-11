import { defineConfig } from "vite";

export default defineConfig({
  base: "/",
  build: {
    outDir: "dist",
    emptyOutDir: true,
    target: "es2020",
    chunkSizeWarningLimit: 4000
  },
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8765",
      "/editor": "http://localhost:8765"
    }
  },
  worker: {
    format: "es"
  }
});
