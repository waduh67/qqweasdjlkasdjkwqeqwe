import { useCallback, useEffect, useMemo, useState } from 'react'
import { Field, Input } from '@fluentui/react-components'
import { api, ApiError } from '@/api/client'
import type { PageResponse } from '@/api/types'
import type {
  AutoProvisionPolicyView,
  DiscoveredOnuState,
  DiscoveredOnuView,
  ProvisioningSuggestion,
  SuggestionConfidence,
} from '@/api/monitoring'
import type { CustomerView } from '@/api/network'
import { useCan } from '@/auth/useCan'
import { DataTable, type Column } from './DataTable'
import { Badge, Button, EmptyState, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { Drawer, SearchInput, Tabs } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { IconInbox } from '@/components/atoms/icons'

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

/** Cukup untuk 1-klik "Terima": pelanggan sudah tertebak. ODP ditunda ke peta. */
const hasCustomer = (s: ProvisioningSuggestion | null): boolean =>
  s != null && s.customerId != null

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

  // Toggle auto-provisi (setelan tenant) hanya relevan di halaman global; dimuat
  // sekali bila operator boleh memprovisi. Daftar ODP tak lagi diperlukan di sini —
  // penautan ONU cukup ke pelanggan, penempelan ke ODP dikerjakan nanti di peta.
  useEffect(() => {
    if (!manage || !showAutoProvision) return
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

  // Terima saran apa adanya: menautkan ke pelanggan tertebak tanpa membuka form.
  // ODP sengaja tak diisi di sini — penempelan ke ODP dilakukan nanti di peta.
  const accept = (onu: DiscoveredOnuView) => {
    const s = onu.suggestion!
    return run(
      () =>
        api.post(`/api/monitoring/discovered-onus/${onu.id}/provision`, {
          customerId: s.customerId,
          odpId: null,
          portNumber: null,
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
      cell: (o) => <span style={{ fontWeight: 600, whiteSpace: 'nowrap' }}>{o.serialNumber}</span>,
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
              {hasCustomer(onu.suggestion) ? (
                <>
                  <Button variant="primary" size="small" onClick={() => void accept(onu)}>
                    Terima
                  </Button>
                  <Button variant="subtle" size="small" onClick={() => setProvision(onu)}>
                    Ubah
                  </Button>
                </>
              ) : (
                <Button variant="primary" size="small" onClick={() => setProvision(onu)}>
                  Provisi
                </Button>
              )}
              <Button
                variant="subtle"
                size="small"
                onClick={() => void run(() => api.post(`/api/monitoring/discovered-onus/${onu.id}/ignore`), 'ONU diabaikan')}
              >
                Abaikan
              </Button>
            </>
          )}
          <Button variant="danger" size="small" onClick={() => void remove(onu)}>
            Hapus
          </Button>
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
            <Button
              variant={autoProvision ? 'primary' : 'subtle'}
              size="small"
              onClick={() => void toggleAutoProvision()}
              title="Saat menyala, ONU cocok-pasti (keyakinan tinggi) ditautkan otomatis tanpa menunggu Anda menekan Terima"
            >
              Auto-provisi: {autoProvision ? 'nyala' : 'mati'}
            </Button>
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
        {suggestion.customerName && (
          <span style={{ fontSize: '0.84rem' }}>{suggestion.customerName}</span>
        )}
      </div>
      <span className="muted" style={{ fontSize: '0.76rem' }}>{suggestion.reason}</span>
    </div>
  )
}

/** Pelanggan terpilih di drawer — cukup id + nama; kode opsional (tak selalu ada dari saran). */
type PickedCustomer = { id: string; name: string; code: string | null }

/** Form penuntasan: cukup pilih pelanggan (cari); serial sudah pasti dari perangkat.
 *  ODP tak diminta di sini — penempelannya dikerjakan nanti di peta jaringan. */
function ProvisionDrawer({
  discovered,
  onClose,
  onDone,
}: {
  discovered: DiscoveredOnuView
  onClose: () => void
  onDone: () => void
}) {
  const toast = useToast()
  const suggestion = discovered.suggestion
  const [query, setQuery] = useState('')
  const [results, setResults] = useState<CustomerView[]>([])
  // Pra-isi pelanggan dari saran; ODP sengaja tak diminta (ditunda ke peta).
  const [customer, setCustomer] = useState<PickedCustomer | null>(
    suggestion?.customerId ? { id: suggestion.customerId, name: suggestion.customerName ?? '', code: null } : null,
  )
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

  // Penautan cukup ke pelanggan; penempelan ke ODP/port dikerjakan nanti di peta.
  const ready = customer != null

  const submit = async () => {
    if (!ready) return
    setSaving(true)
    try {
      await api.post(`/api/monitoring/discovered-onus/${discovered.id}/provision`, {
        customerId: customer!.id,
        // ODP & port ditunda: ONU ditempel ke ODP nanti lewat peta jaringan.
        odpId: null,
        portNumber: null,
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

        <Field label="Pelanggan">
          {customer ? (
            <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
              <span>
                {customer.name}
                {customer.code ? <span className="muted"> · {customer.code}</span> : null}
              </span>
              <Button variant="subtle" size="small" onClick={() => setCustomer(null)}>
                Ganti
              </Button>
            </div>
          ) : (
            <Input
              placeholder="Cari nama atau kode pelanggan…"
              value={query}
              onChange={(_, data) => setQuery(data.value)}
            />
          )}
        </Field>
        {!customer && results.length > 0 && (
          <div className="stack" style={{ gap: '0.3rem' }}>
            {results.map((c) => (
              <Button
                key={c.id}
                variant="subtle"
                size="small"
                style={{ justifyContent: 'flex-start', textAlign: 'left' }}
                onClick={() => {
                  setCustomer({ id: c.id, name: c.name, code: c.code })
                  setQuery('')
                  setResults([])
                }}
              >
                {c.name} · {c.code}
              </Button>
            ))}
          </div>
        )}

        <span className="muted" style={{ fontSize: '0.78rem' }}>
          ONU cukup ditautkan ke pelanggan. Penempelan ke ODP dikerjakan nanti di peta
          jaringan saat menarik kabel.
        </span>

        <TextField
          label="Redaman instalasi (dBm)"
          value={rx}
          onChange={(_, data) => setRx(data.value)}
          placeholder="-22.5"
          hint="Baseline deteksi degradasi; terisi dari bacaan terakhir bila ada."
        />

        <div className="row">
          <Button variant="primary" disabled={!ready || saving} onClick={() => void submit()}>
            {saving ? 'Memproses…' : 'Provisi ONU'}
          </Button>
          <Button onClick={onClose}>Batal</Button>
        </div>
      </div>
    </Drawer>
  )
}
