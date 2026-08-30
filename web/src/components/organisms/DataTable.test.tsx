import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DataTable, type Column } from './DataTable'

/**
 * DataTable menyajikan hampir semua daftar di konsol; ia juga yang menyediakan
 * `data-label` yang dipakai CSS untuk mengubah baris jadi kartu di ponsel — label yang
 * hilang berarti nilai melayang tanpa keterangan di layar kecil.
 */
type Row = { id: string; name: string; fee: number }

const ROWS: Row[] = [
  { id: '2', name: 'Siti', fee: 400_000 },
  { id: '1', name: 'Budi', fee: 250_000 },
]

const COLUMNS: Column<Row>[] = [
  { key: 'name', header: 'Nama', cell: (r) => r.name, sortValue: (r) => r.name },
  { key: 'fee', header: 'Tagihan', align: 'right', cell: (r) => r.fee.toLocaleString('id-ID') },
]

const renderTable = (props: Partial<Parameters<typeof DataTable<Row>>[0]> = {}) =>
  render(<DataTable columns={COLUMNS} rows={ROWS} rowKey={(r) => r.id} {...props} />)

async function mobileCssContract() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  const css = await readFile(resolve('src/index.css'), 'utf8')
  const mobileStart = css.indexOf('@media (max-width: 720px)')
  expect(mobileStart).toBeGreaterThanOrEqual(0)
  return css.slice(mobileStart)
}

