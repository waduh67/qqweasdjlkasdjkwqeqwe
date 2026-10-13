import { useState } from 'react'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from '@/api/client'
import { PonDiscoveryPanel, type PonDiscovery } from './PonPortTab'

vi.mock('@/api/client', () => ({ api: { get: vi.fn(), post: vi.fn() } }))
vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => true }) }))
vi.mock('@/system', () => ({ useConfirm: () => vi.fn(), useToast: () => ({ success: vi.fn(), error: vi.fn() }) }))

const snapshot: PonDiscovery = {
  oltId: 'olt-1', readAt: '2026-10-13T00:00:00Z', warnings: [],
  ports: [{ label: 'PON1', onuCount: 4, fromInterface: false }, { label: 'PON2', onuCount: 0, fromInterface: true }, { label: 'PON3', onuCount: null, fromInterface: true }],
}
function setup(saved = vi.fn()) {
  function Host() {
    const [open, setOpen] = useState(true)
    return <main><p>Daftar OLT tetap terbuka</p>{open && <PonDiscoveryPanel oltId="olt-1" onClose={() => setOpen(false)} onSaved={notice => { saved(notice); setOpen(false) }} />}</main>
  }
  render(<Host />)
  return { user: userEvent.setup(), saved }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(api.get).mockImplementation(async path => path.startsWith('/api/monitoring') ? snapshot : [{ id: 'old', label: 'PON1' }])
})

describe('PON discovery review', () => {
  it('excludes existing ports, preserves selection across review, and only imports once after confirmation', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(api.post).mockImplementation(() => new Promise(done => { resolve = done }))
    const { user, saved } = setup()
    expect(await screen.findByRole('checkbox', { name: 'Pilih PON1' })).toHaveProperty('disabled', true)
    expect(screen.getByRole('checkbox', { name: 'Pilih PON2' })).toHaveProperty('checked', true)
    await user.click(screen.getByRole('checkbox', { name: 'Pilih PON3' }))
    await user.click(screen.getByRole('button', { name: 'Tinjau + buat' }))
    expect(api.post).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Sebelumnya' }))
    expect(screen.getByRole('checkbox', { name: 'Pilih PON3' })).toHaveProperty('checked', false)
    await user.click(screen.getByRole('button', { name: 'Tinjau + buat' }))
    await user.dblClick(screen.getByRole('button', { name: 'Buat' }))
    expect(api.post).toHaveBeenCalledExactlyOnceWith('/api/olts/olt-1/pon-ports/import', { labels: ['PON2'] })
    await user.click(screen.getByRole('button', { name: 'Tutup' }))
    expect(screen.getByRole('dialog')).toBeTruthy()
    await act(async () => { resolve({ created: 1, existing: 0 }) })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(saved).toHaveBeenCalledWith('1 port PON ditambahkan.')
  })

  it('retains the reviewed selection after an import failure and supports an idempotent retry', async () => {
    vi.mocked(api.post).mockRejectedValueOnce(new Error('Koneksi terputus.')).mockResolvedValueOnce({ created: 0, existing: 2 })
    const { user, saved } = setup()
    await screen.findByRole('checkbox', { name: 'Pilih PON2' })
    await user.click(screen.getByRole('button', { name: 'Tinjau + buat' }))
    await user.click(screen.getByRole('button', { name: 'Buat' }))
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Koneksi terputus.')
    expect(saved).not.toHaveBeenCalled()
    await user.click(screen.getByRole('button', { name: 'Buat' }))
    expect(api.post).toHaveBeenNthCalledWith(2, '/api/olts/olt-1/pon-ports/import', { labels: ['PON2', 'PON3'] })
    expect(saved).toHaveBeenCalledWith('Port yang dipilih sudah terdaftar.')
  })

  it('shows failed SNMP reads as errors and allows retry without writing inventory', async () => {
    vi.mocked(api.get).mockRejectedValueOnce(new Error('OLT tidak dapat dihubungi.'))
    const { user } = setup()
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'OLT tidak dapat dihubungi.Coba lagi')
    expect(screen.queryByText('Belum ada port terdeteksi')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Coba lagi' }))
    await screen.findByRole('checkbox', { name: 'Pilih PON2' })
    expect(api.post).not.toHaveBeenCalled()
  })

  it('requires a selection before review', async () => {
    const { user } = setup()
    await user.click(await screen.findByRole('checkbox', { name: 'Pilih semua port baru' }))
    await user.click(screen.getByRole('button', { name: 'Tinjau + buat' }))
    expect(await screen.findByRole('alert')).toHaveProperty('textContent', 'Pilih port yang ingin ditambahkan.')
    expect(api.post).not.toHaveBeenCalled()
  })

  it('closes a populated discovery without a false unsaved-changes prompt or inventory writes', async () => {
    const { user } = setup()
    await screen.findByRole('checkbox', { name: 'Pilih PON2' })
    await user.click(screen.getByRole('button', { name: 'Batal' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.post).not.toHaveBeenCalled()
    expect(screen.getByText('Daftar OLT tetap terbuka')).toBeTruthy()
  })

  it('ignores a read that finishes after closing the panel', async () => {
    let resolve!: (value: unknown) => void
    vi.mocked(api.get).mockImplementation(path => path.startsWith('/api/monitoring') ? new Promise(done => { resolve = done }) : Promise.resolve([]))
    const { user } = setup()
    await user.click(screen.getByRole('button', { name: 'Tutup' }))
    await act(async () => { resolve(snapshot) })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(api.post).not.toHaveBeenCalled()
  })
})
