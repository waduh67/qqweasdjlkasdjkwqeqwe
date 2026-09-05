import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { VisitListView } from '@/api/fieldservice'
import { ToastProvider } from '@/system'

const { checkInVisit, checkOutVisit, listVisits, markVisitOnSite, submitVisit } = vi.hoisted(() => ({
  checkInVisit: vi.fn(),
  checkOutVisit: vi.fn(),
  listVisits: vi.fn(),
  markVisitOnSite: vi.fn(),
  submitVisit: vi.fn(),
}))

vi.mock('@/api/fieldservice', () => ({ checkInVisit, checkOutVisit, listVisits, markVisitOnSite, submitVisit }))

import { MyVisitsPage } from './MyVisitsPage'

const plannedVisit: VisitListView = {
  id: 'visit-1',
  workOrderId: 'work-order-1',
  orderId: 'order-1',
  state: 'PLANNED',
  revision: 7,
  attendanceDecision: null,
  serverReceivedAt: null,
  scheduledAt: '2026-09-05T08:00:00Z',
  session: { startedAt: null, endedAt: null, submittedAt: null },
}

function renderPage() {
  return render(<ToastProvider><MyVisitsPage /></ToastProvider>)
}

describe('MyVisitsPage', () => {
  it('memuat daftar kunjungan teknisi dari proyeksi server', async () => {
    listVisits.mockResolvedValueOnce({ content: [plannedVisit] })

    renderPage()

    await screen.findByText('Work order work-order-1')
    expect(listVisits).toHaveBeenCalledOnce()
  })

  it('menampilkan keadaan kosong ketika server tidak menugaskan kunjungan', async () => {
    listVisits.mockResolvedValueOnce({ content: [] })

    renderPage()

    await screen.findByText('Belum ada kunjungan')
  })

  it('mengirim check-in dengan revisi dan keputusan kehadiran yang berlaku', async () => {
    listVisits.mockResolvedValueOnce({ content: [plannedVisit] }).mockResolvedValueOnce({ content: [] })
    checkInVisit.mockResolvedValueOnce({})

    renderPage()
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Check-in' }))

    await waitFor(() => expect(checkInVisit).toHaveBeenCalledWith('visit-1', 7, 'ACCEPTED', null))
    await screen.findByText('Check-in berhasil')
  })

  it('menyampaikan konflik revisi 409 tanpa menganggap aksi berhasil', async () => {
    listVisits.mockResolvedValueOnce({ content: [plannedVisit] })
    checkInVisit.mockRejectedValueOnce(new ApiError(409, 'Revisi kunjungan sudah berubah'))

    renderPage()
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Check-in' }))

    await screen.findByText('Revisi kunjungan sudah berubah')
    expect(listVisits).toHaveBeenCalledOnce()
  })

  it('menyampaikan penolakan izin 403 tanpa menganggap aksi berhasil', async () => {
    listVisits.mockResolvedValueOnce({ content: [plannedVisit] })
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
