import { useId, useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { getOperationalSettings, saveOperationalSettings, type operationalSettings } from '@/api/warehouse/referenceRequests'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { Button, TextField } from '@/components/atoms'
import { PageHeader } from '@/components/molecules'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { useWarehouseWorkflow } from './WarehouseWorkflowContext'

type Settings = ReturnType<typeof operationalSettings>
export function ReferenceSettingsPage() {
  const workflow = useWarehouseWorkflow()
  return workflow.state.status === 'ready' && workflow.state.data.owner ? <OwnerSettings /> : <WarehouseDenied />
}
function OwnerSettings() {
  const result = useWarehouseQuery(getOperationalSettings), { readOnly } = useAuth(), [editing, setEditing] = useState(false)
  return <div className="settings-page stack"><PageHeader title="Setelan Gudang" subtitle="Kebijakan operasional yang dikelola owner tenant." />
    <WarehouseState {...result}>{data => <>
      <div className="card stack"><p>Persetujuan Manager: <strong>{data.requireManagerApproval ? 'Wajib' : 'Tidak wajib'}</strong></p>
        <p>Batas WO terlambat: <strong>{data.overdueDays} hari</strong></p><p className="muted">Revisi {data.revision}. Perubahan persetujuan berlaku untuk permintaan yang diajukan berikutnya.</p>
        <div className="row wrap"><Button onClick={result.reload}>Muat ulang setelan</Button>{!readOnly && <Button variant="primary" onClick={() => setEditing(true)}>Ubah setelan</Button>}</div>
      </div>
      {editing && !readOnly && <SettingsEditor settings={data} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); result.reload() }} />}
    </>}</WarehouseState>
  </div>
}
function SettingsEditor({ settings, onClose, onSaved }: { readonly settings: Settings; readonly onClose: () => void; readonly onSaved: () => void }) {
  const formId = useId(), [approval, setApproval] = useState(settings.requireManagerApproval), [days, setDays] = useState(String(settings.overdueDays))
  const [error, setError] = useState<string | null>(null), [command, setCommand] = useState<WarehouseCommand<Settings> | null>(null)
  function prepare(event: FormEvent) {
    event.preventDefault()
    if (!/^[1-9][0-9]{0,2}$/.test(days) || Number(days) > 365) { setError('Isi batas terlambat antara 1 dan 365 hari.'); return }
    setCommand(saveOperationalSettings({ expectedRevision: settings.revision, requireManagerApproval: approval, overdueDays: Number(days) })); setError(null)
  }
  return <ResourceForm editing title="Ubah setelan gudang" onClose={onClose} onBack={() => setCommand(null)}
    footer={<><Button onClick={onClose}>Batal</Button><Button form={formId} type="submit" variant="primary">Tinjau setelan</Button></>}
    review={command && <WarehouseCommandDialog embedded title="Simpan kebijakan gudang" confirmLabel="Simpan setelan" command={command} onDone={onSaved} onClose={() => setCommand(null)} onReload={onSaved}
      summary={<><p>Revisi {settings.revision} · Persetujuan Manager {approval ? 'wajib' : 'tidak wajib'}.</p><p>Batas WO terlambat: {days} hari.</p><p>Kebijakan persetujuan pada permintaan yang sudah diajukan tetap berlaku.</p></>} />}>
    <form id={formId} className="stack" onSubmit={prepare}><Checkbox label="Wajibkan persetujuan Manager" checked={approval} onChange={(_, data) => setApproval(data.checked === true)} />
      <TextField label="Batas WO terlambat (hari)" required inputMode="numeric" maxLength={3} value={days} onChange={(_, data) => setDays(data.value)} />
      {error && <p role="alert" className="error">{error}</p>}
    </form>
  </ResourceForm>
}
