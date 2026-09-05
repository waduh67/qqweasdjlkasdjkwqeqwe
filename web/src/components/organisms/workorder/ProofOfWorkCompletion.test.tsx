import { render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '@/system'

const { get } = vi.hoisted(() => ({ get: vi.fn() }))

vi.mock('@/api/client', () => ({
  api: { get },
  ApiError: class ApiError extends Error {},
}))

import { ProofOfWorkCompletion } from './ProofOfWorkCompletion'

const snapshot = {
  revision: 'proof-revision',
  artifacts: [
    { kind: 'FAT', revisionId: 'fat-1', label: 'FAT utama' },
    { kind: 'ODP', revisionId: 'odp-1', label: 'ODP pelanggan' },
    { kind: 'DROPCORE', revisionId: 'drop-1', label: 'Dropcore' },
    { kind: 'OPTICAL_BEFORE', revisionId: 'before-1', label: 'Optik awal' },
    { kind: 'OPTICAL_AFTER', revisionId: 'after-1', label: 'Optik akhir' },
    { kind: 'TECHNICIAN_SIGNATURE', revisionId: 'tech-sign-1', label: 'Tanda tangan teknisi' },
    { kind: 'CUSTOMER_ACKNOWLEDGEMENT', revisionId: 'customer-sign-1', label: 'Pelanggan' },
    { kind: 'LOCATION', revisionId: 'location-1', label: 'Lokasi' },
  ],
}

describe('ProofOfWorkCompletion', () => {
  it('menawarkan hanya revisi yang kompatibel untuk setiap jenis bukti', async () => {
    get.mockResolvedValueOnce(snapshot)

    render(
      <ToastProvider>
        <ProofOfWorkCompletion workOrderId="work-order-1" type="REPAIR" note="" onAct={vi.fn()} />
      </ToastProvider>,
    )

    const fat = await screen.findByLabelText('FAT')
    const acknowledgement = screen.getByLabelText('Persetujuan pelanggan')

    expect(fat.textContent).toContain('FAT utama')
    expect(fat.textContent).not.toContain('ODP pelanggan')
    expect(acknowledgement.textContent).toContain('Pelanggan')
    expect(acknowledgement.textContent).not.toContain('Tanda tangan teknisi')
  })
})

afterEach(() => {
  get.mockReset()
  vi.restoreAllMocks()
})
