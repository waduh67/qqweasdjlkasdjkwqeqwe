import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { CustomerView } from '../api/network'
import { useCan } from '../auth/useCan'
import { EmptyState, StatusBadge, useToast } from '../components/ui'
import { IconCustomers, IconPlus, IconSearch } from '../components/icons'

const EMPTY_CUSTOMER = { code: '', name: '', phone: '', address: '', longitude: '', latitude: '' }

/**
 * Daftar pelanggan — murni navigasi: klik satu baris untuk membuka halaman detail
 * (`/customers/:id`), tempat semua data & aksi per-pelanggan berada (perangkat ONU,
 * jalur, tetangga sejalur, metrik). Halaman ini fokus pada mencari dan menambah.
 */
export function CustomersPage() {
  const { can } = useCan()
  const toast = useToast()
  const navigate = useNavigate()
  const [customers, setCustomers] = useState<CustomerView[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [draft, setDraft] = useState<typeof EMPTY_CUSTOMER | null>(null)

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

  const create = async () => {
    if (!draft) return
    try {
      await api.post('/api/customers', {
        code: draft.code,
        name: draft.name,
        phone: draft.phone || null,
        address: draft.address,
        location: { longitude: Number(draft.longitude), latitude: Number(draft.latitude) },
      })
      setDraft(null)
      await reload()
      toast.success('Pelanggan ditambahkan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menambah pelanggan')
    }
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div className="spread">
        <div>
          <h1 className="page-title">Pelanggan</h1>
          <p className="page-sub">Data pelanggan, perangkat ONU, dan penempatannya di ODP.</p>
        </div>
        {can('customer.customer.create') && (
          <button className="primary" onClick={() => setDraft({ ...EMPTY_CUSTOMER })}>
            <IconPlus size={15} /> Tambah pelanggan
          </button>
        )}
      </div>

      <div style={{ position: 'relative' }}>
        <IconSearch
          size={16}
          style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)', color: 'var(--muted)' }}
        />
        <input
          placeholder="Cari nama, kode, alamat, atau nomor telepon…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ paddingLeft: '2.2rem' }}
        />
      </div>

      {draft && (
        <div className="card stack">
          <div className="row">
            <label style={{ flex: 1 }}>
              <span>Kode</span>
              <input value={draft.code} onChange={(e) => setDraft({ ...draft, code: e.target.value })} placeholder="CUST-0001" />
            </label>
            <label style={{ flex: 2 }}>
              <span>Nama</span>
              <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
            </label>
            <label style={{ flex: 1 }}>
              <span>Telepon</span>
              <input value={draft.phone} onChange={(e) => setDraft({ ...draft, phone: e.target.value })} placeholder="08123456789" />
            </label>
          </div>
          <div className="row">
            <label style={{ flex: 2 }}>
              <span>Alamat</span>
              <input value={draft.address} onChange={(e) => setDraft({ ...draft, address: e.target.value })} />
            </label>
            <label style={{ flex: 1 }}>
              <span>Longitude</span>
              <input value={draft.longitude} onChange={(e) => setDraft({ ...draft, longitude: e.target.value })} />
            </label>
            <label style={{ flex: 1 }}>
              <span>Latitude</span>
              <input value={draft.latitude} onChange={(e) => setDraft({ ...draft, latitude: e.target.value })} />
            </label>
          </div>
          <div className="row">
            <button className="primary" onClick={() => void create()}>Simpan</button>
            <button onClick={() => setDraft(null)}>Batal</button>
          </div>
        </div>
      )}

      {customers.map((customer) => (
        <CustomerRow key={customer.id} customer={customer} onOpen={() => navigate(`/customers/${customer.id}`)} />
      ))}

      {!loading && customers.length === 0 && (
        <div className="card">
          <EmptyState
            title={query ? 'Tidak ada pelanggan yang cocok' : 'Belum ada pelanggan'}
            hint={query ? 'Coba kata kunci lain.' : 'Tambahkan pelanggan pertama untuk mulai memasang ONU.'}
            icon={<IconCustomers size={32} />}
          />
        </div>
      )}
    </div>
  )
}

/** Satu baris pelanggan yang seluruhnya bisa diklik untuk membuka detail. */
function CustomerRow({ customer, onOpen }: { customer: CustomerView; onOpen: () => void }) {
  const attached = customer.onus.find((o) => o.odpCode)
  const onuSummary =
    customer.onus.length === 0
      ? 'Belum ada ONU'
      : `${customer.onus.length} ONU${attached ? ` · ${attached.odpCode} port ${attached.odpPortNumber}` : ''}`

  return (
    <div
      className="card clickable spread"
      role="button"
      tabIndex={0}
      onClick={onOpen}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onOpen()
        }
      }}
    >
      <div>
        <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
          <strong style={{ fontSize: '0.98rem' }}>{customer.name}</strong>
          <span className="badge">{customer.code}</span>
          <StatusBadge status={customer.status} />
          {customer.awaitingInstallation && <StatusBadge status="PENDING" label="menunggu instalasi" />}
        </div>
        <div className="muted" style={{ fontSize: '0.85rem', marginTop: '0.2rem' }}>
          {customer.address} · {customer.phone ?? 'tanpa nomor'} · {onuSummary}
        </div>
      </div>
      <span className="muted" aria-hidden style={{ fontSize: '1.1rem' }}>›</span>
    </div>
  )
}
