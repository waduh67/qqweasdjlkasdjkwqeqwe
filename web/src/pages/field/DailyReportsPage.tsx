import { useCallback, useEffect, useState } from 'react'
import { field, SLOT_LABELS, type DailyReport, type ReportSlot } from '@/api/field'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, TextareaField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { Modal } from '@/components/molecules/Modal'
import { DataTable } from '@/components/organisms/DataTable'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { currentMonth, dateLabel, errorText, LoadError } from '@/pages/b2b/shared'
import { dateError, FieldFilterError, monthError, operationalDate } from './filters'
import { useFieldTechnicians } from './useFieldTechnicians'
import './field.css'

export function DailyReportsPage() {
  const { user } = useAuth(), { can } = useCan()
  const [date, setDate] = useState(operationalDate), [technician, setTechnician] = useState(''), [month, setMonth] = useState(currentMonth), [page, setPage] = useState(0)
  const [editing, setEditing] = useState<{ slot: ReportSlot; report: DailyReport | null } | null>(null), [detail, setDetail] = useState<DailyReport | null>(null)
  const dateValidation = dateError(date) || (date > operationalDate() ? 'Tanggal mendatang belum dapat dilihat.' : ''), monthValidation = monthError(month)
  const load = useCallback(() => dateValidation ? Promise.reject(new FieldFilterError(dateValidation)) : field.completeness(date, technician), [date, technician, dateValidation])
  const today = useWarehouseQuery(load)
  const technicians = useFieldTechnicians(today.state.status === 'ready' ? today.state.data.technicians : undefined)
  const loadHistory = useCallback(() => monthValidation ? Promise.reject(new FieldFilterError(monthValidation)) : field.history(month, technician, page), [month, technician, page, monthValidation])
  const history = useWarehouseQuery(loadHistory)
  const done = () => { setEditing(null); today.reload(); history.reload() }
  return <div className="stack"><PageHeader title="Laporan Pagi / Sore" subtitle="Catat pekerjaan harian dan pantau kelengkapan laporan teknisi NE dan FO." />
    <div className="row wrap"><TextField label="Tanggal kelengkapan" type="date" min="2000-01-01" max={operationalDate()} required value={date} validationState={dateValidation ? 'error' : undefined} validationMessage={dateValidation} onChange={(_, d) => setDate(d.value)} />
      <SelectField label="Teknisi" value={technician} onChange={(_, d) => { setTechnician(d.value); setPage(0) }}><option value="">Semua teknisi dalam akses Anda</option>{technicians.map(t => <option key={t.id} value={t.id}>{t.name} · {t.team}</option>)}</SelectField>
      <Button disabled={!!dateValidation} onClick={today.reload}>Muat ulang kelengkapan</Button></div>
    {dateValidation ? <EmptyState title="Pilih tanggal untuk melihat kelengkapan" /> : <WarehouseState {...today}>{data => <>
      {data.calendarError && <p role="alert" className="error">Sinkron kalender: {data.calendarError}. Kelengkapan menggunakan kalender tersimpan.</p>}
      <div className="card stack"><h2>{dateLabel(data.date)} · {data.workingDay ? 'Hari kerja' : 'Hari libur · Tidak wajib'}</h2>
        <p>{data.total} teknisi · Pagi {data.morning} · Sore {data.evening} · Belum lengkap {data.incomplete}</p><p className="muted">Laporan pada hari libur tetap tersimpan. Laporan hanya dapat dikirim atau direvisi pada tanggal hari ini di WIB.</p></div>
      <DataTable presentation="warehouse" rows={data.rows} rowKey={r => r.technician.id} empty="Tidak ada teknisi dalam akses Anda." columns={[
        { key: 'name', header: 'Teknisi', cell: r => r.technician.name, description: r => 'Teknisi ' + r.technician.team },
        ...(['MORNING', 'EVENING'] as const).map(slot => ({ key: slot, header: SLOT_LABELS[slot], cell: (r: typeof data.rows[number]) => {
          const report = slot === 'MORNING' ? r.morning : r.evening
          return <div className="field-cell-actions">{report ? <Button onClick={() => setDetail(report)}>Terkirim · Revisi {report.revision}</Button> : <Badge>{data.workingDay ? 'Belum dikirim' : 'Tidak wajib'}</Badge>}
            {r.technician.id === user?.id && date === operationalDate() && can('field.report.submit') && <Button onClick={() => setEditing({ slot, report })}>{report ? 'Revisi' : 'Kirim'} {SLOT_LABELS[slot].toLowerCase()}</Button>}</div>
        } })),
      ]} />
    </>}</WarehouseState>}
    <section className="stack" aria-label="Riwayat laporan harian"><h2>Riwayat laporan</h2><TextField label="Bulan riwayat laporan" type="month" min="2000-01" max={currentMonth()} required value={month} validationState={monthValidation ? 'error' : undefined} validationMessage={monthValidation} onChange={(_, d) => { setMonth(d.value); setPage(0) }} />
      {monthValidation ? <EmptyState title="Pilih bulan untuk melihat riwayat" /> : <WarehouseState {...history}>{data => <>
        <DataTable presentation="warehouse" rows={data.content} rowKey={r => r.id} empty="Belum ada laporan pada bulan ini." columns={[
          { key: 'date', header: 'Tanggal', cell: r => dateLabel(r.date), description: r => SLOT_LABELS[r.slot], onCellClick: setDetail },
          { key: 'name', header: 'Teknisi', cell: r => r.technicianName }, { key: 'notes', header: 'Catatan terakhir', cell: r => r.notes },
          { key: 'revision', header: 'Riwayat', cell: r => <Button onClick={() => setDetail(r)}>Revisi {r.revision} · {r.photos.length} foto</Button> },
        ]} /><WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={setPage} />
      </>}</WarehouseState>}
    </section>
    {editing && <ReportEditor slot={editing.slot} previous={editing.report} onClose={() => setEditing(null)} onDone={done} />}
    {detail && <ReportTimeline report={detail} onClose={() => setDetail(null)} />}
  </div>
}
function ReportEditor({ slot, previous, onClose, onDone }: { readonly slot: ReportSlot; readonly previous: DailyReport | null; readonly onClose: () => void; readonly onDone: () => void }) {
  const [date] = useState(operationalDate), [notes, setNotes] = useState(previous?.notes ?? ''), [files, setFiles] = useState<File[]>([]), [error, setError] = useState('')
  const [review, setReview] = useState<WarehouseCommand<DailyReport> | null>(null), { can } = useCan()
  function prepare() {
    if (!can('field.report.submit')) return
    if (!notes.trim() || notes.trim().length > 5000 || files.length > 5 || files.some(f => !['image/jpeg', 'image/png'].includes(f.type) || f.size < 1 || f.size > 5 * 1024 * 1024)) { setError('Isi catatan dan pilih maksimal 5 foto JPEG/PNG, maksimal 5 MB per foto.'); return }
    setError(''); setReview(field.report({ date, slot, notes: notes.trim(), expectedRevision: previous?.revision ?? 0 }, files))
  }
  return <ResourceForm editing={!!previous} title={(previous ? 'Revisi laporan ' : 'Kirim laporan ') + SLOT_LABELS[slot].toLowerCase()} readOnly={!can('field.report.submit')} reviewAction={can('field.report.submit')} onReview={prepare} onBack={() => setReview(null)} onClose={onClose}
    review={review && <WarehouseCommandDialog embedded title="Tinjau laporan harian" command={review} disabled={!can('field.report.submit')} confirmLabel="Kirim laporan" onDone={onDone} onClose={() => setReview(null)} summary={<><p>{dateLabel(date)} · {SLOT_LABELS[slot]}</p><p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{notes.trim()}</p><p>{files.length} foto{files.length ? ': ' + files.map(f => f.name).join(', ') : ''}</p>{previous && <p>Catatan dan foto sebelumnya tetap ada dalam riwayat revisi.</p>}</>} />}>
    <form className="stack" onSubmit={e => { e.preventDefault(); prepare() }}><p>{dateLabel(date)} · {SLOT_LABELS[slot]} · WIB</p>
      <TextareaField label="Catatan pekerjaan" required maxLength={5000} rows={5} value={notes} disabled={!can('field.report.submit')} onChange={(_, d) => setNotes(d.value)} />
      <label className="stack">Foto bukti (opsional, maksimal 5 foto)<input type="file" multiple accept="image/jpeg,image/png" disabled={!can('field.report.submit')} onChange={e => { setFiles(Array.from(e.target.files ?? [])); setError('') }} /></label>
      <p className="muted">JPEG atau PNG, maksimal 5 MB per foto. Setiap revisi menyimpan foto yang dipilih di sini; foto lama tetap dapat dibaca dalam riwayat.</p>{files.length > 0 && <p>{files.map(f => f.name).join(', ')}</p>}{error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
function ReportTimeline({ report, onClose }: { readonly report: DailyReport; readonly onClose: () => void }) {
  const load = useCallback(() => field.timeline(report.id), [report.id]), result = useWarehouseQuery(load)
  return <Modal className="field-detail-dialog" title={'Laporan ' + SLOT_LABELS[report.slot].toLowerCase() + ' · ' + report.technicianName} onClose={onClose} footer={<Button onClick={onClose}>Tutup</Button>}><div className="stack"><p>{dateLabel(report.date)}</p>
    <WarehouseState {...result}>{revisions => <>{revisions.map(r => <section className="card stack" key={r.revisionId}><h3>Revisi {r.revision}</h3><p className="muted">{new Date(r.submittedAt).toLocaleString('id-ID', { timeZone: 'Asia/Jakarta' })} WIB</p><p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{r.notes}</p><ReportPhotos report={r} /></section>)}</>}</WarehouseState>
  </div></Modal>
}
function ReportPhotos({ report }: { readonly report: DailyReport }) {
  const [urls, setUrls] = useState<string[]>([]), [error, setError] = useState(''), [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let live = true; const allocated: string[] = []; setUrls([]); setError('')
    void Promise.all(report.photos.map(async photo => {
      const blob = await field.photo(report, photo.id)
      if (!live) return ''
      const url = URL.createObjectURL(blob); allocated.push(url); return url
    })).then(result => { if (live) setUrls(result) }).catch(e => { if (live) setError(errorText(e)) })
    return () => { live = false; allocated.forEach(url => URL.revokeObjectURL(url)) }
  }, [report, attempt])
  return <div className="stack"><LoadError error={error} reload={() => setAttempt(v => v + 1)} />{!error && report.photos.length > 0 && urls.length === 0 && <p role="status">Memuat foto privat…</p>}{report.photos.length === 0 && <p className="muted">Tanpa foto pada revisi ini.</p>}{urls.map((url, i) => <img key={url} src={url} alt={'Bukti laporan revisi ' + report.revision + ' foto ' + (i + 1)} style={{ maxWidth: '100%', height: 'auto' }} />)}</div>
}
