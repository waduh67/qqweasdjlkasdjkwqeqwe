import { useCallback, useState } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { field, type FieldSettings, type WorkCalendar, type WorkHoliday } from '@/api/field'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { Button, TextField } from '@/components/atoms'
import { DataTable } from '@/components/organisms/DataTable'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { currentMonth, dateLabel } from '@/pages/b2b/shared'
import { dateError, FieldFilterError } from './filters'
import './field.css'

export function FieldSettingsPanel({ calendar = false }: { readonly calendar?: boolean }) {
  const result = useWarehouseQuery(field.settings), { readOnly } = useAuth(), [editing, setEditing] = useState(false)
  return <div className="stack"><WarehouseState {...result}>{settings => <>
    <div className="card stack">{calendar ? <><p>Sabtu <strong>{settings.saturdayOff ? 'libur' : 'masuk'}</strong>. Minggu selalu libur.</p><p className="muted">Hari kerja KPI dihitung untuk seluruh bulan, dikurangi hari libur tersimpan.</p></> : <><p>Batas jumlah WO overdue: <strong>{settings.foOverdueLimit} WO</strong></p><p>Target PSB / Project FO: <strong>{settings.foPointTarget} poin per bulan</strong></p><p className="muted">Batas jumlah ini menentukan penalti KPI. Batas hari tanpa aktivitas tetap di tab Material & WO.</p></>}
      <div className="row wrap"><Button onClick={result.reload}>Muat ulang</Button>{!readOnly && <Button onClick={() => setEditing(true)}>Ubah {calendar ? 'hari kerja' : 'target KPI'}</Button>}</div>
    </div>
    {editing && <FieldSettingsEditor settings={settings} calendar={calendar} onClose={() => setEditing(false)} onDone={() => { setEditing(false); result.reload() }} />}
  </>}</WarehouseState>{calendar && <CalendarPanel />}</div>
}
function FieldSettingsEditor({ settings, calendar, onClose, onDone }: { readonly settings: FieldSettings; readonly calendar: boolean; readonly onClose: () => void; readonly onDone: () => void }) {
  const [saturdayOff, setSaturdayOff] = useState(settings.saturdayOff), [limit, setLimit] = useState(String(settings.foOverdueLimit)), [target, setTarget] = useState(String(settings.foPointTarget))
  const [error, setError] = useState(''), [review, setReview] = useState<WarehouseCommand<FieldSettings> | null>(null), { readOnly } = useAuth()
  function prepare() {
    if (!/^[1-9][0-9]{0,3}$/.test(limit) || Number(limit) > 1000 || !/^[1-9][0-9]{0,4}$/.test(target) || Number(target) > 10000) { setError('Isi batas 1–1000 WO dan target 1–10000 poin.'); return }
    setError(''); setReview(field.saveSettings({ expectedRevision: settings.revision, saturdayOff, foOverdueLimit: Number(limit), foPointTarget: Number(target) }))
  }
  return <ResourceForm editing title={calendar ? 'Ubah hari kerja' : 'Ubah target KPI'} readOnly={readOnly} reviewAction={!readOnly} onReview={prepare} onClose={onClose} onBack={() => setReview(null)}
    review={review && <WarehouseCommandDialog embedded title="Tinjau setelan lapangan" command={review} disabled={readOnly} confirmLabel="Simpan setelan" onClose={() => setReview(null)} onDone={onDone} onReload={onDone} summary={<><p>Sabtu {saturdayOff ? 'libur' : 'masuk'} · Batas {limit} WO overdue · Target {target} poin FO.</p><p>Revisi {settings.revision}. KPI bulan sebelumnya juga menggunakan setelan terbaru.</p></>} />}>
    <form className="stack" onSubmit={e => { e.preventDefault(); prepare() }}>{calendar ? <Checkbox label="Sabtu libur" checked={saturdayOff} onChange={(_, d) => setSaturdayOff(d.checked === true)} /> : <><TextField label="Batas jumlah WO overdue" value={limit} inputMode="numeric" required onChange={(_, d) => setLimit(d.value)} /><TextField label="Target poin PSB / Project FO" value={target} inputMode="numeric" required onChange={(_, d) => setTarget(d.value)} /></>}{error && <p role="alert" className="error">{error}</p>}</form>
  </ResourceForm>
}
function CalendarPanel() {
  const [year, setYear] = useState(currentMonth().slice(0, 4)), [edit, setEdit] = useState<{ readonly calendar: WorkCalendar; readonly holiday: WorkHoliday | null } | null>(null)
  const [review, setReview] = useState<{ readonly operation: WarehouseCommand<WorkCalendar>; readonly title: string; readonly summary: string } | null>(null), { readOnly } = useAuth()
  const validation = /^(20[0-9]{2}|2100)$/.test(year) ? '' : 'Pilih tahun 2000–2100.'
  const load = useCallback(() => validation ? Promise.reject(new FieldFilterError(validation)) : field.calendar(Number(year)), [year, validation]), result = useWarehouseQuery(load)
  const done = () => { setEdit(null); setReview(null); result.reload() }
  return <section className="stack" aria-label="Kalender kerja"><h2>Kalender kerja</h2><TextField label="Tahun kalender" inputMode="numeric" maxLength={4} value={year} validationState={validation ? 'error' : undefined} validationMessage={validation} onChange={(_, d) => setYear(d.value)} />
    {!validation && <WarehouseState {...result}>{data => <>
      {data.syncError && <p role="alert" className="error">Sinkron kalender gagal: {data.syncError}. Hari libur tersimpan tetap dapat dipakai dan diubah.</p>}
      <p className="muted">Sumber tanggalans.com · {data.refreshedAt ? 'Terakhir disinkron ' + new Date(data.refreshedAt).toLocaleString('id-ID', { timeZone: 'Asia/Jakarta' }) : 'Belum disinkron'}. Sinkron tahunan otomatis berjalan 1 Januari. Perubahan manual dipertahankan saat sinkron ulang.</p>
      <div className="row wrap"><Button onClick={result.reload}>Muat ulang kalender</Button>{!readOnly && <><Button variant="primary" onClick={() => setEdit({ calendar: data, holiday: null })}>Tambah hari libur</Button><Button onClick={() => setReview({ operation: field.sync(data), title: 'Sinkron kalender', summary: 'Ambil ulang hari libur tahun ' + data.year + ' dari tanggalans.com. Perubahan dan penghapusan manual dipertahankan.' })}>Sinkron dari sumber</Button></>}</div>
      <DataTable presentation="warehouse" rows={data.holidays} rowKey={r => r.date} empty="Belum ada hari libur tersimpan." columns={[
        { key: 'date', header: 'Tanggal', cell: r => dateLabel(r.date) }, { key: 'name', header: 'Hari libur', cell: r => r.name }, { key: 'source', header: 'Sumber', cell: r => r.source === 'MANUAL' ? 'Manual' : 'tanggalans.com' },
        { key: 'actions', header: 'Tindakan', cell: r => !readOnly && <div className="field-cell-actions"><Button aria-label={'Ubah ' + r.name} onClick={() => setEdit({ calendar: data, holiday: r })}>Ubah</Button><Button aria-label={'Hapus ' + r.name} onClick={() => setReview({ operation: field.deleteHoliday(data, r.date), title: 'Hapus hari libur', summary: dateLabel(r.date) + ' · ' + r.name + '. Tanggal ini tidak kembali saat sinkron ulang.' })}>Hapus</Button></div> },
      ]} />
    </>}</WarehouseState>}
    {edit && <HolidayEditor calendar={edit.calendar} holiday={edit.holiday} onClose={() => setEdit(null)} onDone={done} />}
    {review && <WarehouseCommandDialog title={review.title} command={review.operation} summary={<p>{review.summary}</p>} disabled={readOnly} confirmLabel="Simpan kalender" onClose={() => setReview(null)} onDone={done} onReload={done} />}
  </section>
}
function HolidayEditor({ calendar, holiday, onClose, onDone }: { readonly calendar: WorkCalendar; readonly holiday: WorkHoliday | null; readonly onClose: () => void; readonly onDone: () => void }) {
  const [date, setDate] = useState(holiday?.date ?? ''), [name, setName] = useState(holiday?.name ?? ''), [error, setError] = useState('')
  const [review, setReview] = useState<WarehouseCommand<WorkCalendar> | null>(null), { readOnly } = useAuth()
  function prepare() {
    const invalid = dateError(date) || (!holiday && date.slice(0, 4) !== String(calendar.year) ? 'Tanggal baru harus berada pada tahun kalender.' : '') || (!name.trim() || name.trim().length > 300 ? 'Isi nama hari libur, maksimal 300 karakter.' : '')
    if (invalid) { setError(invalid); return }
    setError(''); setReview(field.saveHoliday(calendar.year, { date, name: name.trim(), previousDate: holiday?.date ?? null, expectedRevision: calendar.revision }))
  }
  return <ResourceForm editing={!!holiday} title={holiday ? 'Ubah hari libur' : 'Tambah hari libur'} readOnly={readOnly} reviewAction={!readOnly} onReview={prepare} onClose={onClose} onBack={() => setReview(null)}
    review={review && <WarehouseCommandDialog embedded title="Tinjau hari libur" command={review} disabled={readOnly} confirmLabel="Simpan hari libur" onClose={() => setReview(null)} onDone={onDone} onReload={onDone} summary={<><p>{dateLabel(date)} · {name.trim()}</p>{holiday && holiday.date !== date && <p>Pindah dari {dateLabel(holiday.date)}. Tanggal lama dihapus dari kalender, termasuk setelah sinkron ulang.</p>}</>} />}>
    <form className="stack" onSubmit={e => { e.preventDefault(); prepare() }}><TextField label="Tanggal hari libur" type="date" required min="2000-01-01" max="2100-12-31" value={date} onChange={(_, d) => setDate(d.value)} /><TextField label="Nama hari libur" required maxLength={300} value={name} onChange={(_, d) => setName(d.value)} /><p className="muted">Mengubah tanggal dapat memindahkan hari libur ke tahun lain.</p>{error && <p role="alert" className="error">{error}</p>}</form>
  </ResourceForm>
}
