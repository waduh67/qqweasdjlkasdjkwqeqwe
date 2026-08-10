import { afterEach, describe, expect, it, vi } from 'vitest'
import { timeAgo } from './timeAgo'

/**
 * Helper ini dipakai bersama antrean helpdesk, daftar insiden, dan lonceng pemberitahuan —
 * satu batas satuan yang meleset membuat tiket berumur sejam terbaca "baru saja" di tiga
 * tempat sekaligus. Waktu dibekukan supaya tesnya tak ikut berdenyut bersama jam nyata.
 */
const NOW = new Date('2026-08-10T12:00:00Z')

/** Menghasilkan ISO sekian detik SEBELUM NOW. */
const agoBy = (seconds: number) => new Date(NOW.getTime() - seconds * 1000).toISOString()

afterEach(() => {
  vi.useRealTimers()
})

const freeze = () => {
  vi.useFakeTimers()
  vi.setSystemTime(NOW)
}

describe('timeAgo', () => {
  it('memakai 60 detik, 60 menit, dan 24 jam sebagai batas pindah satuan', () => {
    freeze()
    expect(timeAgo(agoBy(59))).toBe('baru saja')
    expect(timeAgo(agoBy(60))).toBe('1 menit lalu')
    expect(timeAgo(agoBy(59 * 60))).toBe('59 menit lalu')
    expect(timeAgo(agoBy(60 * 60))).toBe('1 jam lalu')
    expect(timeAgo(agoBy(23 * 3600))).toBe('23 jam lalu')
    expect(timeAgo(agoBy(24 * 3600))).toBe('1 hari lalu')
  })

  it('membulatkan ke bawah, bukan ke terdekat — "2 jam lalu" tak boleh muncul sebelum jam kedua lewat', () => {
    freeze()
    expect(timeAgo(agoBy(119 * 60))).toBe('1 jam lalu')
  })

  /**
   * Jam server dan jam browser tak pernah persis sama; selisih beberapa detik bisa membuat
   * peristiwa tampak berasal dari masa depan. Yang muncul harus "baru saja", bukan angka negatif.
   */
  it('tak pernah menampilkan durasi negatif saat cap waktu mendahului jam lokal', () => {
    freeze()
    expect(timeAgo(new Date(NOW.getTime() + 30_000).toISOString())).toBe('baru saja')
  })
})
