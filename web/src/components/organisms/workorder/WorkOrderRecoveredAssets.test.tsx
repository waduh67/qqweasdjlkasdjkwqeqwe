import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const { apiGet, apiPost, can, prompt, ApiErrorMock } = vi.hoisted(() => {
  class ApiErrorMock extends Error {
    status: number
    constructor(status: number, message: string) {
      super(message)
      this.status = status
    }
  }
  return {
    apiGet: vi.fn(),
    apiPost: vi.fn(),
    can: vi.fn((_permission: string) => true),
    prompt: vi.fn(),
    ApiErrorMock,
  }
})

vi.mock('@/api/client', () => ({
  api: { get: apiGet, post: apiPost, put: vi.fn(), del: vi.fn() },
  ApiError: ApiErrorMock,
}))

vi.mock('@/auth/useCan', () => ({
  useCan: () => ({
    can,
    canAny: (...permissions: string[]) => permissions.some((permission) => can(permission)),
    isPlatformAdmin: false,
  }),
}))

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() }

vi.mock('@/system', () => ({
  useConfirm: () => vi.fn(),
  usePrompt: () => prompt,
  useToast: () => toast,
}))

import { WorkOrderRecoveredAssets } from './WorkOrderRecoveredAssets'

const WO = 'aaaaaaaa-0000-4000-8000-000000000001'
const ITEM_ONT = '11111111-1111-1111-1111-111111111111'
const ASSET_ONE = '99999999-9999-9999-9999-999999999999'
const LOC_VAN = '44444444-4444-4444-4444-444444444444'
const USER_SARI = '66666666-6666-6666-6666-666666666666'
const USER_BUDI = '55555555-5555-5555-5555-555555555555'
const USER_TONO = '77777777-7777-7777-7777-777777777777'
const CUSTOMER = 'cccccccc-cccc-cccc-cccc-cccccccccccc'

/** Bentuk `WorkOrderVanLocationView`: sengaja cuma id + kode, bukan `LocationView` gudang. */
const vans = [{ id: LOC_VAN, code: 'VAN-01' }]

const assignees = [{ id: USER_SARI, name: 'Sari Melati' }]

/**
 * Bentuk baris ini mencerminkan read model server SETELAH resolusi nama: `itemName`,
 * `technicianName`, `technicianLocationCode`, `recoveredByName`, dan `cancelledByName` datang
 * dari server. Kalau fixture-nya dipangkas jadi id saja, tes di bawah gagal — itu memang gunanya.
 */
const row = (over: Record<string, unknown> = {}) => ({
  id: 'rec-1',
  workOrderId: WO,
  assetId: ASSET_ONE,
  serialNumber: 'SN-ONT-001',
  macAddress: 'AA:BB:CC:DD:EE:01',
  itemId: ITEM_ONT,
  itemCode: 'ONT-001',
  itemName: 'ONT ZTE F660',
  itemCategory: 'ONT',
  customerId: CUSTOMER,
  technicianId: USER_SARI,
  technicianName: 'Sari Melati',
  technicianLocationId: LOC_VAN,
  technicianLocationCode: 'VAN-01',
  condition: 'GOOD',
  note: null,
  recoveredAt: '2026-09-01T02:00:00Z',
  recoveredBy: USER_BUDI,
  recoveredByName: 'Budi Santoso',
  cancelledAt: null,
  cancelledBy: null,
  cancelledByName: null,
  cancelReason: null,
  ...over,
})

/**
 * Potret teknisi lapangan sungguhan: `/item-master`, `/api/users`, DAN master lokasi gudang
 * sama-sama 403. Daftar van hanya dijawab dari endpoint sempit milik work order — kalau
 * seseorang mengembalikan `/api/inventory/locations`, pemilih vannya langsung mati di sini.
 */
function mockApi(rows: unknown[], overrides: Record<string, unknown | Error> = {}) {
  apiGet.mockImplementation((path: string) => {
    for (const [fragment, value] of Object.entries(overrides)) {
      if (path.includes(fragment)) {
        return value instanceof Error ? Promise.reject(value) : Promise.resolve(value)
      }
    }
    if (path.startsWith('/api/inventory/item-master')) return Promise.reject(new ApiErrorMock(403, 'forbidden'))
    if (path.startsWith('/api/users')) return Promise.reject(new ApiErrorMock(403, 'forbidden'))
    if (path.startsWith('/api/inventory/locations')) return Promise.reject(new ApiErrorMock(403, 'forbidden'))
    if (path.includes('/van-locations')) return Promise.resolve(vans)
    if (path.includes('/recovered-assets')) return Promise.resolve(rows)
    return Promise.reject(new Error(`tak terduga: ${path}`))
  })
}

