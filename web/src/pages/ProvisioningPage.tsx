import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type { DiscoveredOnuState, DiscoveredOnuView } from '../api/monitoring'
import type { CustomerView, OdpView } from '../api/network'
import { useCan } from '../auth/useCan'
import { Drawer, EmptyState, SkeletonRows, StatusBadge, useToast } from '../components/ui'
import { IconInbox } from '../components/icons'

/**
 * Kotak masuk auto-provisioning: ONU yang dilaporkan OLT tapi belum terdaftar.
 *
 * Alih-alih membuang serial tak dikenal ke log, monitoring menangkapnya ke sini;
 * operator memilih pelanggan + port ODP untuk menuntaskannya tanpa mengetik ulang
 * serial. `seenCount` sengaja ditonjolkan: membedakan perangkat yang benar-benar
 * terpasang dari serial yang cuma sekali lewat.
 */

const STATES: { key: DiscoveredOnuState; label: string }[] = [
  { key: 'DISCOVERED', label: 'Menunggu' },
  { key: 'PROVISIONED', label: 'Terprovisi' },
  { key: 'IGNORED', label: 'Diabaikan' },
]

export function ProvisioningPage() {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('monitoring.provisioning.manage')

  const [state, setState] = useState<DiscoveredOnuState>('DISCOVERED')
  const [items, setItems] = useState<DiscoveredOnuView[]>([])
  const [loading, setLoading] = useState(true)
  const [odps, setOdps] = useState<OdpView[]>([])
  const [provision, setProvision] = useState<DiscoveredOnuView | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      setItems(await api.get<DiscoveredOnuView[]>(`/api/monitoring/discovered-onus?state=${state}`))
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat ONU terdeteksi')
    } finally {
      setLoading(false)
    }
  }, [state, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  // ODP dimuat sekali untuk pemilih di drawer; hanya bila operator boleh memprovisi.
  useEffect(() => {
    if (!manage) return
    api
      .get<PageResponse<OdpView>>('/api/odps?size=200')
      .then((page) => setOdps(page.content))
      .catch(() => {
        /* daftar ODP opsional; drawer tetap bisa dibuka manual */
      })
  }, [manage])

  const run = async (action: () => Promise<unknown>, okMessage?: string) => {
    try {
      await action()
      await reload()
      if (okMessage) toast.success(okMessage)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  const showActions = manage && state === 'DISCOVERED'

  return (
    <div className="stack" style={{ gap: '1.5rem' }}>
      <div>
        <h1 className="page-title">Provisioning ONU</h1>
        <p className="page-sub">
          Perangkat yang dilaporkan OLT tapi belum terdaftar. Tuntaskan menjadi pelanggan terpasang
          tanpa mengetik ulang serial.
        </p>
      </div>

      <div className="card pad-0">
        <div className="card-head">
          <h3>Kotak masuk</h3>
          <div className="segment">
            {STATES.map((s) => (
              <button key={s.key} className={state === s.key ? 'active' : ''} onClick={() => setState(s.key)}>
                {s.label}
              </button>
            ))}
          </div>
        </div>

        {loading ? (
          <div className="card-body">
            <SkeletonRows rows={4} cols={5} />
          </div>
        ) : items.length === 0 ? (
          <div className="card-body">
            <EmptyState
              title={state === 'DISCOVERED' ? 'Tidak ada ONU menunggu' : 'Kosong'}
              hint="Serial tak dikenal yang dilaporkan collector muncul di sini otomatis."
              icon={<IconInbox size={32} />}
            />
          </div>
        ) : (
          <div style={{ overflowX: 'auto' }}>
            <table>
              <thead>
                <tr>
                  <th>Serial</th>
                  <th>OLT / PON</th>
                  <th>Status</th>
                  <th>Redaman</th>
                  <th>Terlihat</th>
                  {showActions && <th />}
                </tr>
              </thead>
              <tbody>
                {items.map((onu) => (
                  <tr key={onu.id}>
                    <td style={{ fontWeight: 550, whiteSpace: 'nowrap' }}>{onu.serialNumber}</td>
                    <td>
                      <div style={{ fontSize: '0.88rem' }}>
                        {onu.oltCode}
                        {onu.oltId ? '' : ' · tak dikenal'}
                      </div>
                      <div className="muted" style={{ fontSize: '0.78rem' }}>{onu.ponPortLabel ?? '—'}</div>
                    </td>
                    <td>
                      <StatusBadge status={onu.lastStatus} />
                    </td>
                    <td className="muted">{onu.lastRxPowerDbm != null ? `${onu.lastRxPowerDbm} dBm` : '—'}</td>
                    <td className="muted" style={{ fontSize: '0.82rem', whiteSpace: 'nowrap' }}>
                      ×{onu.seenCount}
                      <div>{new Date(onu.lastSeenAt).toLocaleString('id-ID')}</div>
                    </td>
                    {showActions && (
                      <td style={{ textAlign: 'right', whiteSpace: 'nowrap' }}>
                        <div className="row" style={{ justifyContent: 'flex-end', gap: '0.35rem' }}>
                          <button className="primary small" onClick={() => setProvision(onu)}>
                            Provisi
                          </button>
                          <button
                            className="ghost small"
                            onClick={() =>
                              void run(() => api.post(`/api/monitoring/discovered-onus/${onu.id}/ignore`), 'ONU diabaikan')
                            }
                          >
                            Abaikan
                          </button>
                        </div>
                      </td>
                    )}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {provision && (
        <ProvisionDrawer
          discovered={provision}
          odps={odps}
          onClose={() => setProvision(null)}
          onDone={() => {
            setProvision(null)
            void reload()
          }}
        />
      )}
    </div>
  )
}

/** Form penuntasan: pilih pelanggan (cari), ODP + port; serial sudah pasti dari perangkat. */
function ProvisionDrawer({
  discovered,
  odps,
  onClose,
  onDone,
}: {
  discovered: DiscoveredOnuView
  odps: OdpView[]
  onClose: () => void
  onDone: () => void
}) {
  const toast = useToast()
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<CustomerView[]>([])
  const [customer, setCustomer] = useState<CustomerView | null>(null)
  const [odpId, setOdpId] = useState('')
  const [port, setPort] = useState('')
  const [rx, setRx] = useState(discovered.lastRxPowerDbm != null ? String(discovered.lastRxPowerDbm) : '')
  const [saving, setSaving] = useState(false)

  // Cari pelanggan begitu ketikan cukup panjang; hasil basi dibuang lewat flag `alive`.
  useEffect(() => {
    const q = query.trim()
    if (q.length < 2) {
      setResults([])
      return
    }
    let alive = true
    api
      .get<PageResponse<CustomerView>>(`/api/customers?size=10&query=${encodeURIComponent(q)}`)
      .then((page) => {
        if (alive) setResults(page.content)
      })
      .catch(() => {
        /* pencarian gagal; biarkan hasil sebelumnya */
      })
    return () => {
      alive = false
    }
  }, [query])

  const ready = customer != null && odpId !== '' && port !== ''

  const submit = async () => {
    if (!ready) return
    setSaving(true)
    try {
      await api.post(`/api/monitoring/discovered-onus/${discovered.id}/provision`, {
        customerId: customer!.id,
        odpId,
        portNumber: Number(port),
        installRxPowerDbm: rx ? Number(rx) : null,
      })
      toast.success(`ONU ${discovered.serialNumber} terprovisi`)
      onDone()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Provisioning gagal')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Drawer title={`Provisi ${discovered.serialNumber}`} onClose={onClose}>
      <div className="stack" style={{ gap: '1rem' }}>
        <div className="muted" style={{ fontSize: '0.85rem' }}>
          Terlihat di {discovered.oltCode}
          {discovered.ponPortLabel ? ` · ${discovered.ponPortLabel}` : ''} · ×{discovered.seenCount}
        </div>

        <label>
          <span>Pelanggan</span>
          {customer ? (
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
              <span>
                {customer.name} <span className="muted">· {customer.code}</span>
              </span>
              <button className="ghost small" onClick={() => setCustomer(null)}>
                Ganti
              </button>
            </div>
          ) : (
            <input
              placeholder="Cari nama atau kode pelanggan…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          )}
        </label>
        {!customer && results.length > 0 && (
          <div className="stack" style={{ gap: '0.3rem' }}>
            {results.map((c) => (
              <button
                key={c.id}
                className="ghost small"
                style={{ justifyContent: 'flex-start', textAlign: 'left' }}
                onClick={() => {
                  setCustomer(c)
                  setQuery('')
                  setResults([])
                }}
              >
                {c.name} · {c.code}
              </button>
            ))}
          </div>
        )}

        <div className="row" style={{ alignItems: 'flex-end' }}>
          <label style={{ flex: 2 }}>
            <span>ODP</span>
            <select value={odpId} onChange={(e) => setOdpId(e.target.value)}>
              <option value="">— pilih ODP —</option>
              {odps.map((odp) => (
                <option key={odp.id} value={odp.id}>
                  {odp.code} ({odp.capacity} port)
                </option>
              ))}
            </select>
          </label>
          <label style={{ flex: 1 }}>
            <span>Port</span>
            <input value={port} onChange={(e) => setPort(e.target.value)} placeholder="1" />
          </label>
        </div>

        <label>
          <span>Redaman instalasi (dBm)</span>
          <input value={rx} onChange={(e) => setRx(e.target.value)} placeholder="-22.5" />
          <span className="muted" style={{ fontSize: '0.78rem' }}>
            Baseline deteksi degradasi; terisi dari bacaan terakhir bila ada.
          </span>
        </label>

        <div className="row">
          <button className="primary" disabled={!ready || saving} onClick={() => void submit()}>
            {saving ? 'Memproses…' : 'Provisi ONU'}
          </button>
          <button onClick={onClose}>Batal</button>
        </div>
      </div>
    </Drawer>
  )
}
