import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { PageResponse } from '../api/types'
import type {
  AutoProvisionPolicyView,
  DiscoveredOnuState,
  DiscoveredOnuView,
  ProvisioningSuggestion,
  SuggestionConfidence,
} from '../api/monitoring'
import type { CustomerView, OdpView } from '../api/network'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from './DataTable'
import { Badge, Drawer, EmptyState, SearchInput, StatusBadge, Tabs, Toolbar, useConfirm, useToast } from './ui'
import { IconInbox } from './icons'

/**
 * Kotak masuk auto-provisioning: ONU yang dilaporkan OLT tapi belum terdaftar.
 *
 * Alih-alih membuang serial tak dikenal ke log, monitoring menangkapnya ke sini
 * lalu menebak {pelanggan, ODP, port} dari topologi PON port + backlog instalasi.
 * Bila cocok tunggal, operator cukup 1-klik "Terima"; selebihnya saran mengisi
 * form di muka agar tinggal diperiksa. `seenCount` tetap ditonjolkan: membedakan
 * perangkat yang benar-benar terpasang dari serial yang cuma sekali lewat.
 *
 * Dipakai dua tempat: halaman Provisioning global (semua OLT, plus toggle
 * auto-provisi tenant), dan tab "ONU Baru" di detail satu OLT ([oltId] diisi →
 * kotak masuk disaring ke OLT itu saja, meniru pengelompokan per-OLT ala kitabill).
 */

const STATES: { key: DiscoveredOnuState; label: string }[] = [
  { key: 'DISCOVERED', label: 'Menunggu' },
  { key: 'PROVISIONED', label: 'Terprovisi' },
  { key: 'IGNORED', label: 'Diabaikan' },
]

type ConfTone = 'good' | 'warning' | 'accent' | 'neutral'
const CONFIDENCE: Record<SuggestionConfidence, { label: string; tone: ConfTone }> = {
  HIGH: { label: 'Cocok', tone: 'good' },
  MEDIUM: { label: 'Perlu cek', tone: 'warning' },
  LOW: { label: 'Sebagian', tone: 'accent' },
  NONE: { label: 'Manual', tone: 'neutral' },
}

/** Lengkap untuk 1-klik: pelanggan, ODP, dan port semuanya tertebak. */
const isComplete = (s: ProvisioningSuggestion | null): boolean =>
  s != null && s.customerId != null && s.odpId != null && s.portNumber != null

/**
 * @param oltId bila diisi, kotak masuk disaring ke ONU baru di bawah OLT itu saja;
 *   kolom OLT diringkas jadi PON karena OLT-nya sudah pasti.
 * @param showAutoProvision tampilkan toggle auto-provisi zero-touch (setelan tenant,
 *   hanya relevan di halaman global — bukan per-OLT).
 */
