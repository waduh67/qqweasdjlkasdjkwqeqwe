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

describe('DataTable', () => {
  it('menggambar tiap baris beserta judul kolomnya', () => {
    renderTable()
    expect(screen.getByText('Budi')).toBeDefined()
    expect(screen.getByText('Siti')).toBeDefined()
    expect(screen.getAllByRole('row')).toHaveLength(3) // 1 kepala + 2 isi
  })

  it('menuliskan judul kolom sebagai data-label tiap sel — bahan mode kartu di ponsel', () => {
    const { container } = renderTable()
    const cells = container.querySelectorAll('tbody tr:first-child td')
    expect(Array.from(cells).map((td) => td.getAttribute('data-label'))).toEqual(['Nama', 'Tagihan'])
  })

  it('mengurutkan naik lalu turun lalu kembali ke urutan asli saat judul diklik', async () => {
    const user = userEvent.setup()
    const { container } = renderTable()
    const firstName = () => container.querySelector('tbody tr td')?.textContent

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
