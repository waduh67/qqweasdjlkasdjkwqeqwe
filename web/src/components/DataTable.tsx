import { useMemo, useState, type KeyboardEvent, type ReactElement, type ReactNode } from 'react'
import {
  Checkbox,
  Menu,
  MenuTrigger,
  MenuPopover,
  MenuList,
  MenuItem,
  MenuButton,
} from '@fluentui/react-components'
import { MoreHorizontal } from 'lucide-react'
import { IconChevronDown, IconChevronsUpDown } from './icons'
import { EmptyState, SkeletonRows } from './ui'

/**
 * Tabel data generik — pengganti tunggal untuk pola "kartu per item" di seluruh
 * aplikasi. Menyajikan data padat, bisa diurut per-kolom, dan (opsional) baris
 * bisa diklik. Filter/pencarian ditaruh di atasnya lewat [Toolbar]; pengurutan
 * dilakukan di sisi klien dari [Column.sortValue] agar tak membebani server untuk
 * daftar berukuran wajar.
 *
 * Ekstensi ala Azure DataGrid: kolom **checkbox** multi-select (`selection`) di
 * paling kiri, dan kolom **menu aksi** (`rowActions`, tombol `…`) tepat setelahnya —
 * dua-duanya opsional agar 16 pemakai lama tak wajib berubah.
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

/** Satu operasi baris di menu aksi kiri (`…`). */
export type RowAction = {
  key: string
  label: string
  icon?: ReactElement
  onClick: () => void
  disabled?: boolean
}

/** Kontrak seleksi multi-baris (controlled). */
export type Selection = {
  selected: Set<string>
  onChange: (next: Set<string>) => void
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
  selection,
  rowActions,
}: {
  columns: Column<T>[]
  rows: T[]
  rowKey: (row: T) => string
  onRowClick?: (row: T) => void
  loading?: boolean
  empty?: ReactNode
  initialSort?: { key: string; dir: 'asc' | 'desc' }
  selection?: Selection
  rowActions?: (row: T) => RowAction[]
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

  // Seleksi: hitung keadaan header (kosong/penuh/campuran) atas baris yang tampak.
  const allKeys = sorted.map(rowKey)
  const selCount = selection ? allKeys.filter((k) => selection.selected.has(k)).length : 0
  const headerChecked: boolean | 'mixed' =
    selCount === 0 ? false : selCount === allKeys.length ? true : 'mixed'

  const toggleAll = () => {
    if (!selection) return
    const next = new Set(selection.selected)
    if (selCount === allKeys.length) allKeys.forEach((k) => next.delete(k))
    else allKeys.forEach((k) => next.add(k))
    selection.onChange(next)
  }

  const toggleOne = (key: string) => {
    if (!selection) return
    const next = new Set(selection.selected)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    selection.onChange(next)
  }

  const leadCols = (selection ? 1 : 0) + (rowActions ? 1 : 0)

  return (
    <div className="card" style={{ padding: 0, overflow: 'hidden' }}>
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              {selection && (
                <th className="dg-check" style={{ width: '1%' }}>
                  <Checkbox
                    checked={headerChecked}
                    onChange={toggleAll}
                    aria-label="Pilih semua baris"
                  />
                </th>
              )}
              {rowActions && <th className="dg-actions" style={{ width: '1%' }} aria-label="Aksi" />}
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
                const key = rowKey(row)
                const onKey = (e: KeyboardEvent<HTMLTableRowElement>) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    e.preventDefault()
                    onRowClick!(row)
                  }
                }
                const selected = selection?.selected.has(key) ?? false
                const actions = rowActions?.(row) ?? []
                return (
                  <tr
                    key={key}
                    className={
                      [clickable ? 'row-click' : '', selected ? 'row-selected' : '']
                        .filter(Boolean)
                        .join(' ') || undefined
                    }
                    onClick={clickable ? () => onRowClick!(row) : undefined}
                    onKeyDown={clickable ? onKey : undefined}
                    tabIndex={clickable ? 0 : undefined}
                  >
                    {selection && (
                      <td className="dg-check" onClick={(e) => e.stopPropagation()}>
                        <Checkbox
                          checked={selected}
                          onChange={() => toggleOne(key)}
                          aria-label="Pilih baris"
                        />
                      </td>
                    )}
                    {rowActions && (
                      <td className="dg-actions" onClick={(e) => e.stopPropagation()}>
                        {actions.length > 0 && (
                          <Menu positioning="below-start">
                            <MenuTrigger disableButtonEnhancement>
                              <MenuButton
                                appearance="transparent"
                                icon={<MoreHorizontal size={16} />}
                                aria-label="Aksi baris"
                                size="small"
                              />
                            </MenuTrigger>
                            <MenuPopover>
                              <MenuList>
                                {actions.map((a) => (
                                  <MenuItem
                                    key={a.key}
                                    icon={a.icon}
                                    disabled={a.disabled}
                                    onClick={a.onClick}
                                  >
                                    {a.label}
                                  </MenuItem>
                                ))}
                              </MenuList>
                            </MenuPopover>
                          </Menu>
                        )}
                      </td>
                    )}
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
          <SkeletonRows rows={5} cols={columns.length + leadCols} />
        </div>
      )}
      {!loading && sorted.length === 0 && (
        <div style={{ padding: '0.75rem' }}>{empty ?? <EmptyState title="Tidak ada data" />}</div>
      )}
    </div>
  )
}
