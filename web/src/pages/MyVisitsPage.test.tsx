import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { VisitListView } from '@/api/fieldservice'
import { MemoryRouter } from 'react-router-dom'
import { ToastProvider } from '@/system'

const { checkInVisit, checkOutVisit, listVisits, markVisitOnSite, submitVisit } = vi.hoisted(() => ({
  checkInVisit: vi.fn(),
  checkOutVisit: vi.fn(),
  listVisits: vi.fn(),
  markVisitOnSite: vi.fn(),
  submitVisit: vi.fn(),
}))

vi.mock('@/api/fieldservice', () => ({ checkInVisit, checkOutVisit, listVisits, markVisitOnSite, submitVisit }))

vi.mock('@/auth/useCan', () => ({ useCan: () => ({ can: () => true }) }))
vi.mock('@/auth/useAuth', () => ({ useAuth: () => ({ user: { tenantId: 'tenant', id: 'technician' } }) }))

import { MyVisitsPage } from './MyVisitsPage'

const plannedVisit: VisitListView = {
  id: 'visit-1',
  workOrderId: 'work-order-1',
  orderId: 'order-1',
  state: 'PLANNED',
  revision: 7,
  workOrderCode: 'WO-0001', title: 'Perbaikan koneksi kantor', customerName: 'Kantor BJM', areaName: 'Banjarmasin', purpose: 'WORK_ORDER', actionable: true,
  scheduledAt: '2026-09-05T08:00:00Z',
  session: { startedAt: null, endedAt: null, submittedAt: null },
}

function renderPage() {
  return render(<MemoryRouter><ToastProvider><MyVisitsPage /></ToastProvider></MemoryRouter>)
}

describe('MyVisitsPage', () => {
  it('memisahkan riwayat penugasan lama dan tidak menawarkan aksi', async () => {
    listVisits.mockResolvedValueOnce({ content: [], page: 0, size: 25, totalElements: 0 })
      .mockResolvedValueOnce({ content: [{ ...plannedVisit, actionable: false }], page: 0, size: 25, totalElements: 1 })
    renderPage()
    await screen.findByText('Belum ada kunjungan aktif')
    await userEvent.setup().click(screen.getByRole('tab', { name: 'Riwayat' }))
    await screen.findByText('WO-0001')
    expect(listVisits).toHaveBeenLastCalledWith('HISTORY', 0)
    expect(screen.queryByRole('button', { name: 'Check-in' })).toBeNull()
    expect(screen.getByRole('link', { name: 'WO-0001' }).getAttribute('href')).toBe('/my-work-orders/work-order-1')
  })
  it('memuat daftar kunjungan teknisi dari proyeksi server', async () => {
    listVisits.mockResolvedValueOnce({ content: [plannedVisit], page: 0, size: 25, totalElements: 1 })

    renderPage()

    await screen.findByText('WO-0001')
    expect(listVisits).toHaveBeenCalledOnce()
  })

  it('menampilkan keadaan kosong ketika server tidak menugaskan kunjungan', async () => {
    listVisits.mockResolvedValueOnce({ content: [], page: 0, size: 25, totalElements: 0 })

    renderPage()

    await screen.findByText('Belum ada kunjungan aktif')
  })

  it('mengirim check-in dengan revisi dan keputusan kehadiran yang berlaku', async () => {
    listVisits.mockResolvedValueOnce({ content: [plannedVisit], page: 0, size: 25, totalElements: 1 }).mockResolvedValueOnce({ content: [], page: 0, size: 25, totalElements: 0 })
    checkInVisit.mockResolvedValueOnce({})

    renderPage()
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Check-in' }))

    await waitFor(() => expect(checkInVisit).toHaveBeenCalledWith('visit-1', 7, 'ACCEPTED', null))
    await screen.findByText('Check-in berhasil')
  })

  it('menyampaikan konflik revisi 409 tanpa menganggap aksi berhasil', async () => {
    listVisits.mockResolvedValueOnce({ content: [plannedVisit], page: 0, size: 25, totalElements: 1 })
    checkInVisit.mockRejectedValueOnce(new ApiError(409, 'Revisi kunjungan sudah berubah'))

    renderPage()
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Check-in' }))

    await screen.findByText('Revisi kunjungan sudah berubah')
    expect(listVisits).toHaveBeenCalledOnce()
  })

  it('menyampaikan penolakan izin 403 tanpa menganggap aksi berhasil', async () => {
    listVisits.mockResolvedValueOnce({ content: [plannedVisit], page: 0, size: 25, totalElements: 1 })
    checkInVisit.mockRejectedValueOnce(new ApiError(403, 'Kunjungan tidak diizinkan'))

    renderPage()
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Check-in' }))

    await screen.findByText('Kunjungan tidak diizinkan')
    expect(listVisits).toHaveBeenCalledOnce()
  })
})

afterEach(() => {
  vi.restoreAllMocks()
  checkInVisit.mockReset()
  checkOutVisit.mockReset()
  listVisits.mockReset()
  markVisitOnSite.mockReset()
  submitVisit.mockReset()
})
