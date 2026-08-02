/**
 * Isi drawer detail work order + aksi lifecycle-nya, dipakai bersama papan dispatch
 * operator dan papan "Tugas Saya" teknisi. Tombol yang muncul mengikuti status WO
 * dan izin: dispatcher lewat `workorder.order.update`/`close`, teknisi lapangan lewat
 * `workorder.order.field` — persis meniru `@authz.canAny(...)` di controller server.
 */
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { api, ApiError } from '../../api/client'
import type { User } from '../../api/types'
import type {
  EvidenceKind,
  EvidenceView,
  SignatureView,
  WorkOrderDetail,
  WorkOrderStatus,
  WorkOrderView,
} from '../../api/workorder'
import { useCan } from '../../auth/useCan'
import { MultiCombobox } from '../MultiCombobox'
import { Badge, SkeletonRows, Tabs, useToast } from '../ui'
import {
  APPROVAL_LABEL,
  APPROVAL_TONE,
  EVENT_LABEL,
  KIND_LABEL,
  KINDS,
  PRIORITY_LABEL,
  TYPE_LABEL,
  fmt,
  fmtDbm,
  rxHealth,
  sameRoster,
  type ActFn,
} from './labels'
import { AssigneeChips, Field, WoStatusBadge } from './views'

/** Detail + aksi lifecycle. Tombol yang muncul mengikuti status & izin. */
export function WorkOrderDetailBody({
  detail,
  fetchTechnicians,
  onAct,
}: {
  detail: WorkOrderDetail
  fetchTechnicians: (term: string) => Promise<User[]>
  onAct: ActFn
}) {
  const { can } = useCan()
  const wo = detail.workOrder
  // State awal cukup dari prop: komponen ini di-`key` pada id work order, jadi
  // berganti work order me-remount dan mereset pilihan ini dengan sendirinya.
  const [tab, setTab] = useState<'ringkasan' | 'bukti' | 'riwayat'>('ringkasan')
  const [assignees, setAssignees] = useState<string[]>(wo.assignees.map((a) => a.id))
  const [note, setNote] = useState('')
  const [reason, setReason] = useState('')

  // Satu kolom catatan dipakai bersama: opsional saat menyetujui, wajib saat menolak.
  const [decisionNote, setDecisionNote] = useState('')

  const id = wo.id
  const canAssign = can('workorder.order.assign')
  const canUpdate = can('workorder.order.update')
  const canClose = can('workorder.order.close')
  const canApprove = can('workorder.order.approve')
  // Aksi lapangan dipegang teknisi lewat `workorder.order.field` (bukan update/close
  // milik dispatcher). Server meng-`canAny(update|close, field)`; cerminkan di sini agar
  // teknisi bisa mulai/selesai/catat redaman/unggah bukti pada WO-nya sendiri.
  const canField = can('workorder.order.field')
  const canStart = canUpdate || canField
  const canComplete = canClose || canField
  const canRecordOptical = canUpdate || canField
  const terminal = wo.status === 'DONE' || wo.status === 'CANCELLED'
  const awaitingApproval = wo.status === 'DONE' && wo.approvalStatus === 'PENDING'

  const showOptical = canRecordOptical || wo.rxBeforeDbm != null || wo.rxAfterDbm != null
  const showEvidence = can('workorder.evidence.view')

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      {/* Baris status (keadaan saja) di atas tab — data rinci pindah ke grid Ringkasan
          agar tak dobel tampil. Prioritas hanya muncul di sini saat perlu perhatian. */}
      <div className="row wrap" style={{ gap: '0.4rem' }}>
        <WoStatusBadge status={wo.status} />
        {wo.approvalStatus && <Badge tone={APPROVAL_TONE[wo.approvalStatus]}>{APPROVAL_LABEL[wo.approvalStatus]}</Badge>}
        {(wo.priority === 'URGENT' || wo.priority === 'HIGH') && (
          <Badge tone="warning">Prioritas {PRIORITY_LABEL[wo.priority]}</Badge>
        )}
      </div>

      <Tabs
        active={tab}
        onChange={setTab}
        tabs={[
          { key: 'ringkasan', label: 'Ringkasan' },
          { key: 'bukti', label: 'Bukti & optik' },
          { key: 'riwayat', label: 'Riwayat', badge: detail.timeline.length },
        ]}
      />

      {tab === 'ringkasan' && (
        <div className="stack" style={{ gap: '1.1rem' }}>
          {wo.description && <p className="wo-desc">{wo.description}</p>}

          <dl className="wo-grid">
            <Field label="Tipe">{TYPE_LABEL[wo.type]}</Field>
            <Field label="Pelanggan">{wo.customerName ?? <span className="muted">—</span>}</Field>
            <Field label="Prioritas">{PRIORITY_LABEL[wo.priority]}</Field>
            <Field label="Teknisi"><AssigneeChips wo={wo} /></Field>
            <Field label="Jadwal">{wo.scheduledAt ? fmt(wo.scheduledAt) : <span className="muted">—</span>}</Field>
            {wo.destinationLat != null && wo.destinationLng != null && (
              <Field label="Lokasi">
                <a
                  href={`https://www.google.com/maps/search/?api=1&query=${wo.destinationLat},${wo.destinationLng}`}
                  target="_blank"
                  rel="noreferrer"
                >
                  Navigasi ke pelanggan ↗
                </a>
              </Field>
            )}
            <Field label="Dibuat">{fmt(wo.createdAt)}</Field>
            {wo.completedAt && <Field label="Selesai">{fmt(wo.completedAt)}</Field>}
            {wo.resolutionNote && <Field label="Catatan">{wo.resolutionNote}</Field>}
            {wo.approvedByName && (
              <Field label={wo.approvalStatus === 'REJECTED' ? 'Ditolak oleh' : 'Disetujui oleh'}>
                {wo.approvedByName}
                {wo.approvedAt ? ` · ${fmt(wo.approvedAt)}` : ''}
              </Field>
            )}
            {wo.approvalNote && (
              <Field label={wo.approvalStatus === 'REJECTED' ? 'Alasan penolakan' : 'Catatan persetujuan'}>
                {wo.approvalNote}
              </Field>
            )}
            {wo.cancelReason && <Field label="Alasan batal">{wo.cancelReason}</Field>}
          </dl>

          {/* Penugasan — selagi work order belum selesai/batal. */}
          {canAssign && !terminal && (
            <section className="stack" style={{ gap: '0.4rem' }}>
              <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Penugasan</h3>
              <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
                <label className="stack" style={{ flex: 1, gap: '0.25rem' }}>
                  <span>Teknisi (bisa lebih dari satu)</span>
                  <MultiCombobox
                    values={assignees}
                    onChange={setAssignees}
                    fetchOptions={fetchTechnicians}
                    toId={(t) => t.id}
                    toLabel={(t) => t.name}
                    initialLabels={Object.fromEntries(
                      wo.assignees.filter((a) => a.name).map((a) => [a.id, a.name as string]),
                    )}
                    debounceMs={0}
                    placeholder="Cari teknisi…"
                    emptyText="Tak ada teknisi"
                  />
                </label>
                <button
                  className="primary"
                  disabled={assignees.length === 0 || sameRoster(assignees, wo.assignees)}
                  onClick={() => onAct(() => api.post(`/api/work-orders/${id}/assign`, { technicianIds: assignees }), 'Teknisi ditugaskan', true)}
                >
                  {wo.assignees.length > 0 ? 'Tugaskan ulang' : 'Tugaskan'}
                </button>
              </div>
            </section>
          )}

          {/* Aksi lifecycle: mulai (aksi lapangan) & hapus draft (khusus operator). */}
          {((canStart && wo.status === 'ASSIGNED') || (canUpdate && wo.status === 'DRAFT')) && (
            <div className="row wrap" style={{ gap: '0.5rem' }}>
              {canStart && wo.status === 'ASSIGNED' && (
                <button onClick={() => onAct(() => api.post(`/api/work-orders/${id}/start`), 'Pengerjaan dimulai', true)}>Mulai</button>
              )}
              {canUpdate && wo.status === 'DRAFT' && (
                <button
                  className="ghost danger"
                  onClick={() => onAct(() => api.del(`/api/work-orders/${id}`), 'Work order dihapus', false)}
                >
                  Hapus
                </button>
              )}
            </div>
          )}

          {/* Selesaikan — hanya saat sedang dikerjakan (aksi lapangan). */}
          {canComplete && wo.status === 'IN_PROGRESS' && (
            <section className="stack" style={{ gap: '0.4rem' }}>
              <label className="stack" style={{ gap: '0.25rem' }}>
                <span>Catatan penyelesaian (opsional)</span>
                <textarea rows={2} maxLength={2000} value={note} onChange={(e) => setNote(e.target.value)} />
              </label>
              <button
                className="primary"
                onClick={() => onAct(() => api.post(`/api/work-orders/${id}/complete`, { resolutionNote: note.trim() || null }), 'Work order selesai', true)}
              >
                Selesaikan
              </button>
            </section>
          )}

          {/* Persetujuan hasil kerja — hanya untuk WO selesai yang menunggu dikurasi. */}
          {canApprove && awaitingApproval && (
            <section className="stack" style={{ gap: '0.5rem' }}>
              <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Persetujuan hasil kerja</h3>
              <label className="stack" style={{ gap: '0.25rem' }}>
                <span>Catatan (opsional untuk setuju, wajib bila menolak)</span>
                <textarea
                  rows={2}
                  maxLength={500}
                  value={decisionNote}
                  onChange={(e) => setDecisionNote(e.target.value)}
                  placeholder="mis. redaman OK, pemasangan rapi"
                />
              </label>
              <div className="row" style={{ gap: '0.5rem' }}>
                <button
                  className="primary"
                  onClick={() => onAct(() => api.post(`/api/work-orders/${id}/approve`, { note: decisionNote.trim() || null }), 'Hasil kerja disetujui', true)}
                >
                  Setujui
                </button>
                <button
                  className="ghost danger"
                  disabled={!decisionNote.trim()}
                  onClick={() => onAct(() => api.post(`/api/work-orders/${id}/reject`, { reason: decisionNote.trim() }), 'Hasil kerja ditolak, WO dibuka kembali', true)}
                  title={decisionNote.trim() ? undefined : 'Isi alasan penolakan dulu'}
                >
                  Tolak &amp; buka kembali
                </button>
              </div>
            </section>
          )}

          {/* Pembatalan — selagi belum selesai/batal. */}
          {canClose && !terminal && (
            <section className="stack" style={{ gap: '0.4rem' }}>
              <label className="stack" style={{ gap: '0.25rem' }}>
                <span>Batalkan work order</span>
                <input placeholder="Alasan (opsional)" value={reason} onChange={(e) => setReason(e.target.value)} />
              </label>
              <button
                className="ghost danger"
                onClick={() => onAct(() => api.post(`/api/work-orders/${id}/cancel`, { reason: reason.trim() || null }), 'Work order dibatalkan', true)}
              >
                Batalkan
              </button>
            </section>
          )}
        </div>
      )}

      {tab === 'bukti' && (
        <div className="stack" style={{ gap: '1.1rem' }}>
          {/* Redaman optik — bukti kualitas; disembunyikan hanya bila belum ada & tak boleh mengubah. */}
          {showOptical && <OpticalSection wo={wo} canEdit={canRecordOptical} onAct={onAct} />}
          {showEvidence && <EvidenceSection workOrderId={id} status={wo.status} />}
          {!showOptical && !showEvidence && (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Tak ada bukti yang bisa ditampilkan.</p>
          )}
        </div>
      )}

      {tab === 'riwayat' && (
        <section className="stack" style={{ gap: '0.5rem' }}>
          <ol className="timeline">
            {detail.timeline.map((ev, i) => (
              <li key={i}>
                <span className="tl-dot" aria-hidden="true" />
                <div className="stack" style={{ gap: '0.15rem' }}>
                  <strong style={{ fontSize: '0.85rem' }}>{EVENT_LABEL[ev.type] ?? ev.type}</strong>
                  <span className="muted" style={{ fontSize: '0.82rem' }}>{ev.message}</span>
                  <span className="muted" style={{ fontSize: '0.75rem' }}>{fmt(ev.at)}</span>
                </div>
              </li>
            ))}
          </ol>
        </section>
      )}
    </div>
  )
}

