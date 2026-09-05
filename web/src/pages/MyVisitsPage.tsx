import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '@/api/client'
import { checkInVisit, checkOutVisit, listVisits, markVisitOnSite, submitVisit, type VisitListView } from '@/api/fieldservice'
import { Badge, Button, EmptyState, Spinner } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { useToast } from '@/system'

const NEXT: Record<string, { readonly label: string; readonly run: (visit: VisitListView) => Promise<unknown> } | undefined> = {
  PLANNED: { label: 'Check-in', run: (visit) => checkInVisit(visit.id, visit.revision, 'ACCEPTED', null) },
  CHECKED_IN: { label: 'Tiba di lokasi', run: (visit) => markVisitOnSite(visit.id, visit.revision) },
  ON_SITE: { label: 'Check-out', run: (visit) => checkOutVisit(visit.id, visit.revision) },
  CHECKED_OUT: { label: 'Kirim kunjungan', run: (visit) => submitVisit(visit.id, visit.revision) },
}

export function MyVisitsPage() {
  const toast = useToast()
  const [visits, setVisits] = useState<readonly VisitListView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const reload = useCallback(async () => { try { setError(null); setVisits((await listVisits()).content) } catch (caught) { setError(caught instanceof ApiError ? caught.message : 'Gagal memuat kunjungan') } finally { setLoading(false) } }, [])
  useEffect(() => { void reload() }, [reload])
  const act = async (visit: VisitListView) => { const action = NEXT[visit.state]; if (!action) return; setBusy(visit.id); try { await action.run(visit); toast.success(`${action.label} berhasil`); await reload() } catch (caught) { toast.error(caught instanceof ApiError ? caught.message : 'Aksi kunjungan ditolak') } finally { setBusy(null) } }
  if (loading) return <div className="card" style={{ display: 'grid', placeItems: 'center', padding: '3rem' }}><Spinner /></div>
  if (error) return <div className="card stack" role="alert"><span className="error">{error}</span><Button onClick={() => void reload()}>Coba lagi</Button></div>
  return <div className="stack"><PageHeader title="Kunjungan saya" /><div className="stack">{visits.length === 0 ? <div className="card"><EmptyState title="Belum ada kunjungan" hint="Kunjungan yang ditugaskan akan muncul di sini." /></div> : visits.map((visit) => { const action = NEXT[visit.state]; return <article className="card spread wrap" key={visit.id}><div className="stack"><strong>Work order {visit.workOrderId}</strong><span className="muted">Jadwal: {visit.scheduledAt ? new Date(visit.scheduledAt).toLocaleString('id-ID') : 'Belum dijadwalkan'}</span></div><div className="row wrap"><Badge tone="accent">{visit.state}</Badge>{action && <Button disabled={busy === visit.id} onClick={() => void act(visit)}>{action.label}</Button>}</div></article> })}</div></div>
}
