/**
 * Isi halaman detail work order + aksi lifecycle-nya, dipakai bersama papan dispatch
 * operator dan papan "Tugas Saya" teknisi (keduanya lewat `WorkOrderDetailPage`). Semua
 * field dalam satu kolom yang bisa di-scroll—bukan tab—karena isiannya banyak. Tombol
 * yang muncul mengikuti status WO dan izin: dispatcher lewat `workorder.order.update`/
 * `close`, teknisi lapangan lewat `workorder.order.field` — persis meniru
 * `@authz.canAny(...)` di controller server.
 */
import { useCallback, useEffect, useRef, useState, type CSSProperties } from 'react'
import { Text } from '@fluentui/react-components'
import { api, ApiError } from '@/api/client'
import type { User } from '@/api/types'
import type {
  EvidenceKind,
  EvidenceView,
  SignatureView,
  WorkOrderDetail,
  WorkOrderStatus,
  WorkOrderView,
} from '@/api/workorder'
import { useCan } from '@/auth/useCan'
import { MultiCombobox } from '@/components/molecules'
import { Badge, Button, SelectField, SkeletonRows, TextField, TextareaField } from '@/components/atoms'
import { useToast } from '@/system'
import {
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
} from '@/utils/woLabels'
import { AssigneeChips, WoField } from './views'
import { WorkOrderFiberWork } from './WorkOrderFiberWork'

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
      {/* Satu kolom scroll (bukan tab): ringkasan, aksi lifecycle, redaman, bukti, lalu
          riwayat — teknisi mengisi banyak field tanpa berpindah tab. Badge status &
          persetujuan tampil di header halaman detail. */}
      <div className="card stack" style={{ gap: '1.1rem' }}>
        {wo.description && <p className="wo-desc">{wo.description}</p>}

        <dl className="wo-grid">
          <WoField label="Tipe">{TYPE_LABEL[wo.type]}</WoField>
          <WoField label="Pelanggan">{wo.customerName ?? <span className="muted">—</span>}</WoField>
          <WoField label="Prioritas">{PRIORITY_LABEL[wo.priority]}</WoField>
          <WoField label="Teknisi"><AssigneeChips wo={wo} /></WoField>
          <WoField label="Jadwal">{wo.scheduledAt ? fmt(wo.scheduledAt) : <span className="muted">—</span>}</WoField>
          {wo.destinationLat != null && wo.destinationLng != null && (
            <WoField label="Lokasi">
              <a
                href={`https://www.google.com/maps/search/?api=1&query=${wo.destinationLat},${wo.destinationLng}`}
                target="_blank"
                rel="noreferrer"
              >
                Navigasi ke pelanggan ↗
              </a>
            </WoField>
          )}
          <WoField label="Dibuat">{fmt(wo.createdAt)}</WoField>
          {wo.completedAt && <WoField label="Selesai">{fmt(wo.completedAt)}</WoField>}
          {wo.resolutionNote && <WoField label="Catatan">{wo.resolutionNote}</WoField>}
          {wo.approvedByName && (
            <WoField label={wo.approvalStatus === 'REJECTED' ? 'Ditolak oleh' : 'Disetujui oleh'}>
              {wo.approvedByName}
              {wo.approvedAt ? ` · ${fmt(wo.approvedAt)}` : ''}
            </WoField>
          )}
          {wo.approvalNote && (
            <WoField label={wo.approvalStatus === 'REJECTED' ? 'Alasan penolakan' : 'Catatan persetujuan'}>
              {wo.approvalNote}
            </WoField>
          )}
          {wo.cancelReason && <WoField label="Alasan batal">{wo.cancelReason}</WoField>}
        </dl>

        {/* Penugasan — selagi work order belum selesai/batal. */}
        {canAssign && !terminal && (
          <section className="stack" style={{ gap: '0.4rem' }}>
            <Text as="h3" size={300} weight="semibold" style={{ margin: 0 }}>Penugasan</Text>
            <div className="row" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
              <div className="stack" style={{ flex: 1, gap: '0.25rem' }}>
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
              </div>
              <Button
                variant="primary"
                disabled={assignees.length === 0 || sameRoster(assignees, wo.assignees)}
                onClick={() => onAct(() => api.post(`/api/work-orders/${id}/assign`, { technicianIds: assignees }), 'Teknisi ditugaskan', true)}
              >
                {wo.assignees.length > 0 ? 'Tugaskan ulang' : 'Tugaskan'}
              </Button>
            </div>
          </section>
        )}

        {/* Aksi lifecycle: mulai (aksi lapangan) & hapus draft (khusus operator). */}
        {((canStart && wo.status === 'ASSIGNED') || (canUpdate && wo.status === 'DRAFT')) && (
          <div className="row wrap" style={{ gap: '0.5rem' }}>
            {canStart && wo.status === 'ASSIGNED' && (
              <Button onClick={() => onAct(() => api.post(`/api/work-orders/${id}/start`), 'Pengerjaan dimulai', true)}>Mulai</Button>
            )}
            {canUpdate && wo.status === 'DRAFT' && (
              <Button
                variant="danger"
                onClick={() => onAct(() => api.del(`/api/work-orders/${id}`), 'Work order dihapus', false)}
              >
                Hapus
              </Button>
            )}
          </div>
        )}

        {/* Selesaikan — hanya saat sedang dikerjakan (aksi lapangan). */}
        {canComplete && wo.status === 'IN_PROGRESS' && (
          <section className="stack" style={{ gap: '0.4rem' }}>
            <TextareaField
              label="Catatan penyelesaian (opsional)"
              rows={2}
              maxLength={2000}
              value={note}
              onChange={(_, data) => setNote(data.value)}
            />
            <Button
              variant="primary"
              onClick={() => onAct(() => api.post(`/api/work-orders/${id}/complete`, { resolutionNote: note.trim() || null }), 'Work order selesai', true)}
            >
              Selesaikan
            </Button>
          </section>
        )}

        {/* Persetujuan hasil kerja — hanya untuk WO selesai yang menunggu dikurasi. */}
        {canApprove && awaitingApproval && (
          <section className="stack" style={{ gap: '0.5rem' }}>
            <Text as="h3" size={300} weight="semibold" style={{ margin: 0 }}>Persetujuan hasil kerja</Text>
            <TextareaField
              label="Catatan (opsional untuk setuju, wajib bila menolak)"
              rows={2}
              maxLength={500}
              value={decisionNote}
              onChange={(_, data) => setDecisionNote(data.value)}
              placeholder="mis. redaman OK, pemasangan rapi"
            />
            <div className="row" style={{ gap: '0.5rem' }}>
              <Button
                variant="primary"
                onClick={() => onAct(() => api.post(`/api/work-orders/${id}/approve`, { note: decisionNote.trim() || null }), 'Hasil kerja disetujui', true)}
              >
                Setujui
              </Button>
              <Button
                variant="danger"
                disabled={!decisionNote.trim()}
                onClick={() => onAct(() => api.post(`/api/work-orders/${id}/reject`, { reason: decisionNote.trim() }), 'Hasil kerja ditolak, WO dibuka kembali', true)}
                title={decisionNote.trim() ? undefined : 'Isi alasan penolakan dulu'}
              >
                Tolak &amp; buka kembali
              </Button>
            </div>
          </section>
        )}

        {/* Pembatalan — selagi belum selesai/batal. */}
        {canClose && !terminal && (
          <section className="stack" style={{ gap: '0.4rem' }}>
            <TextField
              label="Batalkan work order"
              placeholder="Alasan (opsional)"
              value={reason}
              onChange={(_, data) => setReason(data.value)}
            />
            <Button
              variant="danger"
              onClick={() => onAct(() => api.post(`/api/work-orders/${id}/cancel`, { reason: reason.trim() || null }), 'Work order dibatalkan', true)}
            >
              Batalkan
            </Button>
          </section>
        )}
      </div>

      {(showOptical || showEvidence) && (
        <div className="card stack" style={{ gap: '1.1rem' }}>
          {/* Redaman optik (bukti kualitas) + foto & tanda tangan pengerjaan. */}
          {showOptical && <OpticalSection wo={wo} canEdit={canRecordOptical} onAct={onAct} />}
          {showEvidence && <EvidenceSection workOrderId={id} status={wo.status} />}
        </div>
      )}

      {/* Kerja serat yang dibukukan ke tiket ini — kartunya menampilkan diri sendiri
          hanya bila ada isinya (lihat komponennya). */}
      <WorkOrderFiberWork workOrderId={id} />

      <div className="card stack" style={{ gap: '0.5rem' }}>
        <Text as="h3" size={300} weight="semibold" style={{ margin: 0 }}>Riwayat</Text>
        <ol className="timeline">
          {detail.timeline.map((ev, i) => (
            <li key={i}>
              <span className="tl-dot" aria-hidden="true" />
              <div className="stack" style={{ gap: '0.15rem' }}>
                <Text as="strong" size={200} weight="semibold">{EVENT_LABEL[ev.type] ?? ev.type}</Text>
                <Text as="span" className="muted" size={200}>{ev.message}</Text>
                <Text as="span" className="muted" size={100}>{fmt(ev.at)}</Text>
              </div>
            </li>
          ))}
        </ol>
      </div>
    </div>
  )
}

