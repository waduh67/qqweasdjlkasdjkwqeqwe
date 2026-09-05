import { useState, type ReactNode } from 'react'
import { Text } from '@fluentui/react-components'
import {
  createSegmentProfile,
  createServiceIntent,
  createTopologyNode,
  type RevisionedResource,
  type SegmentProfileView,
  type ManagedNodeRole,
  type ProvisioningTopology,
  type VlanAllocationMode,
} from '@/api/provisioning'
import { Button, SelectField, TextField } from '@/components/atoms'
import { Modal } from '@/components/molecules'

export type ProvisioningEditor = 'topology' | 'profiles' | 'intents' | null

type EditorProps = {
  readonly editor: ProvisioningEditor
  readonly profiles: readonly RevisionedResource<SegmentProfileView>[]
  readonly defaultPoolId: string
  readonly topology?: ProvisioningTopology | null
  readonly onClose: () => void
  readonly onCreated: () => Promise<void>
  readonly onError: (cause: unknown) => void
}

export function ProvisioningEditorModal(props: EditorProps) {
  switch (props.editor) {
    case 'topology': return <TopologyEditor {...props} />
    case 'profiles': return <ProfileEditor {...props} />
    case 'intents': return <IntentEditor {...props} />
    case null: return null
  }
}

function TopologyEditor({ onClose, onCreated, onError }: EditorProps) {
  const [name, setName] = useState('')
  const [role, setRole] = useState<ManagedNodeRole>('OLT')
  const [saving, setSaving] = useState(false)
  return <EditorShell title="Tambah node topologi" saving={saving} valid={name.trim() !== ''} onClose={onClose} onSubmit={async () => {
    setSaving(true)
    try { await createTopologyNode({ name: name.trim(), role, status: 'ENABLED' }); await onCreated(); onClose() }
    catch (cause) { onError(cause) }
    finally { setSaving(false) }
  }}>
    <TextField label="Nama node" value={name} onChange={(_, data) => setName(data.value)} required />
    <SelectField label="Peran node" value={role} onChange={(event) => setRole(nodeRole(event.target.value))}><option value="OLT">OLT akses</option><option value="ACCESS_SWITCH">Switch akses</option><option value="AGGREGATION_SWITCH">Switch agregasi</option><option value="BRAS">BRAS</option></SelectField>
  </EditorShell>
}

function nodeRole(value: string): ManagedNodeRole {
  switch (value) {
    case 'OLT': return 'OLT'
    case 'ACCESS_SWITCH': return 'ACCESS_SWITCH'
    case 'AGGREGATION_SWITCH': return 'AGGREGATION_SWITCH'
    case 'BRAS': return 'BRAS'
    default: return 'OLT'
  }
}

function ProfileEditor({ defaultPoolId, onClose, onCreated, onError }: EditorProps) {
  const [name, setName] = useState('')
  const [saving, setSaving] = useState(false)
  return <EditorShell title="Tambah profil segmen" saving={saving} valid={name.trim() !== '' && defaultPoolId !== ''} onClose={onClose} onSubmit={async () => {
    setSaving(true)
    try { await createSegmentProfile({ name: name.trim(), poolId: defaultPoolId }); await onCreated(); onClose() }
    catch (cause) { onError(cause) }
    finally { setSaving(false) }
  }}>
    <TextField label="Nama profil" hint="Gunakan nama yang menjelaskan kelas layanan." value={name} onChange={(_, data) => setName(data.value)} required />
    <Text as="p" className="muted" size={200}>Profil memakai pool VLAN aktif pertama. Pengelolaan rentang pool tetap melalui API tervalidasi server.</Text>
  </EditorShell>
}

