import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as provisioningApi from '@/api/provisioning'
import * as provisioningRolloutApi from '@/api/provisioningRollout'
import type { PlanPreview } from '@/api/provisioning'
import { useProvisioningPermissions } from '@/hooks/useProvisioningPermissions'
import { ProvisioningEditorModal } from '@/components/organisms/provisioning/ProvisioningEditors'
import { ProvisioningExecutionPanel } from '@/components/organisms/provisioning/ProvisioningExecutionPanel'
import { DriftPanel } from '@/components/organisms/provisioning/ProvisioningEvidencePanels'
import { NetworkProvisioningPage } from './NetworkProvisioningPage'

vi.mock('@/hooks/useProvisioningPermissions', () => ({ useProvisioningPermissions: vi.fn() }))
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn() }
vi.mock('@/system', () => ({ useToast: () => toast }))

const mockedPermissions = vi.mocked(useProvisioningPermissions)

const topology = {
  nodes: [
    { id: 'olt-1', name: 'OLT Bekasi', role: 'ACCESS_OLT', administrativeStatus: 'ACTIVE' },
    { id: 'switch-1', name: 'Transit Bekasi', role: 'AGGREGATION_SWITCH', administrativeStatus: 'ACTIVE' },
    { id: 'bras-1', name: 'BRAS Utama', role: 'BRAS', administrativeStatus: 'ACTIVE' },
  ],
  interfaces: [{ id: 'if-1', nodeId: 'olt-1', name: 'PON 0/1', role: 'SUBSCRIBER', administrativeStatus: 'ACTIVE' }],
  links: [{ id: 'link-1', interfaceAId: 'if-1', interfaceZId: 'if-2', administrativeStatus: 'ACTIVE' }],
}

const preview: PlanPreview = {
  plan: {
    id: 'plan-1', tenantId: 'tenant-1', intentId: 'intent-enterprise', revision: 3,
    status: 'VALIDATED' as const, contentHash: 'content-hash', preconditionHash: 'before-hash',
    steps: [
      {
        id: 'step-1', order: 1, device: { kind: 'OLT' as const, id: 'olt-1' },
        operation: 'ENSURE_SUBSCRIBER_VLAN',
        attributes: { vlanId: '3101', interface: 'PON 0/1', onuId: 'ONU-17', blastRadius: '1 pelanggan' },
        preconditionHash: 'olt-before',
      },
      {
        id: 'step-2', order: 2, device: { kind: 'BRAS' as const, id: 'bras-1' },
        operation: 'ENSURE_PPPOE_TERMINATION',
        attributes: { vlanId: '3101', interface: 'ae0.3101', firewallAllowList: 'RADIUS, PPPoE' },
        preconditionHash: 'bras-before',
      },
    ],
  },
  decision: { allowed: true, code: 'ALLOWED', warnings: [], evidenceIds: ['evidence-1'] },
}

