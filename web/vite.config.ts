import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  // Alias `@/` menunjuk ke `src` — dipakai struktur Atomic Design supaya impor
  // tetap ringkas tanpa rantai `../../..` saat file berpindah antar tingkat.
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    // Proxy ke ftth-server supaya dev tidak menyentuh CORS sama sekali.
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
})
