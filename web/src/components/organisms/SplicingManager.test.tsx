import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CableCoreView, SpliceWorkbenchView } from '@/api/network'

const { apiGet, can, confirm, fetchWorkOrders, toast } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  can: vi.fn(() => true),
  confirm: vi.fn(() => Promise.resolve(true)),
  fetchWorkOrders: vi.fn(() => Promise.resolve([])),
  toast: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}))

vi.mock('@/api/client', () => ({
  api: { get: apiGet, post: vi.fn(), put: vi.fn(), del: vi.fn() },
  ApiError: class ApiError extends Error {},
}))

vi.mock('@/auth/useCan', () => ({
  useCan: () => ({ can }),
}))

vi.mock('@/hooks/useOpenWorkOrders', () => ({
  useOpenWorkOrders: () => ({ canPick: false, searchesAll: false, fetchWorkOrders }),
}))

vi.mock('@/system', () => ({
  useConfirm: () => confirm,
  useToast: () => toast,
}))

import { SplicingManager } from './SplicingManager'

const CORE_COLORS = [
  ['Biru', '#2563eb'],
  ['Oranye', '#f97316'],
  ['Hijau', '#16a34a'],
  ['Cokelat', '#92400e'],
  ['Abu-abu', '#6b7280'],
  ['Putih', '#f8fafc'],
  ['Merah', '#dc2626'],
  ['Hitam', '#111827'],
  ['Kuning', '#eab308'],
  ['Ungu', '#7e22ce'],
  ['Merah muda', '#ec4899'],
  ['Aqua', '#06b6d4'],
] as const

function core(coreNumber: number): CableCoreView {
  const color = CORE_COLORS[(coreNumber - 1) % CORE_COLORS.length]
  if (!color) throw new Error(`Warna core ${coreNumber} tidak tersedia`)
  return {
    id: `core-${coreNumber}`,
    tubeNumber: coreNumber <= 12 ? 1 : 2,
    coreNumber,
    positionInTube: ((coreNumber - 1) % 12) + 1,
    color: color[0],
    colorHex: color[1],
    tubeColor: coreNumber <= 12 ? 'Biru' : 'Oranye',
    tubeColorHex: coreNumber <= 12 ? '#2563eb' : '#f97316',
    status: 'FREE',
    note: null,
  }
}

const workbench: SpliceWorkbenchView = {
  closureKind: 'ODP',
  closureId: 'odp-1',
  closureCode: 'ODP-01',
  closureName: 'ODP Mawar',
  spliceCapacity: null,
  spliceCount: 1,
  cables: [
    {
      cableId: 'cable-1',
      code: 'DIST-01',
      name: 'Distribusi Mawar',
      cableType: 'DISTRIBUTION',
      coreCount: 24,
      lengthMeters: 240,
      role: 'END',
      roleLabel: 'Berujung',
      spliceable: true,
      terminatesHere: true,
      tapDistanceMeters: 240,
      cores: Array.from({ length: 24 }, (_, index) => ({
        core: core(index + 1),
        connectionId: index === 2 ? 'connection-3' : null,
        connectedElsewhere: index === 4,
      })),
    },
  ],
  points: [],
  connections: [],
}

async function renderWorkbench() {
  apiGet.mockResolvedValue(workbench)
  const user = userEvent.setup()
  const view = render(<SplicingManager closureKind="ODP" closureId="odp-1" />)
  await screen.findByText('Ujung A')
  return { user, ...view }
}

async function splicingCssContract() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/index.css'), 'utf8')
}

const labels = (grid: Element) => Array.from(grid.querySelectorAll('.core-chip'), (chip) => chip.textContent)

function coreGrids(container: HTMLElement): readonly [HTMLElement, HTMLElement] {
  const grids = container.querySelectorAll('.core-grid')
  const left = grids.item(0)
  const right = grids.item(1)
  if (!(left instanceof HTMLElement) || !(right instanceof HTMLElement) || grids.length !== 2) {
    throw new Error('Kedua kisi core tidak ditemukan')
  }
  return [left, right]
}

