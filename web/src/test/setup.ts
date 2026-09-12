/**
 * Persiapan lingkungan uji web.
 *
 * jsdom tak menyediakan `matchMedia` sama sekali, padahal kode shell membacanya untuk
 * membedakan "ciutkan sidebar" (layar lebar) dari "buka laci nav" (ponsel). Tanpa
 * pengganti, komponen apa pun yang menyentuhnya melempar TypeError sebelum assertion
 * pertama dijalankan. Bawaannya dibuat TIDAK cocok (desktop) supaya uji yang ingin
 * berperan sebagai ponsel harus menyatakannya sendiri lewat [setViewportMatches].
 */
import { afterEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'

/**
 * Tambal `localStorage` yang HILANG, bukan salah pakai.
 *
 * Node 26 mendefinisikan sendiri getter `localStorage` di `globalThis`, dan getter itu
 * memulangkan `undefined` selama `--localstorage-file` tidak diberikan. Getter tersebut sudah
 * terpasang SEBELUM vitest memasang lingkungan jsdom, jadi jsdom tidak menimpanya — dan karena
 * di sini `window === globalThis`, menulis `window.localStorage` pun tetap `undefined`.
 * Buktinya `sessionStorage` baik-baik saja: yang rusak cuma satu nama yang kebetulan
 * diperebutkan Node.
 *
 * Akibatnya `localStorage.clear()` di [afterEach] melempar di SETIAP berkas uji — termasuk uji
 * murni seperti `timeAgo.test.ts` yang tak pernah menyentuh penyimpanan — dan seluruh suite
 * merah karena alasan yang tak ada hubungannya dengan kode yang diuji.
 *
 * Ditambal di sini, BUKAN dengan mewajibkan `NODE_OPTIONS=--localstorage-file=...`: jalan itu
 * menaruh syarat lulus di luar repo (setiap orang dan setiap CI harus ingat menuliskannya) dan
 * menukar penyimpanan dalam-memori dengan berkas NYATA di disk yang bocor antar-run.
 * Deskriptornya `configurable`, jadi penambalan ini sah.
 */
function createMemoryStorage(): Storage {
  const entries = new Map<string, string>()
  return {
    get length() {
      return entries.size
    },
    key: (index: number) => [...entries.keys()][index] ?? null,
    getItem: (key: string) => (entries.has(key) ? entries.get(key)! : null),
    // `String(...)` disengaja: Storage yang asli MENYIMPAN string, jadi shim yang menyimpan
    // angka apa adanya akan meloloskan uji yang di peramban sungguhan gagal berbanding.
    setItem: (key: string, value: string) => {
      entries.set(String(key), String(value))
    },
    removeItem: (key: string) => {
      entries.delete(key)
    },
    clear: () => {
      entries.clear()
    },
  } as Storage
}

if (typeof globalThis.localStorage === 'undefined') {
  Object.defineProperty(globalThis, 'localStorage', {
    value: createMemoryStorage(),
    configurable: true,
    writable: true,
  })
}

let mediaMatches = false

/** Paksa jawaban `matchMedia` berikutnya: `true` = berperilaku seperti layar ponsel. */
export function setViewportMatches(matches: boolean) {
  mediaMatches = matches
}

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string): MediaQueryList =>
    ({
      matches: mediaMatches,
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }) as unknown as MediaQueryList,
})

afterEach(() => {
  cleanup()
  localStorage.clear()
  mediaMatches = false
})
