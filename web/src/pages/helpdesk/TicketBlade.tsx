import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Check, ClipboardPlus, LockKeyhole, Play, RotateCcw, Send, UserRoundCheck } from 'lucide-react'
import { ApiError } from '@/api/client'
import { assignTicket, changeTicketPriority, changeTicketStatus, escalateTicket, getTicket, replyTicket,
  TICKET_CATEGORY_LABEL, TICKET_PRIORITY_LABEL, TICKET_STATUS_LABEL,
  type TicketDetail, type TicketMessageView, type TicketPriority, type TicketView } from '@/api/helpdesk'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, Spinner, TextareaField } from '@/components/atoms'
import { Tabs } from '@/components/molecules'
import { CommandBar, type CommandAction } from '@/components/molecules/CommandBar'
import { ConfirmDialog } from '@/components/molecules/ConfirmDialog'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { CreationSummary } from '@/components/organisms/CreationReview'
import { useStaff } from '@/hooks/useStaff'
import { useToast } from '@/system'
import { ticketSla, ticketTime } from './presentation'

export function TicketBlade({ id, onClose, onChanged }: { id: string; onClose: () => void; onChanged: () => void }) {
  const { can } = useCan(), { user } = useAuth(), toast = useToast()
  const canManage = can('helpdesk.ticket.manage'), canReply = can('helpdesk.ticket.reply')
  const staff = useStaff(canManage)
  const [detail, setDetail] = useState<TicketDetail | null>(null), [loadError, setLoadError] = useState('')
  const [attempt, setAttempt] = useState(0), [tab, setTab] = useState<'conversation' | 'details'>('conversation')
  const [reply, setReply] = useState(''), [busy, setBusy] = useState(false), [actionError, setActionError] = useState('')
  const [escalating, setEscalating] = useState(false)
  const [closing, setClosing] = useState(false)
  const inFlight = useRef(false)
  useEffect(() => {
    let current = true
    setLoadError('')
    void getTicket(id).then(value => { if (current) setDetail(value) })
      .catch(() => { if (current) setLoadError('Detail tiket belum berhasil dimuat.') })
    return () => { current = false }
  }, [id, attempt])
  async function mutate(action: () => Promise<TicketDetail>, message: string) {
    if (inFlight.current) return false
    inFlight.current = true; setBusy(true); setActionError('')
    try { setDetail(await action()); toast.success(message); onChanged(); return true }
    catch (error) { setActionError(error instanceof ApiError ? error.message : 'Perubahan belum berhasil disimpan. Coba lagi.'); return false }
    finally { inFlight.current = false; setBusy(false) }
  }
  const ticket = detail?.ticket
  const open = ticket && ticket.status !== 'CLOSED'
  const actions: CommandAction[] = []
  if (ticket && open && canManage) {
    if (user?.id && ticket.assigneeId !== user.id) actions.push({ key: 'take', label: 'Ambil tiket', icon: <UserRoundCheck size={16} />, disabled: busy,
      onClick: () => void mutate(() => assignTicket(id, user.id), 'Tiket ditugaskan kepada Anda.') })
    if (ticket.status === 'OPEN') actions.push({ key: 'start', label: 'Tandai ditangani', icon: <Play size={16} />, disabled: busy,
      onClick: () => void mutate(() => changeTicketStatus(id, 'IN_PROGRESS'), 'Status tiket diperbarui.') })
    if (ticket.status !== 'OPEN') actions.push({ key: 'reopen', label: 'Kembalikan ke antrean', icon: <RotateCcw size={16} />, disabled: busy,
      onClick: () => void mutate(() => changeTicketStatus(id, 'OPEN'), 'Tiket dikembalikan ke antrean.') })
    if (ticket.status !== 'RESOLVED') actions.push({ key: 'resolve', label: 'Selesaikan penanganan', icon: <Check size={16} />, disabled: busy,
      onClick: () => void mutate(() => changeTicketStatus(id, 'RESOLVED'), 'Tiket menunggu konfirmasi pelanggan.') })
    if (!ticket.workOrderId) actions.push({ key: 'escalate', label: 'Buat work order', icon: <ClipboardPlus size={16} />, disabled: busy, onClick: () => setEscalating(true) })
    actions.push({ key: 'close', label: 'Tutup tiket', icon: <LockKeyhole size={16} />, disabled: busy,
      onClick: () => { setActionError(''); setClosing(true) } })
  }
  const people = staff.some(person => person.id === ticket?.assigneeId) || !ticket?.assigneeId
    ? staff : [{ id: ticket.assigneeId, name: ticket.assigneeName ?? 'Operator' }, ...staff]
  const tabId = `ticket-${id}`
  return <ResourceForm title={ticket?.code ?? 'Detail tiket'} onClose={onClose} onBack={() => {}} readOnly busy={busy}
    dirty={!!reply.trim()} className="helpdesk-ticket-blade">
    {loadError ? <div role="alert" className="helpdesk-error"><p>{loadError}</p><Button onClick={() => setAttempt(value => value + 1)}>Coba lagi</Button></div>
      : !ticket || !detail ? <Spinner /> : <div className="helpdesk-ticket">
        <h2 className="helpdesk-subject">{ticket.subject}</h2>
        <dl className="helpdesk-essentials">
          <div><dt>Pelanggan</dt><dd><Link to={`/customers/${ticket.customerId}`}>{ticket.customerName}</Link></dd></div>
          <div><dt>Status</dt><dd>{TICKET_STATUS_LABEL[ticket.status]}</dd></div>
        </dl>
        {actions.length > 0 && <CommandBar primary={actions[0]} actions={actions.slice(1)} />}
        {actionError && <p role="alert" className="helpdesk-action-error">{actionError}</p>}
        <Tabs idPrefix={tabId} active={tab} onChange={setTab} tabs={[{ key: 'conversation', label: 'Percakapan' }, { key: 'details', label: 'Detail' }]} />
        <div role="tabpanel" id={`${tabId}-panel-${tab}`} aria-labelledby={`${tabId}-tab-${tab}`} className="helpdesk-ticket-content">
          {tab === 'conversation' ? <>
            <ol className="helpdesk-conversation" aria-label="Percakapan tiket">
              <Message author="CUSTOMER" authorName={ticket.customerName} body={detail.description} at={ticket.openedAt} />
              {detail.messages.map((message, index) => <Message key={`${message.at}-${index}`} {...message} />)}
            </ol>
            {open && canReply && <form className="helpdesk-reply" onSubmit={event => { event.preventDefault(); if (!reply.trim()) return
              void mutate(() => replyTicket(id, reply.trim()), 'Balasan terkirim.').then(ok => { if (ok) setReply('') }) }}>
              <TextareaField label="Balasan" required value={reply} onChange={(_, data) => setReply(data.value)} maxLength={2000} rows={4} disabled={busy} />
              <Button type="submit" variant="primary" icon={<Send size={16} />} disabled={busy || !reply.trim()}>{busy ? 'Menyimpan…' : 'Kirim balasan'}</Button>
            </form>}
            {!open && <p className="helpdesk-note">Tiket sudah ditutup. Balasan tidak tersedia.</p>}
          </> : <>
            <dl className="helpdesk-properties">
              <div><dt>Kategori</dt><dd>{TICKET_CATEGORY_LABEL[ticket.category]}</dd></div>
              <div><dt>Dibuka</dt><dd>{ticketTime(ticket.openedAt)}</dd></div>
              <div><dt>Aktivitas terakhir</dt><dd>{ticketTime(ticket.lastActivityAt)}</dd></div>
              <div><dt>SLA</dt><dd className={ticketSla(ticket).overdue ? 'helpdesk-overdue' : undefined}>{ticketSla(ticket).label}</dd></div>
              <div><dt>Work order</dt><dd>{ticket.workOrderId ? <Link to={`/work-orders/${ticket.workOrderId}`}>{ticket.workOrderCode}</Link> : 'Belum dibuat'}</dd></div>
            </dl>
            {canManage && open ? <section className="helpdesk-handling" aria-label="Penanganan tiket">
              <SelectField label="Penanggung jawab" value={ticket.assigneeId ?? ''} disabled={busy}
                onChange={(_, data) => void mutate(() => assignTicket(id, data.value || null), 'Penanggung jawab diperbarui.')}>
                <option value="">Belum ditugaskan</option>{people.map(person => <option key={person.id} value={person.id}>{person.name}</option>)}
              </SelectField>
              <SelectField label="Prioritas" value={ticket.priority} disabled={busy}
                onChange={(_, data) => void mutate(() => changeTicketPriority(id, data.value as TicketPriority), 'Prioritas diperbarui.')}>
                {Object.entries(TICKET_PRIORITY_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </SelectField>
            </section> : <dl className="helpdesk-properties">
              <div><dt>Penanggung jawab</dt><dd>{ticket.assigneeName ?? 'Belum ditugaskan'}</dd></div>
              <div><dt>Prioritas</dt><dd>{TICKET_PRIORITY_LABEL[ticket.priority]}</dd></div>
            </dl>}
          </>}
        </div>
        {escalating && <EscalateTicket ticket={ticket} busy={busy} onClose={() => setEscalating(false)}
          onCreate={(priority, note) => mutate(() => escalateTicket(id, priority, note), 'Work order dibuat.')} />}
        {closing && <ConfirmDialog title="Tutup tiket?" confirmLabel="Tutup tiket" busy={busy} onClose={() => setClosing(false)}
          message={<><p>Tiket yang ditutup tidak dapat dibuka kembali atau menerima balasan.</p>
            {!!reply.trim() && <p>Balasan yang belum dikirim akan dibuang.</p>}
            {actionError && <p role="alert">{actionError}</p>}</>}
          onConfirm={() => void mutate(() => changeTicketStatus(id, 'CLOSED'), 'Tiket ditutup.').then(ok => { if (ok) { setClosing(false); setReply('') } })} />}
      </div>}
  </ResourceForm>
}
function EscalateTicket({ ticket, busy, onClose, onCreate }: {
  ticket: TicketView; busy: boolean; onClose: () => void; onCreate: (priority: TicketPriority, note: string) => Promise<boolean>
}) {
  const [priority, setPriority] = useState(ticket.priority), [note, setNote] = useState(''), [reviewing, setReviewing] = useState(false)
  const [failed, setFailed] = useState(false)
  const create = async () => { setFailed(false); if (await onCreate(priority, note)) onClose(); else setFailed(true) }
  return <ResourceForm title="Buat work order" onClose={onClose} onBack={() => setReviewing(false)} onReview={() => setReviewing(true)}
    busy={busy} reviewAction review={reviewing ? <>
      {failed && <p role="alert">Work order belum berhasil dibuat. Periksa kembali atau coba lagi.</p>}
      <CreationSummary rows={[["Tiket", ticket.code], ["Pelanggan", ticket.customerName], ["Prioritas", TICKET_PRIORITY_LABEL[priority]], ["Catatan", note || '—']]} /></> : undefined}
    reviewFooter={<><Button disabled={busy} onClick={() => setReviewing(false)}>Sebelumnya</Button><Button variant="primary" disabled={busy} onClick={() => void create()}>{busy ? 'Membuat…' : 'Buat work order'}</Button></>}>
    <div className="helpdesk-escalation">
      <p>Buat pekerjaan kunjungan teknisi untuk menangani tiket ini.</p>
      <SelectField label="Prioritas" value={priority} onChange={(_, data) => setPriority(data.value as TicketPriority)}>
        {Object.entries(TICKET_PRIORITY_LABEL).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
      </SelectField>
      <TextareaField label="Catatan untuk teknisi" value={note} onChange={(_, data) => setNote(data.value)} maxLength={500} rows={4} />
    </div>
  </ResourceForm>
}
function Message({ author, authorName, body, at }: TicketMessageView) {
  const role = { CUSTOMER: 'Pelanggan', OPERATOR: 'Tim', SYSTEM: 'Sistem' }[author]
  return <li className={author === 'SYSTEM' ? 'helpdesk-message helpdesk-system-message' : 'helpdesk-message'}>
    <div className="helpdesk-message-meta"><strong>{authorName || role}</strong><span>{role}</span><time dateTime={at}>{ticketTime(at)}</time></div>
    <p>{body}</p>
  </li>
}
