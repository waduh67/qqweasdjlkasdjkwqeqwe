import { useCallback, useEffect, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { api, ApiError } from '@/api/client'
import type { ProofArtifactKind, ProofOfWorkView, WorkOrderType } from '@/api/workorder'
import { Button, SelectField, SkeletonRows } from '@/components/atoms'
import { useToast } from '@/system'
import type { ActFn } from '@/utils/woLabels'

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

export function ProofOfWorkCompletion({ workOrderId, type, note, onAct }: { workOrderId: string; type: WorkOrderType; note: string; onAct: ActFn }) {
  const toast = useToast()
  const [proof, setProof] = useState<ProofOfWorkView | null>(null)
  const [selection, setSelection] = useState<Partial<ProofSelection>>({})

  const load = useCallback(async () => {
    try {
      setProof(await api.get<ProofOfWorkView>(`/api/work-orders/${workOrderId}/proof-of-work`))
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Gagal memuat bukti Proof of Work')
    }
  }, [toast, workOrderId])

  useEffect(() => {
    void load()
  }, [load])

  const required = REQUIRED[type]
  const complete = proof !== null && required.every((kind) => selection[kind])

  if (proof === null) return <SkeletonRows rows={3} />

  return (
    <section className="stack" style={{ gap: '0.6rem' }}>
      <div>
        <Text as="h3" size={300} weight="semibold">Proof of Work</Text>
        <Text as="p" className="muted" size={200} style={{ margin: 0 }}>Hubungkan setiap bukti wajib dengan revisi bukti yang sudah diunggah.</Text>
      </div>
      {proof.artifacts.length === 0 ? (
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
              {proof.artifacts
                .filter((artifact) => artifact.kind === kind)
                .map((artifact) => {
                  const usedByOtherKind = Object.entries(selection).some(([selectedKind, revisionId]) => selectedKind !== kind && revisionId === artifact.revisionId)
                  return <option disabled={usedByOtherKind} key={`${kind}:${artifact.revisionId}`} value={artifact.revisionId}>{artifact.label}</option>
                })}
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
            proofRevision: proof?.revision,
            artifacts: required.map((kind) => ({ kind, revisionId: selection[kind] })),
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
