import { describe, expect, it } from 'vitest'
import { describeInstalledOn, mountingLabel, todayIso } from './closureFieldData'

/**
 * Umur aset dipakai untuk menebak tersangka saat satu klaster pelan-pelan
 * meredup, jadi hitungannya tak boleh "kurang lebih": kotak berumur 11 bulan yang
 * dilaporkan 1 tahun bikin orang mencurigai barang yang masih bergaransi.
 */
describe('umur kotak', () => {
  const at = (iso: string) => new Date(`${iso}T12:00:00`)

  it('menyebut tanggal beserta umurnya dalam tahun', () => {
    expect(describeInstalledOn('2021-03-17', at('2026-08-11'))).toBe('17 Mar 2021 · 5 thn')
  })

  it('memakai bulan selama belum genap setahun', () => {
    expect(describeInstalledOn('2026-02-11', at('2026-08-11'))).toBe('11 Feb 2026 · 6 bln')
  })

  // Ulang tahun yang belum lewat tidak boleh dibulatkan naik.
  it('tak membulatkan naik sebelum tanggalnya lewat', () => {
    expect(describeInstalledOn('2025-08-12', at('2026-08-11'))).toBe('12 Agu 2025 · 11 bln')
  })

  it('menyebut kotak yang baru dipasang bulan ini', () => {
    expect(describeInstalledOn('2026-08-01', at('2026-08-11'))).toBe('1 Agu 2026 · baru')
  })

  // Salah ketik tahun harus kelihatan, bukan disamarkan jadi "0 bln".
  it('menampilkan tanggal masa depan apa adanya tanpa umur', () => {
    expect(describeInstalledOn('2027-01-05', at('2026-08-11'))).toBe('5 Jan 2027')
  })

  it('mengaku belum tahu ketimbang mengarang', () => {
    expect(describeInstalledOn(null)).toBe('belum dicatat')
    expect(mountingLabel(null)).toBe('belum dicatat')
  })

  it('memberi nama pendek dudukan untuk panel baca', () => {
    expect(mountingLabel('UNDERGROUND')).toBe('Bawah tanah (handhole)')
  })

  // Kotak dipasang di zona waktu operator; memakai UTC bikin tanggalnya mundur
  // sehari tiap kali form dibuka sebelum jam 7 pagi WIB.
  it('mengambil tanggal lokal, bukan UTC', () => {
    expect(todayIso(new Date(2026, 7, 11, 0, 30))).toBe('2026-08-11')
  })
})