function renderPanel(props: Partial<Parameters<typeof WorkOrderRecoveredAssets>[0]> = {}) {
  return render(
    <WorkOrderRecoveredAssets
      workOrderId={WO}
      type="DISMANTLE"
      status="IN_PROGRESS"
      assignees={assignees}
      {...props}
    />,
  )
}

/** Melepas microtask supaya "tidak merender apa pun" diuji SETELAH datanya mendarat. */
async function settle() {
  await waitFor(() => expect(apiGet).toHaveBeenCalled())
  await act(async () => {
    await Promise.resolve()
    await Promise.resolve()
  })
}

afterEach(() => {
  cleanup()
  apiGet.mockReset()
  apiPost.mockReset()
  prompt.mockReset()
  can.mockReset()
  can.mockImplementation(() => true)
  toast.error.mockReset()
  toast.success.mockReset()
})

describe('penamaan baris', () => {
  it('menamai item, teknisi, dan pencatat dari read model meski master item dan direktori pengguna 403', async () => {
    // Teknisi lapangan tidak punya `inventory.item.view` maupun `iam.user.view`. Kalau kelak
    // seseorang mengembalikan penggabungan id → nama di klien, layar ini kembali mencetak UUID
    // untuk orang yang justru paling butuh membacanya — dan tes inilah yang gagal duluan.
    mockApi([row({ note: 'Casing retak' })])
    renderPanel()

    expect(await screen.findByText('ONT ZTE F660')).toBeDefined()
    expect(screen.getByText('ONT-001')).toBeDefined()
    expect(screen.getByText('SN-ONT-001')).toBeDefined()
    expect(screen.getByText('AA:BB:CC:DD:EE:01')).toBeDefined()
    // Dua kali: di pemilih teknisi (dari roster WO) dan di barisnya (dari read model).
    expect(screen.getAllByText('Sari Melati').length).toBe(2)
    expect(screen.getByText('oleh Budi Santoso')).toBeDefined()
    expect(screen.getByText('Casing retak')).toBeDefined()

    expect(screen.queryByText(ITEM_ONT)).toBeNull()
    expect(screen.queryByText(USER_SARI)).toBeNull()
    expect(screen.queryByText(USER_BUDI)).toBeNull()
    expect(screen.queryByText(ASSET_ONE)).toBeNull()
    // Dua endpoint terlarang itu tidak boleh disentuh sama sekali.
    const paths = apiGet.mock.calls.map(([path]) => path as string)
    expect(paths.some((path) => path.startsWith('/api/inventory/item-master'))).toBe(false)
    expect(paths.some((path) => path.startsWith('/api/users'))).toBe(false)
  })
})

