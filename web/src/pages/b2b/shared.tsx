import { useEffect, useState } from 'react'
import { Badge, Button } from '@/components/atoms'
import { b2b, type B2BMonth, type B2BPage, type B2BVisit } from '@/api/b2b'
import { Modal } from '@/components/molecules/Modal'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseDataError } from '@/api/warehouse/codec'

export function useB2BPage<T>(load: () => Promise<B2BPage<T>>) {
  const [data, setData] = useState<B2BPage<T> | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [version, setVersion] = useState(0)
  useEffect(() => {
    let live = true; setLoading(true); setError(''); setData(null)
    load().then(result => { if (live) setData(result) }).catch(e => { if (live) setError(errorText(e)) }).finally(() => { if (live) setLoading(false) })
    return () => { live = false }
  }, [load, version])
  return { data, error, loading, reload: () => setVersion(v => v + 1) }
}
export function errorText(e: unknown) { return e instanceof WarehouseDataError ? 'Data B2B tidak dapat dibaca. Coba muat ulang.' : e instanceof Error ? e.message : 'Data B2B gagal dimuat.' }
export function B2BPagination<T>({ data, onChange }: { data: B2BPage<T> | null; onChange: (n: number) => void }) {
  return data && <WarehousePagination page={data.page} size={data.size} total={data.totalElements} onChange={onChange} />
}
export function LoadError({ error, reload }: { error: string; reload: () => void }) {
  return error && <div role="alert" className="stack"><p className="error">{error}</p><Button onClick={reload}>Muat ulang</Button></div>
}
export function currentMonth() {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Jakarta', year: 'numeric', month: '2-digit' }).formatToParts(new Date())
  return parts.find(p => p.type === 'year')?.value + '-' + parts.find(p => p.type === 'month')?.value
}
export function dateLabel(date: string) { return new Date(date + 'T12:00:00Z').toLocaleDateString('id-ID', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'Asia/Jakarta' }) }
export function MonthDetail({ report, onClose }: { report: B2BMonth; onClose: () => void }) {
  return <Modal title={report.setting.name} onClose={onClose} footer={<Button onClick={onClose}>Tutup</Button>}>
    <div className="stack"><p>{report.setting.address}</p><p>{report.setting.contact}</p><p>Teknisi NE: {report.setting.technicianName}</p>
      <p>{report.counted} dari {report.setting.target} visit · Sisa {report.remaining}</p>
      <p className="muted">Minggu Senin–Minggu dipotong pada batas bulan. Belum visit hanya ditandai setelah minggu berakhir.</p>
      {report.weeks.map(w => <div className="spread wrap" key={w.start}><span>{dateLabel(w.start)} – {dateLabel(w.end)}</span><Badge>{w.counted ? w.counted + ' visit' : !report.setting.active ? 'Client nonaktif' : w.missed ? 'Belum visit' : 'Belum berakhir'}</Badge></div>)}
    </div>
  </Modal>
}
export function VisitDetail({ visit, onClose }: { visit: B2BVisit; onClose: () => void }) {
  const [urls, setUrls] = useState<string[]>([]), [error, setError] = useState(''), [version, setVersion] = useState(0)
  useEffect(() => {
    let live = true; const allocated: string[] = []; setUrls([]); setError('')
    Promise.all(visit.photos.map(async p => {
      const blob = await b2b.photo(visit.id, p.id)
      if (!live) return ''
      const url = URL.createObjectURL(blob); allocated.push(url); return url
    })).then(result => { if (live) setUrls(result) }).catch(e => { if (live) setError(errorText(e)) })
    return () => { live = false; allocated.forEach(URL.revokeObjectURL) }
  }, [visit, version])
  return <Modal title={'Visit · ' + visit.clientName} onClose={onClose} footer={<Button onClick={onClose}>Tutup</Button>}>
    <div className="stack"><p>{dateLabel(visit.visitDate)} · {visit.reporterName}</p><Badge>{visit.counted ? 'Dihitung ke target' : 'Visit tambahan'}</Badge>
      <p style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{visit.notes}</p>
      <LoadError error={error} reload={() => setVersion(v => v + 1)} />
      {!error && urls.length === 0 && <p role="status">Memuat foto privat…</p>}
      {urls.map((url, i) => <img key={url} src={url} alt={'Bukti visit ' + (i + 1)} style={{ maxWidth: '100%', height: 'auto' }} />)}
    </div>
  </Modal>
}
