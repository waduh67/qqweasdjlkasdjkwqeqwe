import { useCallback, useState } from 'react'
import { b2b, type B2BMonth, type B2BVisit } from '@/api/b2b'
import { useCan } from '@/auth/useCan'
import { Badge, Button, TextareaField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { B2BPagination, currentMonth, dateLabel, LoadError, MonthDetail, useB2BPage, VisitDetail } from './shared'

function ReportForm({ report, onDone, onClose }: { report: B2BMonth; onDone: () => void; onClose: () => void }) {
  const { can } = useCan(), writable = can('b2b.visit.report')
  const [notes, setNotes] = useState(''), [files, setFiles] = useState<File[]>([]), [error, setError] = useState('')
  const [review, setReview] = useState<WarehouseCommand<B2BVisit> | null>(null)
  const toReview = () => {
    if (!writable) return
    if (!notes.trim() || files.length < 1 || files.length > 5 || files.some(f => !['image/jpeg', 'image/png'].includes(f.type) || f.size < 1 || f.size > 5 * 1024 * 1024)) {
      setError('Isi catatan dan pilih 1–5 foto JPEG/PNG, maksimal 5 MB per foto.'); return
    }
    setError(''); setReview(b2b.report(report.clientId, notes.trim(), files))
  }
  return <ResourceForm title={'Laporkan visit · ' + report.setting.name} readOnly={!writable} reviewAction={writable} onReview={toReview} onBack={() => setReview(null)} onClose={onClose}
    review={review && <WarehouseCommandDialog embedded title="Tinjau visit B2B" confirmLabel="Kirim laporan visit" disabled={!can('b2b.visit.report')} command={review} summary={<><p>{report.setting.name} · {report.setting.address}</p><p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{notes}</p><p>{files.length} foto: {files.map(f => f.name).join(', ')}</p><p>Satu visit per hari dihitung. Laporan tambahan tetap disimpan.</p></>} onDone={onDone} onClose={() => setReview(null)} />}>
    <form className="stack" onSubmit={e => { e.preventDefault(); toReview() }}>
      <p>{report.setting.address} · {report.setting.contact}</p><p>Target {report.setting.target} · Dihitung {report.counted} · Sisa {report.remaining}</p>
      <TextareaField label="Catatan visit" disabled={!writable} required maxLength={5000} rows={5} value={notes} onChange={(_, d) => setNotes(d.value)} />
      <label className="stack">Foto bukti (1–5 foto)<input type="file" disabled={!writable} accept="image/jpeg,image/png" multiple required onChange={e => { setFiles(Array.from(e.target.files ?? [])); setError('') }} /></label>
      <p className="muted">JPEG atau PNG, maksimal 5 MB per foto. Foto hanya dapat dibaca admin dan pelapor.</p>
      {files.length > 0 && <p>{files.map(f => f.name).join(', ')}</p>}{error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
export function B2BVisitsPage({ admin = false }: { admin?: boolean }) {
  const { can } = useCan()
  const [month, setMonth] = useState(currentMonth), [q, setQ] = useState(''), [n, setN] = useState(0), [historyPage, setHistoryPage] = useState(0)
  const [detail, setDetail] = useState<B2BMonth | null>(null), [reporting, setReporting] = useState<B2BMonth | null>(null), [visit, setVisit] = useState<B2BVisit | null>(null)
  const load = useCallback(() => b2b.reports(month, q, n), [month, q, n]), reports = useB2BPage(load)
  const loadHistory = useCallback(() => b2b.visits(month, undefined, historyPage), [month, historyPage]), history = useB2BPage(loadHistory)
  return <div className="stack"><PageHeader title={admin ? 'Rekap B2B' : 'Visit B2B'} subtitle={admin ? 'Pantau target, minggu tanpa visit, dan bukti kunjungan setiap client.' : 'Client yang ditugaskan kepada Anda beserta target dan riwayat visit bulanan.'} />
    <div className="row wrap"><TextField label="Bulan rekap" type="month" required max={currentMonth()} value={month} onChange={(_, d) => { if (/^[0-9]{4}-[0-9]{2}$/.test(d.value)) { setMonth(d.value); setN(0); setHistoryPage(0) } }} />
      <TextField label="Cari client pada rekap" value={q} onChange={(_, d) => { setQ(d.value); setN(0) }} /></div>
    <LoadError error={reports.error} reload={reports.reload} />
    <DataTable presentation="warehouse" rows={reports.data?.content ?? []} rowKey={r => r.clientId} loading={reports.loading} empty={reports.error ? <></> : admin ? 'Belum ada client pada bulan ini.' : 'Belum ada client B2B yang ditugaskan kepada Anda pada bulan ini.'}
      columns={[{ key: 'client', header: 'Client', cell: r => r.setting.name, description: r => r.setting.address, onCellClick: setDetail }, { key: 'ne', header: 'Teknisi NE', cell: r => r.setting.technicianName },
        { key: 'target', header: 'Dihitung / target', cell: r => r.counted + ' / ' + r.setting.target }, { key: 'remaining', header: 'Sisa', cell: r => r.remaining },
        { key: 'weeks', header: 'Minggu tanpa visit', cell: r => r.weeks.filter(w => w.missed).length }, { key: 'status', header: 'Status', cell: r => <Badge>{r.setting.active ? 'Aktif' : 'Nonaktif'}</Badge> },
        ...(!admin ? [{ key: 'report', header: 'Laporan', cell: (r: B2BMonth) => <Button disabled={!can('b2b.visit.report') || !r.setting.active || month !== currentMonth()} onClick={() => setReporting(r)}>Laporkan visit</Button> }] : [])]} />
    <B2BPagination data={reports.data} onChange={setN} />
    <section className="stack" aria-label="Riwayat visit B2B"><h2>Riwayat visit</h2><p className="muted">Visit tambahan pada hari yang sama tetap terlihat, tanpa menambah jumlah yang dihitung.</p>
      <LoadError error={history.error} reload={history.reload} />
      <DataTable presentation="warehouse" rows={history.data?.content ?? []} rowKey={r => r.id} loading={history.loading} empty={history.error ? <></> : 'Belum ada laporan visit pada bulan ini.'} columns={[
        { key: 'client', header: 'Client', cell: r => r.clientName, onCellClick: setVisit }, { key: 'date', header: 'Tanggal visit', cell: r => dateLabel(r.visitDate) }, { key: 'reporter', header: 'Pelapor', cell: r => r.reporterName },
        { key: 'counted', header: 'Perhitungan', cell: r => <Badge>{r.counted ? 'Dihitung' : 'Tambahan'}</Badge> }, { key: 'photos', header: 'Bukti', cell: r => <Button onClick={() => setVisit(r)}>{r.photos.length} foto · Buka</Button> }]} />
      <B2BPagination data={history.data} onChange={setHistoryPage} />
    </section>
    {detail && <MonthDetail report={detail} onClose={() => setDetail(null)} />}{visit && <VisitDetail visit={visit} onClose={() => setVisit(null)} />}
    {reporting && <ReportForm report={reporting} onClose={() => setReporting(null)} onDone={() => { setReporting(null); reports.reload(); history.reload() }} />}
  </div>
}
