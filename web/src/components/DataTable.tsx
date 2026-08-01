import { useMemo, useState, type KeyboardEvent, type ReactNode } from 'react'
import { IconChevronDown, IconChevronsUpDown } from './icons'
import { EmptyState, SkeletonRows } from './ui'

/**
 * Tabel data generik — pengganti tunggal untuk pola "kartu per item" di seluruh
 * aplikasi. Menyajikan data padat, bisa diurut per-kolom, dan (opsional) baris
 * bisa diklik. Filter/pencarian ditaruh di atasnya lewat [Toolbar]; pengurutan
 * dilakukan di sisi klien dari [Column.sortValue] agar tak membebani server untuk
 * daftar berukuran wajar.
 */
export type Column<T> = {
  /** Kunci unik kolom — dipakai sebagai React key dan penanda state urut. */
  key: string
  /** Judul kolom di kepala tabel. */
  header: ReactNode
  /** Render isi sel dari satu baris. */
  cell: (row: T) => ReactNode
  /**
   * Nilai pembanding untuk pengurutan. Bila diisi, kolom jadi bisa diklik-urut.
   * `null`/kosong selalu ditaruh di bawah, apa pun arah urutnya.
   */
  sortValue?: (row: T) => string | number | null | undefined
  /** Perataan sel (default kiri). `right` juga memakai angka tabular. */
  align?: 'left' | 'right' | 'center'
  /** Lebar kolom eksplisit (mis. `'1%'` untuk kolom aksi yang menyusut). */
  width?: string
  /** Kelas tambahan pada `<td>`. */
  className?: string
}

type SortState = { key: string; dir: 'asc' | 'desc' } | null

export function DataTable<T>({
  columns,
  rows,
  rowKey,
  onRowClick,
  loading,
  empty,
  initialSort,
}: {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  onRowClick?: (row: T) => void
  loading?: boolean
  empty?: ReactNode
  initialSort?: { key: string; dir: 'asc' | 'desc' }
}) {
  const [sort, setSort] = useState<SortState>(initialSort ?? null)

  const sorted = useMemo(() => {
    if (!sort) return rows
    const col = columns.find((c) => c.key === sort.key)
    if (!col?.sortValue) return rows
    const getv = col.sortValue
    const factor = sort.dir === 'asc' ? 1 : -1
    // Salin dulu agar prop tak termutasi; nulls-last, sadar-angka untuk "id".
    return [...rows].sort((a, b) => {
      const av = getv(a)
      const bv = getv(b)
      const an = av == null || av === ''
      const bn = bv == null || bv === ''
      if (an && bn) return 0
      if (an) return 1
      if (bn) return -1
      if (typeof av === 'number' && typeof bv === 'number') return (av - bv) * factor
      return String(av).localeCompare(String(bv), 'id', { numeric: true }) * factor
    })
  }, [rows, sort, columns])

  // Tri-state: klik pertama asc → desc → bersih.
  const toggleSort = (col: Column<T>) => {
    if (!col.sortValue) return
    setSort((prev) => {
      if (!prev || prev.key !== col.key) return { key: col.key, dir: 'asc' }
      if (prev.dir === 'asc') return { key: col.key, dir: 'desc' }
      return null
    })
  }

  const clickable = !!onRowClick

  return (
    <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              {columns.map((col) => {
                const active = sort?.key === col.key
                const ariaSort = active
                  ? sort!.dir === 'asc'
                    ? 'ascending'
                    : 'descending'
                  : undefined
                return (
                  <th
                    key={col.key}
                    className={col.align === 'right' ? 'num' : undefined}
                    style={{ width: col.width, textAlign: col.align }}
                    aria-sort={ariaSort}
                  >
                    {col.sortValue ? (
                      <button className="th-sort" onClick={() => toggleSort(col)}>
                        {col.header}
                        {active ? (
                          <IconChevronDown
                            size={14}
                            className={sort!.dir === 'asc' ? 'flip' : undefined}
                          />
                        ) : (
                          <IconChevronsUpDown size={13} className="th-sort-idle" />
                        )}
                      </button>
                    ) : (
                      col.header
                    )}
                  </th>
                )
              })}
            </tr>
          </thead>
          {!loading && (
            <tbody>
              {sorted.map((row) => {
                const onKey = (e: KeyboardEvent<HTMLTableRowElement>) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onRowClick!(row)
                  }
                }
                return (
                  <tr
                    key={rowKey(row)}
                    className={clickable ? 'row-click' : undefined}
                    onClick={clickable ? () => onRowClick!(row) : undefined}
                    onKeyDown={clickable ? onKey : undefined}
                    tabIndex={clickable ? 0 : undefined}
                  >
                    {columns.map((col) => (
                      <td
                        key={col.key}
                        className={[col.align === 'right' ? 'num' : '', col.className ?? '']
                          .filter(Boolean)
                          .join(' ') || undefined}
                        style={{ textAlign: col.align }}
                      >
                        {col.cell(row)}
                      </td>
                    ))}
                  </tr>
                )
              })}
            </tbody>
          )}
        </table>
      </div>
      {loading && (
        <div style={{ padding: '1rem' }}>
          <SkeletonRows rows={5} cols={columns.length} />
        </div>
      )}
      {!loading && sorted.length === 0 && (
        <div style={{ padding: '0.75rem' }}>{empty ?? <EmptyState title="Tidak ada data" />}</div>
      )}
    </div>
  )
}