export function DiscoveredOnuInbox({
  oltId,
  showAutoProvision = false,
}: {
  oltId?: string
  showAutoProvision?: boolean
}) {
  const { can } = useCan()
  const toast = useToast()
  const confirm = useConfirm()
  const manage = can('monitoring.provisioning.manage')

  const [state, setState] = useState<DiscoveredOnuState>('DISCOVERED')
  const [items, setItems] = useState<DiscoveredOnuView[]>([])
  const [query, setQuery] = useState('')
  const [loading, setLoading] = useState(true)
  const [odps, setOdps] = useState<OdpView[]>([])
  const [provision, setProvision] = useState<DiscoveredOnuView | null>(null)
  // Setelan zero-touch tenant; null = belum termuat (atau tak boleh dilihat).
  const [autoProvision, setAutoProvision] = useState<boolean | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    try {
      const scope = oltId ? `&oltId=${oltId}` : ''
      setItems(await api.get<DiscoveredOnuView[]>(`/api/monitoring/discovered-onus?state=${state}${scope}`))
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat ONU terdeteksi')
    } finally {
      setLoading(false)
    }
  }, [state, oltId, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  // ODP dimuat sekali untuk pemilih di drawer; hanya bila operator boleh memprovisi.
  // Toggle auto-provisi (setelan tenant) hanya relevan di halaman global.
  useEffect(() => {
    if (!manage) return
    api
      .get<PageResponse<OdpView>>('/api/odps?size=200')
      .then((page) => setOdps(page.content))
      .catch(() => {
        /* daftar ODP opsional; drawer tetap bisa dibuka manual */
      })
    if (!showAutoProvision) return
    api
      .get<AutoProvisionPolicyView>('/api/monitoring/auto-provision-policy')
      .then((p) => setAutoProvision(p.enabled))
      .catch(() => {
        /* setelan opsional; toggle disembunyikan bila gagal dimuat */
      })
  }, [manage, showAutoProvision])

  // Zero-touch: saat menyala, ONU cocok-pasti (HIGH) ditautkan otomatis oleh
  // pemindai terjadwal — operator tak perlu menekan "Terima" satu per satu.
  const toggleAutoProvision = async () => {
    const next = !(autoProvision ?? false)
    try {
      const p = await api.put<AutoProvisionPolicyView>('/api/monitoring/auto-provision-policy', { enabled: next })
      setAutoProvision(p.enabled)
      toast.success(p.enabled ? 'Auto-provisi zero-touch dinyalakan' : 'Auto-provisi zero-touch dimatikan')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengubah setelan')
    }
  }

  const run = async (action: () => Promise<unknown>, okMessage?: string) => {
    try {
      await action()
      await reload()
      if (okMessage) toast.success(okMessage)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  // Terima saran apa adanya: menuntaskan tanpa membuka form, memakai nilai tebakan.
  const accept = (onu: DiscoveredOnuView) => {
    const s = onu.suggestion!
    return run(
      () =>
        api.post(`/api/monitoring/discovered-onus/${onu.id}/provision`, {
          customerId: s.customerId,
          odpId: s.odpId,
          portNumber: s.portNumber,
          installRxPowerDbm: onu.lastRxPowerDbm,
        }),
      `ONU ${onu.serialNumber} terprovisi`,
    )
  }

  // Hapus permanen baris kotak masuk — beda dari "Abaikan" yang cuma menandainya
  // IGNORED (masih tersimpan). Berguna terutama untuk yatim dari OLT yang sudah
  // dihapus: baris itu takkan hilang sendiri karena OLT-nya tak lagi mem-poll.
  const remove = async (onu: DiscoveredOnuView) => {
    if (!(await confirm({ title: 'Hapus ONU', message: `Hapus permanen ONU ${onu.serialNumber} dari kotak masuk? Kalau OLT masih melihatnya, ia muncul lagi pada polling berikutnya.`, confirmLabel: 'Hapus', danger: true }))) {
      return
    }
    return run(() => api.del(`/api/monitoring/discovered-onus/${onu.id}`), 'ONU dihapus')
  }

  // Baris di state mana pun boleh dihapus; provisi/abaikan hanya untuk yang menunggu.
  const showActions = manage
  const showSuggestion = state === 'DISCOVERED'

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return items
    return items.filter((onu) =>
      [onu.serialNumber, onu.oltCode, onu.ponPortLabel ?? '', onu.suggestion?.customerName ?? '', onu.suggestion?.odpCode ?? '']
        .join(' ')
        .toLowerCase()
        .includes(q),
    )
  }, [items, query])

  const columns: Column<DiscoveredOnuView>[] = [
    {
      key: 'serial',
      header: 'Serial',
      sortValue: (o) => o.serialNumber,
      cell: (o) => <span style={{ fontWeight: 550, whiteSpace: 'nowrap' }}>{o.serialNumber}</span>,
    },
  ]
  // Disaring per-OLT: OLT-nya sudah pasti, jadi cukup tunjukkan PON port-nya.
  if (oltId) {
    columns.push({
      key: 'pon',
      header: 'PON',
      sortValue: (o) => o.ponPortLabel ?? '',
      cell: (o) => <span className="muted" style={{ fontSize: '0.85rem' }}>{o.ponPortLabel ?? '—'}</span>,
    })
  } else {
    columns.push({
      key: 'olt',
      header: 'OLT / PON',
      sortValue: (o) => o.oltCode,
      cell: (o) => (
        <div>
          <div style={{ fontSize: '0.88rem' }}>
            {o.oltCode}
            {o.oltId ? '' : ' · tak dikenal'}
          </div>
          <div className="muted" style={{ fontSize: '0.78rem' }}>{o.ponPortLabel ?? '—'}</div>
        </div>
      ),
    })
  }
  columns.push(
    { key: 'status', header: 'Status', sortValue: (o) => o.lastStatus, cell: (o) => <StatusBadge status={o.lastStatus} /> },
    {
      key: 'rx',
      header: 'Redaman',
      align: 'right',
      sortValue: (o) => o.lastRxPowerDbm,
      cell: (o) => <span className="muted">{o.lastRxPowerDbm != null ? `${o.lastRxPowerDbm} dBm` : '—'}</span>,
    },
    {
      key: 'seen',
      header: 'Terlihat',
      sortValue: (o) => o.lastSeenAt,
      cell: (o) => (
        <span className="muted" style={{ fontSize: '0.82rem', whiteSpace: 'nowrap' }}>
          ×{o.seenCount}
          <div>{new Date(o.lastSeenAt).toLocaleString('id-ID')}</div>
        </span>
      ),
    },
  )
  if (showSuggestion) {
    columns.push({
      key: 'suggestion',
      header: 'Saran auto-link',
      sortValue: (o) => o.suggestion?.confidence,
      cell: (o) => <SuggestionCell suggestion={o.suggestion} />,
    })
  }
  if (showActions) {
    columns.push({
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (onu) => (
        <div className="row" style={{ justifyContent: 'flex-end', gap: '0.35rem', flexWrap: 'nowrap' }}>
          {state === 'DISCOVERED' && (
            <>
              {isComplete(onu.suggestion) ? (
                <>
                  <button className="primary small" onClick={() => void accept(onu)}>
                    Terima
                  </button>
                  <button className="ghost small" onClick={() => setProvision(onu)}>
                    Ubah
                  </button>
                </>
              ) : (
                <button className="primary small" onClick={() => setProvision(onu)}>
                  Provisi
                </button>
              )}
              <button
                className="ghost small"
                onClick={() => void run(() => api.post(`/api/monitoring/discovered-onus/${onu.id}/ignore`), 'ONU diabaikan')}
              >
                Abaikan
              </button>
            </>
          )}
          <button className="ghost small danger" onClick={() => void remove(onu)}>
            Hapus
          </button>
        </div>
      ),
    })
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      {/* Filter status kini strip tab garis-bawah ala Azure (Image #7), di baris sendiri. */}
      <Tabs tabs={STATES} active={state} onChange={setState} />

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari serial, OLT, atau pelanggan tertebak…" />
        {showAutoProvision && manage && autoProvision !== null && (
          <>
            <span className="spacer" />
            <button
              className={`small ${autoProvision ? 'primary' : 'ghost'}`}
              onClick={() => void toggleAutoProvision()}
              title="Saat menyala, ONU cocok-pasti (keyakinan tinggi) ditautkan otomatis tanpa menunggu Anda menekan Terima"
            >
              Auto-provisi: {autoProvision ? 'nyala' : 'mati'}
            </button>
          </>
        )}
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(o) => o.id}
        loading={loading}
        initialSort={{ key: 'seen', dir: 'desc' }}
        empty={
          <EmptyState
            title={query ? 'Tidak ada yang cocok' : state === 'DISCOVERED' ? 'Tidak ada ONU menunggu' : 'Kosong'}
            hint={query ? 'Coba ubah kata kunci.' : 'Serial tak dikenal yang dilaporkan collector muncul di sini otomatis.'}
            icon={<IconInbox size={32} />}
          />
        }
      />

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

/** Ringkas saran di satu sel: nada keyakinan + target tertebak + alasannya. */
function SuggestionCell({ suggestion }: { suggestion: ProvisioningSuggestion | null }) {
  if (!suggestion) return <span className="muted">—</span>
  const conf = CONFIDENCE[suggestion.confidence]
  return (
    <div className="stack" style={{ gap: '0.25rem' }}>
      <div className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
        <Badge tone={conf.tone}>{conf.label}</Badge>
        {suggestion.odpId && (
          <span style={{ fontSize: '0.84rem' }}>
            {suggestion.customerName ? `${suggestion.customerName} · ` : ''}
            {suggestion.odpCode}
            {suggestion.portNumber != null ? ` · port ${suggestion.portNumber}` : ''}
          </span>
        )}
      </div>
      <span className="muted" style={{ fontSize: '0.76rem' }}>{suggestion.reason}</span>
    </div>
  )
}

/** Pelanggan terpilih di drawer — cukup id + nama; kode opsional (tak selalu ada dari saran). */
type PickedCustomer = { id: string; name: string; code: string | null }

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
  const suggestion = discovered.suggestion
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<CustomerView[]>([])
  // Pra-isi dari saran: pelanggan, ODP, dan port yang berhasil ditebak.
  const [customer, setCustomer] = useState<PickedCustomer | null>(
    suggestion?.customerId ? { id: suggestion.customerId, name: suggestion.customerName ?? '', code: null } : null,
  )
  const [odpId, setOdpId] = useState(suggestion?.odpId ?? '')
  const [port, setPort] = useState(suggestion?.portNumber != null ? String(suggestion.portNumber) : '')
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

  // ODP opsional, tapi utuh: ODP tanpa port (atau sebaliknya) ambigu — tolak.
  // Kosong dua-duanya = tempel ke pelanggan dulu, ODP menyusul di peta.
  const odpFilled = odpId !== ''
  const portFilled = port.trim() !== ''
  const odpConsistent = odpFilled === portFilled
  const ready = customer != null && odpConsistent

  const submit = async () => {
    if (!ready) return
    setSaving(true)
    try {
      await api.post(`/api/monitoring/discovered-onus/${discovered.id}/provision`, {
        customerId: customer!.id,
        odpId: odpFilled ? odpId : null,
        portNumber: portFilled ? Number(port) : null,
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

        {suggestion && suggestion.confidence !== 'NONE' && (
          <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-start' }}>
            <Badge tone={CONFIDENCE[suggestion.confidence].tone}>{CONFIDENCE[suggestion.confidence].label}</Badge>
            <span className="muted" style={{ fontSize: '0.82rem' }}>{suggestion.reason}</span>
          </div>
        )}

        <label>
          <span>Pelanggan</span>
          {customer ? (
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
              <span>
                {customer.name}
                {customer.code ? <span className="muted"> · {customer.code}</span> : null}
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
                  setCustomer({ id: c.id, name: c.name, code: c.code })
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
            <span>ODP (opsional)</span>
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
        <span className="muted" style={{ fontSize: '0.78rem', marginTop: '-0.5rem' }}>
          {odpConsistent
            ? 'Kosongkan ODP untuk menautkan ke pelanggan dulu; tempel ke ODP belakangan saat menarik kabel di peta.'
            : 'ODP dan port harus diisi bersamaan, atau keduanya dikosongkan.'}
        </span>

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
