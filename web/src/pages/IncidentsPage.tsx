import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { IncidentAlarm, IncidentDetail, IncidentEventView, IncidentView } from '../api/incident'
import { useCan } from '../auth/useCan'
import { Drawer, EmptyState, SkeletonRows, StatusBadge, useToast } from '../components/ui'
import { IconAlert } from '../components/icons'

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

function timeAgo(iso: string): string {
  const secs = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000))
  if (secs < 60) return 'baru saja'
  const mins = Math.floor(secs / 60)
  if (mins < 60) return `${mins} menit lalu`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} jam lalu`
  return `${Math.floor(hours / 24)} hari lalu`
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

  return (
    <div className="stack" style={{ gap: '1.2rem' }}>
      <div>
        <h1 className="page-title">Insiden</h1>
        <p className="page-sub">
          Banjir alarm dikelompokkan menurut akar masalah — {incidents.length} insiden aktif.
        </p>
      </div>

      {loading ? (
        <div className="card">
          <SkeletonRows rows={4} />
        </div>
      ) : incidents.length === 0 ? (
        <div className="card">
          <EmptyState
            title="Tidak ada insiden aktif"
            hint="Jaringan tenang. Insiden muncul di sini saat alarm terkorelasi jadi gangguan."
            icon={<IconAlert size={34} />}
          />
        </div>
      ) : (
        <div className="stack" style={{ gap: '0.6rem' }}>
          {incidents.map((inc) => (
            <button key={inc.id} className="incident-row" onClick={() => void openDetail(inc.id)}>
              <span className={`sev-stripe sev-${inc.severity.toLowerCase()}`} aria-hidden="true" />
              <span className="stack" style={{ gap: '0.3rem', minWidth: 0, alignItems: 'flex-start', flex: 1 }}>
                <span className="row" style={{ gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                  <StatusBadge status={inc.severity} />
                  <strong style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{inc.title}</strong>
                </span>
                <span className="muted" style={{ fontSize: '0.82rem' }}>
                  {ROOT_LABEL[inc.rootType] ?? inc.rootType} {inc.rootLabel} · {inc.alarmCount} alarm ·{' '}
                  {inc.affectedCustomerCount} pelanggan · dibuka {timeAgo(inc.openedAt)}
                </span>
              </span>
              <span className={`badge ${inc.status === 'ACKNOWLEDGED' ? 'warning' : ''}`}>
                {inc.status === 'ACKNOWLEDGED' ? 'Diakui' : 'Terbuka'}
              </span>
            </button>
          ))}
        </div>
      )}

      {detail && (
        <Drawer title={detail.incident.title} onClose={() => setDetail(null)}>
          <IncidentDetailBody
            detail={detail}
            canAck={can('incident.ticket.update')}
            canResolve={can('incident.ticket.close')}
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
  onAcknowledge,
  onResolve,
}: {
  detail: IncidentDetail
  canAck: boolean
  canResolve: boolean
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
      </div>

      {(canAck || canResolve) && (
        <div className="row" style={{ gap: '0.5rem' }}>
          {canAck && inc.status !== 'ACKNOWLEDGED' && (
            <button className="ghost" onClick={onAcknowledge}>
              Akui
            </button>
          )}
          {canResolve && (
            <button className="primary" onClick={onResolve}>
              Tutup insiden
            </button>
          )}
        </div>
      )}

      <section className="stack" style={{ gap: '0.5rem' }}>
        <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Alarm anggota ({detail.members.length})</h3>
        {detail.members.length === 0 ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Tidak ada alarm hidup (insiden sudah selesai).</p>
        ) : (
          detail.members.map((m: IncidentAlarm) => (
            <div key={m.entityId + m.kind} className="row spread" style={{ gap: '0.5rem', alignItems: 'center' }}>
              <span className="row" style={{ gap: '0.45rem', alignItems: 'center', minWidth: 0 }}>
                <span className={`dot-sev sev-${m.severity.toLowerCase()}`} aria-hidden="true" />
                <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.label}</span>
              </span>
              <span className="badge">{m.kind}</span>
            </div>
          ))
        )}
      </section>

      <section className="stack" style={{ gap: '0.5rem' }}>
        <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Timeline</h3>
        <ol className="timeline">
          {detail.timeline.map((ev: IncidentEventView, i: number) => (
            <li key={i}>
              <span className="tl-dot" aria-hidden="true" />
              <div className="stack" style={{ gap: '0.15rem' }}>
                <strong style={{ fontSize: '0.85rem' }}>{EVENT_LABEL[ev.type] ?? ev.type}</strong>
                <span className="muted" style={{ fontSize: '0.82rem' }}>{ev.message}</span>
                <span className="muted" style={{ fontSize: '0.75rem' }}>{new Date(ev.at).toLocaleString('id-ID')}</span>
              </div>
            </li>
          ))}
        </ol>
      </section>
    </div>
  )
}
