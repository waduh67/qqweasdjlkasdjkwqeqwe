import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Download, FileUp, Pencil, Plus, RefreshCw, Trash2, Upload } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { CustomerStatus, CustomerView } from '../api/network'
import { useCan } from '../auth/useCan'
import { DataTable, type Column, type RowAction } from '../components/DataTable'
import { CommandBar, type CommandAction } from '../components/CommandBar'
import { PageHeader } from '../components/PageHeader'
import { LocationPicker } from '../components/LocationPicker'
import { exportCustomersCsv } from '../api/onboarding'
import { EmptyState, Modal, SearchInput, StatusBadge, Toolbar, useToast } from '../components/ui'
import { IconCustomers } from '../components/icons'

/**
 * Draft form pelanggan, dipakai bersama untuk tambah & sunting. `id` null = tambah baru;
 * terisi = menyunting pelanggan itu (PUT). `areaId` dibawa apa adanya (form ini tak punya
 * pemilih area) agar sunting field lain tak diam-diam menghapus penempatan area pelanggan.
 */
type CustomerDraft = {
  id: string | null
  code: string
  name: string
  phone: string
  email: string
  idCardNumber: string
  address: string
  longitude: string
  latitude: string
  areaId: string | null
}

const EMPTY_CUSTOMER: CustomerDraft = {
  id: null,
  code: '',
  name: '',
  phone: '',
  email: '',
  idCardNumber: '',
  address: '',
  longitude: '',
  latitude: '',
  areaId: null,
}

const STATUS_OPTIONS: { value: CustomerStatus | ''; label: string }[] = [
  { value: '', label: 'Semua status' },
  { value: 'PROSPECT', label: 'Prospek' },
  { value: 'ACTIVE', label: 'Aktif' },
  { value: 'SUSPENDED', label: 'Ditangguhkan' },
  { value: 'TERMINATED', label: 'Berhenti' },
]

/** Ringkas kepemilikan ONU pelanggan untuk satu sel tabel. */
function onuSummary(customer: CustomerView): string {
  if (customer.onus.length === 0) return 'Belum ada ONU'
  const attached = customer.onus.find((o) => o.odpCode)
  return `${customer.onus.length} ONU${attached ? ` · ${attached.odpCode} port ${attached.odpPortNumber}` : ''}`
}

/**
 * Daftar pelanggan — tabel padat bisa-urut dengan pencarian & filter status di atasnya.
 * Klik satu baris membuka halaman detail (`/customers/:id`), tempat semua data & aksi
 * per-pelanggan berada. Halaman ini fokus mencari, menyaring, menambah, dan menyunting
 * data pokok pelanggan lewat modal (aksi "Edit" di tiap baris — beda dari klik-baris).
 */
