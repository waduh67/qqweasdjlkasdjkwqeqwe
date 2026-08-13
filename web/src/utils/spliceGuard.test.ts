import { describe, expect, it } from 'vitest'
import { impossibleSpliceWarning, sameCableWarning, type SpliceEnd } from './spliceGuard'

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

const SPL = 'spl-1'

const core = (cableId: string, cableCode: string): SpliceEnd => ({
  kind: 'CORE',
  nodeId: null,
  portNumber: null,
  cableId,
  cableCode,
})
const input = (splitterId = SPL): SpliceEnd => ({
  kind: 'SPLITTER_IN',
  nodeId: splitterId,
  portNumber: null,
  cableId: null,
  cableCode: null,
})
const leg = (portNumber: number, splitterId = SPL): SpliceEnd => ({
  kind: 'SPLITTER_OUT',
  nodeId: splitterId,
  portNumber,
  cableId: null,
  cableCode: null,
})

const dist = core('c-dist', 'DIST-JB-PM-001-ODP-XXX-01')
const drop = core('c-drop', 'DROP-ODP-XXX-01-P1')

describe('pasangan splitter yang mustahil ada wujudnya', () => {
  it('diam saja selama salah satu sisi belum dipilih', () => {
    expect(impossibleSpliceWarning(null, leg(1), [])).toBeNull()
    expect(impossibleSpliceWarning(dist, null, [])).toBeNull()
  })

  // Arah cahaya di splitter cuma satu: masuk lewat input, keluar di kaki.
  it('menolak bentuk yang tak perlu menengok isi kotak untuk dinilai', () => {
    expect(impossibleSpliceWarning(leg(1), leg(2), [])).toContain('Dua kaki splitter')
    expect(impossibleSpliceWarning(input(), input('spl-2'), [])).toContain('Dua input splitter')
    expect(impossibleSpliceWarning(input(), leg(1), [])).toContain('modulnya sendiri')
  })

  // Kabinet bertingkat: kaki modul atas menyuapi INPUT modul bawah.
  it('membiarkan splitter bertingkat lewat', () => {
    expect(impossibleSpliceWarning(leg(1), input('spl-2'), [])).toBeNull()
  })

  /**
   * Kekeliruan yang paling sering terjadi di meja ODP, dan yang paling sulit
   * dilihat: kabel distribusi masuk, core 1 ke input — benar — lalu core
   * tetangganya di selubung yang SAMA ke salah satu kaki.
   */
  it('menolak kaki yang diarahkan balik ke kabel penyuap inputnya', () => {
    const wired = [{ a: dist, b: input() }]
    const pesan = impossibleSpliceWarning(dist, leg(7), wired)
    expect(pesan).toContain('DIST-JB-PM-001-ODP-XXX-01')
    expect(pesan).toContain('MENYUAPI')
    expect(pesan).toContain('kabel DROP')

    // Yang benar tetap lolos: kaki bertemu core kabel drop ke rumah pelanggan.
    expect(impossibleSpliceWarning(drop, leg(7), wired)).toBeNull()
  })

  // Urutan kerja tak selalu rapi — kaki bisa lebih dulu dilas daripada inputnya.
  it('menolak input yang disuapi kabel yang seratnya sudah dipakai kakinya', () => {
    const wired = [{ a: leg(3), b: dist }]
    const pesan = impossibleSpliceWarning(dist, input(), wired)
    expect(pesan).toContain('pulang ke masukannya sendiri')

    // Modul sebelah tak ikut kena: yang bentrok hanya kaki milik modul itu.
    expect(impossibleSpliceWarning(dist, input('spl-2'), wired)).toBeNull()
  })
})
