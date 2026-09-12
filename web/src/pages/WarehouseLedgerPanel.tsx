import { useCallback, useEffect, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  listLedger,
  type LedgerFilter,
  type MovementEntryView,
  type MovementKind,
  type MovementState,
} from '@/api/inventory'
import { Badge, Button, EmptyState, SelectField, Toolbar } from '@/components/atoms'
import { DataTable, type Column } from '@/components/organisms'
import { useToast } from '@/system'
import type { WarehouseReference } from './WarehouseOperationsPage'
import {
  LOCATION_KIND_LABEL,
  MOVEMENT_KIND_LABEL,
  MOVEMENT_STATE_LABEL,
  MOVEMENT_STATE_TONE,
} from './WarehouseLabels'

const PAGE_SIZE = 20
const KINDS = Object.keys(MOVEMENT_KIND_LABEL) as MovementKind[]
const STATES = Object.keys(MOVEMENT_STATE_LABEL) as MovementState[]

/**
 * Riwayat mutasi — sumber kebenaran saat stok fisik tidak cocok dengan sistem.
 *
 * Ledger dipaging di server dan TIDAK diurut ulang di klien: halaman kedua yang diurut
 * sendiri hanya mengurutkan 20 baris yang kebetulan terlihat, lalu menampilkan urutan waktu
 * yang bohong saat petugas menelusuri "kapan barang ini hilang".
 */
export function WarehouseLedgerPanel({ reference }: { reference: WarehouseReference }) {
  const toast = useToast()
  const [entries, setEntries] = useState<readonly MovementEntryView[]>([])
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [totalElements, setTotalElements] = useState(0)
  const [loading, setLoading] = useState(true)
  const [filter, setFilter] = useState<LedgerFilter>({ kind: '', state: '', itemId: '', locationId: '' })

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const result = await listLedger(filter, page, PAGE_SIZE)
      setEntries(result.content)
      setTotalPages(result.totalPages)
      setTotalElements(result.totalElements)
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Gagal memuat riwayat mutasi')
      setEntries([])
    } finally {
      setLoading(false)
    }
  }, [filter, page, toast])

  useEffect(() => {
    void load()
  }, [load])

  // Setiap perubahan filter WAJIB melempar balik ke halaman pertama: menyaring saat berada di
  // halaman 7 dari hasil lama biasanya menyisakan tabel kosong, dan yang terbaca petugas
  // adalah "tidak ada mutasi jenis ini" padahal ada puluhan di halaman 1.
  const changeFilter = (patch: Partial<LedgerFilter>) => {
    setFilter((current) => ({ ...current, ...patch }))
    setPage(0)
  }

  const { names } = reference

  const columns: Column<MovementEntryView>[] = [
    {
      key: 'occurredAt',
      header: 'Waktu',
      cell: (row) => <Text as="span" className="tnum" size={200}>{formatMoment(row.occurredAt)}</Text>,
    },
    {
      key: 'kind',
      header: 'Jenis',
      cell: (row) => (
        <div className="stack" style={{ gap: 0 }}>
          <Text as="span">{MOVEMENT_KIND_LABEL[row.kind]}</Text>
          {row.compensatesMovementId && (
            <Text as="span" className="muted" size={200}>Pembalik mutasi sebelumnya</Text>
          )}
        </div>
      ),
    },
    {
      key: 'state',
      header: 'Status',
      cell: (row) => <Badge tone={MOVEMENT_STATE_TONE[row.state]}>{MOVEMENT_STATE_LABEL[row.state]}</Badge>,
    },
    {
      key: 'legs',
      header: 'Barang',
      cell: (row) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          {row.legs.map((leg, index) => (
            <Text as="span" size={200} key={`${leg.itemId}:${leg.locationId}:${leg.direction}:${index}`}>
              {leg.direction === 'IN' ? '↓ masuk' : '↑ keluar'} {names.itemName(leg.itemId)} × {leg.quantity} @{' '}
              {leg.locationCode}
              {leg.serialNumber ? ` · SN ${leg.serialNumber}` : ''}
            </Text>
          ))}
        </div>
      ),
    },
    {
      key: 'actor',
      header: 'Pelaku',
      cell: (row) => (
        <div className="stack" style={{ gap: 0 }}>
          <Text as="span">{names.user(row.actorId)}</Text>
          <Text as="span" className="muted" size={200}>{row.reason}</Text>
        </div>
      ),
    },
  ]

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <Toolbar>
        <SelectField
          label="Jenis"
          aria-label="Filter jenis mutasi"
          value={filter.kind ?? ''}
          onChange={(_, data) => changeFilter({ kind: data.value as MovementKind | '' })}
        >
          <option value="">Semua jenis</option>
          {KINDS.map((kind) => (
            <option key={kind} value={kind}>{MOVEMENT_KIND_LABEL[kind]}</option>
          ))}
        </SelectField>
        <SelectField
          label="Status"
          aria-label="Filter status mutasi"
          value={filter.state ?? ''}
          onChange={(_, data) => changeFilter({ state: data.value as MovementState | '' })}
        >
          <option value="">Semua status</option>
          {STATES.map((state) => (
            <option key={state} value={state}>{MOVEMENT_STATE_LABEL[state]}</option>
          ))}
        </SelectField>
        <SelectField
          label="Item"
          aria-label="Filter item"
          value={filter.itemId ?? ''}
          onChange={(_, data) => changeFilter({ itemId: data.value })}
        >
          <option value="">Semua item</option>
          {reference.items.map((item) => (
            <option key={item.id} value={item.id}>{item.name} ({item.code})</option>
          ))}
        </SelectField>
        <SelectField
          label="Lokasi"
          aria-label="Filter lokasi mutasi"
          value={filter.locationId ?? ''}
          onChange={(_, data) => changeFilter({ locationId: data.value })}
        >
          <option value="">Semua lokasi</option>
          {reference.locations.map((location) => (
            <option key={location.id} value={location.id}>
              {location.code} · {LOCATION_KIND_LABEL[location.kind]}
            </option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={[...entries]}
        rowKey={(row) => row.movementId}
        loading={loading}
        presentation="resource"
        empty={<EmptyState title="Tidak ada mutasi" hint="Ubah filter atau lakukan mutasi lebih dulu." />}
      />

      <div className="spread wrap">
        <Text as="span" className="muted" size={200}>
          {totalElements} mutasi · halaman {totalPages === 0 ? 0 : page + 1} dari {totalPages}
        </Text>
        <div className="row">
          <Button disabled={loading || page === 0} onClick={() => setPage((current) => Math.max(0, current - 1))}>
            Sebelumnya
          </Button>
          <Button
            disabled={loading || page + 1 >= totalPages}
            onClick={() => setPage((current) => current + 1)}
          >
            Berikutnya
          </Button>
        </div>
      </div>
    </div>
  )
}

function formatMoment(iso: string): string {
  const moment = new Date(iso)
  if (Number.isNaN(moment.getTime())) return iso
  return moment.toLocaleString('id-ID', { dateStyle: 'short', timeStyle: 'short' })
}