function IntentEditor({ profiles, topology, onClose, onCreated, onError }: EditorProps) {
  const [subscriptionId, setSubscriptionId] = useState('')
  const [profileId, setProfileId] = useState('')
  const [mode, setMode] = useState<VlanAllocationMode>('SHARED')
  const [vlan, setVlan] = useState('')
  const [oltNodeId, setOltNodeId] = useState('')
  const [ponInterfaceId, setPonInterfaceId] = useState('')
  const [onuId, setOnuId] = useState('')
  const [saving, setSaving] = useState(false)
  const dedicatedVlanId = Number(vlan)
  const validVlan = vlan === '' || (Number.isInteger(dedicatedVlanId) && dedicatedVlanId >= 2 && dedicatedVlanId <= 4094)
  const oltNodes = topology?.nodes.filter((node) => node.role === 'OLT' && node.reference?.kind === 'OLT' && node.administrativeStatus === 'ENABLED') ?? []
  const ponPorts = topology?.interfaces.filter((networkInterface) => networkInterface.nodeId === oltNodeId && networkInterface.role === 'ACCESS' && networkInterface.reference?.kind === 'PON' && networkInterface.administrativeStatus === 'ENABLED') ?? []
  const selectedOlt = oltNodes.find((node) => node.id === oltNodeId)
  const selectedPon = ponPorts.find((networkInterface) => networkInterface.id === ponInterfaceId)
  const valid = subscriptionId.trim() !== '' && profileId !== '' && selectedOlt?.reference?.id != null && selectedPon?.reference?.id != null && onuId.trim() !== '' && (mode === 'SHARED' || validVlan)
  return <EditorShell title="Buat intent layanan" saving={saving} valid={valid} onClose={onClose} onSubmit={async () => {
    setSaving(true)
    if (!selectedOlt?.reference || !selectedPon?.reference) return
    try { await createServiceIntent({ subscriptionId: subscriptionId.trim(), segmentProfileId: profileId, allocationMode: mode, dedicatedVlanId: mode === 'DEDICATED' && vlan !== '' ? dedicatedVlanId : null, accessOltId: selectedOlt.reference.id, accessPonPortId: selectedPon.reference.id, accessOnuId: onuId.trim() }); await onCreated(); onClose() }
    catch (cause) { onError(cause) }
    finally { setSaving(false) }
  }}>
    <TextField label="ID langganan" value={subscriptionId} onChange={(_, data) => setSubscriptionId(data.value)} required />
    <SelectField label="OLT akses" value={oltNodeId} onChange={(event) => { setOltNodeId(event.target.value); setPonInterfaceId('') }} required><option value="">Pilih OLT</option>{oltNodes.map((node) => <option key={node.id} value={node.id}>{node.name}</option>)}</SelectField>
    <SelectField label="Port PON" value={ponInterfaceId} onChange={(event) => setPonInterfaceId(event.target.value)} required><option value="">Pilih port PON</option>{ponPorts.map((networkInterface) => <option key={networkInterface.id} value={networkInterface.id}>{networkInterface.name}</option>)}</SelectField>
    <TextField label="ID ONU" value={onuId} onChange={(_, data) => setOnuId(data.value)} required />
    <SelectField label="Profil segmen" value={profileId} onChange={(event) => setProfileId(event.target.value)} required><option value="">Pilih profil</option>{profiles.map((profile) => <option key={profile.value.id} value={profile.value.id}>{profile.value.name}</option>)}</SelectField>
    <SelectField label="Mode intent" value={mode} onChange={(event) => setMode(event.target.value === 'DEDICATED' ? 'DEDICATED' : 'SHARED')}><option value="SHARED">Residential shared</option><option value="DEDICATED">Enterprise dedicated</option></SelectField>
    {mode === 'DEDICATED' && <TextField type="number" min={2} max={4094} label="Override VLAN dedicated (opsional)" value={vlan} onChange={(_, data) => setVlan(data.value)} />}
  </EditorShell>
}

function EditorShell({ title, saving, valid, onClose, onSubmit, children }: { readonly title: string; readonly saving: boolean; readonly valid: boolean; readonly onClose: () => void; readonly onSubmit: () => Promise<void>; readonly children: ReactNode }) {
  return <Modal title={title} onClose={() => !saving && onClose()} footer={<><Button variant="subtle" disabled={saving} onClick={onClose}>Batal</Button><Button variant="primary" disabled={!valid || saving} onClick={() => void onSubmit()}>{saving ? 'Menyimpan…' : 'Simpan'}</Button></>}><div className="stack">{children}</div></Modal>
}