export function CustomersPage() {
  const { can } = useCan()
  const toast = useToast()
  const navigate = useNavigate()
  const [customers, setCustomers] = useState<CustomerView[]>([])
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState<CustomerStatus | ''>('')
  const [loading, setLoading] = useState(true)
  const [draft, setDraft] = useState<CustomerDraft | null>(null)
  const [saving, setSaving] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [deleting, setDeleting] = useState(false)

  // Ekspor butuh izin BACA union (pelanggan+langganan+akun) — cocok gating server; disable, bukan sembunyi.
  const canExport =
    can('customer.customer.view') && can('customer.subscription.view') && can('bng.access.view')
  const canDelete = can('customer.customer.delete')

  const exportCsv = async () => {
    setExporting(true)
    try {
      // Byte ter-gate (butuh Bearer) → ambil Blob dulu, lalu jadikan unduhan lewat object URL.
      const blob = await exportCustomersCsv()
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'pelanggan.csv'
      document.body.appendChild(a)
      a.click()
      a.remove()
      URL.revokeObjectURL(url)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengekspor CSV')
    } finally {
      setExporting(false)
    }
  }

  const reload = useCallback(async () => {
    try {
      const page = await api.get<PageResponse<CustomerView>>(
        `/api/customers?size=100&query=${encodeURIComponent(query)}`,
      )
      setCustomers(page.content)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat pelanggan')
    } finally {
      setLoading(false)
    }
  }, [query, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const rows = useMemo(
    () => (statusFilter ? customers.filter((c) => c.status === statusFilter) : customers),
    [customers, statusFilter],
  )

  // Buka modal sunting berisi data pelanggan saat ini. `code` immutable di server, jadi
  // ditampilkan read-only; `email`/`areaId` ikut dibawa agar tak terhapus saat menyimpan.
  const startEdit = (c: CustomerView) =>
    setDraft({
      id: c.id,
      code: c.code,
      name: c.name,
      phone: c.phone ?? '',
      email: c.email ?? '',
      idCardNumber: c.idCardNumber ?? '',
      address: c.address,
      longitude: String(c.location.longitude),
      latitude: String(c.location.latitude),
      areaId: c.areaId,
    })

  // Satu jalur simpan untuk tambah (POST) & sunting (PUT) — badan permintaan sama;
  // `id` menentukan endpoint. `code` disertakan tapi diabaikan server saat menyunting.
  const save = async () => {
    if (!draft) return
    setSaving(true)
    try {
      const body = {
        code: draft.code.trim() || undefined,
        name: draft.name,
        phone: draft.phone || null,
        email: draft.email.trim() || null,
        idCardNumber: draft.idCardNumber.trim() || null,
        address: draft.address,
        location: { longitude: Number(draft.longitude), latitude: Number(draft.latitude) },
        areaId: draft.areaId,
      }
      if (draft.id) {
        await api.put(`/api/customers/${draft.id}`, body)
      } else {
        await api.post('/api/customers', body)
      }
      setDraft(null)
      await reload()
      toast.success(draft.id ? 'Data pelanggan diperbarui' : 'Pelanggan ditambahkan')
    } catch (err) {
      const fallback = draft.id ? 'Gagal memperbarui pelanggan' : 'Gagal menambah pelanggan'
      toast.error(err instanceof ApiError ? err.message : fallback)
    } finally {
      setSaving(false)
    }
  }

  // Hapus satu pelanggan lewat menu aksi baris (`…`) — konfirmasi dulu (aksi destruktif),
  // lalu bersihkan dari seleksi bila kebetulan tercentang agar CommandBar tak salah hitung.
  const deleteOne = async (c: CustomerView) => {
    if (!window.confirm(`Hapus pelanggan "${c.name}"? Tindakan ini tidak dapat dibatalkan.`)) return
    try {
      await api.del(`/api/customers/${c.id}`)
      setSelected((prev) => {
        const next = new Set(prev)
        next.delete(c.id)
        return next
      })
      await reload()
      toast.success('Pelanggan dihapus')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus pelanggan')
    }
  }

  // Hapus massal dari CommandBar — nonaktif sampai ada baris tercentang (pola Azure).
  const deleteSelected = async () => {
    const ids = [...selected]
    if (ids.length === 0) return
    if (!window.confirm(`Hapus ${ids.length} pelanggan terpilih? Tindakan ini tidak dapat dibatalkan.`))
      return
    setDeleting(true)
    try {
      await Promise.all(ids.map((id) => api.del(`/api/customers/${id}`)))
      setSelected(new Set())
      await reload()
      toast.success(`${ids.length} pelanggan dihapus`)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus pelanggan')
    } finally {
      setDeleting(false)
    }
  }

  const columns: Column<CustomerView>[] = [
    {
      key: 'name',
      header: 'Nama',
      sortValue: (c) => c.name,
      cell: (c) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <strong>{c.name}</strong>
          <span className="muted" style={{ fontSize: '0.8rem' }}>{c.phone ?? 'tanpa nomor'}</span>
        </div>
      ),
    },
    { key: 'code', header: 'Kode', sortValue: (c) => c.code, cell: (c) => <span className="badge">{c.code}</span> },
    {
      key: 'status',
      header: 'Status',
      sortValue: (c) => c.status,
      cell: (c) => (
        <div className="row" style={{ gap: '0.3rem', flexWrap: 'wrap' }}>
          <StatusBadge status={c.status} />
          {c.awaitingInstallation && <StatusBadge status="PENDING" label="menunggu instalasi" />}
        </div>
      ),
    },
    { key: 'address', header: 'Alamat', sortValue: (c) => c.address, cell: (c) => c.address },
    { key: 'onu', header: 'ONU', sortValue: (c) => c.onus.length, cell: (c) => onuSummary(c) },
  ]

  // Aksi per-baris kini di menu `…` ala Azure DataGrid (kiri baris), bukan tombol inline.
  const canUpdate = can('customer.customer.update')
  const hasRowActions = canUpdate || canDelete
  const rowActions = (c: CustomerView): RowAction[] => {
    const list: RowAction[] = []
    if (canUpdate)
      list.push({ key: 'edit', label: 'Edit', icon: <Pencil size={16} />, onClick: () => startEdit(c) })
    if (canDelete)
      list.push({
        key: 'delete',
        label: 'Hapus',
        icon: <Trash2 size={16} />,
        onClick: () => void deleteOne(c),
      })
    return list
  }

  // CommandBar: primary `+ Tambah` dipatok kiri; sekunder berjajar berkelompok
  // (hapus | ekspor/impor | segarkan). Pemisah disisipkan di awal tiap kelompok non-kosong.
  const primary: CommandAction | undefined = can('customer.customer.create')
    ? {
        key: 'create',
        label: 'Tambah pelanggan',
        icon: <Plus size={16} />,
        onClick: () => setDraft({ ...EMPTY_CUSTOMER }),
      }
    : undefined

  const actions: CommandAction[] = []
  const pushGroup = (items: CommandAction[]) => {
    items.forEach((it, i) => actions.push({ ...it, dividerBefore: i === 0 && actions.length > 0 }))
  }
  pushGroup(
    canDelete
      ? [
          {
            key: 'delete',
            label: 'Hapus',
            icon: <Trash2 size={16} />,
            onClick: () => void deleteSelected(),
            disabled: selected.size === 0 || deleting,
          },
        ]
      : [],
  )
  pushGroup([
    ...(canExport
      ? [
          {
            key: 'export',
            label: exporting ? 'Mengekspor…' : 'Ekspor CSV',
            icon: <Download size={16} />,
            onClick: () => void exportCsv(),
            disabled: exporting,
          },
        ]
      : []),
    ...(can('customer.customer.create')
      ? [
          {
            key: 'import-csv',
            label: 'Impor CSV',
            icon: <Upload size={16} />,
            onClick: () => navigate('/import-customers'),
          },
          {
            key: 'import-pppoe',
            label: 'Impor PPPoE',
            icon: <FileUp size={16} />,
            onClick: () => navigate('/import-pppoe'),
          },
        ]
      : []),
  ])
  pushGroup([
    { key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, onClick: () => void reload() },
  ])

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <PageHeader
        title="Pelanggan"
        subtitle="Data pelanggan, perangkat ONU, dan penempatannya di ODP."
      />

      {/* Impor/ekspor massal menyatu di area Pelanggan (dulu menu sidebar tersendiri). Ekspor
          digating izin BACA (bisa untuk operator view-only); impor digating izin buat pelanggan. */}
      <CommandBar primary={primary} actions={actions} />

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama, kode, alamat, atau telepon…" />
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as CustomerStatus | '')}>
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(c) => c.id}
        onRowClick={(c) => navigate(`/customers/${c.id}`)}
        loading={loading}
        initialSort={{ key: 'name', dir: 'asc' }}
        selection={canDelete ? { selected, onChange: setSelected } : undefined}
        rowActions={hasRowActions ? rowActions : undefined}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada pelanggan yang cocok' : 'Belum ada pelanggan'}
            hint={query || statusFilter ? 'Coba ubah kata kunci atau filter.' : 'Tambahkan pelanggan pertama untuk mulai memasang ONU.'}
            icon={<IconCustomers size={32} />}
          />
        }
      />

      {draft && (
        <Modal
          title={draft.id ? 'Edit pelanggan' : 'Tambah pelanggan'}
          onClose={() => setDraft(null)}
          footer={
            <>
              <button onClick={() => setDraft(null)}>Batal</button>
              <button className="primary" onClick={() => void save()} disabled={saving}>Simpan</button>
            </>
          }
        >
          <div className="stack">
            <div className="row">
              <label style={{ flex: 1 }}>
                <span>Kode</span>
                <input
                  value={draft.code}
                  onChange={(e) => setDraft({ ...draft, code: e.target.value })}
                  placeholder="Otomatis: CUST-000001"
                  disabled={draft.id != null}
                  title={draft.id ? 'Kode pelanggan tidak dapat diubah' : undefined}
                />
              </label>
              <label style={{ flex: 2 }}>
                <span>Nama</span>
                <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} autoFocus />
              </label>
            </div>
            <div className="row">
              <label style={{ flex: 1 }}>
                <span>Telepon</span>
                <input value={draft.phone} onChange={(e) => setDraft({ ...draft, phone: e.target.value })} placeholder="08123456789" />
              </label>
              <label style={{ flex: 1 }}>
                <span>NIK / No. identitas</span>
                <input value={draft.idCardNumber} onChange={(e) => setDraft({ ...draft, idCardNumber: e.target.value })} placeholder="opsional" />
              </label>
            </div>
            <label>
              <span>Email</span>
              <input
                type="email"
                value={draft.email}
                onChange={(e) => setDraft({ ...draft, email: e.target.value })}
                placeholder="opsional"
              />
            </label>
            <label>
              <span>Alamat</span>
              <input value={draft.address} onChange={(e) => setDraft({ ...draft, address: e.target.value })} />
            </label>
            <label>
              <span>Lokasi</span>
              <LocationPicker
                longitude={draft.longitude}
                latitude={draft.latitude}
                onChange={(longitude, latitude) => setDraft({ ...draft, longitude, latitude })}
                onAddress={(address) => setDraft(draft.address.trim() ? draft : { ...draft, address })}
              />
            </label>
          </div>
        </Modal>
      )}
    </div>
  )
}