describe('menampilkan diri sendiri', () => {
  it('tidak merender apa pun untuk WO non-DISMANTLE tanpa baris penarikan', async () => {
    mockApi([])
    const { container } = renderPanel({ type: 'PSB' })
    await settle()

    expect(container.innerHTML).toBe('')
    expect(screen.queryByText('Penarikan aset pelanggan')).toBeNull()
  })

  it('tetap merender baris lama saat tipe WO sudah bukan DISMANTLE lagi', async () => {
    // Tipe yang pernah salah lalu diperbaiki tidak boleh melenyapkan baris yang sudah tercatat:
    // baris itulah satu-satunya penjelasan kenapa ada unit nyangkut di van teknisi.
    mockApi([row()])
    renderPanel({ type: 'REPAIR' })

    expect(await screen.findByText('SN-ONT-001')).toBeDefined()
    expect(screen.getByText('ONT ZTE F660')).toBeDefined()
    // Form scan tetap tidak ditawarkan — tiket ini memang bukan pembongkaran.
    expect(screen.queryByLabelText('Nomor seri unit ditarik')).toBeNull()
  })

  it('tidak merender apa pun dan tidak memanggil server tanpa izin baca maupun catat', async () => {
    // Izinnya sudah bisa diketahui di klien, jadi request yang PASTI dijawab 403 tak perlu
    // diberangkatkan sama sekali. Kartunya juga tak boleh muncul kosong: "belum ada unit yang
    // ditarik" adalah klaim yang tak pernah kita verifikasi ke server.
    mockApi([row()])
    can.mockImplementation(() => false)
    const { container } = renderPanel()
    await act(async () => {
      await Promise.resolve()
      await Promise.resolve()
    })

    expect(container.innerHTML).toBe('')
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('mengaku tidak bisa membaca daftarnya saat hanya punya izin catat', async () => {
    // Peran rakitan tangan bisa saja memegang `.record` tanpa `.view`. Yang TIDAK boleh
    // terjadi: teknisi mencatat penarikan lalu membaca "belum ada unit yang tercatat ditarik"
    // dan menyimpulkan scan-nya gagal, padahal daftarnya memang tak pernah diminta.
    mockApi([row()])
    can.mockImplementation((permission: string) => permission === 'workorder.material.record')
    renderPanel()

    expect(await screen.findByText(/butuh izin workorder\.material\.view/)).toBeDefined()
    expect(screen.getByLabelText('Nomor seri unit ditarik')).toBeDefined()
    const paths = apiGet.mock.calls.map(([path]) => path as string)
    expect(paths.some((path) => path.includes('/recovered-assets'))).toBe(false)
  })
})

describe('baris yang dibatalkan', () => {
  it('tetap menampilkan baris batal lengkap dengan alasan dan nama pembatalnya', async () => {
    mockApi([
      row({
        id: 'rec-2',
        cancelledAt: '2026-09-02T03:00:00Z',
        cancelledBy: USER_TONO,
        cancelledByName: 'Tono Wijaya',
        cancelReason: 'Salah scan, unit milik pelanggan lain',
      }),
    ])
    renderPanel()

    expect(await screen.findByText('SN-ONT-001')).toBeDefined()
    expect(screen.getByText('Dibatalkan')).toBeDefined()
    expect(screen.getByText(/Tono Wijaya/)).toBeDefined()
    expect(screen.getByText('Alasan: Salah scan, unit milik pelanggan lain')).toBeDefined()
    // Barisnya tetap terbaca, dan tak ada tombol batal kedua untuk baris yang sudah batal.
    expect(screen.queryByRole('button', { name: 'Batalkan' })).toBeNull()
  })

  it('meminta alasan lalu mengirim pembatalan', async () => {
    mockApi([row()])
    prompt.mockResolvedValue('Salah scan')
    apiPost.mockResolvedValue(
      row({ cancelledAt: '2026-09-02T03:00:00Z', cancelledByName: 'Tono Wijaya', cancelReason: 'Salah scan' }),
    )
    const actor = userEvent.setup()
    renderPanel()

    await actor.click(await screen.findByRole('button', { name: 'Batalkan' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    const [path, body] = apiPost.mock.calls[0] as [string, { reason: string | null }]
    expect(path).toBe(`/api/work-orders/${WO}/materials/recovered-assets/rec-1/cancel`)
    expect(body.reason).toBe('Salah scan')
    expect(await screen.findByText('Alasan: Salah scan')).toBeDefined()
  })
})

describe('WO yang sudah selesai', () => {
  it('tidak menawarkan pembatalan aktif saat status DONE', async () => {
    // Server masih menerima pembatalan setelah WO disetujui padahal saldonya sudah bergerak
    // (celah yang tercatat di docs/serah-terima-gudang-pesanan.md §4). Ditutup dari sisi klien.
    mockApi([row()])
    renderPanel({ status: 'DONE' })

    const button = await screen.findByRole('button', { name: 'Batalkan' })
    expect(button.hasAttribute('disabled')).toBe(true)
    expect(button.getAttribute('title')).toContain('penyesuaian stok')
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('tidak menawarkan aksi tulis untuk WO yang dibatalkan', async () => {
    mockApi([row()])
    renderPanel({ status: 'CANCELLED' })

    expect(await screen.findByText('SN-ONT-001')).toBeDefined()
    expect(screen.queryByLabelText('Nomor seri unit ditarik')).toBeNull()
    expect(screen.queryByRole('button', { name: 'Batalkan' })).toBeNull()
  })
})

describe('form scan', () => {
  it('mencatat penarikan saat Enter ditekan lalu mengembalikan fokus ke kolom serial', async () => {
    mockApi([])
    apiPost.mockResolvedValue(row({ serialNumber: 'SN-ONT-777' }))
    const actor = userEvent.setup()
    renderPanel()

    const serial = await screen.findByLabelText('Nomor seri unit ditarik')
    await actor.type(serial, 'SN-ONT-777{Enter}')

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    const [path, body] = apiPost.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe(`/api/work-orders/${WO}/materials/recovered-assets`)
    expect(body).toEqual({
      serialNumber: 'SN-ONT-777',
      // Teknisi datang dari roster WO, bukan dari direktori pengguna.
      technicianId: USER_SARI,
      technicianLocationId: LOC_VAN,
      condition: 'GOOD',
      note: null,
    })
    // Siap dipindai unit berikutnya tanpa menyentuh layar.
    await waitFor(() => expect(document.activeElement).toBe(serial))
    expect((serial as HTMLInputElement).value).toBe('')
  })

  it('menampilkan pesan penolakan server apa adanya', async () => {
    mockApi([])
    apiPost.mockRejectedValue(new ApiErrorMock(409, 'Unit SN-ONT-777 sudah tercatat ditarik di work order lain'))
    const actor = userEvent.setup()
    renderPanel()

    await actor.type(await screen.findByLabelText('Nomor seri unit ditarik'), 'SN-ONT-777{Enter}')

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('Unit SN-ONT-777 sudah tercatat ditarik di work order lain'),
    )
  })

  it('memuat daftar van dari endpoint work order, bukan master lokasi gudang', async () => {
    // Pemilih vannya dijaga `workorder.material.record` — izin yang memang dipegang teknisi.
    // `/api/inventory/locations` menuntut `inventory.location.view` yang membuka gudang, bin,
    // dan seluruh master data gudang; menyentuhnya di sini membuat formulir ini tak bisa
    // disubmit justru oleh orang yang mencabut ONT-nya. Tes ini menjaga agar tak ada yang
    // diam-diam mengembalikannya.
    mockApi([])
    renderPanel()

    const picker = (await screen.findByLabelText('Van teknisi')) as HTMLSelectElement
    expect(Array.from(picker.options).map((option) => option.textContent)).toEqual(['Pilih van…', 'VAN-01'])
    // Satu van di daftar = langsung terpilih, tak ada yang perlu diketuk teknisi.
    expect(picker.value).toBe(LOC_VAN)

    const paths = apiGet.mock.calls.map(([path]) => path as string)
    expect(paths).toContain(`/api/work-orders/${WO}/materials/van-locations`)
    expect(paths.some((path) => path.includes('/api/inventory/locations'))).toBe(false)
    // Tak ada alarm: daftarnya memang berhasil dimuat.
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('mengaku gagal memuat daftar van tanpa menyuruh minta izin yang bukan jawabannya', async () => {
    // Pemegang `workorder.material.record` semestinya SUDAH bisa memuat daftar ini, jadi
    // kegagalannya berarti peran yang tak lengkap atau server bermasalah — bukan lagi
    // `inventory.location.view`, dan menyebut izin itu akan mengirim orangnya mengejar
    // sesuatu yang tak akan menolongnya. Kartunya tetap utuh.
    mockApi([row()], { '/van-locations': new ApiErrorMock(403, 'forbidden') })
    renderPanel()

    const notice = await screen.findByRole('alert')
    expect(notice.textContent).toContain('gagal dimuat')
    expect(notice.textContent).toContain('workorder.material.record')
    expect(notice.textContent).not.toContain('inventory.location.view')
    // Dibedakan dari "belum ada van terdaftar": dua keadaan itu menuntut tindakan berbeda.
    expect(notice.textContent).not.toContain('Belum ada lokasi berjenis kendaraan')
    // Kartunya tidak rusak: daftar penarikan tetap terbaca.
    expect(screen.getByText('SN-ONT-001')).toBeDefined()
    expect(screen.getByText('ONT ZTE F660')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Catat penarikan' }).hasAttribute('disabled')).toBe(true)
  })

  it('membedakan "belum ada van terdaftar" dari "gagal memuat"', async () => {
    mockApi([], { '/van-locations': [] })
    renderPanel()

    const notice = await screen.findByRole('alert')
    expect(notice.textContent).toContain('Belum ada lokasi berjenis kendaraan')
    expect(notice.textContent).not.toContain('gagal dimuat')
  })
})

describe('kolom van', () => {
  it('menampilkan kode van dari read model, bukan UUID lokasinya', async () => {
    // `technicianLocationCode` dibawa server justru supaya klien tak perlu menukar
    // `technicianLocationId` lewat `/api/inventory/locations` — endpoint yang pembacanya
    // tak punya izinnya, dan yang dulu membuat layar gudang memajang UUID telanjang.
    mockApi([row()])
    renderPanel()

    expect(await screen.findByText('SN-ONT-001')).toBeDefined()
    expect(screen.getByRole('columnheader', { name: 'Van' })).toBeDefined()
    // Dua kali: di pemilih van (dari endpoint work order) dan di barisnya (dari read model).
    expect(screen.getAllByText('VAN-01').length).toBe(2)
    expect(screen.queryByText(LOC_VAN)).toBeNull()

    const paths = apiGet.mock.calls.map(([path]) => path as string)
    expect(paths.some((path) => path.includes('/api/inventory/locations'))).toBe(false)
  })
})