beforeEach(() => {
  vi.restoreAllMocks()
  HTMLDialogElement.prototype.showModal = function showModal() { this.setAttribute('open', '') }
  HTMLDialogElement.prototype.close = function close() { this.removeAttribute('open') }
  mockedPermissions.mockReturnValue({ view: true, manage: true, plan: true, apply: true, cancel: true, drift: true, adopt: true, certification: false })
  vi.spyOn(provisioningApi, 'getTopology').mockResolvedValue(topology)
  vi.spyOn(provisioningRolloutApi, 'getProvisioningRollout').mockResolvedValue({ plannerEnabled: true, uiEnabled: true, autoApplyEnabled: true, maxAffectedSubscribers: 1, circuitFailureThreshold: 1, bulkExpansionEnabled: false })
  vi.spyOn(provisioningApi, 'listVlanPools').mockResolvedValue([{ revision: 1, value: { id: 'pool-1', name: 'Enterprise', range: { start: 3000, endInclusive: 3999 }, reservedRanges: [] } }])
  vi.spyOn(provisioningApi, 'listSegmentProfiles').mockResolvedValue([
    { revision: 1, value: { id: 'profile-shared', name: 'Residential shared', poolId: 'pool-1' } },
    { revision: 1, value: { id: 'profile-dedicated', name: 'Enterprise dedicated', poolId: 'pool-1' } },
  ])
  vi.spyOn(provisioningApi, 'listServiceIntents').mockResolvedValue([
    { revision: 2, value: { id: 'intent-home', subscriptionId: 'sub-home', segmentProfileId: 'profile-shared', status: 'ACTIVE' } },
    { revision: 4, value: { id: 'intent-enterprise', subscriptionId: 'sub-enterprise', segmentProfileId: 'profile-dedicated', status: 'ACTIVE' } },
  ])
  vi.spyOn(provisioningApi, 'listProvisioningCapabilities').mockResolvedValue([
    { id: 'cap-1', deviceKind: 'OLT', deviceId: 'olt-1', vendor: 'HUAWEI', model: 'MA5800-X7', firmware: 'R019', transport: 'SSH', operationClass: 'ENSURE_SUBSCRIBER_VLAN', supported: true, observedAt: '2026-09-03T00:00:00Z', expiresAt: '2099-09-03T00:00:00Z' },
    { id: 'cap-2', deviceKind: 'BRAS', deviceId: 'bras-1', vendor: 'MIKROTIK', model: 'CCR2004', firmware: '7.20', transport: 'HTTPS_REST', operationClass: 'ENSURE_PPPOE_TERMINATION', supported: true, observedAt: '2026-09-03T00:00:00Z', expiresAt: '2099-09-03T00:00:00Z' },
  ])
  vi.spyOn(provisioningApi, 'listManagementProtections').mockResolvedValue([
    { id: 'protect-1', deviceKind: 'OLT', deviceId: 'olt-1', complete: true, sourceType: 'DEVICE_OBSERVATION', sourceEvidenceId: 'obs-1', validUntil: '2099-09-03T00:00:00Z' },
    { id: 'protect-2', deviceKind: 'BRAS', deviceId: 'bras-1', complete: true, sourceType: 'DEVICE_OBSERVATION', sourceEvidenceId: 'obs-2', validUntil: '2099-09-03T00:00:00Z' },
  ])
  vi.spyOn(provisioningApi, 'listProvisioningDrift').mockResolvedValue([
    { id: 'drift-1', deviceKind: 'OLT', deviceId: 'olt-1', revision: 2, status: 'BENIGN', recordedAt: '2026-09-03T00:00:00Z' },
  ])
  vi.spyOn(provisioningApi, 'listAdapterCertifications').mockResolvedValue([])
  vi.spyOn(provisioningApi, 'createServiceIntent').mockResolvedValue({ revision: 1, value: { id: 'intent-new', subscriptionId: 'sub-new', segmentProfileId: 'profile-shared', status: 'PLANNED' } })
  vi.spyOn(provisioningApi, 'previewProvisioning').mockResolvedValue(preview)
  vi.spyOn(provisioningApi, 'applyProvisioningPlan').mockResolvedValue({ id: 'exec-1', planId: 'plan-1', revision: 1, status: 'QUEUED' })
  vi.spyOn(provisioningApi, 'getProvisioningExecution').mockResolvedValue({ id: 'exec-1', planId: 'plan-1', revision: 2, status: 'MANUAL_RECONCILIATION' })
  vi.spyOn(provisioningApi, 'getProvisioningTimeline').mockResolvedValue([
    { stepOrder: 1, deviceKind: 'BRAS', deviceId: 'bras-1', attemptNumber: 1, phase: 'APPLY', status: 'SUCCEEDED', errorCode: null, startedAt: '2026-09-03T00:00:00Z', completedAt: '2026-09-03T00:00:01Z' },
    { stepOrder: 2, deviceKind: 'SWITCH', deviceId: 'switch-1', attemptNumber: 1, phase: 'ROLLBACK', status: 'FAILED', errorCode: 'ROLLBACK_POLICY_DENIED', startedAt: '2026-09-03T00:00:02Z', completedAt: '2026-09-03T00:00:03Z' },
  ])
})

