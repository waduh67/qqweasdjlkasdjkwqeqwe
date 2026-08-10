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