beforeEach(() => {
  apiGet.mockReset()
  can.mockReset()
  can.mockReturnValue(true)
})

describe('SplicingManager', () => {
  it('mempertahankan urutan core 1–24 di kedua ujung', async () => {
    const { container } = await renderWorkbench()
    const grids = coreGrids(container)
    const expected = Array.from({ length: 24 }, (_, index) => String(index + 1))

    expect(labels(grids[0])).toEqual(expected)
    expect(labels(grids[1])).toEqual(expected)
    expect(screen.getAllByRole('button', { name: '7' })).toHaveLength(2)
    expect(screen.getAllByRole('button', { name: '12' })).toHaveLength(2)
  })

  it('memilih core bebas di tiap ujung tanpa mengubah urutan', async () => {
    const { container, user } = await renderWorkbench()
    const grids = coreGrids(container)
    const expected = Array.from({ length: 24 }, (_, index) => String(index + 1))
    const leftSeven = within(grids[0]).getByRole('button', { name: '7' })
    const rightTwelve = within(grids[1]).getByRole('button', { name: '12' })

    await user.click(leftSeven)
    await user.click(rightTwelve)

    expect(leftSeven.getAttribute('aria-pressed')).toBe('true')
    expect(rightTwelve.getAttribute('aria-pressed')).toBe('true')
    expect(labels(grids[0])).toEqual(expected)
    expect(labels(grids[1])).toEqual(expected)
  })

  it('tetap menampilkan core terpakai dan terblokir sebagai tombol nonaktif', async () => {
    const { container } = await renderWorkbench()
    const grids = coreGrids(container)

    for (const grid of grids) {
      expect(within(grid).getByRole('button', { name: '3' }).hasAttribute('disabled')).toBe(true)
      expect(within(grid).getByRole('button', { name: '5' }).hasAttribute('disabled')).toBe(true)
    }
  })

  it('membatasi grid responsif ke meja splicing tanpa mengubah grid core bersama', async () => {
    const { container } = await renderWorkbench()
    const css = await splicingCssContract()

    expect(container.querySelectorAll('.splice-core-grid')).toHaveLength(2)
    expect(css).toMatch(/\.splice-side\s*\{[^}]*container-type:\s*inline-size;/s)
    expect(css).toMatch(/\.splice-core-grid\s*\{[^}]*grid-template-columns:\s*repeat\(8,\s*32px\);[^}]*width:\s*fit-content;/s)
    expect(css).toMatch(/\.splice-core-grid\s*>\s*\.core-chip\s*\{[^}]*width:\s*32px;[^}]*height:\s*32px;[^}]*aspect-ratio:\s*1;/s)
    expect(css).toMatch(/\.splice-core-grid\s*>\s*\.core-chip:not\(:disabled\):hover\s*\{[^}]*transform:\s*none;[^}]*filter:\s*brightness\(1\.12\);/s)
    expect(css).toMatch(/\.splice-core-grid\s*>\s*\.core-chip\.is-selected\s*\{[^}]*transform:\s*none;/s)
    expect(css).toMatch(/@container\s*\(max-width:\s*18rem\)\s*\{\s*\.splice-core-grid\s*\{[^}]*grid-template-columns:\s*repeat\(4,\s*32px\);/s)
    expect(css).toMatch(/\.core-grid\s*\{\s*display:\s*grid;\s*grid-template-columns:\s*repeat\(8,\s*32px\);\s*gap:\s*0\.3rem;\s*width:\s*fit-content;\s*\}/s)
    expect(css).toMatch(/\.splice-bench\s*\{[^}]*grid-template-columns:\s*repeat\(auto-fit,\s*minmax\(min\(25rem,\s*100%\),\s*1fr\)\);/s)
  })
})
