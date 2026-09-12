import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'

const { apiGet, apiPost, apiPut, can, confirm } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  can: vi.fn((_permission: string) => true),
  // Bawaannya MENOLAK: tes yang lupa menyetujui konfirmasi harus gagal karena requestnya tak
  // pernah berangkat, bukan diam-diam lulus karena dialognya dilewati.
  confirm: vi.fn(async (_options: unknown) => false),
}))

vi.mock('@/api/client', () => ({
  api: { get: apiGet, post: apiPost, put: apiPut, del: vi.fn() },
  // Bentuknya SETIA pada `ApiError` sungguhan (status lebih dulu, lalu pesan) supaya tes yang
  // memeriksa pemajangan pesan server tidak diam-diam memajang angka status.
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

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() }

vi.mock('@/system', () => ({
  useConfirm: () => confirm,
  useToast: () => toast,
}))

import { WorkOrderMaterials } from './WorkOrderMaterials'

const WO = 'aaaaaaaa-0000-0000-0000-000000000000'
const ITEM_ONT = '11111111-1111-1111-1111-111111111111'
const ITEM_DROP = '22222222-2222-2222-2222-222222222222'
const LOC_GUDANG = '33333333-3333-3333-3333-333333333333'
const LOC_VAN = '44444444-4444-4444-4444-444444444444'
const USER_BUDI = '55555555-5555-5555-5555-555555555555'
const USER_SARI = '66666666-6666-6666-6666-666666666666'

/**
 * Bentuk baris ini mencerminkan read model server SETELAH resolusi nama: `itemName`,
 * `technicianName`, dan `serials[].scannedByName` datang dari server. Kalau fixture ini
 * dipangkas kembali jadi id saja, tes di bawah gagal — itu memang gunanya.
 */
const ontRow = {
  id: 'row-ont',
  workOrderId: WO,
  itemId: ITEM_ONT,
  itemCode: 'ONT-001',
  itemName: 'ONT ZTE F660',
  itemCategory: 'ONT',
  unit: 'PCS',
  serialized: true,
  templateQuantity: 1,
  plannedQuantity: 1,
  issuedQuantity: 2,
  usedQuantity: 1,
  returnedQuantity: 0,
  lostQuantity: 0,
  unscannedQuantity: 1,
  technicianId: USER_SARI,
  technicianName: 'Sari Melati',
  technicianLocationId: LOC_VAN,
  varianceReason: null,
  serials: [
    {
      id: 'serial-1',
      assetId: 'asset-1',
      serialNumber: 'SN-001',
      macAddress: 'AA:BB:CC:DD:EE:FF',
      outcome: 'INSTALLED',
      scannedAt: '2026-09-01T02:00:00Z',
      scannedBy: USER_BUDI,
      scannedByName: 'Budi Santoso',
    },
  ],
}

const dropRow = {
  id: 'row-drop',
  workOrderId: WO,
  itemId: ITEM_DROP,
  itemCode: 'DRP-150',
  itemName: 'Dropcore 150 meter',
  itemCategory: 'DROPCORE',
  unit: 'ROLL',
  serialized: false,
  templateQuantity: 2,
  plannedQuantity: 2,
  issuedQuantity: 2,
  usedQuantity: 0,
  returnedQuantity: 0,
  lostQuantity: 0,
  unscannedQuantity: 0,
  technicianId: null,
  technicianName: null,
  technicianLocationId: null,
  varianceReason: null,
  serials: [],
}

const template = [
  {
    itemId: ITEM_ONT,
    itemCode: 'ONT-001',
    itemName: 'ONT ZTE F660',
    itemCategory: 'ONT',
    unit: 'PCS',
    serialized: true,
    plannedQuantity: 1,
    note: null,
  },
  {
    itemId: ITEM_DROP,
    itemCode: 'DRP-150',
    itemName: 'Dropcore 150 meter',
    itemCategory: 'DROPCORE',
    unit: 'ROLL',
    serialized: false,
    plannedQuantity: 2,
    note: 'Sesuaikan jarak tiang',
  },
]

const locations = [
  { id: LOC_GUDANG, code: 'GD-PUSAT', kind: 'WAREHOUSE', parentId: null },
  { id: LOC_VAN, code: 'VAN-01', kind: 'VEHICLE', parentId: null },
]

const user = (id: string, name: string) => ({
  id,
  email: `${name.toLowerCase().replace(/\s+/g, '.')}@example.test`,
  name,
  status: 'ACTIVE',
  platformAdmin: false,
  roleIds: [],
  areaIds: [],
  createdAt: '2026-01-01T00:00:00Z',
  twoFactorEnabled: false,
})

const users = [user(USER_BUDI, 'Budi Santoso'), user(USER_SARI, 'Sari Melati')]

/** Semua direktori tersedia — potret operator gudang lengkap. */
function mockApi(rows: unknown[] = [ontRow, dropRow]) {
  apiGet.mockImplementation((path: string) => {
    if (path === `/api/work-orders/${WO}/materials`) return Promise.resolve(rows)
    if (path === `/api/work-orders/${WO}/materials/template`) return Promise.resolve(template)
    if (path.startsWith('/api/inventory/locations')) return Promise.resolve(locations)
    if (path.startsWith('/api/users')) {
      return Promise.resolve({ content: users, page: 0, size: 200, totalElements: users.length, totalPages: 1 })
    }
    return Promise.reject(new Error(`tak terduga: ${path}`))
  })
}

async function renderCard(status: 'IN_PROGRESS' | 'DONE' = 'IN_PROGRESS') {
  const actor = userEvent.setup()
  render(<WorkOrderMaterials workOrderId={WO} status={status} />)
  await screen.findByRole('table', { name: 'Material work order' })
  return actor
}

/**
 * Isi kepala formulir pengeluaran. Pemilih lokasi & pengguna dimuat terpisah dari tabel
 * material (kegagalannya ditelan), jadi tunggu dulu sampai pilihannya benar-benar ada.
 */
async function fillIssueHeader(actor: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('option', { name: 'GD-PUSAT' })
  await actor.selectOptions(screen.getByLabelText('Lokasi gudang asal'), LOC_GUDANG)
  await actor.selectOptions(screen.getByLabelText('Pemegang custody'), USER_BUDI)
  await actor.selectOptions(screen.getByLabelText('Teknisi'), USER_SARI)
  await actor.selectOptions(screen.getByLabelText('Lokasi van teknisi'), LOC_VAN)
}

afterEach(() => {
  apiGet.mockReset()
  apiPost.mockReset()
  apiPut.mockReset()
  can.mockReset()
  can.mockImplementation(() => true)
  confirm.mockReset()
  confirm.mockImplementation(async () => false)
  toast.error.mockReset()
  toast.success.mockReset()
  cleanup()
})

describe('tabel material', () => {
  it('menamai item, teknisi, dan pemindai dari read model meski master item dan direktori pengguna ditolak', async () => {
    // Potret teknisi lapangan sungguhan: TANPA `inventory.item.view` dan TANPA `iam.user.view`.
    // Kalau seseorang kelak mengembalikan penggabungan id → nama di klien, layar ini kembali
    // mencetak UUID untuk orang yang paling sering membacanya — dan tes inilah yang gagal
    // duluan, bukan teknisinya yang mengeluh dari atas tangga.
    can.mockImplementation((permission: string) => permission.startsWith('workorder.material.'))
    apiGet.mockImplementation((path: string) => {
      if (path === `/api/work-orders/${WO}/materials`) return Promise.resolve([ontRow, dropRow])
      if (path === `/api/work-orders/${WO}/materials/template`) return Promise.resolve(template)
      if (path.startsWith('/api/inventory/item-master')) return Promise.reject(new Error('forbidden'))
      if (path.startsWith('/api/users')) return Promise.reject(new Error('forbidden'))
      if (path.startsWith('/api/inventory/locations')) return Promise.reject(new Error('forbidden'))
      return Promise.reject(new Error(`tak terduga: ${path}`))
    })
    await renderCard()

    expect(screen.getAllByText('ONT ZTE F660').length).toBeGreaterThan(0)
    expect(screen.getAllByText('ONT-001').length).toBeGreaterThan(0)
    expect(screen.getByText('Sari Melati')).toBeDefined()
    expect(screen.getByText('SN-001')).toBeDefined()
    // Nama pemindai dari `serials[].scannedByName`, bukan dari `/api/users` yang barusan 403.
    expect(screen.getByText('Budi Santoso')).toBeDefined()

    expect(screen.queryByText(ITEM_ONT)).toBeNull()
    expect(screen.queryByText(ITEM_DROP)).toBeNull()
    expect(screen.queryByText(USER_BUDI)).toBeNull()
    expect(screen.queryByText(USER_SARI)).toBeNull()

    // Master item tidak boleh disentuh sama sekali — bukan sekadar "ditelan kalau gagal".
    const paths = apiGet.mock.calls.map(([path]) => path as string)
    expect(paths.some((path) => path.startsWith('/api/inventory/item-master'))).toBe(false)
  })

  it('menonjolkan unit yang belum di-scan karena angka itulah yang menahan penyelesaian WO', async () => {
    mockApi()
    await renderCard()

    expect(screen.getByText('1 unit belum di-scan')).toBeDefined()
    expect(screen.getByText('1 belum di-scan')).toBeDefined()
    expect(
      screen.getByText(/Work order tidak bisa diselesaikan sampai nasib setiap unit berserial/),
    ).toBeDefined()
  })

  it('menyembunyikan peringatan saat semua unit sudah dideklarasikan', async () => {
    mockApi([{ ...ontRow, unscannedQuantity: 0 }, dropRow])
    await renderCard()

    expect(screen.queryByText(/unit belum di-scan/)).toBeNull()
  })
})

describe('izin dan status', () => {
  it('tidak merender apa pun tanpa izin baca material', () => {
    can.mockImplementation((permission: string) => permission !== 'workorder.material.view')
    mockApi()
    const { container } = render(<WorkOrderMaterials workOrderId={WO} status="IN_PROGRESS" />)

    expect(container.firstChild).toBeNull()
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('menampilkan data tanpa aksi tulis saat work order sudah selesai', async () => {
    mockApi()
    await renderCard('DONE')

    expect(screen.getAllByText('ONT ZTE F660').length).toBeGreaterThan(0)
    expect(screen.queryByRole('button', { name: 'Keluarkan material' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Catat pemakaian' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Simpan scan' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Pakai BOM apa adanya' })).toBeNull()
  })
})

describe('rencana material', () => {
  it('mengirim daftar kosong saat BOM dipakai apa adanya — pra-isi, bukan pengosongan', async () => {
    mockApi()
    apiPut.mockResolvedValue([ontRow, dropRow])
    const actor = await renderCard()

    await actor.click(screen.getByRole('button', { name: 'Pakai BOM apa adanya' }))

    await waitFor(() => expect(apiPut).toHaveBeenCalled())
    expect(apiPut.mock.calls[0]).toEqual([`/api/work-orders/${WO}/materials`, { lines: [] }])
  })

  it('meminta konfirmasi sebelum mengosongkan rencana dan tidak mengirim apa pun bila ditolak', async () => {
    mockApi()
    confirm.mockResolvedValue(false)
    const actor = await renderCard()

    await actor.click(screen.getByRole('button', { name: 'Kosongkan rencana' }))

    await waitFor(() => expect(confirm).toHaveBeenCalled())
    expect(apiPut).not.toHaveBeenCalled()
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('mengirim clear yang eksplisit saat pengosongan disetujui — bukan daftar kosong yang berarti "pakai BOM"', async () => {
    mockApi()
    confirm.mockResolvedValue(true)
    apiPut.mockResolvedValue([])
    const actor = await renderCard()

    await actor.click(screen.getByRole('button', { name: 'Kosongkan rencana' }))

    await waitFor(() => expect(apiPut).toHaveBeenCalled())
    // `{ lines: [] }` saja akan MENGISI rencana dari BOM — kebalikan persis dari yang diminta.
    expect(apiPut.mock.calls[0]).toEqual([`/api/work-orders/${WO}/materials`, { lines: [], clear: true }])
  })

  it('menampilkan penolakan server apa adanya saat rencana tidak boleh dikosongkan', async () => {
    mockApi()
    confirm.mockResolvedValue(true)
    const { ApiError } = await import('@/api/client')
    apiPut.mockRejectedValue(
      new ApiError(409, 'Rencana tidak bisa dikosongkan: 2 ONT ZTE F660 sudah keluar gudang.'),
    )
    const actor = await renderCard()

    await actor.click(screen.getByRole('button', { name: 'Kosongkan rencana' }))

    await waitFor(() => expect(toast.error).toHaveBeenCalled())
    // Pesan server dipajang UTUH: ia sudah menyebut item dan jumlahnya, dan itulah yang
    // memberi tahu dispatcher apa yang harus dikembalikan dulu ke gudang.
    expect(toast.error).toHaveBeenCalledWith(
      'Rencana tidak bisa dikosongkan: 2 ONT ZTE F660 sudah keluar gudang.',
    )
  })
})

describe('pemakaian curah', () => {
  it('menolak item berserial di klien dan menjelaskan bahwa jumlahnya datang dari scan', async () => {
    mockApi()
    const actor = await renderCard()

    await actor.selectOptions(screen.getByLabelText('Item'), ITEM_ONT)

    expect(
      screen.getByText(/ONT ZTE F660 adalah barang berserial: jumlah terpakainya datang dari scan/),
    ).toBeDefined()
    expect(screen.getByRole('button', { name: 'Catat pemakaian' }).hasAttribute('disabled')).toBe(true)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('mencatat pemakaian item curah', async () => {
    mockApi()
    apiPost.mockResolvedValue(dropRow)
    const actor = await renderCard()

    await actor.selectOptions(screen.getByLabelText('Item'), ITEM_DROP)
    await actor.type(screen.getByLabelText('Terpakai'), '2')
    await actor.click(screen.getByRole('button', { name: 'Catat pemakaian' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    const [path, body] = apiPost.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe(`/api/work-orders/${WO}/materials/usage`)
    expect(body.itemId).toBe(ITEM_DROP)
    expect(body.usedQuantity).toBe(2)
    // Kolom kosong berarti "tidak dilaporkan", bukan nol yang menimpa angka di server.
    expect(body.returnedQuantity).toBeUndefined()
    expect(body.lostQuantity).toBeUndefined()
  })
})

describe('pengeluaran dari gudang', () => {
  it('menolak nomor seri kembar tanpa peduli besar-kecil huruf sebelum satu request pun berangkat', async () => {
    mockApi()
    const actor = await renderCard()

    await fillIssueHeader(actor)
    await actor.selectOptions(screen.getByLabelText('Item baris 1'), ITEM_ONT)
    await actor.type(screen.getByLabelText('Nomor seri baris 1'), 'SN-001\nsn-001')
    await actor.type(screen.getByLabelText(/^Alasan\s*\*?\s*$/), 'Pemasangan baru')

    expect(screen.getByText('Baris 1: nomor seri sn-001 ditulis dua kali.')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Keluarkan material' }).hasAttribute('disabled')).toBe(true)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('menurunkan jumlah item berserial dari cacah nomor seri, bukan kolom terpisah', async () => {
    mockApi()
    const actor = await renderCard()

    await actor.selectOptions(screen.getByLabelText('Item baris 1'), ITEM_ONT)
    expect(screen.queryByLabelText('Jumlah baris 1')).toBeNull()

    await actor.type(screen.getByLabelText('Nomor seri baris 1'), 'SN-001\nSN-002')
    expect(screen.getByText('Jumlah').parentElement?.textContent).toContain('2')
  })

  it('mempertahankan kunci operasi saat payload yang sama dikirim ulang', async () => {
    mockApi()
    apiPost.mockRejectedValueOnce(new Error('jaringan putus'))
    apiPost.mockResolvedValueOnce([ontRow, dropRow])
    const actor = await renderCard()

    await fillIssueHeader(actor)
    await actor.selectOptions(screen.getByLabelText('Item baris 1'), ITEM_DROP)
    await actor.type(screen.getByLabelText(/^Alasan\s*\*?\s*$/), 'Pemasangan baru')

    const issue = screen.getByRole('button', { name: 'Keluarkan material' })
    await actor.click(issue)
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
    await actor.click(screen.getByRole('button', { name: 'Keluarkan material' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(2))

    const first = apiPost.mock.calls[0][1] as Record<string, unknown>
    const second = apiPost.mock.calls[1][1] as Record<string, unknown>
    expect(apiPost.mock.calls[0][0]).toBe(`/api/work-orders/${WO}/materials/issues`)
    // Klik kedua atas isian yang SAMA adalah percobaan ulang, bukan pengeluaran kedua.
    expect(second.operationKey).toBe(first.operationKey)
    expect(second.payloadHash).toBe(first.payloadHash)
  })

  it('menjelaskan butuh izin gudang saat direktori lokasi dan pengguna ditolak', async () => {
    can.mockImplementation((permission: string) => permission.startsWith('workorder.material.'))
    apiGet.mockImplementation((path: string) => {
      if (path === `/api/work-orders/${WO}/materials`) return Promise.resolve([ontRow, dropRow])
      if (path === `/api/work-orders/${WO}/materials/template`) return Promise.resolve(template)
      return Promise.reject(new Error('forbidden'))
    })
    await renderCard()

    expect(await screen.findByText(/formulir pengeluaran butuh izin gudang/)).toBeDefined()
    // Kartunya TIDAK ikut kosong: tabel materialnya tetap terbaca.
    expect(screen.getAllByText('ONT ZTE F660').length).toBeGreaterThan(0)
  })
})

describe('scan nomor seri', () => {
  it('mengirim scan saat Enter ditekan supaya pemindai barcode cukup menembak', async () => {
    mockApi()
    apiPost.mockResolvedValue(ontRow)
    const actor = await renderCard()

    await actor.type(screen.getByLabelText('Nomor seri'), 'SN-002{Enter}')

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    const [path, body] = apiPost.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe(`/api/work-orders/${WO}/materials/serials`)
    expect(body).toEqual({ serialNumber: 'SN-002', outcome: 'INSTALLED', macAddress: null })
  })

  it('hanya menawarkan tiga nasib yang dikenal server', async () => {
    mockApi()
    await renderCard()

    const outcomes = screen.getByLabelText('Nasib unit') as HTMLSelectElement
    expect([...outcomes.options].map((option) => option.value)).toEqual(['INSTALLED', 'RETURNED', 'LOST'])
  })
})

describe('koreksi scan nomor seri', () => {
  it('mengisi formulir dari baris yang dikoreksi dan menyatakan bahwa catatan lama akan diganti', async () => {
    mockApi()
    const actor = await renderCard()

    await actor.click(screen.getByRole('button', { name: 'Koreksi SN-001' }))

    expect((screen.getByLabelText('Nomor seri') as HTMLInputElement).value).toBe('SN-001')
    expect((screen.getByLabelText('Nasib unit') as HTMLSelectElement).value).toBe('INSTALLED')
    expect((screen.getByLabelText('MAC address (opsional)') as HTMLInputElement).value).toBe('AA:BB:CC:DD:EE:FF')
    // Nasib LAMA disebut apa adanya: tanpa itu teknisi tak tahu catatan mana yang akan tertimpa.
    expect(screen.getByText(/akan MENGGANTI catatan SN-001 .*Terpasang di pelanggan/s)).toBeDefined()
    // Fokus mendarat di pemilih nasib, bukan di kolom serial: serialnya sudah benar.
    expect(document.activeElement).toBe(screen.getByLabelText('Nasib unit'))
  })

  it('mengirim nomor seri yang sama dengan nasib baru — server meng-upsert, bukan menambah baris', async () => {
    mockApi()
    apiPost.mockResolvedValue(ontRow)
    const actor = await renderCard()

    await actor.click(screen.getByRole('button', { name: 'Koreksi SN-001' }))
    await actor.selectOptions(screen.getByLabelText('Nasib unit'), 'RETURNED')
    await actor.click(screen.getByRole('button', { name: 'Simpan koreksi' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    const [path, body] = apiPost.mock.calls[0] as [string, Record<string, unknown>]
    // Endpoint yang SAMA dengan scan biasa. Kalau kelak muncul endpoint "hapus serial",
    // tes inilah yang harus dibaca dulu: id baris serial sengaja dipertahankan supaya saga
    // pemotongan saldo tidak memotong unit yang sama dua kali.
    expect(path).toBe(`/api/work-orders/${WO}/materials/serials`)
    expect(body).toEqual({ serialNumber: 'SN-001', outcome: 'RETURNED', macAddress: 'AA:BB:CC:DD:EE:FF' })
  })

  it('bisa membatalkan mode koreksi dan mengembalikan formulir ke keadaan kosong', async () => {
    mockApi()
    const actor = await renderCard()

    await actor.click(screen.getByRole('button', { name: 'Koreksi SN-001' }))
    await actor.click(screen.getByRole('button', { name: 'Batalkan koreksi' }))

    expect(screen.queryByText(/akan MENGGANTI catatan SN-001/s)).toBeNull()
    expect((screen.getByLabelText('Nomor seri') as HTMLInputElement).value).toBe('')
    expect((screen.getByLabelText('MAC address (opsional)') as HTMLInputElement).value).toBe('')
    expect(screen.getByRole('button', { name: 'Simpan scan' })).toBeDefined()
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('tidak menawarkan koreksi saat work order sudah terminal — gerbang yang sama dengan scan', async () => {
    mockApi()
    await renderCard('DONE')

    expect(screen.getByText('SN-001')).toBeDefined()
    expect(screen.queryByRole('button', { name: 'Koreksi SN-001' })).toBeNull()
  })

  it('tidak menawarkan koreksi tanpa izin mencatat material', async () => {
    can.mockImplementation((permission: string) => permission !== 'workorder.material.record')
    mockApi()
    await renderCard()

    expect(screen.getByText('SN-001')).toBeDefined()
    expect(screen.queryByRole('button', { name: 'Koreksi SN-001' })).toBeNull()
  })
})
