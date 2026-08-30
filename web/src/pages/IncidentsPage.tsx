import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { api, ApiError } from '../api/client'
import type { IncidentAlarm, IncidentDetail, IncidentEventView, IncidentView } from '../api/incident'
import type { BroadcastView, NotificationChannel } from '../api/notification'
import { useCan } from '../auth/useCan'
import { DataTable, type Column } from '@/components/organisms'
import { Button, EmptyState, SelectField, StatusBadge, TextareaField, Toolbar } from '@/components/atoms'
import { Drawer, SearchInput } from '@/components/molecules'
import { useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconAlert } from '@/components/atoms/icons'
import { timeAgo } from '@/utils/timeAgo'

/** Urutan keparahan untuk pengurutan tabel (turun = paling parah di atas). */
const SEV_RANK: Record<string, number> = { CRITICAL: 5, MAJOR: 4, MINOR: 3, WARNING: 2, INFO: 1 }

const ROOT_LABEL: Record<string, string> = {
  OLT: 'OLT',
  ODC: 'ODC',
  ODP: 'ODP',
  ONU: 'ONU',
  COLLECTOR: 'Collector',
}

const EVENT_LABEL: Record<string, string> = {
  OPENED: 'Dibuka',
  SEVERITY_CHANGED: 'Keparahan berubah',
  ACKNOWLEDGED: 'Diakui',
  RESOLVED: 'Selesai',
}

/**
 * Dugaan sebab blast-radius dari register ONU — dua gangguan yang di layar tampak
 * sama tapi tindakannya berbeda: mati listrik area (tunggu PLN) vs fiber putus
 * (kirim teknisi).
 */
const CAUSE_LABEL: Record<string, string> = {
  POWER_OUTAGE: 'Dugaan mati listrik area',
  FIBER_CUT: 'Dugaan fiber putus',
  MIXED: 'Sebab campuran',
}

/** Sebab putus per-ONU dari register OLT untuk ditampilkan di anggota alarm. */
const DOWN_CAUSE_LABEL: Record<string, string> = {
  DYING_GASP: 'mati listrik',
  LOS: 'sinyal hilang',
  UNKNOWN: 'sebab tak diketahui',
}

/**
 * Insiden: banjir alarm sejenis digabung menjadi satu insiden ber-akar-masalah.
 * Operator melihat sedikit insiden yang bermakna, bukan ratusan alarm mentah,
 * lalu bisa mengakui atau menutupnya.
 */
