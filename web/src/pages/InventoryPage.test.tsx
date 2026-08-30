import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { afterEach, describe, expect, it, vi } from 'vitest'

const { apiGet, can } = vi.hoisted(() => ({ apiGet: vi.fn(), can: vi.fn(() => true) }))

vi.mock('../api/client', () => ({
  api: { get: apiGet, post: vi.fn(), put: vi.fn(), del: vi.fn() },
  ApiError: class ApiError extends Error {},
}))

vi.mock('../auth/useCan', () => ({
  useCan: () => ({ can }),
}))

vi.mock('./OltDetailPage', () => ({
  OltDetail: ({ oltId }: { oltId: string }) => <p>Detail OLT {oltId}</p>,
}))

const toast = { error: vi.fn(), success: vi.fn(), info: vi.fn() }

vi.mock('@/system', () => ({
  useConfirm: () => vi.fn(),
  useToast: () => toast,
}))

import { InventoryPage, compactPageWindow, OltPager } from './InventoryPage'

const sitesPage = { content: [], page: 0, size: 100, totalElements: 0, totalPages: 0 }
const olt = (id: string, name: string) => ({
  id,
  code: `OLT-${id}`,
  name,
  siteId: 'site-1',
  siteName: 'POP Pusat',
  vendor: 'ZTE',
  model: null,
  managementIp: null,
  status: 'ACTIVE' as const,
  snmpConfigured: true,
  snmpPort: 161,
  pollable: true,
  ponPortCount: 1,
  location: { longitude: 0, latitude: 0 },
  areaId: null,
  description: null,
  snmpEnabled: true,
  snmpVersion: 'V2C' as const,
  webEnabled: false,
  webProtocol: 'HTTP' as const,
  webPort: null,
  webUsername: null,
  webPasswordConfigured: false,
})

const oltPage = (content: ReturnType<typeof olt>[], page = 0, size = 25, totalElements = 52) => ({
  content,
  page,
  size,
  totalElements,
  totalPages: Math.ceil(totalElements / size),
})

function mockOltPages() {
  apiGet.mockImplementation((path: string) => {
    if (path.startsWith('/api/sites')) return Promise.resolve(sitesPage)
    if (path.includes('page=1')) return Promise.resolve(oltPage([olt('dua', 'OLT Timur')], 1))
    return Promise.resolve(oltPage([olt('satu', 'OLT Pusat')]))
  })
}

async function renderOlts() {
  const user = userEvent.setup()
  render(<MemoryRouter><InventoryPage /></MemoryRouter>)
  await user.click(screen.getByRole('tab', { name: 'OLT' }))
  await screen.findByText('OLT Pusat')
  return user
}

afterEach(() => {
  apiGet.mockReset()
  toast.error.mockReset()
  toast.success.mockReset()
  toast.info.mockReset()
  can.mockReset()
  can.mockReturnValue(true)
})

describe('compactPageWindow', () => {
  it('menampilkan semua halaman saat totalnya ringkas', () => {
    expect(compactPageWindow(4, 1)).toEqual([0, 1, 2, 3])
  })

  it('menjaga batas, halaman aktif, dan elipsis secara deterministik', () => {
    expect(compactPageWindow(20, 9)).toEqual([0, 'start-ellipsis', 8, 9, 10, 'end-ellipsis', 19])
  })

  it('tidak membuat elipsis untuk satu halaman yang terlewati', () => {
    expect(compactPageWindow(8, 2)).toEqual([0, 1, 2, 3, 'end-ellipsis', 7])
  })
})