describe('DataTable', () => {
  it('mempertahankan struktur grid OLT untuk CSS ponsel', () => {
    const { container } = renderTable({ presentation: 'olt' })

    expect(container.querySelector('.olt-table-card .olt-table-wrap')).not.toBeNull()
    expect(container.querySelector('.olt-table-wrap .olt-data-table-grid[role="grid"]')).not.toBeNull()
    expect(container.querySelector('.olt-data-table-grid [role="columnheader"]')).not.toBeNull()
    expect(container.querySelectorAll('.olt-data-table-grid [role="row"]')).toHaveLength(3)
  })

  it('mengunci kontrak CSS OLT pada viewport sempit', async () => {
    const mobileCss = await mobileCssContract()

    expect(mobileCss).toContain('.table-card:not(.olt-table-card) .table-wrap')
    expect(mobileCss).toMatch(/\.olt-table-card \.olt-table-wrap\s*\{\s*overflow-x:\s*auto;/)
    expect(mobileCss).toMatch(/\.olt-table-card \.olt-data-table-grid\s*\{\s*display:\s*grid;\s*width:\s*max-content;\s*min-width:\s*100%;/)
    expect(mobileCss).toMatch(/\.olt-table-card \.olt-data-table-grid \.fui-DataGridHeader\s*\{\s*display:\s*contents;/)
    expect(mobileCss).toMatch(/\.olt-table-card \.olt-data-table-grid \.fui-DataGridRow\s*\{\s*display:\s*grid;\s*width:\s*max-content;\s*min-width:\s*100%;/)
    expect(mobileCss).toMatch(/\.fui-DataGridHeaderCell,\s*\.olt-table-card \.olt-data-table-grid \.fui-DataGridCell\s*\{\s*min-width:\s*0;\s*overflow:\s*hidden;\s*text-overflow:\s*ellipsis;\s*white-space:\s*nowrap;/)
    expect(mobileCss).toMatch(/\.table-card:not\(\.olt-table-card\) table,[\s\S]*display:\s*block;/)
  })


    it('menggambar tiap baris beserta judul kolomnya', () => {
        renderTable()
        expect(screen.getByText('Budi')).toBeDefined()
        expect(screen.getByText('Siti')).toBeDefined()
        expect(screen.getAllByRole('row')).toHaveLength(3)
      })

  it('menuliskan judul kolom sebagai data-label tiap sel — bahan mode kartu di ponsel', () => {
    const { container } = renderTable()
    const cells = container.querySelectorAll('[role="row"]:nth-child(2) [role="gridcell"]')
    expect(Array.from(cells).map((cell) => cell.getAttribute('data-label'))).toEqual(['Nama', 'Tagihan'])
  })

  it('menambahkan hook OLT tanpa mengubah kelas tabel bawaan', () => {
      const { container, rerender } = renderTable()
      expect(container.querySelector('.olt-table-card')).toBeNull()
      expect(container.querySelector('.olt-data-table-grid')).toBeNull()

      rerender(<DataTable columns={COLUMNS} rows={ROWS} rowKey={(row) => row.id} presentation="olt" />)
      expect(container.querySelector('.olt-table-card')).not.toBeNull()
      expect(container.querySelector('.olt-table-wrap')).not.toBeNull()
      expect(container.querySelector('.olt-data-table-grid')).not.toBeNull()
      expect(container.querySelector('.olt-data-table-grid [role="columnheader"]')).not.toBeNull()
      expect(container.querySelector('.olt-data-table-grid [role="row"]')).not.toBeNull()
    })

  it('mengurutkan naik lalu turun lalu kembali ke urutan asli saat judul diklik', async () => {
    const user = userEvent.setup()
    const { container } = renderTable()
    const firstName = () =>
      Array.from(container.querySelectorAll('[role="row"]'))[1]?.querySelector('[role="gridcell"]')?.textContent

    expect(firstName()).toBe('Siti') // urutan bawaan = urutan data
    await user.click(screen.getByRole('button', { name: /Nama/ }))
    expect(firstName()).toBe('Budi')
    await user.click(screen.getByRole('button', { name: /Nama/ }))
    expect(firstName()).toBe('Siti')
    await user.click(screen.getByRole('button', { name: /Nama/ }))
    expect(firstName()).toBe('Siti')
  })

  it('tak menawarkan pengurutan pada kolom tanpa sortValue', () => {
    renderTable()
    expect(screen.queryByRole('button', { name: /Tagihan/ })).toBeNull()
  })

  it('memanggil onRowClick dengan baris yang diklik', async () => {
    const user = userEvent.setup()
    const onRowClick = vi.fn()
    renderTable({ onRowClick })

    await user.click(screen.getByText('Budi'))

    expect(onRowClick).toHaveBeenCalledWith(ROWS[1])
  })

  it('menjalankan detail hanya dari sel kolom yang dikonfigurasi', async () => {
    const user = userEvent.setup()
    const onCellClick = vi.fn()
    const onRowClick = vi.fn()
    const columns: Column<Row>[] = [
      { ...COLUMNS[0], onCellClick },
      COLUMNS[1],
    ]
    render(<DataTable columns={columns} rows={ROWS} rowKey={(row) => row.id} onRowClick={onRowClick} />)

    await user.click(screen.getByRole('button', { name: 'Siti' }))
    await user.click(screen.getByText('400.000'))

    expect(onCellClick).toHaveBeenCalledWith(ROWS[0])
    expect(onRowClick).toHaveBeenCalledWith(ROWS[0])
  })

  it('menampilkan aksi inline di sel tanpa menambah header Aksi dan tanpa memicu klik baris', async () => {
    const user = userEvent.setup()
    const onCellClick = vi.fn()
    const onInlineAction = vi.fn()
    const onRowClick = vi.fn()
    const columns: Column<Row>[] = [
      {
        ...COLUMNS[0],
        onCellClick,
        inlineActions: () => [{ key: 'ubah', label: 'Ubah', onClick: onInlineAction }],
      },
      COLUMNS[1],
    ]
    render(<DataTable columns={columns} rows={ROWS} rowKey={(row) => row.id} onRowClick={onRowClick} />)

    expect(screen.queryByRole('columnheader', { name: 'Aksi' })).toBeNull()
    await user.click(screen.getAllByLabelText('Aksi sel')[0])
    await user.click(screen.getByRole('menuitem', { name: 'Ubah' }))

    expect(onInlineAction).toHaveBeenCalledTimes(1)
    expect(onRowClick).not.toHaveBeenCalled()
  })

  it('mempertahankan menu aksi legacy dan mencegahnya memicu klik baris', async () => {
    const user = userEvent.setup()
    const onLegacyAction = vi.fn()
    const onRowClick = vi.fn()
    renderTable({
      onRowClick,
      rowActions: () => [{ key: 'hapus', label: 'Hapus', onClick: onLegacyAction }],
    })

    expect(screen.getByRole('columnheader', { name: 'Aksi' })).toBeDefined()
    await user.click(screen.getAllByLabelText('Aksi baris')[0])
    await user.click(screen.getByRole('menuitem', { name: 'Hapus' }))

    expect(onLegacyAction).toHaveBeenCalledTimes(1)
    expect(onRowClick).not.toHaveBeenCalled()
  })

  it('menampilkan keadaan kosong, bukan tabel tanpa isi', () => {
    render(<DataTable columns={COLUMNS} rows={[]} rowKey={(r: Row) => r.id} />)
    expect(screen.getByText('Tidak ada data')).toBeDefined()
  })

  it('mengubah seleksi lewat checkbox baris tanpa ikut memicu klik baris', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const onRowClick = vi.fn()
    renderTable({ selection: { selected: new Set<string>(), onChange }, onRowClick })

    const firstRow = screen.getAllByRole('row')[1]
    await user.click(within(firstRow).getByLabelText('Pilih baris'))

    expect(onChange).toHaveBeenCalledWith(new Set(['2']))
    // Sel checkbox menghentikan propagasi; kalau tidak, memilih baris ikut membukanya.
    expect(onRowClick).not.toHaveBeenCalled()
  })
})
