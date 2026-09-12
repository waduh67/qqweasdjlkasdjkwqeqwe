/**
 * Penyambungan kartu material & penarikan aset ke halaman detail work order.
 *
 * Kedua komponennya sudah punya tes sendiri, tapi tes itu merender komponennya LANGSUNG.
 * Yang tidak terjaga siapa pun adalah kabelnya: prop `type`/`status`/`assignees` yang salah
 * alamat tidak akan membuat TypeScript mengeluh (ketiganya bertipe sama-sama string union
 * atau array) sementara akibatnya persis kebalikan dari yang dimaksud — form penarikan
 * muncul di WO yang bukan pembongkaran, atau tombol tulis tetap ditawarkan di WO batal.
 * Tes inilah yang gagal duluan kalau kabelnya tertukar.
 */
import { cleanup, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const { apiGet, apiPost, apiBlob, can } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  // Foto bukti diambil sebagai byte. Kalau mock-nya memulangkan `undefined`, `.then(...)`
  // di dalamnya melempar dan menjatuhkan seluruh pohon — kegagalan yang tak ada
  // hubungannya dengan apa pun yang diuji di sini.
  apiBlob: vi.fn(() => Promise.resolve(new Blob())),
  can: vi.fn((_permission: string) => true),
}))

vi.mock('@/api/client', () => ({
  api: { get: apiGet, post: apiPost, put: vi.fn(), del: vi.fn(), blob: apiBlob },
  ApiError: class ApiErrorMock extends Error {
    status: number
    constructor(status: number, message: string) {
      super(message)
      this.status = status
    }
  },
}))

vi.mock('@/auth/useCan', () => ({
  useCan: () => ({
    can,
    canAny: (...permissions: string[]) => permissions.some((permission) => can(permission)),
    isPlatformAdmin: false,
  }),
}))

vi.mock('@/system', () => ({
  useConfirm: () => vi.fn(),
  usePrompt: () => vi.fn(),
  useToast: () => ({ error: vi.fn(), success: vi.fn(), info: vi.fn() }),
}))

import { WorkOrderDetailBody } from './WorkOrderDetailBody'
import type { WorkOrderDetail, WorkOrderStatus, WorkOrderType } from '@/api/workorder'

const WO = 'aaaaaaaa-0000-4000-8000-000000000001'
const TECH = '55555555-5555-5555-5555-555555555555'
const ITEM = '11111111-1111-1111-1111-111111111111'

/** Satu baris material yang SUDAH dinamai server — persis bentuk read model-nya. */
const materialRow = {
  id: 'm1',
  workOrderId: WO,
  itemId: ITEM,
  itemCode: 'DRP-100',
  itemName: 'Dropcore 1 core 100m',
  itemCategory: 'CABLE',
  unit: 'ROLL',
  serialized: false,
  templateQuantity: 1,
  plannedQuantity: 1,
  issuedQuantity: 1,
  usedQuantity: 0,
  returnedQuantity: 0,
  lostQuantity: 0,
  unscannedQuantity: 0,
  technicianId: TECH,
  technicianName: 'Sari Melati',
  technicianLocationId: '44444444-4444-4444-4444-444444444444',
  varianceReason: null,
  serials: [],
}

function detailOf(type: WorkOrderType, status: WorkOrderStatus): WorkOrderDetail {
  return {
    workOrder: {
      id: WO,
      code: 'WO-0001',
      type,
      status,
      priority: 'NORMAL',
      title: 'Pasang baru',
      description: null,
      customerId: null,
      customerName: null,
      incidentId: null,
      areaId: null,
      destinationLat: null,
      destinationLng: null,
      assignees: [{ id: TECH, name: 'Sari Melati' }],
      scheduledAt: null,
      assignedAt: null,
      startedAt: null,
      completedAt: null,
      resolutionNote: null,
      cancelReason: null,
      rxBeforeDbm: null,
      rxAfterDbm: null,
      approvalStatus: null,
      approvedBy: null,
      approvedByName: null,
      approvedAt: null,
      approvalNote: null,
      createdAt: '2026-09-01T02:00:00Z',
    },
    timeline: [],
  }
}

