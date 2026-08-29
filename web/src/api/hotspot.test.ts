import { describe, expect, it, vi } from 'vitest'
import { api } from './client'
import {
  canViewHotspot,
  createVoucherBatch,
  HOTSPOT_VIEW_PERMISSIONS,
  getPublicHotspotPortalContext,
  isHotspotPlan,
  listVoucherBatches,
  listVouchers,
  revokeVoucher,
} from './hotspot'

describe('canViewHotspot', () => {
  it('mengizinkan salah satu kebijakan baca hotspot', () => {
    expect(canViewHotspot((permission) => permission === 'hotspot.site.view')).toBe(true)
  })

  it('menolak tanpa kebijakan baca hotspot', () => {
    expect(canViewHotspot(() => false)).toBe(false)
  })

  it('menjaga katalog izin baca sebagai satu sumber', () => {
    expect(HOTSPOT_VIEW_PERMISSIONS).toEqual([
      'hotspot.voucher.view',
      'hotspot.site.view',
      'hotspot.session.view',
    ])
  })
})

describe('API voucher hotspot', () => {
  it('menyelesaikan konteks portal publik hanya dari state bertanda tangan', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({} as never)
    await getPublicHotspotPortalContext('signed-state')
    expect(post).toHaveBeenCalledWith('/api/public/hotspot/portal-context/resolve', { state: 'signed-state' })
  })

  it('membentuk filter daftar batch tanpa parameter kosong', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    await listVoucherBatches({ siteId: 'site-1', page: 1 })
    expect(get).toHaveBeenCalledWith('/api/hotspot/voucher-batches?siteId=site-1&page=1')
  })

  it('membuat batch dengan durasi dalam detik', async () => {
    const post = vi.spyOn(api, 'post').mockResolvedValue({} as never)
    await createVoucherBatch({ siteId: 'site-1', planId: 'plan-1', quantity: 10, durationSeconds: 86_400 })
    expect(post).toHaveBeenCalledWith('/api/hotspot/voucher-batches', { siteId: 'site-1', planId: 'plan-1', quantity: 10, durationSeconds: 86_400 })
  })

  it('membentuk filter voucher dan mengirim alasan pencabutan', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    const post = vi.spyOn(api, 'post').mockResolvedValue({} as never)
    await Promise.all([
      listVouchers({ batchId: 'batch-1', status: 'AVAILABLE', size: 100 }),
      revokeVoucher('voucher-1', 'Salah cetak'),
    ])
    expect(get).toHaveBeenCalledWith('/api/hotspot/vouchers?batchId=batch-1&status=AVAILABLE&size=100')
    expect(post).toHaveBeenCalledWith('/api/hotspot/vouchers/voucher-1/revoke', { reason: 'Salah cetak' })
  })

  it('hanya menerima paket hotspot yang aktif untuk penerbitan', () => {
    expect(isHotspotPlan({ active: true, serviceTypes: ['HOTSPOT'] })).toBe(true)
    expect(isHotspotPlan({ active: false, serviceTypes: ['HOTSPOT'] })).toBe(false)
    expect(isHotspotPlan({ active: true, serviceTypes: ['PPPOE'] })).toBe(false)
  })
})