/** Satu angka redaman + indikator sehat/waspada/lemah. */
function RxStat({ label, value }: { label: string; value: number | null }) {
  const health = value != null ? rxHealth(value) : null
  return (
    <div className="stack" style={{ gap: '0.15rem' }}>
      <span className="muted" style={{ fontSize: '0.78rem' }}>{label}</span>
      <span className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
        <strong>{fmtDbm(value)}</strong>
        {health && <Badge tone={health.tone}>{health.label}</Badge>}
      </span>
    </div>
  )
}

/**
 * Redaman optik (Rx, dBm) sebelum & sesudah pengerjaan sebagai bukti kualitas.
 * Selisihnya menunjukkan perbaikan/penurunan sinyal; posisi terhadap ambang sehat
 * memberi indikasi cepat. Nilai GPON selalu negatif (rentang wajar −40..0 dBm).
 * Bisa direkam bertahap (before saat datang, after setelah selesai) selama WO belum batal.
 */
function OpticalSection({ wo, canEdit, onAct }: { wo: WorkOrderView; canEdit: boolean; onAct: ActFn }) {
  const [editing, setEditing] = useState(false)
  const [before, setBefore] = useState(wo.rxBeforeDbm?.toString() ?? '')
  const [after, setAfter] = useState(wo.rxAfterDbm?.toString() ?? '')

  const hasReading = wo.rxBeforeDbm != null || wo.rxAfterDbm != null
  const delta = wo.rxBeforeDbm != null && wo.rxAfterDbm != null ? wo.rxAfterDbm - wo.rxBeforeDbm : null

  const parse = (s: string): number | null => {
    const t = s.trim()
    if (!t) return null
    const n = Number(t)
    return Number.isFinite(n) ? n : null
  }

  const save = () => {
    onAct(
      () => api.put(`/api/work-orders/${wo.id}/optical`, { rxBeforeDbm: parse(before), rxAfterDbm: parse(after) }),
      'Pengukuran redaman optik disimpan',
      true,
    )
    setEditing(false)
  }

  const cancelEdit = () => {
    setBefore(wo.rxBeforeDbm?.toString() ?? '')
    setAfter(wo.rxAfterDbm?.toString() ?? '')
    setEditing(false)
  }

  return (
    <section className="stack" style={{ gap: '0.5rem' }}>
      <div className="spread" style={{ alignItems: 'center' }}>
        <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Redaman optik</h3>
        {canEdit && !editing && (
          <button className="ghost" style={{ fontSize: '0.78rem', padding: '0.2rem 0.5rem' }} onClick={() => setEditing(true)}>
            {hasReading ? 'Ubah' : 'Catat'}
          </button>
        )}
      </div>

      {editing ? (
        <div className="stack" style={{ gap: '0.5rem' }}>
          <div className="row wrap" style={{ gap: '0.6rem' }}>
            <label style={{ flex: 1, minWidth: 130 }}>
              <span>Rx sebelum (dBm)</span>
              <input type="number" step="0.01" min={-40} max={0} value={before} onChange={(e) => setBefore(e.target.value)} placeholder="mis. -24.5" />
            </label>
            <label style={{ flex: 1, minWidth: 130 }}>
              <span>Rx sesudah (dBm)</span>
              <input type="number" step="0.01" min={-40} max={0} value={after} onChange={(e) => setAfter(e.target.value)} placeholder="mis. -20.1" />
            </label>
          </div>
          <div className="row" style={{ gap: '0.5rem' }}>
            <button className="primary" onClick={save}>Simpan</button>
            <button onClick={cancelEdit}>Batal</button>
          </div>
          <p className="muted" style={{ margin: 0, fontSize: '0.75rem' }}>GPON selalu negatif; rentang wajar −40..0 dBm. Kosongkan bila belum diukur.</p>
        </div>
      ) : hasReading ? (
        <div className="row wrap" style={{ gap: '1.2rem', alignItems: 'flex-start' }}>
          <RxStat label="Sebelum" value={wo.rxBeforeDbm} />
          <RxStat label="Sesudah" value={wo.rxAfterDbm} />
          {delta != null && (
            <div className="stack" style={{ gap: '0.15rem' }}>
              <span className="muted" style={{ fontSize: '0.78rem' }}>Selisih</span>
              <Badge tone={delta >= 0 ? 'good' : 'warning'}>
                {delta >= 0 ? '▲ membaik' : '▼ menurun'} {Math.abs(delta).toFixed(2)} dB
              </Badge>
            </div>
          )}
        </div>
      ) : (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada pengukuran.</p>
      )}
    </section>
  )
}

