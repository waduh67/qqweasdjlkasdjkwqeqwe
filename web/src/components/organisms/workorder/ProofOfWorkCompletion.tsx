import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { api, ApiError } from '@/api/client'
import type { EvidenceView, SignatureView, WorkOrderType } from '@/api/workorder'
import { Button, SelectField, SkeletonRows } from '@/components/atoms'
import { useToast } from '@/system'
import type { ActFn } from '@/utils/woLabels'

type ProofArtifactKind =
  | 'FAT'
  | 'ODP'
  | 'DROPCORE'
  | 'ONT'
  | 'ONU'
  | 'OPTICAL_BEFORE'
  | 'OPTICAL_AFTER'
  | 'TECHNICIAN_SIGNATURE'
  | 'CUSTOMER_ACKNOWLEDGEMENT'
  | 'LOCATION'

type ProofSelection = Record<ProofArtifactKind, string>

const REQUIRED: Record<WorkOrderType, readonly ProofArtifactKind[]> = {
  PSB: ['FAT', 'ODP', 'DROPCORE', 'ONT', 'ONU', 'OPTICAL_BEFORE', 'OPTICAL_AFTER', 'TECHNICIAN_SIGNATURE', 'CUSTOMER_ACKNOWLEDGEMENT', 'LOCATION'],
  REPAIR: ['FAT', 'DROPCORE', 'OPTICAL_BEFORE', 'OPTICAL_AFTER', 'TECHNICIAN_SIGNATURE', 'CUSTOMER_ACKNOWLEDGEMENT', 'LOCATION'],
  MIGRATION: ['FAT', 'ODP', 'DROPCORE', 'ONT', 'ONU', 'OPTICAL_BEFORE', 'OPTICAL_AFTER', 'TECHNICIAN_SIGNATURE', 'CUSTOMER_ACKNOWLEDGEMENT', 'LOCATION'],
  DISMANTLE: ['FAT', 'ONT', 'ONU', 'TECHNICIAN_SIGNATURE', 'CUSTOMER_ACKNOWLEDGEMENT', 'LOCATION'],
  PREVENTIVE: ['FAT', 'OPTICAL_BEFORE', 'OPTICAL_AFTER', 'TECHNICIAN_SIGNATURE', 'LOCATION'],
}

const LABEL: Record<ProofArtifactKind, string> = {
  FAT: 'FAT',
  ODP: 'ODP',
  DROPCORE: 'Dropcore',
  ONT: 'ONT',
  ONU: 'ONU',
  OPTICAL_BEFORE: 'Optik sebelum',
  OPTICAL_AFTER: 'Optik sesudah',
  TECHNICIAN_SIGNATURE: 'Tanda tangan teknisi',
  CUSTOMER_ACKNOWLEDGEMENT: 'Persetujuan pelanggan',
  LOCATION: 'Lokasi',
}

type EvidenceOption = { readonly id: string; readonly label: string }

export function ProofOfWorkCompletion({ workOrderId, type, note, onAct }: { workOrderId: string; type: WorkOrderType; note: string; onAct: ActFn }) {
  const toast = useToast()
  const [photos, setPhotos] = useState<readonly EvidenceView[] | null>(null)
  const [signature, setSignature] = useState<SignatureView | null>(null)
  const [selection, setSelection] = useState<Partial<ProofSelection>>({})

  const load = useCallback(async () => {
    try {
      const [evidence, signed] = await Promise.all([
        api.get<EvidenceView[]>(`/api/work-orders/${workOrderId}/evidence`),
        api.get<SignatureView | undefined>(`/api/work-orders/${workOrderId}/signature`),
      ])
      setPhotos(evidence)
      setSignature(signed ?? null)
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Gagal memuat bukti Proof of Work')
    }
  }, [toast, workOrderId])

  useEffect(() => {
    void load()
  }, [load])

  const options = useMemo<readonly EvidenceOption[]>(() => [
    ...(photos ?? []).map((photo) => ({ id: photo.id, label: `${photo.kind} · ${photo.caption ?? 'Bukti foto'}` })),
    ...(signature ? [{ id: signature.id, label: `Tanda tangan · ${signature.signerName}` }] : []),
  ], [photos, signature])
  const required = REQUIRED[type]
  const complete = required.every((kind) => selection[kind])

  if (photos === null) return <SkeletonRows rows={3} />

  return (
    <section className="stack" style={{ gap: '0.6rem' }}>
      <div>
        <Text as="h3" size={300} weight="semibold">Proof of Work</Text>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Hubungkan setiap bukti wajib dengan revisi bukti yang sudah diunggah.</Text>
      </div>
      {options.length === 0 ? (
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Unggah bukti dan tanda tangan sebelum menyelesaikan pekerjaan.</Text>
      ) : (
        <div className="stack" style={{ gap: '0.5rem' }}>
          {required.map((kind) => (
            <SelectField
              key={kind}
              label={LABEL[kind]}
              value={selection[kind] ?? ''}
              onChange={(_, data) => setSelection((current) => ({ ...current, [kind]: data.value }))}
            >
              <option value="">Pilih bukti</option>
              {options.map((option) => <option key={`${kind}:${option.id}`} value={option.id}>{option.label}</option>)}
            </SelectField>
          ))}
        </div>
      )}
      <Button
        variant="primary"
        disabled={!complete}
        onClick={() => onAct(
          () => api.post(`/api/work-orders/${workOrderId}/complete`, {
            resolutionNote: note.trim() || null,
            proofRevision: 1,
            artifacts: required.map((kind) => ({ kind, revisionId: selection[kind], revisionState: 'COMMITTED', correctionReason: null })),
          }),
          'Work order dikirim untuk persetujuan',
          true,
        )}
      >
        Kirim untuk persetujuan
      </Button>
    </section>
  )
}
