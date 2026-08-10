import { describe, expect, it } from 'vitest'
import type { WorkOrderView } from '@/api/workorder'
import { assigneeLabel, fmtDbm, priorityTone, rxHealth, sameRoster } from './woLabels'

/**
 * Helper work order dipakai bersama papan dispatch, "Tugas Saya", dan detail — salah
 * satu ambang di sini salah berarti teknisi diberi tahu redaman "sehat" padahal lemah.
 */
describe('rxHealth', () => {
  it('menyebut redaman di rentang wajar GPON sehat', () => {
    expect(rxHealth(-20).tone).toBe('good')
  })

  // Batas-batas inilah yang mudah bergeser saat aturan diubah, jadi dipatok eksplisit.
  it('memakai −25 dan −28 sebagai batas sehat/waspada/lemah', () => {
    expect(rxHealth(-25).tone).toBe('good')
    expect(rxHealth(-25.01).tone).toBe('warning')
    expect(rxHealth(-28).tone).toBe('warning')
    expect(rxHealth(-28.01)).toEqual({ tone: 'critical', label: 'lemah' })
  })

  it('menganggap sinyal terlalu dekat 0 dBm justru berbahaya, bukan terbaik', () => {
    expect(rxHealth(-7.9)).toEqual({ tone: 'critical', label: 'terlalu kuat' })
  })
})

describe('fmtDbm', () => {
  it('menulis dua desimal beserta satuannya', () => {
    expect(fmtDbm(-24.5)).toBe('-24.50 dBm')
  })

  it('memberi tanda pisah saat belum diukur — bukan "0.00 dBm" yang menyesatkan', () => {
    expect(fmtDbm(null)).toBe('—')
  })
})

describe('sameRoster', () => {
  const current = [
    { id: 'a', name: 'Andi' },
    { id: 'b', name: 'Budi' },
  ] as WorkOrderView['assignees']

  it('mengabaikan urutan: roster yang sama tak boleh terhitung berubah', () => {
    expect(sameRoster(['b', 'a'], current)).toBe(true)
  })

  it('mendeteksi anggota bertambah, berkurang, atau bertukar', () => {
    expect(sameRoster(['a'], current)).toBe(false)
    expect(sameRoster(['a', 'b', 'c'], current)).toBe(false)
    expect(sameRoster(['a', 'c'], current)).toBe(false)
  })
})

describe('priorityTone & assigneeLabel', () => {
  it('hanya prioritas tinggi/mendesak yang ditonjolkan', () => {
    expect(priorityTone('URGENT')).toBe('warning')
    expect(priorityTone('HIGH')).toBe('warning')
    expect(priorityTone('NORMAL')).toBe('neutral')
    expect(priorityTone('LOW')).toBe('neutral')
  })

  it('menggabungkan nama roster untuk teks & pengurutan', () => {
    const wo = { assignees: [{ id: 'a', name: 'Andi' }, { id: 'b', name: null }] } as WorkOrderView
    expect(assigneeLabel(wo)).toBe('Andi, —')
    expect(assigneeLabel({ assignees: [] } as unknown as WorkOrderView)).toBe('')
  })
})
