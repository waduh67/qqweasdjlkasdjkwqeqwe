import { useMemo, useState, type KeyboardEvent, type MouseEvent, type ReactElement, type ReactNode } from 'react'
import {
  Button,
  DataGrid,
  DataGridBody,
  DataGridCell,
  DataGridHeader,
  DataGridHeaderCell,
  DataGridRow,
  Link,
  Menu,
  MenuButton,
  MenuItem,
  MenuList,
  MenuPopover,
  MenuTrigger,
  TableCellActions,
  createTableColumn,
  makeStyles,
  mergeClasses,
  tokens,
  type TableColumnDefinition,
} from '@fluentui/react-components'
import { MoreHorizontal } from 'lucide-react'
import { EmptyState, SkeletonRows } from '@/components/atoms'

/**
 * Tabel data generik — pengganti tunggal untuk pola "kartu per item" di seluruh
 * aplikasi. Menyajikan data padat, bisa diurut per-kolom, dan (opsional) baris
 * bisa diklik. Filter/pencarian ditaruh di atasnya lewat [Toolbar]; pengurutan
 * dilakukan di sisi klien dari [Column.sortValue] agar tak membebani server untuk
 * daftar berukuran wajar.
 *
 * Ekstensi ala Azure DataGrid: kolom **checkbox** multi-select (`selection`) di
 * paling kiri, dan kolom **menu aksi** (`rowActions`, tombol `…`) tepat setelahnya —
 * dua-duanya opsional agar pemakai lama tak wajib berubah.
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
  /** Kelas tambahan pada sel grid. */
  className?: string
  /** Membuka detail dari kontrol tautan pada sel kolom ini. */
  onCellClick?: (row: T) => void
  /** Aksi yang tampil di samping isi sel kolom ini. */
  inlineActions?: (row: T) => RowAction[]
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

const ACTIONS_COLUMN_ID = '__data_table_actions__'

const useStyles = makeStyles({
  grid: {
    minWidth: 'max-content',
  },
  headerCell: {
    minWidth: '0',
    justifyContent: 'flex-start',
    fontWeight: tokens.fontWeightSemibold,
    textAlign: 'left',
  },
  headerSortButton: {
    justifyContent: 'flex-start',
    textAlign: 'left',
  },
  dataCell: {
    fontWeight: tokens.fontWeightRegular,
    textAlign: 'left',
  },
  numeric: {
    fontVariantNumeric: 'tabular-nums',
  },
  clickableRow: {
    cursor: 'pointer',
  },
  actionCell: {
    width: '1%',
  },
  cellLink: {
    color: tokens.colorBrandForegroundLink,
    fontWeight: tokens.fontWeightRegular,
    ':hover': {
      color: tokens.colorBrandForegroundLinkHover,
      textDecorationLine: 'underline',
    },
    ':focus-visible': {
      color: tokens.colorBrandForegroundLinkHover,
      textDecorationLine: 'underline',
    },
  },
})

