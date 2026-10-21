import { useCallback, useState } from 'react'
import { tokenStore } from '@/api/client'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { field, type KpiRow } from '@/api/field'
import { KPI_CATEGORY_LABELS } from '@/api/warehouse/referenceWorkTypes'
import { Button, EmptyState, SelectField, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { Modal } from '@/components/molecules/Modal'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { currentMonth } from '@/pages/b2b/shared'
import { FieldFilterError, monthError } from './filters'
import { useFieldTechnicians } from './useFieldTechnicians'
import './field.css'

export function scoreLabel(value: number) { return value.toLocaleString('id-ID', { maximumFractionDigits: 1 }) + '%' }
export function KpiPage() { return <div className="stack"><PageHeader title="KPI Teknisi" subtitle="Capaian bulanan teknisi NE dan FO berdasarkan laporan dan pekerjaan yang tersimpan." /><KpiPanel /></div> }
export function KpiPanel() {
  const { user } = useAuth()
  const scope = user?.tenantId + ':' + user?.id + ':' + user?.permissions.join(',') + ':' + tokenStore.getSessionVersion()
  return <KpiContent key={scope} />
}
function KpiContent() {
  const { hasPermission } = useCan(), teamView = hasPermission('kpi.team.view')
  const [month, setMonth] = useState(currentMonth), [team, setTeam] = useState(''), [technician, setTechnician] = useState(''), [detail, setDetail] = useState<KpiRow | null>(null)
  const validation = monthError(month)
  const load = useCallback(() => validation ? Promise.reject(new FieldFilterError(validation)) : field.kpi(month, teamView ? team : '', teamView ? technician : ''), [month, team, technician, validation, teamView])
  const result = useWarehouseQuery(load)
  const technicians = useFieldTechnicians(result.state.status === 'ready' ? result.state.data.technicians : undefined)
  return <section className="stack" aria-label="KPI teknisi bulanan">
    <div className="row wrap"><TextField label="Bulan KPI" type="month" required min="2000-01" max={currentMonth()} value={month} validationState={validation ? 'error' : undefined} validationMessage={validation} onChange={(_, d) => { setMonth(d.value); setDetail(null) }} />
      {teamView && <><SelectField label="Tim teknisi" value={team} onChange={(_, d) => { setTeam(d.value); setTechnician(''); setDetail(null) }}><option value="">Semua tim</option><option value="NE">Teknisi NE</option><option value="FO">Teknisi FO</option></SelectField>
      <SelectField label="Teknisi" value={technician} onChange={(_, d) => { setTechnician(d.value); setDetail(null) }}><option value="">Semua teknisi dalam akses Anda</option>{technicians.filter(t => !team || t.team === team).map(t => <option key={t.id} value={t.id}>{t.name} · {t.team}</option>)}</SelectField></>}
      <Button onClick={result.reload} disabled={!!validation}>Muat ulang KPI</Button></div>
    {validation ? <EmptyState title="Pilih bulan untuk melihat KPI" /> : <WarehouseState {...result}>{data => <>
      {data.calendarError && <p role="alert" className="error">Sinkron kalender: {data.calendarError}. Perhitungan menggunakan kalender tersimpan.</p>}
      <div className="card stack"><h2>Rata-rata {scoreLabel(data.average)}</h2><p>{data.rows.length} teknisi · {data.workingDays} hari kerja sebulan · Sabtu {data.saturdayOff ? 'libur' : 'masuk'}</p>
        <p className="muted">Target hari kerja mencakup seluruh bulan. Kehadiran dihitung dari minimal satu laporan; pagi dan sore masing-masing dihitung sekali. Kalender dan target terbaru berlaku juga pada bulan sebelumnya.</p>
        {data.rows.length === 0 ? <EmptyState title="Tidak ada teknisi pada filter ini" /> : <div className="stack" aria-label="Grafik capaian KPI">{data.rows.map(row => <div className="stack" key={row.technician.id}>
          <div className="spread wrap"><Button onClick={() => setDetail(row)}>{row.technician.name} · {row.technician.team}</Button><strong>{scoreLabel(row.percentage)}</strong></div>
          <div role="meter" aria-label={'KPI ' + row.technician.name} aria-valuemin={0} aria-valuemax={100} aria-valuenow={row.percentage} style={{ background: 'var(--surface-2)', height: '0.5rem', borderRadius: '0.25rem', overflow: 'hidden' }}><div style={{ background: 'var(--accent)', width: row.percentage + '%', height: '100%' }} /></div>
        </div>)}</div>}
      </div>
      <DataTable presentation="warehouse" rows={data.rows} rowKey={r => r.technician.id} empty="Tidak ada teknisi pada filter ini." columns={[
        { key: 'name', header: 'Teknisi', cell: r => r.technician.name, description: r => 'Teknisi ' + r.technician.team, onCellClick: setDetail },
        { key: 'attendance', header: 'Kehadiran', cell: r => <KpiMetric metric={r.metrics[0]} /> },
        { key: 'reports', header: 'Pagi / sore', cell: r => <KpiMetric metric={r.metrics[1]} /> },
        { key: 'work', header: 'Visit / WO overdue', cell: r => <KpiMetric metric={r.metrics[2]} /> },
        { key: 'points', header: 'Aktivasi / Poin WO', cell: r => <KpiMetric metric={r.metrics[3]} /> },
        { key: 'average', header: 'KPI', cell: r => <Button onClick={() => setDetail(r)}>{scoreLabel(r.percentage)} · Detail</Button> },
      ]} />
    </>}</WarehouseState>}
    {detail && <KpiDetail row={detail} onClose={() => setDetail(null)} />}
  </section>
}
function KpiMetric({ metric }: { readonly metric: KpiRow['metrics'][number] }) {
  return <div className="kpi-metric"><strong>{metric.actual} / {metric.target}</strong><small>Aktual / target · {scoreLabel(metric.percentage)}</small></div>
}
function KpiDetail({ row, onClose }: { readonly row: KpiRow; readonly onClose: () => void }) {
  return <Modal className="field-detail-dialog" title={'KPI · ' + row.technician.name} onClose={onClose} footer={<Button onClick={onClose}>Tutup</Button>}><div className="stack">
    <p>Teknisi {row.technician.team} · Rata-rata {scoreLabel(row.percentage)}</p>
    <DataTable presentation="warehouse" rows={row.metrics} rowKey={r => r.key} columns={[{ key: 'metric', header: 'Indikator', cell: r => r.label }, { key: 'actual', header: 'Aktual', cell: r => r.actual }, { key: 'target', header: 'Target / batas', cell: r => r.target }, { key: 'score', header: 'Capaian', cell: r => scoreLabel(r.percentage) }]} />
    {row.technician.team === 'FO' ? <><p className="muted">Setiap WO overdue mengurangi skor sesuai batas jumlah. Satu WO dihitung sekali per teknisi per bulan, termasuk yang kemudian selesai. PSB 1 poin; Project 3 poin berdasarkan jenis saat penugasan.</p>
      <h3>WO overdue ({row.overdue.length})</h3><DataTable presentation="warehouse" rows={row.overdue} rowKey={r => r.id} empty="Tidak ada WO overdue." columns={[{ key: 'wo', header: 'WO', cell: r => r.code, description: r => r.title }, { key: 'date', header: 'Mulai overdue', cell: r => new Date(r.overdueAt).toLocaleString('id-ID', { timeZone: 'Asia/Jakarta' }) }]} />
      <h3>Pekerjaan selesai</h3><DataTable presentation="warehouse" rows={row.completed} rowKey={r => r.id} empty="Belum ada pekerjaan selesai." columns={[{ key: 'wo', header: 'WO', cell: r => r.code, description: r => r.title }, { key: 'date', header: 'Selesai', cell: r => new Date(r.completedAt).toLocaleString('id-ID', { timeZone: 'Asia/Jakarta' }) }, { key: 'points', header: 'Poin', cell: r => KPI_CATEGORY_LABELS[r.category] }]} />
    </> : <><p className="muted">Visit dibatasi target tiap client sebelum dijumlahkan. Tanpa target visit, skor visit 100%. Aktivasi dihitung dari client baru pada bulan pembuatan, untuk teknisi awal.</p>
      <h3>Client dan target visit</h3><DataTable presentation="warehouse" rows={row.clients} rowKey={r => r.id} empty="Tidak ada target client." columns={[{ key: 'name', header: 'Client', cell: r => r.name }, { key: 'visits', header: 'Visit tercatat', cell: r => r.visits }, { key: 'target', header: 'Target', cell: r => r.target }, { key: 'counted', header: 'Untuk KPI', cell: r => Math.min(r.visits, r.target) }]} /></>}
  </div></Modal>
}
