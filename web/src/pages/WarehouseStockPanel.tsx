import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  listStockBalances,
  listVanStock,
  type InventoryStatus,
  type StockBalanceView,
  type VanStockView,
} from '@/api/inventory'
import { Badge, EmptyState, SelectField, Toolbar } from '@/components/atoms'
import { SearchInput } from '@/components/molecules'
import { DataTable, type Column } from '@/components/organisms'
import { useToast } from '@/system'
import type { WarehouseReference } from './WarehouseOperationsPage'
import {
  INVENTORY_STATUS_LABEL,
  LOCATION_KIND_LABEL,
  OWNER_KIND_LABEL,
} from './WarehouseLabels'

const STATUSES = Object.keys(INVENTORY_STATUS_LABEL) as InventoryStatus[]

/**
 * Saldo per item per lokasi.
 *
 * Kolom Item menampilkan NAMA, bukan UUID dan bukan sekadar kode. `GET /balances` hanya
 * mengirim `itemCode`, jadi namanya digabungkan di sini dari master data — inilah perbedaan
 * antara petugas yang bisa mencari "ONT ZTE F660" di rak dan petugas yang membaca
 * `9f3c…-a1` dan menyerah.
 */
export function WarehouseStockPanel({ reference }: { reference: WarehouseReference }) {
  const toast = useToast()
  const [balances, setBalances] = useState<readonly StockBalanceView[]>([])
  const [vanStock, setVanStock] = useState<readonly VanStockView[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<InventoryStatus | ''>('')
  const [locationId, setLocationId] = useState('')

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [nextBalances, nextVanStock] = await Promise.all([
        listStockBalances(undefined, locationId || undefined),
        listVanStock(),
      ])
      setBalances(nextBalances)
      setVanStock(nextVanStock)
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Gagal memuat saldo stok')
      setBalances([])
      setVanStock([])
    } finally {
      setLoading(false)
    }
  }, [locationId, toast])

  useEffect(() => {
    void load()
  }, [load])

  const { names } = reference

  const rows = useMemo(() => {
    const term = query.trim().toLowerCase()
    return balances.filter((row) => {
      if (status && row.status !== status) return false
      if (!term) return true
      return (
        names.itemName(row.itemId).toLowerCase().includes(term) ||
        row.itemCode.toLowerCase().includes(term) ||
        row.locationCode.toLowerCase().includes(term)
      )
    })
  }, [balances, names, query, status])

  const totalUnits = useMemo(() => rows.reduce((sum, row) => sum + row.quantity, 0), [rows])

  const columns: Column<StockBalanceView>[] = [
    {
      key: 'item',
      header: 'Item',
      sortValue: (row) => names.itemName(row.itemId),
      cell: (row) => (
        <div className="stack" style={{ gap: 0 }}>
          <Text as="span">{names.itemName(row.itemId)}</Text>
          <Text as="span" className="muted" size={200}>{row.itemCode}</Text>
        </div>
      ),
    },
    {
      key: 'location',
      header: 'Lokasi',
      sortValue: (row) => row.locationCode,
      cell: (row) => (
        <div className="stack" style={{ gap: 0 }}>
          <Text as="span">{row.locationCode}</Text>
          <Text as="span" className="muted" size={200}>{locationKindLabel(reference, row.locationId)}</Text>
        </div>
      ),
    },
    {
      key: 'custodian',
      header: 'Pemegang',
      sortValue: (row) => names.user(row.custodyOwnerId),
      cell: (row) => (
        <div className="stack" style={{ gap: 0 }}>
          <Text as="span">{names.user(row.custodyOwnerId)}</Text>
          <Text as="span" className="muted" size={200}>{OWNER_KIND_LABEL[row.custodyOwnerKind]}</Text>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (row) => row.status,
      cell: (row) => <Badge tone={row.status === 'AVAILABLE' ? 'good' : 'neutral'}>{INVENTORY_STATUS_LABEL[row.status]}</Badge>,
    },
    {
      key: 'quantity',
      header: 'Jumlah',
      align: 'right',
      sortValue: (row) => row.quantity,
      cell: (row) => <Text as="span" className="tnum">{row.quantity}</Text>,
    },
  ]

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama item, kode, atau lokasi…" />
        <SelectField
          label="Lokasi"
          aria-label="Filter lokasi"
          value={locationId}
          onChange={(_, data) => setLocationId(data.value)}
        >
          <option value="">Semua lokasi</option>
          {reference.locations.map((location) => (
            <option key={location.id} value={location.id}>
              {location.code} · {LOCATION_KIND_LABEL[location.kind]}
            </option>
          ))}
        </SelectField>
        <SelectField
          label="Status"
          aria-label="Filter status stok"
          value={status}
          onChange={(_, data) => setStatus(data.value as InventoryStatus | '')}
        >
          <option value="">Semua status</option>
          {STATUSES.map((entry) => (
            <option key={entry} value={entry}>{INVENTORY_STATUS_LABEL[entry]}</option>
          ))}
        </SelectField>
      </Toolbar>

      <div className="stat-grid">
        <Metric label="Baris saldo" value={String(rows.length)} />
        <Metric label="Total unit" value={String(totalUnits)} />
        <Metric label="Teknisi bawa stok" value={String(vanStock.length)} />
      </div>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(row) => `${row.itemId}:${row.locationId}:${row.custodyOwnerId}:${row.status}`}
        loading={loading}
        presentation="resource"
        initialSort={{ key: 'item', dir: 'asc' }}
        empty={<EmptyState title="Belum ada saldo stok" hint="Saldo muncul setelah penerimaan barang dibukukan." />}
      />

      <section className="card stack">
        <Text as="h3" weight="semibold">Stok di tangan teknisi</Text>
        {vanStock.length === 0 ? (
          <EmptyState title="Tidak ada material di teknisi" hint="Material yang dikeluarkan ke teknisi muncul di sini." />
        ) : (
          vanStock.map((van) => (
            <div className="stack" key={van.technicianId} style={{ gap: '0.25rem' }}>
              <Text as="strong">{names.user(van.technicianId)}</Text>
              {van.lines.map((line) => (
                <div className="spread wrap" key={`${line.itemId}:${line.locationId}:${line.status}`}>
                  <Text as="span">{names.itemName(line.itemId)}</Text>
                  <Text as="span" className="muted" size={200}>
                    {line.locationCode} · {INVENTORY_STATUS_LABEL[line.status]}
                    {line.serialNumbers.length > 0 && ` · SN ${line.serialNumbers.join(', ')}`}
                  </Text>
                  <Text as="span" className="tnum">{line.quantity}</Text>
                </div>
              ))}
            </div>
          ))
        )}
      </section>
    </div>
  )
}

function locationKindLabel(reference: WarehouseReference, locationId: string): string {
  const location = reference.locations.find((entry) => entry.id === locationId)
  return location ? LOCATION_KIND_LABEL[location.kind] : '—'
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="card stack" style={{ gap: '0.25rem' }}>
      <Text as="span" className="muted" size={200}>{label}</Text>
      <Text as="strong" size={600}>{value}</Text>
    </div>
  )
}