/** Satu angka redaman + indikator sehat/waspada/lemah. */
function RxStat({ label, value }: { label: string; value: number | null }) {
  const health = value != null ? rxHealth(value) : null
  return (
    <div className="stack" style={{ gap: '0.15rem' }}>
      <Text as="span" className="muted" size={100}>{label}</Text>
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
        <Text as="h3" size={300} weight="semibold" style={{ margin: 0 }}>Redaman optik</Text>
        {canEdit && !editing && (
          <Button variant="subtle" size="small" onClick={() => setEditing(true)}>
            {hasReading ? 'Ubah' : 'Catat'}
          </Button>
        )}
      </div>

      {editing ? (
        <div className="stack" style={{ gap: '0.5rem' }}>
          <div className="row wrap" style={{ gap: '0.6rem' }}>
            <TextField
              label="Rx sebelum (dBm)"
              type="number"
              step="0.01"
              min={-40}
              max={0}
              value={before}
              onChange={(_, data) => setBefore(data.value)}
              placeholder="mis. -24.5"
              // Lebar minimum 160: di layar ponsel dua kolom tak lagi muat berdampingan
              // sehingga keduanya menumpuk selebar layar — angka minus berkoma terbaca
              // utuh, dan salah ketik saat berdiri di tiang jadi lebih kecil peluangnya.
              style={{ flex: 1, minWidth: 160 }}
            />
            <TextField
              label="Rx sesudah (dBm)"
              type="number"
              step="0.01"
              min={-40}
              max={0}
              value={after}
              onChange={(_, data) => setAfter(data.value)}
              placeholder="mis. -20.1"
              style={{ flex: 1, minWidth: 160 }}
            />
          </div>
          <div className="row" style={{ gap: '0.5rem' }}>
            <Button variant="primary" onClick={save}>Simpan</Button>
            <Button onClick={cancelEdit}>Batal</Button>
          </div>
          <Text as="p" className="muted" size={100} style={{ margin: 0 }}>GPON selalu negatif; rentang wajar −40..0 dBm. Kosongkan bila belum diukur.</Text>
        </div>
      ) : hasReading ? (
        <div className="row wrap" style={{ gap: '1.2rem', alignItems: 'flex-start' }}>
          <RxStat label="Sebelum" value={wo.rxBeforeDbm} />
          <RxStat label="Sesudah" value={wo.rxAfterDbm} />
          {delta != null && (
            <div className="stack" style={{ gap: '0.15rem' }}>
              <Text as="span" className="muted" size={100}>Selisih</Text>
              <Badge tone={delta >= 0 ? 'good' : 'warning'}>
                {delta >= 0 ? '▲ membaik' : '▼ menurun'} {Math.abs(delta).toFixed(2)} dB
              </Badge>
            </div>
          )}
        </div>
      ) : (
          <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Belum ada pengukuran.</Text>
      )}
    </section>
  )
}

