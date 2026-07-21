import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { CustomerTrace, CustomerView, OdpView } from '../api/network'
import { useCan } from '../auth/useCan'
import { EmptyState, StatusBadge, useToast } from '../components/ui'
import { IconCustomers, IconPlus, IconRoute, IconSearch } from '../components/icons'

/** Warna kesehatan optik selaras token status. */
const HEALTH_COLOR: Record<string, string> = {
  GOOD: 'var(--good-ink)',
  WARNING: 'var(--warning-ink)',
  CRITICAL: 'var(--critical-ink)',
  UNKNOWN: 'var(--muted)',
}

const EMPTY_CUSTOMER = { code: '', name: '', phone: '', address: '', longitude: '', latitude: '' }

/**
 * Daftar pelanggan beserta perangkat dan penempatannya di ODP.
 *
 * Alur yang didukung adalah alur pemasangan sungguhan: daftarkan pelanggan →
 * daftarkan ONU → pasang ONU ke port ODP tertentu. Server menolak port yang
 * sudah terisi atau di luar kapasitas, jadi UI tidak perlu menebak-nebak.
 */
export function CustomersPage() {
  const { can } = useCan()
  const toast = useToast()
  const [customers, setCustomers] = useState<CustomerView[]>([])
  const [odps, setOdps] = useState<OdpView[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [draft, setDraft] = useState<typeof EMPTY_CUSTOMER | null>(null)
  const [trace, setTrace] = useState<CustomerTrace | null>(null)

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

  useEffect(() => {
    void api
      .get<PageResponse<OdpView>>('/api/odps?size=100')
      .then((page) => setOdps(page.content))
      .catch(() => setOdps([]))
  }, [])

  const run = async (action: () => Promise<unknown>, okMessage?: string) => {
    try {
      await action()
      await reload()
      if (okMessage) toast.success(okMessage)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
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
            <button
              className="primary"
              onClick={() =>
                void run(async () => {
                  await api.post('/api/customers', {
                    code: draft.code,
                    name: draft.name,
                    phone: draft.phone || null,
                    address: draft.address,
                    location: { longitude: Number(draft.longitude), latitude: Number(draft.latitude) },
                  })
                  setDraft(null)
                })
              }
            >
              Simpan
            </button>
            <button onClick={() => setDraft(null)}>Batal</button>
          </div>
        </div>
      )}

      {customers.map((customer) => (
        <CustomerCard
          key={customer.id}
          customer={customer}
          odps={odps}
          onRun={run}
          onTrace={() =>
            void api
              .get<CustomerTrace>(`/api/gis/trace/customers/${customer.id}`)
              .then(setTrace)
              .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal menelusuri jalur'))
          }
        />
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

      {trace && <TracePanel trace={trace} onClose={() => setTrace(null)} />}
    </div>
  )
}

function CustomerCard({
  customer,
  odps,
  onRun,
  onTrace,
}: {
  customer: CustomerView
  odps: OdpView[]
  onRun: (action: () => Promise<unknown>, okMessage?: string) => Promise<void>
  onTrace: () => void
}) {
  const { can } = useCan()
  const [serial, setSerial] = useState('')
  const [attach, setAttach] = useState<{ onuId: string; odpId: string; port: string; rx: string } | null>(null)

  return (
    <div className="card stack">
      <div className="spread">
        <div>
          <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap' }}>
            <strong style={{ fontSize: '0.98rem' }}>{customer.name}</strong>
            <span className="badge">{customer.code}</span>
            <StatusBadge status={customer.status} />
            {customer.awaitingInstallation && <StatusBadge status="PENDING" label="menunggu instalasi" />}
          </div>
          <div className="muted" style={{ fontSize: '0.85rem', marginTop: '0.2rem' }}>
            {customer.address} · {customer.phone ?? 'tanpa nomor'}
          </div>
        </div>
        <div className="row">
          <button onClick={onTrace}>
            <IconRoute size={15} /> Telusur jalur
          </button>
          {can('customer.customer.delete') && (
            <button
              className="ghost danger"
              onClick={() => void onRun(() => api.del(`/api/customers/${customer.id}`), 'Pelanggan dihapus')}
            >
              Hapus
            </button>
          )}
        </div>
      </div>

      <div>
        <strong style={{ fontSize: '0.9rem' }}>Perangkat ONU</strong>
        {customer.onus.length === 0 && (
          <p className="muted" style={{ margin: '0.25rem 0 0', fontSize: '0.85rem' }}>
            Belum ada ONU terdaftar.
          </p>
        )}
        {customer.onus.map((onu) => (
          <div key={onu.id} className="spread" style={{ marginTop: '0.4rem' }}>
            <span style={{ fontSize: '0.85rem' }}>
              {onu.serialNumber}{' '}
              {onu.odpCode ? (
                <span className="badge accent">
                  {onu.odpCode} port {onu.odpPortNumber}
                </span>
              ) : (
                <span className="badge">belum terpasang</span>
              )}{' '}
              <span style={{ color: HEALTH_COLOR[onu.opticalHealth], fontWeight: 600 }}>
                {onu.installRxPowerDbm != null ? `${onu.installRxPowerDbm} dBm` : onu.opticalHealth}
              </span>
            </span>
            {can('customer.onu.assign') && (
              <div className="row">
                {onu.odpId ? (
                  <button onClick={() => void onRun(() => api.post(`/api/customers/onus/${onu.id}/detach`))}>
                    Lepas
                  </button>
                ) : (
                  <button onClick={() => setAttach({ onuId: onu.id, odpId: odps[0]?.id ?? '', port: '1', rx: '' })}>
                    Pasang ke ODP
                  </button>
                )}
              </div>
            )}
          </div>
        ))}

        {attach && (
          <div className="row" style={{ marginTop: '0.6rem', alignItems: 'flex-end' }}>
            <label style={{ flex: 2 }}>
              <span>ODP</span>
              <select value={attach.odpId} onChange={(e) => setAttach({ ...attach, odpId: e.target.value })}>
                {odps.map((odp) => (
                  <option key={odp.id} value={odp.id}>
                    {odp.code} ({odp.capacity} port)
                  </option>
                ))}
              </select>
            </label>
            <label style={{ flex: 1 }}>
              <span>Port</span>
              <input value={attach.port} onChange={(e) => setAttach({ ...attach, port: e.target.value })} />
            </label>
            <label style={{ flex: 1 }}>
              <span>Redaman (dBm)</span>
              <input value={attach.rx} onChange={(e) => setAttach({ ...attach, rx: e.target.value })} placeholder="-22.5" />
            </label>
            <button
              className="primary"
              onClick={() =>
                void onRun(async () => {
                  await api.post(`/api/customers/onus/${attach.onuId}/attach`, {
                    odpId: attach.odpId,
                    portNumber: Number(attach.port),
                    installRxPowerDbm: attach.rx ? Number(attach.rx) : null,
                  })
                  setAttach(null)
                })
              }
            >
              Pasang
            </button>
            <button onClick={() => setAttach(null)}>Batal</button>
          </div>
        )}

        {can('customer.onu.assign') && (
          <div className="row" style={{ marginTop: '0.6rem' }}>
            <input
              placeholder="Serial ONU baru, mis. ZTEG-C0FFEE01"
              value={serial}
              onChange={(e) => setSerial(e.target.value)}
            />
            <button
              onClick={() =>
                void onRun(async () => {
                  await api.post(`/api/customers/${customer.id}/onus`, { serialNumber: serial })
                  setSerial('')
                })
              }
            >
              Daftarkan ONU
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

/** Telusur jalur fisik pelanggan sampai OLT, lengkap dengan perkiraan redaman. */
function TracePanel({ trace, onClose }: { trace: CustomerTrace; onClose: () => void }) {
  return (
    <div className="card stack">
      <div className="spread">
        <h3 style={{ margin: 0 }}>Jalur — {trace.customerName}</h3>
        <button onClick={onClose}>Tutup</button>
      </div>
      {trace.hops.length <= 1 ? (
        <p className="muted">Pelanggan ini belum tersambung ke jaringan.</p>
      ) : (
        <>
          <div className="row" style={{ flexWrap: 'wrap', gap: '0.4rem' }}>
            {trace.hops.map((hop, index) => (
              <span key={`${hop.kind}-${index}`} className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
                <span className="badge">
                  {hop.kind}
                  {hop.code && ` ${hop.code}`}
                </span>
                {index < trace.hops.length - 1 && <span className="muted">→</span>}
              </span>
            ))}
          </div>
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
            ONU {trace.onuSerialNumber} · port {trace.odpPortNumber} · redaman terpasang{' '}
            <span style={{ color: HEALTH_COLOR[trace.opticalHealth ?? 'UNKNOWN'] }}>
              {trace.installRxPowerDbm != null ? `${trace.installRxPowerDbm} dBm` : '—'}
            </span>
            {trace.estimatedLossDb != null && ` · perkiraan rugi jalur ${trace.estimatedLossDb.toFixed(1)} dB`}
          </p>
        </>
      )}
    </div>
  )
}
