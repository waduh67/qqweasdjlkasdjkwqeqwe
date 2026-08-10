import { fileURLToPath, URL } from 'node:url'
// `defineConfig` diambil dari vitest, bukan vite: ia superset yang menerima blok
// `test` di bawah. Satu berkas konfigurasi supaya alias `@/` dan plugin React
// otomatis berlaku juga saat pengujian — tak ada dua sumber kebenaran.
import { defineConfig } from 'vitest/config'
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
  test: {
    // jsdom dipakai untuk SEMUA berkas uji, termasuk yang murni logika: helper klien
    // HTTP menyentuh `localStorage`, dan memisahkan environment per-berkas hanya
    // menambah anotasi tanpa menghemat apa pun yang terasa.
    environment: 'jsdom',
    globals: false,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.test.{ts,tsx}'],
  },
})
