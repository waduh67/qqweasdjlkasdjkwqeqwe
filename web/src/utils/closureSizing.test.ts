import { describe, expect, it } from 'vitest'
import {
  CUSTOM_SIZE,
  JOINT_BOX_SIZES,
  matchClosureSize,
  matchJointBoxSize,
  ODC_SIZES,
  ODP_SIZES,
  SPLICES_PER_TRAY,
} from './closureSizing'

/**
 * Preset ukuran kotak menyetel dua angka sekaligus, jadi satu preset yang meleset
 * berarti kotak tercatat punya port yang splitternya tak sanggup melayani — port
 * hantu yang tetap tampak "tersedia" di heatmap sampai teknisi berdiri di depan
 * kotaknya dan bingung.
 */
describe('preset ukuran kotak', () => {
  it('menyamakan jumlah port ODP dengan kaki splitternya', () => {
    for (const size of ODP_SIZES) {
      if (!size.splitterRatio) continue
      const legs = Number(size.splitterRatio.split(':')[1])
      expect(size.capacity).toBe(legs)
    }
  })

  it('menyamakan kapasitas cabang ODC dengan kaki splitternya', () => {
    for (const size of ODC_SIZES) {
      if (!size.splitterRatio) continue
      const legs = Number(size.splitterRatio.split(':')[1])
      expect(size.capacity).toBe(legs)
    }
  })

  it('menghitung kapasitas joint box dari tray × 12 sambungan', () => {
    for (const size of JOINT_BOX_SIZES) {
      if (size.value === CUSTOM_SIZE) continue
      expect(size.capacity).toBe(size.trayCount * SPLICES_PER_TRAY)
    }
  })
})

describe('matchClosureSize', () => {
  it('mengenali kotak berukuran lazim sebagai presetnya', () => {
    expect(matchClosureSize(ODP_SIZES, '1:8', 8)).toBe('1:8')
    expect(matchClosureSize(ODC_SIZES, '1:4', 4)).toBe('1:4')
  })

  // "—" adalah cara view melaporkan kotak tanpa splitter; ia bentuk yang sah, bukan data kosong.
  it('mengenali kotak tanpa splitter', () => {
    expect(matchClosureSize(ODP_SIZES, '—', 8)).toBe('none')
  })

  // Kotak yang sudah terpasang tak boleh dibulatkan ke preset terdekat: itu sama
  // dengan mengarang port yang tak ada badannya.
  it('menjatuhkan ukuran tak lazim ke "Atur sendiri", bukan preset terdekat', () => {
    expect(matchClosureSize(ODP_SIZES, '1:8', 12)).toBe(CUSTOM_SIZE)
    expect(matchClosureSize(ODC_SIZES, '1:8 ×2', 16)).toBe(CUSTOM_SIZE)
  })
})

describe('matchJointBoxSize', () => {
  it('mengenali ukuran tray yang lazim', () => {
    expect(matchJointBoxSize(2, 24)).toBe('2')
  })

  it('menjatuhkan tray yang kapasitasnya tak standar ke "Atur sendiri"', () => {
    expect(matchJointBoxSize(2, 48)).toBe(CUSTOM_SIZE)
  })
})