/**
 * Gambar berkonten terautentikasi. `<img src>` biasa tak bisa mengirim header
 * Bearer, jadi byte-nya diambil sebagai blob lalu dijadikan object URL; URL-nya
 * dicabut saat unmount / ganti sumber agar tak bocor memori.
 */
function AuthedImage({ path, alt, size }: { path: string; alt: string; size: number }) {
  const [url, setUrl] = useState<string | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let active = true
    let objectUrl: string | null = null
    setUrl(null)
    setFailed(false)
    api
      .blob(path)
      .then((b) => {
        if (!active) return
        objectUrl = URL.createObjectURL(b)
        setUrl(objectUrl)
      })
      .catch(() => active && setFailed(true))
    return () => {
      active = false
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [path])

  const box: CSSProperties = {
    width: size,
    height: size,
    borderRadius: 8,
    objectFit: 'cover',
    background: 'var(--surface-2, #1e2530)',
    border: '1px solid var(--border, #2a3340)',
  }
  if (failed) return <div style={{ ...box, display: 'grid', placeItems: 'center', fontSize: '0.7rem' }} className="muted">gagal</div>
  if (!url) return <div style={box} aria-busy="true" />
  return (
    <a href={url} target="_blank" rel="noreferrer" title={alt}>
      <img src={url} alt={alt} style={box} />
    </a>
  )
}

