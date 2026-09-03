import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  adoptProvisioningDrift,
  cancelProvisioningExecution,
  certifyAdapter,
  getProvisioningTimeline,
  getTopology,
  listAdapterCertifications,
  listManagementProtections,
  listProvisioningCapabilities,
  listProvisioningDrift,
  listSegmentProfiles,
  listServiceIntents,
  listVlanPools,
  revokeAdapterCertification,
  type AdapterCertificationView,
  type CapabilityEvidenceView,
  type DriftView,
  type ExecutionTimelineEntry,
  type ManagementProtectionView,
  type ProvisioningTopology,
  type RevisionedResource,
  type SegmentProfileView,
  type ServiceIntentView,
  type VlanPoolView,
} from '@/api/provisioning'
import { getProvisioningRollout, type ProvisioningRolloutView } from '@/api/provisioningRollout'
import { EmptyState } from '@/components/atoms'
import { PageHeader, Tabs } from '@/components/molecules'
import { ProvisioningExecutionPanel } from '@/components/organisms/provisioning/ProvisioningExecutionPanel'
import { ProvisioningEditorModal, type ProvisioningEditor } from '@/components/organisms/provisioning/ProvisioningEditors'
import { CertificationPanel, DriftPanel } from '@/components/organisms/provisioning/ProvisioningEvidencePanels'
import { ProvisioningPreviewPanel } from '@/components/organisms/provisioning/ProvisioningPreviewPanel'
import { IntentsPanel, ProfilesPanel, TopologyPanel } from '@/components/organisms/provisioning/ProvisioningResourcePanels'
import { productionReadiness } from '@/components/organisms/provisioning/provisioningPresentation'
import { useProvisioningApply, useProvisioningDraft } from '@/hooks/useProvisioning'
import { useProvisioningExecution } from '@/hooks/useProvisioningExecution'
import { useProvisioningPermissions } from '@/hooks/useProvisioningPermissions'
import { useToast } from '@/system'

type WorkspaceTab = 'topology' | 'profiles' | 'intents' | 'executions' | 'drift' | 'certification'

const EMPTY_TOPOLOGY: ProvisioningTopology = { nodes: [], interfaces: [], links: [] }
const SAFE_ROLLOUT: ProvisioningRolloutView = {
  plannerEnabled: true,
  uiEnabled: true,
  autoApplyEnabled: false,
  maxAffectedSubscribers: 1,
  bulkExpansionEnabled: false,
}
const WORKSPACE_TABS = [
  { key: 'topology', label: 'Topologi' }, { key: 'profiles', label: 'Profil' },
  { key: 'intents', label: 'Intent' }, { key: 'executions', label: 'Eksekusi' },
  { key: 'drift', label: 'Drift' }, { key: 'certification', label: 'Sertifikasi' },
] satisfies readonly { readonly key: WorkspaceTab; readonly label: string }[]

