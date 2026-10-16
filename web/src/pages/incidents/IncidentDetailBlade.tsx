import { useEffect, useRef, useState } from 'react'
import { Check, CheckCircle2, Megaphone, RefreshCw } from 'lucide-react'
import { api, ApiError } from '@/api/client'
import type { IncidentDetail, IncidentView } from '@/api/incident'
import type { BroadcastView, NotificationChannel } from '@/api/notification'
import { useCan } from '@/auth/useCan'
import { useAuth } from '@/auth/useAuth'
import { Button, SelectField, Spinner, TextareaField } from '@/components/atoms'
import { CommandBar, Tabs, type CommandAction } from '@/components/molecules'
import { DataTable } from '@/components/organisms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { ResourceStep } from '@/components/organisms/ResourceStep'
import { CreationSummary } from '@/components/organisms/CreationReview'
import { useToast } from '@/system'
import './incident-detail.css'

const severity: Record<string, string> = { CRITICAL: 'Kritis', MAJOR: 'Tinggi', MINOR: 'Sedang', WARNING: 'Peringatan', INFO: 'Informasi' }
const status: Record<string, string> = { OPEN: 'Terbuka', ACKNOWLEDGED: 'Diakui', RESOLVED: 'Selesai' }
const event: Record<string, string> = { OPENED: 'Dibuka', SEVERITY_CHANGED: 'Keparahan diperbarui', ACKNOWLEDGED: 'Diakui', RESOLVED: 'Selesai' }
const cause: Record<string, string> = { POWER_OUTAGE: 'Gangguan listrik', FIBER_CUT: 'Kabel serat putus', MIXED: 'Beberapa penyebab' }
const downCause: Record<string, string> = { DYING_GASP: 'Gangguan listrik', LOS: 'Sinyal hilang', UNKNOWN: 'Belum diketahui' }
const alarm: Record<string, string> = { ONU_HIGH_RX: 'Sinyal terima terlalu kuat', ONU_LOW_RX: 'Sinyal terima lemah', ONU_LOS: 'Sinyal hilang (LOS)', ONU_OFFLINE: 'ONU tidak terhubung', OLT_UNREACHABLE: 'OLT tidak dapat dijangkau', COLLECTOR_SILENT: 'Collector tidak melapor', PPPOE_DOWN: 'Sesi PPPoE terputus' }
const time = (value: string | null) => value ? new Date(value).toLocaleString('id-ID') : '—'

