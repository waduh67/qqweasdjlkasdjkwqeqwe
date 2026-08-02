import { useCallback, useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { WorkOrderDetail } from '../api/workorder'
import { useCan } from '../auth/useCan'
import { Badge, EmptyState, Spinner, useToast } from '../components/ui'
import { IconWorkOrder } from '../components/icons'
import { APPROVAL_LABEL, APPROVAL_TONE, PRIORITY_LABEL, type ActFn } from '../components/workorder/labels'
import { WoStatusBadge } from '../components/workorder/views'
import { WorkOrderDetailBody } from '../components/workorder/WorkOrderDetailBody'
import { useTechnicians } from '../components/workorder/useTechnicians'

/**
 * Detail satu work order sebagai rute tersendiri (`/work-orders/:id`, `/my-work-orders/:id`)
 * — bukan drawer. Isiannya banyak (ringkasan, penugasan, aksi lifecycle, redaman optik,
 * bukti foto/tanda tangan, riwayat); halaman penuh satu kolom lebih lapang dan tombol
 * "kembali" peramban jalan alami, terutama di ponsel. Prop `backTo`/`backLabel` menautkan
 * balik ke papan asalnya (dispatch operator atau "Tugas Saya" teknisi).
 */
export function WorkOrderDetailPage({ backTo, backLabel }: { backTo: string; backLabel: string }) {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const { can } = useCan()
  // Pemilih teknisi hanya dipakai section penugasan (izin `assign`) — teknisi lapangan
  // tak punya izin itu, jadi pemuatannya dilewati agar tak menembak `/api/users` sia-sia.
  const { fetchTechnicians } = useTechnicians(can('workorder.order.assign'))

  const [detail, setDetail] = useState<WorkOrderDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)

  const load = useCallback(async () => {
    try {
      setDetail(await api.get<WorkOrderDetail>(`/api/work-orders/${id}`))
    } catch (err) {
      setNotFound(true)
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat detail work order')
    } finally {
      setLoading(false)
    }
  }, [id, toast])

  useEffect(() => {
    void load()
  }, [load])

  // Kontrak aksi lifecycle: jalankan, tampilkan pesan, lalu muat ulang detail agar tombol
  // & data ikut status baru — kecuali `keepOpen=false` (WO dihapus) yang kembali ke daftar.
  const onAct: ActFn = (action, ok, keepOpen) => {
    void (async () => {
      try {
        await action()
        if (ok) toast.success(ok)
        if (keepOpen) await load()
        else navigate(backTo)
      } catch (err) {
        toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
      }
    })()
  }

  if (loading) {
    return (
      <div className="stack" style={{ gap: '1.25rem' }}>
        <BackLink label={backLabel} onClick={() => navigate(backTo)} />
        <div className="card" style={{ display: 'grid', placeItems: 'center', padding: '3rem' }}>
          <Spinner />
        </div>
      </div>
    )
  }

  if (notFound || !detail) {
    return (
      <div className="stack" style={{ gap: '1rem' }}>
        <BackLink label={backLabel} onClick={() => navigate(backTo)} />
        <div className="card">
          <EmptyState
            title="Work order tidak ditemukan"
            hint="Mungkin sudah dihapus atau kamu tak berizin melihatnya."
            icon={<IconWorkOrder size={32} />}
          />
        </div>
      </div>
    )
  }

  const wo = detail.workOrder
  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <BackLink label={backLabel} onClick={() => navigate(backTo)} />

      <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'center' }}>
        <h1 className="page-title" style={{ margin: 0 }}>{wo.title}</h1>
        <span className="badge accent">{wo.code}</span>
        <WoStatusBadge status={wo.status} />
        {wo.approvalStatus && <Badge tone={APPROVAL_TONE[wo.approvalStatus]}>{APPROVAL_LABEL[wo.approvalStatus]}</Badge>}
        {(wo.priority === 'URGENT' || wo.priority === 'HIGH') && (
          <Badge tone="warning">Prioritas {PRIORITY_LABEL[wo.priority]}</Badge>
        )}
      </div>

      <WorkOrderDetailBody detail={detail} fetchTechnicians={fetchTechnicians} onAct={onAct} />
    </div>
  )
}

function BackLink({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <button className="ghost" onClick={onClick} style={{ alignSelf: 'flex-start', gap: '0.35rem' }}>
      <span aria-hidden>←</span> {label}
    </button>
  )
}
