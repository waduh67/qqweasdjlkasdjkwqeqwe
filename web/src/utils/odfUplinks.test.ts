import { describe, expect, it } from 'vitest'
import { summarizeOdfUplinks, uplinkLabel } from './odfUplinks'

const uplink = (oltCode: string, portCount: number) => ({
  oltId: oltCode,
  oltCode,
  oltName: `OLT ${oltCode}`,
  portCount,
})

describe('OLT terkait rak', () => {
  it('menyebut jumlah port, bukan cuma nama OLT-nya', () => {
    expect(uplinkLabel(uplink('OLT-JKT-01', 12))).toBe('OLT-JKT-01 · 12 port')
  })

  // Rak yang belum dicolok apa pun tak boleh terbaca seperti rak menganggur
  // milik OLT tertentu — di tabel, sel kosong dan sel "—" dibaca berbeda.
  it('mengaku belum tahu saat belum ada patchcord', () => {
    expect(summarizeOdfUplinks([])).toBe('—')
  })

  it('menyebut apa adanya kalau cuma satu OLT', () => {
    expect(summarizeOdfUplinks([uplink('OLT-JKT-01', 12)])).toBe('OLT-JKT-01')
  })

  // Yang dicari saat menyapu daftar rak adalah "punya siapa"; sisanya cukup
  // dihitung, dan detail lengkapnya ada di panel.
  it('menyebut yang portnya terbanyak lalu menghitung sisanya', () => {
    expect(summarizeOdfUplinks([uplink('OLT-JKT-01', 12), uplink('OLT-JKT-02', 1)])).toBe('OLT-JKT-01 +1')
  })
})