export function IncidentDetailBlade({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useCan(), { readOnly } = useAuth(), toast = useToast()
  const [detail, setDetail] = useState<IncidentDetail | null>(null)
  const [error, setError] = useState(''), [attempt, setAttempt] = useState(0)
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState(false)
  const [tab, setTab] = useState<'overview' | 'activity'>('overview')
  const [broadcast, setBroadcast] = useState(false)
  const inFlight = useRef(false)
  useEffect(() => {
    let current = true
    setLoading(true); setError('')
    void api.get<IncidentDetail>(`/api/incidents/${id}`).then(value => { if (current) setDetail(value) })
      .catch(error => { if (current) setError(error instanceof ApiError ? error.message : 'Detail insiden belum berhasil dimuat.') })
      .finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [id, attempt])
  async function act(action: 'acknowledge' | 'resolve') {
    if (inFlight.current || readOnly) return
    inFlight.current = true; setBusy(true); setError('')
    try {
      const incident = await api.post<IncidentView>(`/api/incidents/${id}/${action}`)
      setDetail(previous => previous ? { ...previous, incident } : previous)
      setAttempt(value => value + 1); onChanged()
      toast.success(action === 'acknowledge' ? 'Insiden diakui.' : 'Insiden ditutup.')
    } catch (error) { setError(error instanceof ApiError ? error.message : 'Perubahan belum berhasil disimpan.') }
    finally { inFlight.current = false; setBusy(false) }
  }
  const inc = detail?.incident
  const actions: CommandAction[] = []
  if (inc?.status === 'OPEN' && can('incident.ticket.update')) actions.push({ key: 'ack', label: 'Akui insiden', icon: <Check size={16} />, disabled: busy || readOnly, onClick: () => void act('acknowledge') })
  if (inc && inc.status !== 'RESOLVED' && can('incident.ticket.close')) actions.push({ key: 'resolve', label: 'Tutup insiden', icon: <CheckCircle2 size={16} />, disabled: busy || readOnly, onClick: () => void act('resolve') })
  if (inc && inc.status !== 'RESOLVED' && can('notification.broadcast.send')) actions.push({ key: 'broadcast', label: 'Beritahu pelanggan', icon: <Megaphone size={16} />, disabled: busy || readOnly || inc.affectedCustomerCount === 0, onClick: () => setBroadcast(true) })
  actions.push({ key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, disabled: loading || busy, onClick: () => setAttempt(value => value + 1) })
  const tabs = `incident-${id}`
  return <ResourceForm title={inc?.title ?? 'Detail insiden'} readOnly busy={busy} onClose={onClose} onBack={() => {}} className="incident-detail-blade">
    <div className="incident-detail">
      <CommandBar layout="wrap" primary={actions[0]} actions={actions.slice(1)} />
      {error && <div role="alert" className="incident-error"><p>{error}</p>{!detail && <Button onClick={() => setAttempt(value => value + 1)}>Coba lagi</Button>}</div>}
      {!detail || !inc ? loading && <Spinner /> : <>
        <Tabs idPrefix={tabs} active={tab} onChange={setTab} tabs={[{ key: 'overview', label: 'Ringkasan' }, { key: 'activity', label: 'Aktivitas' }]} />
        <section role="tabpanel" id={`${tabs}-panel-${tab}`} aria-labelledby={`${tabs}-tab-${tab}`} className="incident-content">
          {tab === 'overview' ? <>
            <dl className="incident-essentials">
              <div><dt>Status</dt><dd>{status[inc.status] ?? inc.status}</dd></div>
              <div><dt>Keparahan</dt><dd>{severity[inc.severity] ?? inc.severity}</dd></div>
              <div><dt>Perangkat</dt><dd>{inc.rootLabel}</dd></div>
              <div><dt>Jenis perangkat</dt><dd>{inc.rootType}</dd></div>
              <div><dt>Pelanggan terdampak</dt><dd>{inc.affectedCustomerCount}</dd></div>
              <div><dt>Jumlah alarm</dt><dd>{inc.alarmCount}</dd></div>
              <div><dt>Dibuka</dt><dd>{time(inc.openedAt)}</dd></div>
              <div><dt>Terakhir terdeteksi</dt><dd>{time(inc.lastSeenAt)}</dd></div>
              {inc.suspectedCause && <div><dt>Dugaan penyebab</dt><dd>{cause[inc.suspectedCause] ?? inc.suspectedCause}</dd></div>}
              {inc.resolvedAt && <div><dt>Diselesaikan</dt><dd>{time(inc.resolvedAt)}</dd></div>}
            </dl>
            <section className="incident-section" aria-label="Alarm terkait"><h2>Alarm terkait</h2>
              <DataTable rows={detail.members} rowKey={row => `${row.entityType}:${row.entityId}:${row.kind}`} presentation="resource" columns={[
                { key: 'device', header: 'Perangkat', minWidth: 240, cell: row => row.label },
                { key: 'alarm', header: 'Alarm', minWidth: 220, cell: row => alarm[row.kind] ?? row.kind },
                { key: 'severity', header: 'Keparahan', minWidth: 120, cell: row => severity[row.severity] ?? row.severity },
                { key: 'cause', header: 'Penyebab terputus', minWidth: 160, cell: row => row.downCause ? downCause[row.downCause] ?? row.downCause : '—' },
              ]} empty={<p>Tidak ada alarm aktif.</p>} />
            </section>
          </> : <DataTable rows={detail.timeline.map((item, index) => ({ ...item, key: index }))} rowKey={row => String(row.key)} presentation="resource" initialSort={{ key: 'time', dir: 'desc' }} columns={[
            { key: 'time', header: 'Waktu', minWidth: 190, cell: row => time(row.at), sortValue: row => row.at },
            { key: 'event', header: 'Aktivitas', minWidth: 170, cell: row => event[row.type] ?? row.type },
            { key: 'message', header: 'Keterangan', minWidth: 340, cell: row => row.message },
          ]} empty={<p>Belum ada aktivitas.</p>} />}
        </section>
        {broadcast && <BroadcastComposer incident={inc} onClose={() => setBroadcast(false)} />}
      </>}
    </div>
  </ResourceForm>
}

function BroadcastComposer({ incident, onClose }: { incident: IncidentView; onClose: () => void }) {
  const toast = useToast()
  const [channel, setChannel] = useState<NotificationChannel>('WHATSAPP')
  const [message, setMessage] = useState('Layanan internet Anda sedang mengalami gangguan. Tim teknis kami sedang menanganinya. Mohon maaf atas ketidaknyamanannya.')
  const [review, setReview] = useState(false), [sending, setSending] = useState(false), [error, setError] = useState('')
  const sendingRef = useRef(false)
  async function send() {
    if (sendingRef.current || !message.trim()) return
    sendingRef.current = true; setSending(true); setError('')
    try {
      const result = await api.post<BroadcastView>('/api/notifications/broadcasts', { incidentId: incident.id, channel, message: message.trim() })
      toast.success(`Pemberitahuan: ${result.sentCount} terkirim, ${result.skippedCount} dilewati.`)
      onClose()
    } catch (error) { setError(error instanceof ApiError ? error.message : 'Hasil pengiriman belum diketahui. Periksa riwayat pemberitahuan sebelum mengirim ulang.') }
    finally { sendingRef.current = false; setSending(false) }
  }
  return <ResourceForm title="Beritahu pelanggan" onClose={onClose} onBack={() => setReview(false)} onReview={() => setReview(true)}
    busy={sending} reviewLabel="Tinjau + kirim" steps={[{ key: 'message', label: 'Pesan', validate: () => message.trim() ? null : 'Isi pesan pemberitahuan.' }]}
    review={review ? <div className="stack"><CreationSummary rows={[["Insiden", incident.title], ["Pelanggan terdampak", incident.affectedCustomerCount], ["Kanal", channel === 'WHATSAPP' ? 'WhatsApp' : 'Email']]} /><p className="incident-message">{message}</p>{error && <p role="alert">{error}</p>}</div> : undefined}
    reviewFooter={<><Button disabled={sending} onClick={() => setReview(false)}>Sebelumnya</Button><Button disabled={sending} onClick={onClose}>Batal</Button><Button variant="primary" disabled={sending} onClick={() => void send()}>{sending ? 'Mengirim…' : 'Kirim pemberitahuan'}</Button></>}>
    <ResourceStep step="message"><p>{incident.affectedCustomerCount} pelanggan terdampak.</p>
      <SelectField label="Kanal" value={channel} onChange={(_, data) => setChannel(data.value as NotificationChannel)}><option value="WHATSAPP">WhatsApp</option><option value="EMAIL">Email</option></SelectField>
      <TextareaField label="Pesan" required rows={5} maxLength={2000} value={message} onChange={(_, data) => setMessage(data.value)} />
    </ResourceStep>
  </ResourceForm>
}