describe('OltPager', () => {
  it('menjelaskan bahwa total server tidak mengikuti filter status lokal', () => {
    render(
      <OltPager
        page={1}
        size={25}
        totalElements={60}
        totalPages={3}
        disabled={false}
        statusFiltered
        onPageChange={vi.fn()}
        onSizeChange={vi.fn()}
      />,
    )

    expect(screen.getByText('26–50 dari 60 OLT total; status difilter pada halaman ini')).toBeDefined()
  })

  it('menonaktifkan pager pada batas pertama dan terakhir', () => {
    const { rerender } = render(
      <OltPager page={0} size={25} totalElements={60} totalPages={3} disabled={false} statusFiltered={false} onPageChange={vi.fn()} onSizeChange={vi.fn()} />,
    )
    expect(screen.getByRole('button', { name: 'Previous' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('button', { name: 'Next' }).hasAttribute('disabled')).toBe(false)

    rerender(
      <OltPager page={2} size={25} totalElements={60} totalPages={3} disabled={false} statusFiltered={false} onPageChange={vi.fn()} onSizeChange={vi.fn()} />,
    )
    expect(screen.getByRole('button', { name: 'Previous' }).hasAttribute('disabled')).toBe(false)
    expect(screen.getByRole('button', { name: 'Next' }).hasAttribute('disabled')).toBe(true)
  })
})

describe('kontrak presentasi tabel Inventory', () => {
  it('mengaktifkan presentasi resource tepat untuk lima daftar non-OLT dan mempertahankan alias OLT', async () => {
    const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
    const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
    const source = await readFile(resolve('src/pages/InventoryPage.tsx'), 'utf8')

    expect((source.match(/presentation="resource"/g) ?? [])).toHaveLength(5)
    expect((source.match(/presentation="olt"/g) ?? [])).toHaveLength(1)
    expect(source).not.toMatch(/inlineActions:/)
  })
})

describe('tab OLT — kontrak tampilan dan aksi', () => {
  it('menampilkan tepat sembilan kolom teks biasa dan nama satu-baris yang membuka Blade', async () => {
    mockOltPages()
    const user = await renderOlts()

    const grid = screen.getByRole('grid')
    expect(Array.from(grid.querySelectorAll('[role="columnheader"]')).map((header) => header.textContent)).toEqual([
      'Nama', 'Kode', 'Site', 'Vendor', 'Model', 'IP manajemen', 'Status', 'Monitoring', 'PON port',
    ])
    expect(grid.querySelector('[role="row"]:nth-child(2) [role="gridcell"] [class*="fui-Badge"]')).toBeNull()
    expect(grid.querySelector('[role="row"]:nth-child(2) [role="gridcell"] [role="button"][aria-label="Aksi baris"]')).toBeNull()
    expect(screen.queryByText('Ubah')).toBeNull()

    const name = screen.getByRole('button', { name: 'OLT Pusat' })
    const cellText = name.querySelector('span')
    expect(cellText?.getAttribute('title')).toBe('OLT Pusat')
    expect(cellText?.getAttribute('aria-label')).toBe('OLT Pusat')
    expect(cellText?.style.whiteSpace).toBe('nowrap')
    expect(name.className).toContain('fui-Link')

    await user.click(name)
    expect(screen.getByRole('heading', { name: 'OLT-satu' })).toBeDefined()
    expect(screen.getByText('OLT Pusat · Site POP Pusat')).toBeDefined()
    expect(screen.getByText('Detail OLT satu')).toBeDefined()
  })

  it('menempatkan aksi create, hapus, dan segarkan di command bar sesuai izin dan seleksi', async () => {
    mockOltPages()
    const user = await renderOlts()

    const toolbar = screen.getByRole('toolbar', { name: 'Aksi' })
    expect(Array.from(toolbar.querySelectorAll('button')).map((button) => button.textContent?.trim())).toEqual([
      'Tambah OLT', 'Hapus', 'Segarkan',
    ])
    expect(screen.getByRole('button', { name: 'Hapus' }).hasAttribute('disabled')).toBe(true)
    await user.click(screen.getAllByRole('checkbox')[1])
    expect(screen.getByRole('button', { name: 'Hapus' }).hasAttribute('disabled')).toBe(false)

    cleanup()
    can.mockImplementation((...permissions: string[]) => !['network.olt.create', 'network.olt.delete'].includes(permissions[0]))
    mockOltPages()
    await renderOlts()
    expect(screen.queryByRole('button', { name: 'Tambah OLT' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Hapus' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Segarkan' })).toBeDefined()
  })
})

describe('tab OLT berpaginasi', () => {
  it('meminta halaman awal, mengganti halaman dan ukuran, lalu mengosongkan seleksi lokal', async () => {
    mockOltPages()
    const user = await renderOlts()

    expect(apiGet).toHaveBeenCalledWith('/api/olts?page=0&size=25')
    expect(screen.getByRole('button', { name: 'Nama' })).toBeDefined()
    expect(screen.getByRole('button', { name: 'Kode' })).toBeDefined()

    await user.click(screen.getAllByRole('checkbox')[1])
    expect(screen.getByRole('button', { name: 'Hapus' }).hasAttribute('disabled')).toBe(false)

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('OLT Timur')
    expect(apiGet).toHaveBeenCalledWith('/api/olts?page=1&size=25')
    expect(screen.getByRole('button', { name: 'Hapus' }).hasAttribute('disabled')).toBe(true)

    await user.selectOptions(screen.getByLabelText('Hasil per halaman'), '50')
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/olts?page=0&size=50'))
    expect(screen.getByRole('button', { name: 'Hapus' }).hasAttribute('disabled')).toBe(true)
  })

  it('menampilkan empty state dan pesan API error tanpa mempertahankan baris lama', async () => {
    apiGet.mockImplementation((path: string) => {
      if (path.startsWith('/api/sites')) return Promise.resolve(sitesPage)
      return Promise.resolve(oltPage([], 0, 25, 0))
    })
    const emptyUser = userEvent.setup()
    render(<MemoryRouter><InventoryPage /></MemoryRouter>)
    await emptyUser.click(screen.getByRole('tab', { name: 'OLT' }))
    expect(await screen.findByText('Belum ada OLT')).toBeDefined()

    cleanup()
    apiGet.mockImplementation((path: string) => {
      if (path.startsWith('/api/sites')) return Promise.resolve(sitesPage)
      return Promise.reject(new Error('network unavailable'))
    })
    const user = userEvent.setup()
    render(<MemoryRouter><InventoryPage /></MemoryRouter>)
    await user.click(screen.getByRole('tab', { name: 'OLT' }))
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('Gagal memuat data'))
    expect(screen.queryByText('OLT Pusat')).toBeNull()
  })

  it('mengoreksi halaman terakhir yang sudah tidak valid', async () => {
    apiGet.mockImplementation((path: string) => {
      if (path.startsWith('/api/sites')) return Promise.resolve(sitesPage)
      if (path.includes('page=2')) return Promise.resolve(oltPage([], 2, 25, 26))
      if (path.includes('page=1')) return Promise.resolve(oltPage([olt('dua', 'OLT Timur')], 1, 25, 26))
      return Promise.resolve(oltPage([olt('satu', 'OLT Pusat')], 0, 25, 75))
    })
    const user = await renderOlts()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('OLT Timur')
    await user.click(screen.getByRole('button', { name: 'Next' }))
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/olts?page=1&size=25'))
    expect(await screen.findByText('OLT Timur')).toBeDefined()
  })

  it('mempertahankan hanya respons OLT terbaru saat permintaan lama selesai belakangan', async () => {
    let resolveOldPage: (value: ReturnType<typeof oltPage>) => void = () => undefined
    let resolveNewestPage: (value: ReturnType<typeof oltPage>) => void = () => undefined
    const oldPage = new Promise<ReturnType<typeof oltPage>>((resolve) => { resolveOldPage = resolve })
    const newestPage = new Promise<ReturnType<typeof oltPage>>((resolve) => { resolveNewestPage = resolve })

    apiGet.mockImplementation((path: string) => {
      if (path.startsWith('/api/sites')) return Promise.resolve(sitesPage)
      if (path.includes('query=baru') && path.includes('page=0')) return newestPage
      if (path.includes('page=1')) return oldPage
      return Promise.resolve(oltPage([olt('satu', 'OLT Pusat')]))
    })
    const user = await renderOlts()

    await user.click(screen.getByRole('button', { name: 'Next' }))
    await user.type(screen.getByRole('searchbox'), 'baru')
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/api/olts?page=0&size=25&query=baru'))

    resolveNewestPage(oltPage([olt('baru', 'OLT Baru')]))
    await screen.findByText('OLT Baru')
    resolveOldPage(oltPage([olt('lama', 'OLT Lama')], 1))

    await waitFor(() => expect(screen.queryByText('OLT Lama')).toBeNull())
    expect(screen.getByText('OLT Baru')).toBeDefined()
  })
})
