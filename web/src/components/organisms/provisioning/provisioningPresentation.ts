import type {
  AdapterCertificationView,
  CapabilityEvidenceView,
  ExecutionStatus,
  ManagementProtectionView,
  PlanPreview,
  ProvisioningDeviceKind,
} from '@/api/provisioning'
import type { Tone } from '@/components/atoms'

export const DEVICE_LABEL: Record<ProvisioningDeviceKind, string> = {
  OLT: 'OLT / PON / ONU',
  SWITCH: 'Transit switch',
  ROUTER: 'Transit router',
  BRAS: 'Binding BRAS',
}

export const EXECUTION_LABEL: Record<ExecutionStatus, string> = {
  QUEUED: 'Menunggu antrean',
  RUNNING: 'Sedang diterapkan',
  VERIFYING: 'Sedang diverifikasi',
  SUCCEEDED: 'Berhasil',
  ROLLING_BACK: 'Sedang rollback',
  ROLLED_BACK: 'Rollback selesai',
  FAILED: 'Gagal',
  MANUAL_RECONCILIATION: 'Perlu rekonsiliasi manual',
  CANCELLED: 'Dibatalkan',
}

export function executionTone(status: ExecutionStatus): Tone {
  switch (status) {
    case 'SUCCEEDED': return 'good'
    case 'QUEUED': return 'neutral'
    case 'RUNNING':
    case 'VERIFYING': return 'accent'
    case 'ROLLING_BACK': return 'warning'
    case 'ROLLED_BACK':
    case 'CANCELLED': return 'serious'
    case 'FAILED':
    case 'MANUAL_RECONCILIATION': return 'critical'
  }
}

export type ProductionReadiness = {
  readonly ready: boolean
  readonly capabilityReady: boolean
  readonly protectionReady: boolean
  readonly certificationReady: boolean
  readonly reasons: readonly string[]
}

export function productionReadiness(
  preview: PlanPreview | null,
  capabilities: readonly CapabilityEvidenceView[],
  protections: readonly ManagementProtectionView[],
  certifications: readonly AdapterCertificationView[] | null,
  now: Date = new Date(),
): ProductionReadiness {
  if (!preview) {
    return { ready: false, capabilityReady: false, protectionReady: false, certificationReady: false, reasons: ['Pratinjau tervalidasi belum tersedia.'] }
  }

  const currentCapabilities = (step: PlanPreview['plan']['steps'][number]) => capabilities.filter((capability) =>
    capability.deviceKind === step.device.kind && capability.deviceId === step.device.id &&
    capability.operationClass === step.operation && capability.supported && Date.parse(capability.expiresAt) > now.getTime(),
  )
  const capabilityReady = preview.plan.steps.every((step) => currentCapabilities(step).length > 0)
  const protectionReady = preview.plan.steps.every((step) => protections.some((protection) =>
    protection.deviceKind === step.device.kind && protection.deviceId === step.device.id && protection.complete &&
    Date.parse(protection.validUntil) > now.getTime(),
  ))
  const certificationReady = certifications === null
    ? preview.decision.allowed
    : preview.plan.steps.every((step) => currentCapabilities(step).some((capability) =>
        certifications.some((certification) =>
          certification.deviceKind === capability.deviceKind && certification.deviceId === capability.deviceId &&
          certification.vendor === capability.vendor && certification.model === capability.model &&
          certification.firmware === capability.firmware && certification.transport === capability.transport &&
          certification.operationClass === capability.operationClass && certification.status === 'CERTIFIED' &&
          certification.revokedAt === null && Date.parse(certification.validUntil) > now.getTime(),
        )
      ))

  const reasons = [
    ...(!preview.decision.allowed ? [`Server menolak produksi: ${preview.decision.code}.`] : []),
    ...(!capabilityReady ? ['Satu atau lebih adapter tidak mendukung operasi ini.'] : []),
    ...(!protectionReady ? ['Proteksi manajemen belum lengkap pada seluruh perangkat.'] : []),
    ...(!certificationReady ? ['Sertifikasi adapter eksak belum lengkap atau sudah dicabut.'] : []),
  ]
  return {
    ready: preview.plan.status === 'VALIDATED' && preview.decision.allowed && capabilityReady && protectionReady && certificationReady,
    capabilityReady,
    protectionReady,
    certificationReady,
    reasons,
  }
}

export function stableCodeLabel(code: string): string {
  const labels: Record<string, string> = {
    ALLOWED: 'Semua pemeriksaan server lulus',
    UNCERTIFIED_FINGERPRINT: 'Fingerprint adapter belum tersertifikasi',
    MANAGEMENT_PROTECTION_REQUIRED: 'Proteksi jalur manajemen belum lengkap',
    PROVISIONAL_ADAPTER: 'Adapter masih provisional',
    PROTECTED_MANAGEMENT_RESOURCE: 'Perubahan menyentuh resource manajemen yang dilindungi',
    ROLLBACK_POLICY_DENIED: 'Kebijakan proteksi menolak rollback otomatis',
    STALE_PLAN: 'Revisi plan sudah berubah',
  }
  return labels[code] ?? 'Kode penolakan server'
}