export function NetworkProvisioningPage() {
  const permissions = useProvisioningPermissions()
  const toast = useToast()
  const [tab, setTab] = useState<WorkspaceTab>('topology')
  const [loading, setLoading] = useState(true)
  const [topology, setTopology] = useState(EMPTY_TOPOLOGY)
  const [pools, setPools] = useState<readonly RevisionedResource<VlanPoolView>[]>([])
  const [profiles, setProfiles] = useState<readonly RevisionedResource<SegmentProfileView>[]>([])
  const [intents, setIntents] = useState<readonly RevisionedResource<ServiceIntentView>[]>([])
  const [capabilities, setCapabilities] = useState<readonly CapabilityEvidenceView[]>([])
  const [protections, setProtections] = useState<readonly ManagementProtectionView[]>([])
  const [drift, setDrift] = useState<readonly DriftView[]>([])
  const [certifications, setCertifications] = useState<readonly AdapterCertificationView[]>([])
  const [timeline, setTimeline] = useState<readonly ExecutionTimelineEntry[]>([])
  const [rollout, setRollout] = useState<ProvisioningRolloutView>(SAFE_ROLLOUT)
  const [adoptingId, setAdoptingId] = useState<string | null>(null)
  const [cancelling, setCancelling] = useState(false)
  const [editor, setEditor] = useState<ProvisioningEditor>(null)
  const draft = useProvisioningDraft({ planId: '' })
  const apply = useProvisioningApply()
  const executionId = apply.execution?.id ?? null
  const polled = useProvisioningExecution(executionId)
  const execution = polled.execution ?? apply.execution

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [nextTopology, nextPools, nextProfiles, nextIntents, nextCapabilities, nextProtections, nextDrift, nextRollout] = await Promise.all([
        getTopology(), listVlanPools(), listSegmentProfiles(), listServiceIntents(),
        permissions.plan ? listProvisioningCapabilities() : Promise.resolve([]),
        listManagementProtections(), permissions.drift ? listProvisioningDrift() : Promise.resolve([]),
        permissions.plan ? getProvisioningRollout() : Promise.resolve(SAFE_ROLLOUT),
      ])
      setTopology(nextTopology); setPools(nextPools); setProfiles(nextProfiles); setIntents(nextIntents)
      setCapabilities(nextCapabilities); setProtections(nextProtections); setDrift(nextDrift)
      setRollout(nextRollout)
    } catch (cause) {
      toast.error(messageOf(cause, 'Gagal memuat workspace provisioning'))
    } finally {
      setLoading(false)
    }
  }, [permissions.drift, permissions.plan, toast])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    if (!draft.preview || !permissions.certification) { setCertifications([]); return }
    void listAdapterCertifications(draft.preview.plan.tenantId)
      .then(setCertifications)
      .catch((cause) => toast.error(messageOf(cause, 'Gagal memuat sertifikasi adapter')))
  }, [draft.preview, permissions.certification, toast])
  useEffect(() => {
    if (!executionId) return
    const controller = new AbortController()
    void getProvisioningTimeline(executionId, controller.signal).then(setTimeline).catch((cause) => {
      if (!(cause instanceof DOMException && cause.name === 'AbortError')) toast.error(messageOf(cause, 'Gagal memuat urutan eksekusi'))
    })
    return () => controller.abort()
  }, [executionId, execution?.status, toast])
  useEffect(() => { if (draft.error) toast.error(messageOf(draft.error, 'Pratinjau plan ditolak')) }, [draft.error, toast])
  useEffect(() => { if (apply.error) toast.error(messageOf(apply.error, 'Apply produksi ditolak')) }, [apply.error, toast])
  useEffect(() => { if (polled.error) toast.error(messageOf(polled.error, 'Status eksekusi tidak dapat dimuat')) }, [polled.error, toast])

  const readiness = useMemo(() => productionReadiness(
    draft.preview, capabilities, protections, permissions.certification ? certifications : null,
  ), [capabilities, certifications, draft.preview, permissions.certification, protections])

  const cancel = async () => {
    if (!execution) return
    setCancelling(true)
    try { await cancelProvisioningExecution(execution.id, execution.revision); toast.success('Pembatalan eksekusi diterima') }
    catch (cause) { toast.error(messageOf(cause, 'Gagal membatalkan eksekusi')) }
    finally { setCancelling(false) }
  }
  const adopt = async (item: DriftView) => {
    setAdoptingId(item.id)
    try {
      const adopted = await adoptProvisioningDrift(item.id, item.revision)
      setDrift((current) => current.map((candidate) => candidate.id === adopted.id ? adopted : candidate))
      toast.success('Baseline drift diperbarui')
    } catch (cause) { toast.error(messageOf(cause, 'Drift tidak dapat diadopsi')) }
    finally { setAdoptingId(null) }
  }

  if (loading) return <Text as="p" className="muted">Memuat workspace provisioning…</Text>
  if (!permissions.view) return <EmptyState title="Akses provisioning ditolak" hint="Minta izin melihat segmen jaringan kepada administrator." />
  if (!rollout.uiEnabled) return <EmptyState title="Workspace provisioning dinonaktifkan" hint="Planner dan layanan yang sudah aktif tidak diubah." />

  return (
    <div className="stack network-provisioning-page">
      <PageHeader title="Provisioning jaringan" subtitle="Rencanakan InterVLAN dari OLT hingga BRAS, uji tanpa mutasi, lalu terapkan hanya setelah seluruh gerbang keselamatan lulus." />
      <section className="workspace-summary" aria-label="Ringkasan workspace">
        <Summary label="Node topologi" value={topology.nodes.length} /><Summary label="Profil segmen" value={profiles.length} /><Summary label="Intent aktif" value={intents.filter((item) => item.value.status === 'ACTIVE').length} /><Summary label="Drift terbuka" value={drift.filter((item) => item.status !== 'NONE').length} />
      </section>
      <div className="workspace-tabs"><Tabs tabs={WORKSPACE_TABS} active={tab} onChange={setTab} /></div>
      {permissions.manage && (tab === 'topology' || tab === 'profiles' || tab === 'intents') && (
        <div className="azure-commandbar"><button type="button" className="cmd-btn cmd-primary" onClick={() => setEditor(tab)}>+ {tab === 'topology' ? 'Tambah node' : tab === 'profiles' ? 'Tambah profil' : 'Buat intent'}</button></div>
      )}
      {tab === 'topology' && <TopologyPanel topology={topology} />}
      {tab === 'profiles' && <ProfilesPanel pools={pools} profiles={profiles} />}
      {tab === 'intents' && <div className="stack"><IntentsPanel intents={intents} profiles={profiles} /><ProvisioningPreviewPanel planId={draft.draft.planId} preview={draft.preview} capabilities={capabilities} readiness={readiness} loading={false} applying={apply.applying} canPreview={permissions.plan && rollout.plannerEnabled} canApply={permissions.apply && permissions.plan && rollout.autoApplyEnabled} autoApplyEnabled={rollout.autoApplyEnabled} onPlanIdChange={(planId) => draft.setDraft({ planId })} onPreview={() => void draft.previewPlan(draft.draft.planId, 'DRY_RUN')} onApply={() => { if (draft.preview) void apply.apply(draft.preview.plan.id, draft.preview.plan.revision) }} /></div>}
      {tab === 'executions' && <ProvisioningExecutionPanel execution={execution} timeline={timeline} canCancel={permissions.cancel} cancelling={cancelling} onCancel={() => void cancel()} />}
      {tab === 'drift' && <DriftPanel drift={drift} canAdopt={permissions.adopt} adoptingId={adoptingId} onAdopt={(item) => void adopt(item)} />}
      {tab === 'certification' && <CertificationPanel tenantId={draft.preview?.plan.tenantId ?? null} capabilities={capabilities} certifications={certifications} canCertify={permissions.certification} onCertify={async (tenantId, capability, validUntil) => { const created = await certifyAdapter(tenantId, { deviceKind: capability.deviceKind, deviceId: capability.deviceId, vendor: capability.vendor, model: capability.model, firmware: capability.firmware, transport: capability.transport, operationClass: capability.operationClass, validUntil }); setCertifications((current) => [...current, created]); toast.success('Adapter tersertifikasi') }} onRevoke={async (certification) => { const revoked = await revokeAdapterCertification(certification.tenantId, certification.id, certification.revision); setCertifications((current) => current.map((item) => item.id === revoked.id ? revoked : item)); toast.success('Sertifikasi dicabut') }} onError={(cause) => toast.error(messageOf(cause, 'Sertifikasi adapter ditolak'))} />}
      <ProvisioningEditorModal editor={editor} profiles={profiles} defaultPoolId={pools[0]?.value.id ?? ''} onClose={() => setEditor(null)} onCreated={load} onError={(cause) => toast.error(messageOf(cause, 'Perubahan provisioning ditolak'))} />
    </div>
  )
}

function Summary({ label, value }: { readonly label: string; readonly value: number }) {
  return <div className="card workspace-summary-item"><Text as="strong" size={500} weight="semibold">{value}</Text><Text as="span" className="muted" size={200}>{label}</Text></div>
}

function messageOf(cause: unknown, fallback: string): string { return cause instanceof ApiError ? `${fallback}: ${cause.code ?? cause.message}` : fallback }