export function IncidentsPage() {
  const { can } = useCan()
  const toast = useToast()
  const [incidents, setIncidents] = useState<IncidentView[]>([])
  const [loading, setLoading] = useState(true)
  const [detail, setDetail] = useState<IncidentDetail | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('')

  const reload = useCallback(async () => {
    try {
      setIncidents(await api.get<IncidentView[]>('/api/incidents'))
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat insiden')
    } finally {
      setLoading(false)
    }
  }, [toast])

  useEffect(() => {
    void reload()
    // Insiden hidup — segarkan berkala agar korelasi terbaru terlihat.
    const t = window.setInterval(() => void reload(), 20_000)
    return () => window.clearInterval(t)
  }, [reload])

  const openDetail = async (id: string) => {
    try {
      setDetail(await api.get<IncidentDetail>(`/api/incidents/${id}`))
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat detail insiden')
    }
  }

  const act = async (id: string, action: 'acknowledge' | 'resolve', ok: string, close = false) => {
    try {
      await api.post(`/api/incidents/${id}/${action}`)
      await reload()
      if (close) setDetail(null)
      else if (detail?.incident.id === id) await openDetail(id)
      toast.success(ok)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    }
  }

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return incidents.filter(
      (inc) =>
        (!statusFilter || inc.status === statusFilter) &&
        (!q ||
          inc.title.toLowerCase().includes(q) ||
          inc.rootLabel.toLowerCase().includes(q)),
    )
  }, [incidents, query, statusFilter])

  const columns: Column<IncidentView>[] = [
    {
      key: 'title',
      header: 'Nama',
      sortValue: (i) => i.title,
      cell: (i) => i.title,
      onCellClick: (i) => void openDetail(i.id),
    },
    {
      key: 'severity',
      header: 'Keparahan',
      sortValue: (i) => SEV_RANK[i.severity] ?? 0,
      cell: (i) => i.severity,
    },
    {
      key: 'cause',
      header: 'Dugaan sebab',
      sortValue: (i) => i.suspectedCause ?? '',
      cell: (i) => (i.suspectedCause ? CAUSE_LABEL[i.suspectedCause] : undefined) ?? '—',
    },
    {
      key: 'root',
      header: 'Akar masalah',
      sortValue: (i) => i.rootLabel,
      cell: (i) => `${ROOT_LABEL[i.rootType] ?? i.rootType} ${i.rootLabel}`,
    },
    { key: 'alarms', header: 'Alarm', align: 'right', sortValue: (i) => i.alarmCount, cell: (i) => i.alarmCount },
    {
      key: 'customers',
      header: 'Pelanggan',
      align: 'right',
      sortValue: (i) => i.affectedCustomerCount,
      cell: (i) => i.affectedCustomerCount,
    },
    { key: 'opened', header: 'Dibuka', sortValue: (i) => i.openedAt, cell: (i) => timeAgo(i.openedAt) },
    {
      key: 'status',
      header: 'Status',
      sortValue: (i) => i.status,
      cell: (i) => (i.status === 'ACKNOWLEDGED' ? 'Diakui' : 'Terbuka'),
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.2rem' }}>
      <PageHeader title="Insiden" />

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari judul atau akar masalah…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value)}>
          <option value="">Semua status</option>
          <option value="OPEN">Terbuka</option>
          <option value="ACKNOWLEDGED">Diakui</option>
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(i) => i.id}
        loading={loading}
        initialSort={{ key: 'severity', dir: 'desc' }}
        presentation="resource"
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada insiden yang cocok' : 'Tidak ada insiden aktif'}
            icon={<IconAlert size={34} />}
          />
        }
      />

      {detail && (
        <Drawer title={detail.incident.title} onClose={() => setDetail(null)}>
          <IncidentDetailBody
            detail={detail}
            canAck={can('incident.ticket.update')}
            canResolve={can('incident.ticket.close')}
            canBroadcast={can('notification.broadcast.send')}
            onAcknowledge={() => void act(detail.incident.id, 'acknowledge', 'Insiden diakui')}
            onResolve={() => void act(detail.incident.id, 'resolve', 'Insiden ditutup', true)}
          />
        </Drawer>
      )}
    </div>
  )
}

function IncidentDetailBody({
  detail,
  canAck,
  canResolve,
  canBroadcast,
  onAcknowledge,
  onResolve,
}: {
  detail: IncidentDetail
  canAck: boolean
  canResolve: boolean
  canBroadcast: boolean
  onAcknowledge: () => void
  onResolve: () => void
}) {
  const inc = detail.incident
  return (
    <div className="stack" style={{ gap: '1.1rem' }}>
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        <StatusBadge status={inc.severity} />
        <span className={`badge ${inc.status === 'ACKNOWLEDGED' ? 'warning' : ''}`}>
          {inc.status === 'ACKNOWLEDGED' ? 'Diakui' : 'Terbuka'}
        </span>
        <span className="badge">{ROOT_LABEL[inc.rootType] ?? inc.rootType} {inc.rootLabel}</span>
        <span className="badge">{inc.alarmCount} alarm</span>
        <span className="badge">{inc.affectedCustomerCount} pelanggan</span>
        {inc.suspectedCause && CAUSE_LABEL[inc.suspectedCause] && (
          <span className={`badge cause-${inc.suspectedCause.toLowerCase()}`}>{CAUSE_LABEL[inc.suspectedCause]}</span>
        )}
      </div>

      {(canAck || canResolve) && (
        <div className="row" style={{ gap: '0.5rem' }}>
          {canAck && inc.status !== 'ACKNOWLEDGED' && (
            <Button variant="subtle" onClick={onAcknowledge}>
              Akui
            </Button>
          )}
          {canResolve && (
            <Button variant="primary" onClick={onResolve}>
              Tutup insiden
            </Button>
          )}
        </div>
      )}

      {canBroadcast && (
        <BroadcastComposer
          incidentId={inc.id}
          rootLabel={`${ROOT_LABEL[inc.rootType] ?? inc.rootType} ${inc.rootLabel}`}
          affectedCount={inc.affectedCustomerCount}
        />
      )}

      <section className="stack" style={{ gap: '0.5rem' }}>
          <Text as="h3" weight="semibold" size={300} style={{ margin: 0 }}>Alarm anggota ({detail.members.length})</Text>
        {detail.members.length === 0 ? (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Tidak ada alarm hidup (insiden sudah selesai).</Text>
        ) : (
          detail.members.map((m: IncidentAlarm) => (
            <div key={m.entityId + m.kind} className="row spread" style={{ gap: '0.5rem', alignItems: 'center' }}>
              <span className="row" style={{ gap: '0.45rem', alignItems: 'center', minWidth: 0 }}>
                <span className={`dot-sev sev-${m.severity.toLowerCase()}`} aria-hidden="true" />
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.label}</span>
              </span>
              <span className="row" style={{ gap: '0.35rem', alignItems: 'center' }}>
                {m.downCause && DOWN_CAUSE_LABEL[m.downCause] && (
                  <Text as="span" className="muted" size={200}>{DOWN_CAUSE_LABEL[m.downCause]}</Text>
                )}
                <span className="badge">{m.kind}</span>
              </span>
            </div>
          ))
        )}
      </section>

      <section className="stack" style={{ gap: '0.5rem' }}>
          <Text as="h3" weight="semibold" size={300} style={{ margin: 0 }}>Timeline</Text>
        <ol className="timeline">
          {detail.timeline.map((ev: IncidentEventView, i: number) => (
            <li key={i}>
              <span className="tl-dot" aria-hidden="true" />
              <div className="stack" style={{ gap: '0.15rem' }}>
              <Text as="strong" weight="semibold" size={200}>{EVENT_LABEL[ev.type] ?? ev.type}</Text>
              <Text as="span" className="muted" size={200}>{ev.message}</Text>
              <Text as="span" className="muted" size={100}>{new Date(ev.at).toLocaleString('id-ID')}</Text>
              </div>
            </li>
          ))}
        </ol>
      </section>
    </div>
  )
}

