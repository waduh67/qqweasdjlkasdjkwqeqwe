import { cleanup, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

const { apiGet, apiPost, apiPut, can } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  can: vi.fn((_permission: string) => true),
}))

vi.mock('@/api/client', () => ({
  api: { get: apiGet, post: apiPost, put: apiPut, del: vi.fn() },
  ApiError: class ApiError extends Error {},
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
  useToast: () => toast,
}))

import { WarehouseOperationsPage } from './WarehouseOperationsPage'

const ITEM_ONT = '11111111-1111-1111-1111-111111111111'
const ITEM_DROP = '22222222-2222-2222-2222-222222222222'
const LOC_GUDANG = '33333333-3333-3333-3333-333333333333'
const LOC_VAN = '44444444-4444-4444-4444-444444444444'
const USER_BUDI = '55555555-5555-5555-5555-555555555555'
const USER_SARI = '66666666-6666-6666-6666-666666666666'
const USER_TONO = '77777777-7777-7777-7777-777777777777'

const locations = [
  { id: LOC_GUDANG, code: 'GD-PUSAT', kind: 'WAREHOUSE', parentId: null },
  { id: LOC_VAN, code: 'VAN-01', kind: 'VEHICLE', parentId: null },
]

const items = [
  {
    id: ITEM_ONT,
    code: 'ONT-001',
    name: 'ONT ZTE F660',
    category: 'ONT',
    unit: 'PCS',
    serialized: true,
    trackMac: true,
    reorderPoint: 5,
    active: true,
  },
  {
    id: ITEM_DROP,
    code: 'DRP-150',
    name: 'Dropcore 150 meter',
    category: 'DROPCORE',
    unit: 'ROLL',
    serialized: false,
    trackMac: false,
    reorderPoint: 2,
    active: true,
  },
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

const users = [user(USER_BUDI, 'Budi Santoso'), user(USER_SARI, 'Sari Melati'), user(USER_TONO, 'Tono Wijaya')]

const balances = [
  {
    itemId: ITEM_ONT,
    itemCode: 'ONT-001',
    locationId: LOC_GUDANG,
    locationCode: 'GD-PUSAT',
    custodyOwnerId: USER_BUDI,
    custodyOwnerKind: 'WAREHOUSE',
    status: 'AVAILABLE',
    quantity: 12,
  },
]

const policies = [
  {
    type: 'WRITE_OFF',
    expiryHours: 24,
    emergencyAllowed: false,
    configured: true,
    tiers: [
      {
        number: 1,
        minimumAmount: 0,
        approverRole: 'warehouse.supervisor',
        approverIds: [USER_BUDI],
        roleHolderIds: [USER_SARI],
      },
    ],
  },
  {
    type: 'LOSS',
    expiryHours: 24,
    emergencyAllowed: false,
    configured: false,
    tiers: [
      { number: 1, minimumAmount: 0, approverRole: 'warehouse.manager', approverIds: [], roleHolderIds: [] },
    ],
  },
]

const pendingApprovals = [
  {
    approvalId: 'aaaa1111-0000-0000-0000-000000000000',
    tenantId: 'tenant-1',
    type: 'WRITE_OFF',
    amount: 3,
    requesterId: USER_TONO,
    custodianId: USER_BUDI,
    movementId: null,
    policy: { version: 1, tiers: [], expiry: 'PT24H', emergencyAllowed: false },
    policySnapshotHash: 'hash',
    operationKey: 'key-1',
    operationHash: 'hash-1',
    emergencyReason: null,
    requestedAt: '2026-09-01T02:00:00Z',
    expiresAt: '2026-09-02T02:00:00Z',
    status: 'PENDING',
    revision: 1,
    decisions: [],
  },
]

function mockApi(overrides: Record<string, unknown> = {}) {
  apiGet.mockImplementation((path: string) => {
    for (const [prefix, value] of Object.entries(overrides)) {
      if (path.startsWith(prefix)) return Promise.resolve(value)
    }
    if (path.startsWith('/api/inventory/locations')) return Promise.resolve(locations)
    if (path.startsWith('/api/inventory/item-master')) return Promise.resolve(items)
    if (path.startsWith('/api/users')) {
      return Promise.resolve({ content: users, page: 0, size: 200, totalElements: users.length, totalPages: 1 })
    }
    if (path.startsWith('/api/inventory/balances')) return Promise.resolve(balances)
    if (path.startsWith('/api/inventory/van-stock')) return Promise.resolve([])
    if (path.startsWith('/api/inventory/approvals/pending')) return Promise.resolve(pendingApprovals)
    if (path.startsWith('/api/inventory/approvals/policies')) return Promise.resolve(policies)
    if (path.startsWith('/api/inventory/approvals/emergency-overrides')) return Promise.resolve([])
    if (path.startsWith('/api/inventory/counts/open')) return Promise.resolve([])
    if (path.startsWith('/api/inventory/variance-report')) return Promise.resolve({ openCounts: [], anomalies: [] })
    if (path.startsWith('/api/inventory/ledger')) {
      return Promise.resolve({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })
    }
    return Promise.reject(new Error(`tak terduga: ${path}`))
  })
}

async function renderPage() {
  const actor = userEvent.setup()
  render(<MemoryRouter><WarehouseOperationsPage /></MemoryRouter>)
  await screen.findByRole('tab', { name: 'Stok' })
  return actor
}

afterEach(() => {
  apiGet.mockReset()
  apiPost.mockReset()
  apiPut.mockReset()
  can.mockReset()
  can.mockImplementation(() => true)
  toast.error.mockReset()
  toast.success.mockReset()
})

describe('daftar stok', () => {
  it('menampilkan nama item, lokasi, dan pemegang — bukan UUID', async () => {
    mockApi()
    await renderPage()

    expect(await screen.findByText('ONT ZTE F660')).toBeDefined()
    expect(screen.getByText('ONT-001')).toBeDefined()
    expect(screen.getByText('Budi Santoso')).toBeDefined()
    expect(screen.getByText('GD-PUSAT')).toBeDefined()
    expect(screen.queryByText(ITEM_ONT)).toBeNull()
    expect(screen.queryByText(USER_BUDI)).toBeNull()
    expect(screen.queryByText(LOC_GUDANG)).toBeNull()
  })

  it('tetap menampilkan saldo saat direktori pengguna ditolak server', async () => {
    mockApi()
    apiGet.mockImplementation((path: string) => {
      if (path.startsWith('/api/users')) return Promise.reject(new Error('forbidden'))
      if (path.startsWith('/api/inventory/locations')) return Promise.resolve(locations)
      if (path.startsWith('/api/inventory/item-master')) return Promise.resolve(items)
      if (path.startsWith('/api/inventory/balances')) return Promise.resolve(balances)
      if (path.startsWith('/api/inventory/van-stock')) return Promise.resolve([])
      return Promise.reject(new Error(`tak terduga: ${path}`))
    })
    await renderPage()

    expect(await screen.findByText('ONT ZTE F660')).toBeDefined()
  })
})

describe('kebijakan persetujuan', () => {
  async function openPolicies() {
    const actor = await renderPage()
    await actor.click(screen.getByRole('tab', { name: 'Persetujuan' }))
    await actor.click(await screen.findByRole('button', { name: 'Kebijakan' }))
    await screen.findByLabelText('Kebijakan Hapus buku')
    return actor
  }

  it('memisahkan pemegang peran dari penyetuju yang ditunjuk dan tidak menawarkan hapus untuk pemegang peran', async () => {
    mockApi()
    await openPolicies()

    const tier = within(within(screen.getByLabelText('Kebijakan Hapus buku')).getByLabelText('Tier 1'))
    const appointed = within(tier.getByRole('group', { name: 'Penyetuju ditunjuk tier 1' }))
    const roleHolders = within(tier.getByRole('group', { name: 'Pemegang peran tier 1' }))

    expect(appointed.getByText('Budi Santoso')).toBeDefined()
    expect(appointed.getByRole('button', { name: 'Hapus penyetuju Budi Santoso' })).toBeDefined()
    // Pemegang peran TIDAK boleh bocor ke daftar yang bisa dihapus — kalau ia ikut di sana,
    // administrator menghapusnya, menyimpan, lalu namanya muncul lagi dari resolusi peran.
    expect(appointed.queryByRole('button', { name: 'Hapus penyetuju Sari Melati' })).toBeNull()

    expect(roleHolders.getByText('Pemegang peran warehouse.supervisor')).toBeDefined()
    expect(roleHolders.getByText('Sari Melati')).toBeDefined()
    expect(roleHolders.queryAllByRole('button')).toHaveLength(0)
    expect(
      roleHolders.getByText(/Diresolusi otomatis dari modul pengguna\. Tidak bisa dihapus di sini/),
    ).toBeDefined()
  })

  it('tidak mengirim balik pemegang peran saat kebijakan disimpan', async () => {
    mockApi()
    apiPut.mockResolvedValue(policies[0])
    const actor = await openPolicies()

    const card = within(screen.getByLabelText('Kebijakan Hapus buku'))
    await actor.click(card.getByRole('button', { name: 'Simpan kebijakan' }))

    await waitFor(() => expect(apiPut).toHaveBeenCalled())
    const [path, body] = apiPut.mock.calls[0] as [string, { tiers: Record<string, unknown>[] }]
    expect(path).toBe('/api/inventory/approvals/policies/WRITE_OFF')
    expect(body.tiers[0]).toEqual({
      number: 1,
      minimumAmount: 0,
      approverRole: 'warehouse.supervisor',
      approverIds: [USER_BUDI],
    })
    expect('roleHolderIds' in body.tiers[0]).toBe(false)
  })

  it('menandai tipe kebijakan yang belum siap dan tier tanpa penyetuju', async () => {
    mockApi()
    await openPolicies()

    const notReady = within(screen.getByLabelText('Kebijakan Kehilangan'))
    expect(notReady.getByText('Belum siap — permintaan akan ditolak')).toBeDefined()
    expect(notReady.getByText('Tier belum siap — tanpa penyetuju')).toBeDefined()
    expect(within(screen.getByLabelText('Kebijakan Hapus buku')).getByText('Siap dipakai')).toBeDefined()
  })

  it('menyembunyikan tombol simpan dan hapus penyetuju tanpa izin kelola', async () => {
    can.mockImplementation((permission: string) => permission !== 'inventory.approval.manage')
    mockApi()
    await openPolicies()

    expect(screen.queryByRole('button', { name: 'Simpan kebijakan' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Hapus penyetuju Budi Santoso' })).toBeNull()
    // Nama tetap terbaca meski tak bisa diubah — layar audit, bukan layar kosong.
    expect(screen.getAllByText('Budi Santoso').length).toBeGreaterThan(0)
  })
})

describe('antrean persetujuan', () => {
  it('mengunci keputusan saat izin memutus tidak ada', async () => {
    can.mockImplementation((permission: string) => permission !== 'inventory.approval.decide')
    mockApi()
    const actor = await renderPage()
    await actor.click(screen.getByRole('tab', { name: 'Persetujuan' }))

    const approve = await screen.findByRole('button', { name: 'Setujui' })
    expect(approve.hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('button', { name: 'Tolak' }).hasAttribute('disabled')).toBe(true)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('menahan penolakan sampai catatan diisi', async () => {
    mockApi()
    apiPost.mockResolvedValue(pendingApprovals[0])
    const actor = await renderPage()
    await actor.click(screen.getByRole('tab', { name: 'Persetujuan' }))

    const reject = await screen.findByRole('button', { name: 'Tolak' })
    expect(reject.hasAttribute('disabled')).toBe(true)

    await actor.type(screen.getByLabelText('Catatan keputusan'), 'Bukti foto tidak terbaca')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Tolak' }).hasAttribute('disabled')).toBe(false))

    await actor.click(screen.getByRole('button', { name: 'Tolak' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    const [path, body] = apiPost.mock.calls[0] as [string, Record<string, unknown>]
    expect(path).toBe(`/api/inventory/approvals/${pendingApprovals[0].approvalId}/decision`)
    expect(body.decision).toBe('REJECT')
    expect(body.reason).toBe('Bukti foto tidak terbaca')
    // Jalur keputusan memakai nama `operationHash`, bukan `payloadHash` seperti mutasi.
    expect(typeof body.operationHash).toBe('string')
    expect(typeof body.operationKey).toBe('string')
  })
})

describe('aksi mutasi', () => {
  it('menyembunyikan tab mutasi saat tak satu pun izin tulis dimiliki', async () => {
    const writes = [
      'inventory.restock.request',
      'inventory.restock.receive',
      'inventory.movement.transfer',
      'inventory.movement.issue',
      'inventory.movement.return',
      'inventory.movement.adjust',
    ]
    can.mockImplementation((permission: string) => !writes.includes(permission))
    mockApi()
    await renderPage()

    expect(screen.queryByRole('tab', { name: 'Mutasi' })).toBeNull()
    expect(screen.getByRole('tab', { name: 'Stok' })).toBeDefined()
  })

  it('hanya menawarkan aksi yang diizinkan', async () => {
    can.mockImplementation((permission: string) => permission !== 'inventory.movement.adjust')
    mockApi()
    const actor = await renderPage()
    await actor.click(screen.getByRole('tab', { name: 'Mutasi' }))

    expect(await screen.findByRole('button', { name: 'Transfer antar lokasi' })).toBeDefined()
    expect(screen.queryByRole('button', { name: 'Penyesuaian / hapus buku' })).toBeNull()
  })

  it('menurunkan kuantitas item berserial dari daftar SN, bukan dari kolom terpisah', async () => {
    mockApi()
    const actor = await renderPage()
    await actor.click(screen.getByRole('tab', { name: 'Mutasi' }))
    await actor.click(await screen.findByRole('button', { name: 'Penerimaan barang' }))

    await actor.selectOptions(screen.getByLabelText('Item baris 1'), ITEM_ONT)
    expect(screen.queryByLabelText('Jumlah baris 1')).toBeNull()

    await actor.type(screen.getByLabelText('Nomor seri baris 1'), 'SN-001\nSN-002\nSN-003')
    const quantity = screen.getByText('Jumlah').parentElement
    expect(quantity?.textContent).toContain('3')
  })

  it('menolak simpan saat nomor seri kembar dan tidak memanggil server', async () => {
    mockApi()
    const actor = await renderPage()
    await actor.click(screen.getByRole('tab', { name: 'Mutasi' }))
    await actor.click(await screen.findByRole('button', { name: 'Penerimaan barang' }))

    await actor.selectOptions(screen.getByLabelText('Lokasi'), LOC_GUDANG)
    await actor.selectOptions(screen.getByLabelText('Pemegang custody'), USER_BUDI)
    await actor.selectOptions(screen.getByLabelText('Item baris 1'), ITEM_ONT)
    await actor.type(screen.getByLabelText('Nomor seri baris 1'), 'SN-001\nsn-001')
    await actor.type(screen.getByLabelText(/^Alasan\s*\*?\s*$/), 'Penerimaan dari vendor')

    // Kembar dikenali TANPA peduli besar-kecil huruf: pemindai dan ketikan tangan sering
    // berbeda huruf untuk unit fisik yang sama.
    expect(screen.getByText('Baris 1: nomor seri sn-001 ditulis dua kali.')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Simpan mutasi' }).hasAttribute('disabled')).toBe(true)
    expect(apiPost).not.toHaveBeenCalled()
  })
})

describe('master data', () => {
  it('menonaktifkan item alih-alih menawarkan hapus', async () => {
    mockApi()
    const actor = await renderPage()
    await actor.click(screen.getByRole('tab', { name: 'Master data' }))
    await actor.click(await screen.findByRole('button', { name: 'Item master' }))

    expect(await screen.findByText('ONT ZTE F660')).toBeDefined()
    expect(screen.queryByRole('menuitem', { name: 'Hapus' })).toBeNull()
  })

  it('mengirim seluruh isi template WO saat satu baris dikeluarkan', async () => {
    mockApi({
      '/api/inventory/material-templates': [
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
      ],
    })
    apiPut.mockResolvedValue([])
    const actor = await renderPage()
    await actor.click(screen.getByRole('tab', { name: 'Master data' }))
    await actor.click(await screen.findByRole('button', { name: 'Template material WO' }))

    await actor.click(await screen.findByRole('button', { name: 'Keluarkan ONT ZTE F660 dari template' }))
    await actor.click(screen.getByRole('button', { name: 'Simpan template' }))

    await waitFor(() => expect(apiPut).toHaveBeenCalled())
    const [path, body] = apiPut.mock.calls[0] as [string, { lines: unknown[] }]
    expect(path).toBe('/api/inventory/material-templates/PSB')
    // Simpan mengganti SELURUH daftar: baris yang dikeluarkan hilang karena TIDAK ikut dikirim.
    expect(body.lines).toEqual([{ itemId: ITEM_DROP, plannedQuantity: 2, note: 'Sesuaikan jarak tiang' }])
  })
})

afterEach(cleanup)
