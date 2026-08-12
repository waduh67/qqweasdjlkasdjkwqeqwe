import { describe, expect, it } from 'vitest'
import { sameCableWarning } from './spliceGuard'

describe('peringatan kabel yang sama di meja sambung', () => {
  it('diam saja saat kedua sisi memang kabel berbeda', () => {
    expect(sameCableWarning(null, false)).toBeNull()
    expect(sameCableWarning(null, true)).toBeNull()
  })

  // Salah pilih: kabel lanjutannya ada, cuma belum dipilih di sisi sebelah.
  it('menyebut kabelnya dan akibat fisiknya saat sisi-sisinya kembar', () => {
    const pesan = sameCableWarning('DIST-ODC-XX-001-JB-YY-001', false)
    expect(pesan).toContain('DIST-ODC-XX-001-JB-YY-001')
    expect(pesan).toContain('berbalik pulang')
    expect(pesan).toContain('Ganti salah satu sisi')
  })

  // Bukan salah pilih: memang belum ada lawan mainnya. Dua kemungkinan sebabnya
  // disebut dua-duanya, sebab kabel lanjutan bisa saja sudah ada di dalam kotak
  // dan cuma belum tercatat singgahannya.
  it('menawarkan dua jalan keluar saat kotaknya baru disinggahi satu kabel', () => {
    const pesan = sameCableWarning('DIST-ODC-XX-001-JB-YY-001', true)
    expect(pesan).toContain('belum ada yang bisa disambung')
    expect(pesan).toContain('catat singgahan kabel lanjutannya')
    expect(pesan).toContain('tarik dulu kabelnya di peta')
    expect(pesan).not.toContain('Ganti salah satu sisi')
  })
})