/**
 * Gambar berkonten terautentikasi. `<img src>` biasa tak bisa mengirim header
 * Bearer, jadi byte-nya diambil sebagai blob lalu dijadikan object URL; URL-nya
 * dicabut saat unmount / ganti sumber agar tak bocor memori.
 *
 * `size` = sisi ubin dalam piksel; `'fill'` menyerahkan lebarnya pada induk (jalur
 * grid galeri) dan menjaga bentuk bujur sangkar — dipakai agar ubin foto ikut
 * membesar di ponsel tanpa perlu tahu lebar layar dari JavaScript.
 */
function AuthedImage({ path, alt, size }: { path: string; alt: string; size: number | 'fill' }) {
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

  const fill = size === 'fill'
  const box: CSSProperties = {
    width: fill ? '100%' : size,
    height: fill ? undefined : size,
    aspectRatio: fill ? '1 / 1' : undefined,
    borderRadius: 8,
    objectFit: 'cover',
    background: 'var(--surface-2, #1e2530)',
    border: '1px solid var(--border, #2a3340)',
  }
  if (failed) return <div style={{ ...box, display: 'grid', placeItems: 'center' }} className="muted">gagal</div>
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
      <Text as="h3" size={300} weight="semibold" style={{ margin: 0 }}>Bukti pengerjaan</Text>

      {loading ? (
        <SkeletonRows rows={1} />
      ) : (
        <>
          {photos.length === 0 && !signature ? (
            <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Belum ada bukti diunggah.</Text>
          ) : (
            <div className="stack" style={{ gap: '0.6rem' }}>
              {photos.length > 0 && (
                <div className="evidence-grid">
                  {photos.map((ph) => (
                    <div key={ph.id} className="stack" style={{ gap: '0.25rem', minWidth: 0 }}>
                      <AuthedImage path={`/api/work-orders/${workOrderId}/evidence/${ph.id}/content`} alt={ph.caption ?? KIND_LABEL[ph.kind]} size="fill" />
                      <Text as="span" className="badge" size={100}>{KIND_LABEL[ph.kind]}</Text>
                      {ph.caption && <Text as="span" className="muted" size={100}>{ph.caption}</Text>}
                      {ph.uploadedByName && <Text as="span" className="muted" size={100}>oleh {ph.uploadedByName}</Text>}
                      {canManage && (
                        <Button variant="danger" size="small" onClick={() => void removePhoto(ph.id)}>
                          Hapus
                        </Button>
                      )}
                    </div>
                  ))}
                </div>
              )}

              {signature && (
                <div className="stack" style={{ gap: '0.25rem', alignItems: 'flex-start' }}>
                  <Text as="span" className="muted" size={200}>Tanda tangan · {signature.signerName}</Text>
                  <AuthedImage path={`/api/work-orders/${workOrderId}/signature/content`} alt={`Tanda tangan ${signature.signerName}`} size={140} />
                  <Text as="span" className="muted" size={100}>{fmt(signature.signedAt)}</Text>
                  {canManage && (
                    <Button variant="danger" size="small" onClick={() => void removeSignature()}>
                      Hapus tanda tangan
                    </Button>
                  )}
                </div>
              )}
            </div>
          )}

          {canManage && documentable && (
            /* `wo-upload` = kail responsif: di ponsel tiap kontrol turun selebar layar
               (lihat index.css) karena di sinilah teknisi bekerja dari lapangan. */
            <div className="row wrap wo-upload" style={{ gap: '0.5rem', alignItems: 'flex-end' }}>
              <SelectField
                label="Jenis"
                value={kind}
                onChange={(_, data) => setKind(data.value as EvidenceKind)}
                style={{ minWidth: 130 }}
              >
                {KINDS.map((k) => (
                  <option key={k} value={k}>
                    {KIND_LABEL[k]}
                  </option>
                ))}
              </SelectField>
              <TextField
                label="Keterangan (opsional)"
                value={caption}
                onChange={(_, data) => setCaption(data.value)}
                placeholder="mis. sambungan core setelah splice"
                style={{ flex: 1, minWidth: 160 }}
              />
              {/* Input file dibiarkan native: butuh ref untuk baca `.files` & reset `.value`;
                  Fluent Input tak mendukung type=file (ref-nya tak menunjuk ke elemen input).
                  `capture` sengaja TAK dipasang — teknisi kerap memotret dulu lalu mengunggah
                  belakangan, dan `capture` mengunci pilihan hanya ke kamera saat itu juga. */}
              <input ref={fileRef} className="wo-file" type="file" accept="image/*" />
              <Button variant="primary" disabled={busy} onClick={() => void upload()}>
                {busy ? 'Mengunggah…' : 'Unggah foto'}
              </Button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
