import { describe, expect, it } from 'vitest'
import { blokFirewallScript, isCidrLike, ovpnInterfaceName } from './blokPelanggan'

describe('isCidrLike', () => {
  it('menerima blok yang lazim dipakai kolam PPPoE', () => {
    expect(isCidrLike('10.20.0.0/16')).toBe(true)
    expect(isCidrLike('172.16.8.0/24')).toBe(true)
    expect(isCidrLike(' 10.20.30.40/32 ')).toBe(true)
  })

  it('menolak salah ketik yang paling sering terjadi', () => {
    expect(isCidrLike('10.20.0.0')).toBe(false) // lupa prefix
    expect(isCidrLike('10.20.0.0/')).toBe(false)
    expect(isCidrLike('10.300.0.0/16')).toBe(false)
    expect(isCidrLike('10.20.0.0/33')).toBe(false)
    expect(isCidrLike('')).toBe(false)
  })

  it('menolak prefix di bawah /8 — blok selebar itu pasti bukan kolam pelanggan', () => {
    expect(isCidrLike('10.0.0.0/4')).toBe(false)
  })
})

describe('blokFirewallScript', () => {
  it('membuka dua arah untuk tiap blok', () => {
    const script = blokFirewallScript(ovpnInterfaceName('bras-rumah'), ['10.20.0.0/16'])

    expect(script).toContain('in-interface=ovpn-bras-rumah')
    expect(script).toContain('out-interface=ovpn-bras-rumah')
    expect(script).toContain('dst-address=10.20.0.0/16')
    expect(script).toContain('src-address=10.20.0.0/16')
  })

  it('menaruh aturan di paling atas lewat move, bukan place-before', () => {
    // place-before menunjuk aturan yang harus sudah ada: di chain forward yang masih
    // kosong RouterOS menjawab "no such item" dan seluruh tempelan gagal.
    const script = blokFirewallScript('ovpn-x', ['10.20.0.0/16'])

    expect(script).not.toContain('place-before')
    expect(script.trimEnd().endsWith('on-error={}')).toBe(true)
  })

  it('tak berakhir dengan baris merah saat aturannya sudah di urutan teratas', () => {
    // `move` menolak dengan "destination item in source list" bila sumber = tujuan;
    // benar hasilnya, tapi tanpa penjaga ini operator mengira tempelannya gagal.
    const script = blokFirewallScript('ovpn-x', ['10.20.0.0/16'])

    expect(script).toContain(':do {')
    expect(script).toContain('on-error={}')
  })

  it('boleh ditempel berulang tanpa menumpuk aturan kembar', () => {
    const script = blokFirewallScript('ovpn-x', ['10.20.0.0/16'])

    expect(script.split('\n')[0]).toContain('remove [find comment~"^ftth-blok"]')
  })

  it('merangkai semua blok dalam satu tempelan', () => {
    const satu = blokFirewallScript('ovpn-x', ['10.20.0.0/16'])
    const dua = blokFirewallScript('ovpn-x', ['10.20.0.0/16', '10.30.0.0/16'])

    // Tiap blok menambah tepat empat baris; remove/move-nya tetap sekali.
    expect(dua.split('\n')).toHaveLength(satu.split('\n').length + 4)
    expect(dua).toContain('10.30.0.0/16')
  })

  it('tak menghasilkan apa-apa bila belum ada blok terdaftar', () => {
    expect(blokFirewallScript('ovpn-x', [])).toBe('')
  })
})