/**
 * Penyiaran pemberitahuan gangguan ke pelanggan terdampak insiden — fitur
 * "outage broadcast proaktif": kabari pelanggan sebelum mereka komplain. Daftar
 * penerima dihitung server dari akar masalah; di sini operator hanya memilih
 * kanal dan menyusun pesan (sudah diprefill dengan template yang bisa disunting).
 */
function BroadcastComposer({
  incidentId,
  rootLabel,
  affectedCount,
}: {
  incidentId: string
  rootLabel: string
  affectedCount: number
}) {
  const toast = useToast()
  const [open, setOpen] = useState(false)
  const [channel, setChannel] = useState<NotificationChannel>('WHATSAPP')
  const [message, setMessage] = useState(
    `Pelanggan Yth, layanan internet Anda sedang mengalami gangguan (${rootLabel}). ` +
      'Tim teknis kami sedang menanganinya. Mohon maaf atas ketidaknyamanannya.',
  )
  const [sending, setSending] = useState(false)

  const submit = async () => {
    if (!message.trim()) {
      toast.error('Pesan tidak boleh kosong')
      return
    }
    setSending(true)
    try {
      const res = await api.post<BroadcastView>('/api/notifications/broadcasts', {
        incidentId,
        channel,
        message: message.trim(),
      })
      toast.success(
        `Broadcast terkirim: ${res.sentCount} terkirim, ${res.skippedCount} dilewati dari ${res.recipientCount} pelanggan`,
      )
      setOpen(false)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Broadcast gagal')
    } finally {
      setSending(false)
    }
  }

  if (!open) {
    return (
      <div className="row" style={{ gap: '0.5rem' }}>
        <Button variant="subtle" onClick={() => setOpen(true)}>
          Broadcast ke pelanggan
        </Button>
      </div>
    )
  }

  return (
    <section className="stack" style={{ gap: '0.55rem' }}>
          <Text as="h3" weight="semibold" size={300} style={{ margin: 0 }}>Broadcast pemberitahuan</Text>
      <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
        {affectedCount} pelanggan terdampak; tanpa alamat di kanal terpilih akan dilewati.
      </Text>
      <SelectField
        label="Kanal"
        value={channel}
        onChange={(_, data) => setChannel(data.value as NotificationChannel)}
      >
        <option value="WHATSAPP">WhatsApp</option>
        <option value="EMAIL">Email</option>
      </SelectField>
      <TextareaField
        label="Pesan"
        rows={4}
        maxLength={2000}
        value={message}
        onChange={(_, data) => setMessage(data.value)}
      />
      <div className="row" style={{ gap: '0.5rem' }}>
        <Button variant="primary" onClick={() => void submit()} disabled={sending}>
          {sending ? 'Mengirim…' : 'Kirim broadcast'}
        </Button>
        <Button variant="subtle" onClick={() => setOpen(false)} disabled={sending}>
          Batal
        </Button>
      </div>
    </section>
  )
}