describe('NetworkProvisioningPage', () => {
  it('menampilkan enam area kerja dan membedakan intent shared serta dedicated', async () => {
    render(<NetworkProvisioningPage />)

    expect(await screen.findByRole('tab', { name: /Topologi/ })).toBeTruthy()
    for (const label of ['Profil', 'Intent', 'Eksekusi', 'Drift', 'Sertifikasi']) {
      expect(screen.getByRole('tab', { name: new RegExp(label) })).toBeTruthy()
    }

    fireEvent.click(screen.getByRole('tab', { name: /Intent/ }))
    expect((await screen.findAllByText('Residential shared')).length).toBeGreaterThan(0)
    expect(screen.getAllByText('Enterprise dedicated').length).toBeGreaterThan(0)
  })

  it('menjaga dry-run tersedia tetapi memblokir apply saat adapter provisional atau proteksi gagal', async () => {
    vi.mocked(provisioningApi.listProvisioningCapabilities).mockResolvedValueOnce([
      { id: 'cap-1', deviceKind: 'OLT', deviceId: 'olt-1', vendor: 'HUAWEI', model: 'MA5800-X7', firmware: 'R019', transport: 'SSH', operationClass: 'ENSURE_SUBSCRIBER_VLAN', supported: false, observedAt: '2026-09-03T00:00:00Z', expiresAt: '2099-09-03T00:00:00Z' },
    ])
    vi.mocked(provisioningApi.listManagementProtections).mockResolvedValueOnce([
      { id: 'protect-1', deviceKind: 'OLT', deviceId: 'olt-1', complete: false, sourceType: null, sourceEvidenceId: null, validUntil: '2099-09-03T00:00:00Z' },
    ])
    render(<NetworkProvisioningPage />)

    fireEvent.click(await screen.findByRole('tab', { name: /Intent/ }))
    fireEvent.change(screen.getByLabelText('ID plan aktif'), { target: { value: 'plan-1' } })
    const dryRun = screen.getByRole('button', { name: 'Pratinjau dry-run' })
    expect(dryRun.hasAttribute('disabled')).toBe(false)
    fireEvent.click(dryRun)

    expect(await screen.findByText('ENSURE_SUBSCRIBER_VLAN')).toBeTruthy()
    expect(screen.getByText('PON 0/1')).toBeTruthy()
    expect(screen.getByText('ONU-17')).toBeTruthy()
    expect(screen.getAllByText('3101').length).toBeGreaterThan(0)
    expect(screen.getByText('RADIUS, PPPoE')).toBeTruthy()
    expect(screen.getByText('1 pelanggan')).toBeTruthy()
    expect(screen.getByText('ALLOWED')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Terapkan ke produksi' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByText(/adapter tidak mendukung/i)).toBeTruthy()
    expect(screen.getByText(/proteksi manajemen belum lengkap/i)).toBeTruthy()
  })

  it('menjaga dry-run tersedia tetapi memblokir apply pada konfigurasi produksi baru', async () => {
    vi.mocked(provisioningRolloutApi.getProvisioningRollout).mockResolvedValueOnce({
      plannerEnabled: true,
      uiEnabled: true,
      autoApplyEnabled: false,
      maxAffectedSubscribers: 1,
      circuitFailureThreshold: 1,
      bulkExpansionEnabled: false,
    })
    render(<NetworkProvisioningPage />)

    fireEvent.click(await screen.findByRole('tab', { name: /Intent/ }))
    fireEvent.change(screen.getByLabelText('ID plan aktif'), { target: { value: 'plan-1' } })
    const dryRun = screen.getByRole('button', { name: 'Pratinjau dry-run' })
    expect(dryRun.hasAttribute('disabled')).toBe(false)
    fireEvent.click(dryRun)

    expect(await screen.findByText('ALLOWED')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Terapkan ke produksi' }).hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('status').textContent).toContain('Auto-apply produksi dinonaktifkan')
    expect(provisioningApi.applyProvisioningPlan).not.toHaveBeenCalled()
  })

  it('mengaktifkan apply hanya setelah preview, kapabilitas, dan proteksi lulus', async () => {
    render(<NetworkProvisioningPage />)
    fireEvent.click(await screen.findByRole('tab', { name: /Intent/ }))
    fireEvent.change(screen.getByLabelText('ID plan aktif'), { target: { value: 'plan-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Pratinjau dry-run' }))

    const apply = await screen.findByRole('button', { name: 'Terapkan ke produksi' })
    await waitFor(() => expect(apply.hasAttribute('disabled')).toBe(false))
    fireEvent.click(apply)

    await waitFor(() => expect(provisioningApi.applyProvisioningPlan).toHaveBeenCalledWith('plan-1', 3, expect.any(String)))
  })

  it('menampilkan urutan per perangkat, rollback, kode stabil, dan rekonsiliasi manual', async () => {
    render(<NetworkProvisioningPage />)
    fireEvent.click(await screen.findByRole('tab', { name: /Intent/ }))
    fireEvent.change(screen.getByLabelText('ID plan aktif'), { target: { value: 'plan-1' } })
    fireEvent.click(screen.getByRole('button', { name: 'Pratinjau dry-run' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Terapkan ke produksi' }))
    fireEvent.click(screen.getByRole('tab', { name: /Eksekusi/ }))

    const timeline = await screen.findByLabelText('Urutan eksekusi perangkat')
    const items = within(timeline).getAllByRole('listitem')
    expect(items[0]?.textContent).toContain('Langkah 1')
    expect(items[1]?.textContent).toContain('Rollback')
    expect(items[1]?.textContent).toContain('ROLLBACK_POLICY_DENIED')
    expect(await screen.findByText('Perlu rekonsiliasi manual')).toBeTruthy()
  })

  it('menampilkan kontrol sertifikasi hanya untuk platform admin', async () => {
    const { rerender } = render(<NetworkProvisioningPage />)
    fireEvent.click(await screen.findByRole('tab', { name: /Sertifikasi/ }))
    expect(screen.queryByRole('button', { name: 'Sertifikasi adapter' })).toBeNull()

    mockedPermissions.mockReturnValue({ view: true, manage: true, plan: true, apply: true, cancel: true, drift: true, adopt: true, certification: true })
    rerender(<NetworkProvisioningPage />)
    expect(await screen.findByRole('button', { name: 'Sertifikasi adapter' })).toBeTruthy()
  })

  it('memisahkan izin manage dari view saat membuat intent', async () => {
    mockedPermissions.mockReturnValue({ view: true, manage: false, plan: true, apply: false, cancel: false, drift: false, adopt: false, certification: false })
    const readonlyView = render(<NetworkProvisioningPage />)
    fireEvent.click(await screen.findByRole('tab', { name: /Intent/ }))
    expect(screen.queryByRole('button', { name: /Buat intent/ })).toBeNull()

    readonlyView.unmount()
    mockedPermissions.mockReturnValue({ view: true, manage: true, plan: true, apply: false, cancel: false, drift: false, adopt: false, certification: false })
    const managed = render(<NetworkProvisioningPage />)
    fireEvent.click(await screen.findByRole('tab', { name: /Intent/ }))
    expect(screen.getByRole('button', { name: /Buat intent/ })).toBeTruthy()
    managed.unmount()

    render(<ProvisioningEditorModal editor="intents" profiles={[{ revision: 1, value: { id: 'profile-shared', name: 'Residential shared', poolId: 'pool-1' } }]} defaultPoolId="pool-1" onClose={vi.fn()} onCreated={async () => {}} onError={vi.fn()} />)
    await screen.findByText('Buat intent layanan')
    const input = document.querySelector<HTMLInputElement>('.modal input')
    const profile = document.querySelector<HTMLSelectElement>('.modal select')
    expect(input).not.toBeNull()
    expect(profile).not.toBeNull()
    if (!input || !profile) return
    fireEvent.change(input, { target: { value: 'sub-new' } })
    fireEvent.change(profile, { target: { value: 'profile-shared' } })
    fireEvent.click(screen.getByRole('button', { name: 'Simpan', hidden: true }))

    await waitFor(() => expect(provisioningApi.createServiceIntent).toHaveBeenCalledWith({ subscriptionId: 'sub-new', segmentProfileId: 'profile-shared', dedicatedVlanId: null }))
  })

  it('memisahkan izin cancel dan adopt dari izin melihat', () => {
    const execution = { id: 'exec-2', planId: 'plan-1', revision: 2, status: 'RUNNING' as const }
    const executionView = render(<ProvisioningExecutionPanel execution={execution} timeline={[]} canCancel={false} cancelling={false} onCancel={vi.fn()} />)
    expect(screen.queryByRole('button', { name: 'Batalkan eksekusi' })).toBeNull()
    executionView.rerender(<ProvisioningExecutionPanel execution={execution} timeline={[]} canCancel cancelling={false} onCancel={vi.fn()} />)
    expect(screen.queryByRole('button', { name: 'Batalkan eksekusi' })).toBeNull()
    executionView.rerender(<ProvisioningExecutionPanel execution={{ ...execution, status: 'QUEUED' }} timeline={[]} canCancel cancelling={false} onCancel={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Batalkan eksekusi' })).toBeTruthy()
    executionView.rerender(<ProvisioningExecutionPanel execution={{ ...execution, status: 'SUCCEEDED' }} timeline={[]} canCancel cancelling={false} onCancel={vi.fn()} />)
    expect(screen.queryByRole('button', { name: 'Batalkan eksekusi' })).toBeNull()
    executionView.unmount()

    render(<DriftPanel drift={[{ id: 'drift-2', deviceKind: 'OLT', deviceId: 'olt-1', revision: 1, status: 'BENIGN', recordedAt: '2026-09-03T00:00:00Z' }]} canAdopt={false} adoptingId={null} onAdopt={vi.fn()} />)
    expect(screen.getByRole('button', { name: 'Adopsi baseline' }).hasAttribute('disabled')).toBe(true)
  })

  it('tidak memuat bukti plan dan drift tanpa izin baca masing-masing', async () => {
    mockedPermissions.mockReturnValue({ view: true, manage: false, plan: false, apply: false, cancel: false, drift: false, adopt: false, certification: false })

    render(<NetworkProvisioningPage />)

    expect(await screen.findByRole('tab', { name: /Topologi/ })).toBeTruthy()
    expect(provisioningApi.listProvisioningCapabilities).not.toHaveBeenCalled()
    expect(provisioningApi.listProvisioningDrift).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('tab', { name: /Intent/ }))
    expect(screen.getByRole('button', { name: 'Pratinjau dry-run' }).hasAttribute('disabled')).toBe(true)
  })
})