function compareValues<T>(column: Column<T>, a: T, b: T): number {
  const av = column.sortValue!(a)
  const bv = column.sortValue!(b)
  const an = av == null || av === ''
  const bn = bv == null || bv === ''
  if (an && bn) return 0
  if (an) return 1
  if (bn) return -1
  if (typeof av === 'number' && typeof bv === 'number') return av - bv
  return String(av).localeCompare(String(bv), 'id', { numeric: true })
}

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
  const styles = useStyles()
  const [sort, setSort] = useState<SortState>(initialSort ?? null)
  const clickable = !!onRowClick

  const sorted = useMemo(() => {
    if (!sort) return rows
    const column = columns.find((candidate) => candidate.key === sort.key)
    if (!column?.sortValue) return rows
    const factor = sort.dir === 'asc' ? 1 : -1
    // Salin dulu agar prop tak termutasi; nulls-last, sadar-angka untuk "id".
    return [...rows].sort((a, b) => {
      const comparison = compareValues(column, a, b)
      const aEmpty = column.sortValue!(a) == null || column.sortValue!(a) === ''
      const bEmpty = column.sortValue!(b) == null || column.sortValue!(b) === ''
      return aEmpty || bEmpty ? comparison : comparison * factor
    })
  }, [columns, rows, sort])

  const dataGridColumns = useMemo<TableColumnDefinition<T>[]>(() => {
    const contentColumns = columns.map((column) =>
      createTableColumn<T>({
        columnId: column.key,
        renderHeaderCell: () => column.header,
        renderCell: (row) => {
          const actions = column.inlineActions?.(row) ?? []
          const hasInlineControls = !!column.onCellClick || actions.length > 0
          return (
            <>
              {column.onCellClick ? (
                <Link
                  as="button"
                  appearance="subtle"
                  className={styles.cellLink}
                  onClick={(event) => {
                    event.stopPropagation()
                    column.onCellClick?.(row)
                  }}
                >
                  {column.cell(row)}
                </Link>
              ) : (
                column.cell(row)
              )}
              {actions.length > 0 && (
                <TableCellActions visible={hasInlineControls}>
                  <Menu positioning="below-end">
                    <MenuTrigger disableButtonEnhancement>
                      <MenuButton
                        appearance="transparent"
                        icon={<MoreHorizontal size={16} />}
                        aria-label="Aksi sel"
                        size="small"
                        onClick={(event) => event.stopPropagation()}
                      />
                    </MenuTrigger>
                    <MenuPopover>
                      <MenuList>
                        {actions.map((action) => (
                          <MenuItem
                            key={action.key}
                            icon={action.icon}
                            disabled={action.disabled}
                            onClick={(event) => {
                              event.stopPropagation()
                              action.onClick()
                            }}
                          >
                            {action.label}
                          </MenuItem>
                        ))}
                      </MenuList>
                    </MenuPopover>
                  </Menu>
                </TableCellActions>
              )}
            </>
          )
        },
      }),
    )

    if (!rowActions) return contentColumns

    return [
      createTableColumn<T>({
        columnId: ACTIONS_COLUMN_ID,
        renderHeaderCell: () => 'Aksi',
        renderCell: (row) => {
          const actions = rowActions(row)
          if (actions.length === 0) return null
          return (
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
                  {actions.map((action) => (
                    <MenuItem
                      key={action.key}
                      icon={action.icon}
                      disabled={action.disabled}
                      onClick={action.onClick}
                    >
                      {action.label}
                    </MenuItem>
                  ))}
                </MenuList>
              </MenuPopover>
            </Menu>
          )
        },
      }),
      ...contentColumns,
    ]
  }, [columns, rowActions, styles.cellLink])

  const toggleSort = (column: Column<T>) => {
    setSort((previous) => {
      if (!previous || previous.key !== column.key) return { key: column.key, dir: 'asc' }
      if (previous.dir === 'asc') return { key: column.key, dir: 'desc' }
      return null
    })
  }

  const handleSelectionChange = (
    _event: React.MouseEvent | React.KeyboardEvent,
    data: { selectedItems: Set<string | number> },
  ) => {
    if (!selection) return
    const visibleKeys = new Set(rows.map(rowKey))
    const next = new Set([...selection.selected].filter((key) => !visibleKeys.has(key)))
    data.selectedItems.forEach((key) => next.add(String(key)))
    selection.onChange(next)
  }

  const leadCols = (selection ? 1 : 0) + (rowActions ? 1 : 0)

  return (
    <div className="card table-card">
      {!loading && (
        <div className="table-wrap">
          <DataGrid
            className={mergeClasses('data-table-grid', styles.grid)}
            aria-label="Tabel data"
            items={sorted}
            columns={dataGridColumns}
            getRowId={(row) => rowKey(row)}
            selectionMode={selection ? 'multiselect' : undefined}
            selectedItems={selection?.selected}
            onSelectionChange={selection ? handleSelectionChange : undefined}
            focusMode={clickable ? 'row_unstable' : 'cell'}
            selectionAppearance="neutral"
          >
            <DataGridHeader>
              <DataGridRow
                selectionCell={
                  selection
                    ? { checkboxIndicator: { 'aria-label': 'Pilih semua baris' } }
                    : undefined
                }
              >
                {({ renderHeaderCell, columnId }) => {
                  const column = columns.find((candidate) => candidate.key === columnId)
                  return (
                    <DataGridHeaderCell
                      className={mergeClasses(styles.headerCell, column?.align === 'right' && styles.numeric)}
                      style={{ width: columnId === ACTIONS_COLUMN_ID ? '1%' : column?.width, textAlign: column?.align }}
                    >
                      {column?.sortValue ? (
                        <Button
                          appearance="transparent"
                          className={styles.headerSortButton}
                          onClick={() => toggleSort(column)}
                        >
                          {renderHeaderCell()}
                        </Button>
                      ) : (
                        renderHeaderCell()
                      )}
                    </DataGridHeaderCell>
                  )
                }}
              </DataGridRow>
            </DataGridHeader>
            <DataGridBody<T>>
              {({ item, rowId }) => {
                const activateRow = () => onRowClick?.(item)
                const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
                  if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault()
                    activateRow()
                  }
                }

                return (
                  <DataGridRow
                    key={rowId}
                    className={mergeClasses(clickable && styles.clickableRow)}
                    onClick={
                      clickable
                        ? (event: MouseEvent<HTMLDivElement>) => {
                            if (event.target instanceof HTMLElement && event.target.closest('input[type="checkbox"]')) return
                            activateRow()
                          }
                        : undefined
                    }
                    onKeyDown={clickable ? onKeyDown : undefined}
                    selectionCell={
                      selection ? { checkboxIndicator: { 'aria-label': 'Pilih baris' } } : undefined
                    }
                  >
                    {({ renderCell, columnId }) => {
                       const column = columns.find((candidate) => candidate.key === columnId)
                       const isActionCell = columnId === ACTIONS_COLUMN_ID
                       const hasInlineControls = !!column?.onCellClick || (column?.inlineActions?.(item).length ?? 0) > 0
                       return (
                        <DataGridCell
                          className={mergeClasses(
                            styles.dataCell,
                            column?.align === 'right' && styles.numeric,
                            column?.className,
                            isActionCell && styles.actionCell,
                          )}
                          data-label={typeof column?.header === 'string' ? column.header : undefined}
                          focusMode={isActionCell || hasInlineControls ? 'group' : 'none'}
                          onClick={
                            isActionCell || hasInlineControls
                              ? (event) => event.stopPropagation()
                              : undefined
                          }
                          style={{ textAlign: column?.align }}
                        >
                          {renderCell(item)}
                        </DataGridCell>
                      )
                    }}
                  </DataGridRow>
                )
              }}
            </DataGridBody>
          </DataGrid>
        </div>
      )}
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