/**
 * Bukti pengerjaan sebuah work order: galeri foto + tanda tangan. Operator meninjau
 * (dan bila perlu mengkurasi) bukti; teknisi mengunggahnya dari lapangan. Unggah/hapus
 * untuk pemegang `workorder.evidence.manage` (operator) ATAU `workorder.order.field`
 * (teknisi), dan selama work order sudah dikerjakan (bukan draft/batal — server juga
 * menegakkan ini).
 */
function EvidenceSection({ workOrderId, status }: { workOrderId: string; status: WorkOrderStatus }) {
  const { can } = useCan()
  const toast = useToast()
  const canManage = can('workorder.evidence.manage') || can('workorder.order.field')
  const documentable = status !== 'DRAFT' && status !== 'CANCELLED'

  const [photos, setPhotos] = useState<EvidenceView[]>([])
  const [signature, setSignature] = useState<SignatureView | null>(null)
  const [loading, setLoading] = useState(true)
  const [kind, setKind] = useState<EvidenceKind>('AFTER')
  const [caption, setCaption] = useState('')
  const [busy, setBusy] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)

  const reload = useCallback(async () => {
    try {
      const [ph, sg] = await Promise.all([
        api.get<EvidenceView[]>(`/api/work-orders/${workOrderId}/evidence`),
        // 204 (belum ada tanda tangan) → api.get mengembalikan undefined.
        api.get<SignatureView | undefined>(`/api/work-orders/${workOrderId}/signature`),
      ])
      setPhotos(ph)
      setSignature(sg ?? null)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat bukti pengerjaan')
    } finally {
      setLoading(false)
    }
  }, [workOrderId, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const upload = async () => {
    const file = fileRef.current?.files?.[0]
    if (!file) {
      toast.error('Pilih berkas foto dulu')
      return
    }
    const form = new FormData()
    form.set('file', file)
    form.set('kind', kind)
    if (caption.trim()) form.set('caption', caption.trim())
    setBusy(true)
    try {
      await api.postForm(`/api/work-orders/${workOrderId}/evidence`, form)
      setCaption('')
      if (fileRef.current) fileRef.current.value = ''
      await reload()
      toast.success('Foto bukti diunggah')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mengunggah foto')
    } finally {
      setBusy(false)
    }
  }

  const removePhoto = async (evidenceId: string) => {
    try {
      await api.del(`/api/work-orders/${workOrderId}/evidence/${evidenceId}`)
      await reload()
      toast.success('Foto dihapus')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus foto')
    }
  }

  const removeSignature = async () => {
    try {
      await api.del(`/api/work-orders/${workOrderId}/signature`)
      setSignature(null)
      toast.success('Tanda tangan dihapus')
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal menghapus tanda tangan')
    }
  }

  return (
    <section className="stack" style={{ gap: '0.6rem' }}>
      <h3 style={{ margin: 0, fontSize: '0.95rem' }}>Bukti pengerjaan</h3>

      {loading ? (
        <SkeletonRows rows={1} />
      ) : (
        <>
          {photos.length === 0 && !signature ? (
            <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada bukti diunggah.</p>
          ) : (
            <div className="stack" style={{ gap: '0.6rem' }}>
              {photos.length > 0 && (
                <div className="row wrap" style={{ gap: '0.6rem' }}>
                  {photos.map((ph) => (
                    <div key={ph.id} className="stack" style={{ gap: '0.25rem', width: 96 }}>
                      <AuthedImage path={`/api/work-orders/${workOrderId}/evidence/${ph.id}/content`} alt={ph.caption ?? KIND_LABEL[ph.kind]} size={96} />
                      <span className="badge" style={{ fontSize: '0.7rem' }}>{KIND_LABEL[ph.kind]}</span>
                      {ph.caption && <span className="muted" style={{ fontSize: '0.72rem' }}>{ph.caption}</span>}
                      {ph.uploadedByName && <span className="muted" style={{ fontSize: '0.68rem' }}>oleh {ph.uploadedByName}</span>}
                      {canManage && (
                        <button className="ghost danger" style={{ fontSize: '0.72rem', padding: '0.15rem 0.4rem' }} onClick={() => void removePhoto(ph.id)}>
                          Hapus
                        </button>
                      )}
                    </div>
                  ))}
                </div>
              )}

              {signature && (
                <div className="stack" style={{ gap: '0.25rem', alignItems: 'flex-start' }}>
                  <span className="muted" style={{ fontSize: '0.82rem' }}>Tanda tangan · {signature.signerName}</span>
                  <AuthedImage path={`/api/work-orders/${workOrderId}/signature/content`} alt={`Tanda tangan ${signature.signerName}`} size={140} />
                  <span className="muted" style={{ fontSize: '0.72rem' }}>{fmt(signature.signedAt)}</span>
                  {canManage && (
                    <button className="ghost danger" style={{ fontSize: '0.72rem', padding: '0.15rem 0.4rem' }} onClick={() => void removeSignature()}>
                      Hapus tanda tangan
                    </button>
                  )}
                </div>
              )}
            </div>
          )}

          {canManage && documentable && (
            <div className="row wrap" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
              <label style={{ minWidth: 130 }}>
                <span>Jenis</span>
                <select value={kind} onChange={(e) => setKind(e.target.value as EvidenceKind)}>
                  {KINDS.map((k) => (
                    <option key={k} value={k}>
                      {KIND_LABEL[k]}
                    </option>
                  ))}
                </select>
              </label>
              <label style={{ flex: 1, minWidth: 160 }}>
                <span>Keterangan (opsional)</span>
                <input value={caption} onChange={(e) => setCaption(e.target.value)} placeholder="mis. sambungan core setelah splice" />
              </label>
              <input ref={fileRef} type="file" accept="image/*" style={{ maxWidth: 200 }} />
              <button className="primary" disabled={busy} onClick={() => void upload()}>
                {busy ? 'Mengunggah…' : 'Unggah foto'}
              </button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