function mockApi({ recovered = [] as unknown[] } = {}) {
  apiGet.mockImplementation((path: string) => {
    if (path.includes('/recovered-assets')) return Promise.resolve(recovered)
    if (path.includes('/materials/template')) return Promise.resolve([])
    if (path.includes('/materials')) return Promise.resolve([materialRow])
    // Bentuknya OBJEK, bukan daftar — kartu penyelesaian membaca `proof.artifacts` langsung
    // dan akan meledak kalau diberi array kosong.
    if (path.includes('/proof-of-work')) return Promise.resolve({ artifacts: [], ready: false })
    // Direktori pengguna DIPAGING (`listUserDirectory` membaca `page.content`). Mengembalikan
    // array telanjang di sini membuat daftarnya jadi `undefined` dan komponennya meledak —
    // kegagalan yang terlihat seperti bug produk padahal murni cacat mock.
    if (path.startsWith('/api/users')) return Promise.resolve({ content: [], totalElements: 0 })
    // Lampiran & bukti foto lain: bukan urusan tes ini.
    return Promise.resolve([])
  })
}

function renderBody(type: WorkOrderType, status: WorkOrderStatus) {
  return render(
    <WorkOrderDetailBody
      detail={detailOf(type, status)}
      fetchTechnicians={() => Promise.resolve([])}
      onAct={vi.fn()}
    />,
  )
}

afterEach(() => {
  cleanup()
  apiGet.mockReset()
  apiPost.mockReset()
  can.mockReset()
  can.mockImplementation(() => true)
})

describe('kartu material di halaman detail', () => {
  it('memasang kartu material dengan nama dari read model', async () => {
    mockApi()
    renderBody('PSB', 'IN_PROGRESS')

    expect(await screen.findByText('Dropcore 1 core 100m')).toBeDefined()
    // Kalau id work order salah alamat, request-nya tidak akan pernah menyebut WO ini.
    await waitFor(() =>
      expect(apiGet.mock.calls.some(([path]) => path === `/api/work-orders/${WO}/materials`)).toBe(true),
    )
  })

  it('tidak memasang kartu material tanpa izin baca material', async () => {
    mockApi()
    can.mockImplementation((permission: string) => !permission.startsWith('workorder.material'))
    renderBody('PSB', 'IN_PROGRESS')

    await waitFor(() => expect(screen.queryByText('Material')).toBeNull())
    const paths = apiGet.mock.calls.map(([path]) => path as string)
    expect(paths.some((path) => path.includes('/materials'))).toBe(false)
  })
})

describe('kartu penarikan aset di halaman detail', () => {
  it('menawarkan pencatatan penarikan pada WO DISMANTLE', async () => {
    mockApi()
    renderBody('DISMANTLE', 'IN_PROGRESS')

    expect(await screen.findByText('Penarikan aset pelanggan')).toBeDefined()
    // Prop `assignees` benar-benar diteruskan: pemilih teknisi terisi dari roster WO,
    // bukan dari direktori pengguna.
    expect(await screen.findByLabelText('Nomor seri unit ditarik')).toBeDefined()
  })

  it('menyembunyikan kartu penarikan pada WO yang bukan pembongkaran', async () => {
    mockApi()
    renderBody('PSB', 'IN_PROGRESS')

    // Ditunggu sampai material mendarat supaya "tidak ada" diuji setelah semua muat selesai,
    // bukan pada frame pertama yang memang masih kosong.
    expect(await screen.findByText('Dropcore 1 core 100m')).toBeDefined()
    expect(screen.queryByText('Penarikan aset pelanggan')).toBeNull()
  })
})
