import { useCallback, useId, useState, type FormEvent } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { Link } from 'react-router-dom'
import { ApiError } from '@/api/client'
import { getLocation, listLocations, saveLocation } from '@/api/warehouse/masters'
import type { WarehouseLocation } from '@/api/warehouse/models'
import { getSetupSite, getSetupUser, listSetupAreas, listSetupSites, listSetupUsers, type SetupArea } from '@/api/warehouse/setup'
import type { WarehouseCommand } from '@/api/warehouse/transport'
import { useAuth } from '@/auth/useAuth'
import { useCan } from '@/auth/useCan'
import { Button, SelectField, TextField } from '@/components/atoms'
import { Modal } from '@/components/molecules/Modal'
import { WarehouseCommandDialog } from '@/components/organisms/warehouse/WarehouseCommandDialog'
import { WarehousePicker } from '@/components/organisms/warehouse/WarehousePicker'
import { WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'

type LocationChoice = { id: string; label: string; areaId?: string | null; siteId?: string | null; kind?: WarehouseLocation['kind'] }
type UserChoice = { id: string; label: string; active?: boolean }
type SiteChoice = { id: string; label: string; areaId?: string | null }
type EditorProps = { row: WarehouseLocation | null; readOnly: boolean; onClose: () => void; onSaved: () => void; onReload: () => void; preset?: Pick<WarehouseLocation, 'code' | 'name' | 'kind' | 'issueEligible'> }
const kinds: Record<WarehouseLocation['kind'], string> = { WAREHOUSE: 'Gudang', BIN: 'Bin / rak', VEHICLE: 'Kendaraan', TECHNICIAN: 'Teknisi', CUSTOMER_SITE: 'Lokasi pelanggan', QUARANTINE: 'Karantina', LOST: 'Barang hilang', DISPOSED: 'Penghapusan', TRANSIT: 'Transit' }
const canIssue = (kind: WarehouseLocation['kind']) => ['WAREHOUSE', 'BIN', 'VEHICLE', 'TECHNICIAN'].includes(kind)
const locationChoice = (row: WarehouseLocation): LocationChoice => ({ id: row.id, label: `${row.name ?? row.code} · ${row.code}`, areaId: row.areaId, siteId: row.siteId, kind: row.kind })
const locations = async (search: string, page: number) => { const data = await listLocations({ search, page, state: 'ACTIVE' }); return { ...data, items: data.items.map(locationChoice) } }
const users = async (search: string, page: number) => { const data = await listSetupUsers(search, page); return { ...data, items: data.items.map(row => ({ id: row.id, label: `${row.name} · ${row.email}`, active: row.status === 'ACTIVE' })) } }
const sites = async (search: string, page: number) => { const data = await listSetupSites(search, page); return { ...data, items: data.items.map(row => ({ id: row.id, label: `${row.name} · ${row.code}`, areaId: row.areaId })) } }
async function accessible<T>(promise: Promise<T>): Promise<T | null> {
  try { return await promise } catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error }
}

export function WarehouseLocationEditor(props: EditorProps) {
  const { can } = useCan()
  const { row } = props
  const loader = useCallback(async () => {
    const [areas, parent, custodian, site] = await Promise.all([
      can('iam.area.view') ? listSetupAreas() : Promise.resolve([]),
      row?.parentLocationId ? accessible(getLocation(row.parentLocationId)) : Promise.resolve(null),
      row?.custodianId && can('iam.user.view') ? accessible(getSetupUser(row.custodianId)) : Promise.resolve(null),
      row?.siteId && can('network.site.view') ? accessible(getSetupSite(row.siteId)) : Promise.resolve(null),
    ])
    return { areas, parent, custodian, site }
  }, [can, row])
  const result = useWarehouseQuery(loader)
  if (result.state.status !== 'ready') return <Modal title="Lokasi" onClose={props.onClose}><WarehouseState {...result}>{() => null}</WarehouseState></Modal>
  const { areas, parent, custodian, site } = result.state.data
  return <LocationForm {...props} areas={areas}
    initialParent={row?.parentLocationId ? parent ? locationChoice(parent) : { id: row.parentLocationId, label: 'Lokasi induk tersimpan (nama tidak dapat diakses)' } : null}
    initialCustodian={row?.custodianId ? custodian ? { id: custodian.id, label: `${custodian.name} · ${custodian.email}`, active: custodian.status === 'ACTIVE' } : { id: row.custodianId, label: 'Penanggung jawab tersimpan (nama tidak dapat diakses)' } : null}
    initialSite={row?.siteId ? site ? { id: site.id, label: `${site.name} · ${site.code}`, areaId: site.areaId } : { id: row.siteId, label: 'Site tersimpan (nama tidak dapat diakses)' } : null} />
}

function LocationForm({ row, readOnly, onClose, onSaved, onReload, preset, areas, initialParent, initialCustodian, initialSite }: EditorProps & {
  areas: SetupArea[]; initialParent: LocationChoice | null; initialCustodian: UserChoice | null; initialSite: SiteChoice | null
}) {
  const { can } = useCan()
  const { user } = useAuth()
  const formId = useId()
  const [code, setCode] = useState(row?.code ?? preset?.code ?? '')
  const [name, setName] = useState(row?.name ?? preset?.name ?? '')
  const [kind, setKind] = useState<WarehouseLocation['kind']>(row?.kind ?? preset?.kind ?? 'WAREHOUSE')
  const [areaId, setAreaId] = useState(row?.areaId ?? '')
  const [parent, setParent] = useState(initialParent)
  const [custodian, setCustodian] = useState(initialCustodian)
  const [site, setSite] = useState(initialSite)
  const [issueEligible, setIssueEligible] = useState(row?.issueEligible ?? preset?.issueEligible ?? true)
  const [error, setError] = useState<string | null>(null)
  const [operation, setOperation] = useState<WarehouseCommand<WarehouseLocation> | null>(null)
  const areaIds = new Set(user?.areaIds ?? [])
  for (let pass = 0; pass < areas.length; pass++) {
    const previous = areaIds.size
    for (const area of areas) if (area.parentId && areaIds.has(area.parentId)) areaIds.add(area.id)
    if (areaIds.size === previous) break
  }
  const choices = user?.platformAdmin ? areas : areas.filter(area => areaIds.has(area.id))
  function prepare(event: FormEvent) {
    event.preventDefault(); if (readOnly) return
    if (!user?.platformAdmin && !areaId) { setError('Pilih area yang diberikan kepada akun Anda.'); return }
    if (kind === 'BIN' && !parent) { setError('Bin memerlukan lokasi induk.'); return }
    if (kind === 'TECHNICIAN' && !custodian) { setError('Pilih teknisi yang bertanggung jawab.'); return }
    setError(null)
    setOperation(saveLocation({ code: code.trim(), name: name.trim(), kind, areaId: areaId || null, parentLocationId: parent?.id ?? null,
      custodianId: custodian?.id ?? null, siteId: site?.id ?? null, issueEligible, ...(row ? { expectedRevision: row.revision } : {}) }, row?.id))
  }
  return <>
    <Modal title={readOnly ? 'Detail lokasi' : row ? 'Ubah lokasi' : 'Tambah lokasi'} onClose={onClose} wide footer={<>
      <Button onClick={onClose}>{readOnly ? 'Tutup' : 'Batal'}</Button>{!readOnly && <Button variant="primary" type="submit" form={formId}>Tinjau perubahan</Button>}
    </>}>
      <form id={formId} className="stack" onSubmit={prepare}>
        {row && <p className="muted">Tersimpan: {row.name ?? row.code} · Revisi {row.revision}</p>}
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(min(16rem, 100%), 1fr))', gap: '1rem' }}>
          <TextField label="Kode lokasi" required maxLength={64} pattern="[A-Z0-9][A-Z0-9._-]{0,63}" value={code} disabled={readOnly} onChange={(_, data) => setCode(data.value.toUpperCase())} />
          <TextField label="Nama lokasi" required maxLength={200} value={name} disabled={readOnly} onChange={(_, data) => setName(data.value)} />
          <SelectField label="Jenis lokasi" value={kind} disabled={readOnly} onChange={(_, data) => { const next = data.value as typeof kind; setKind(next); if (!canIssue(next)) setIssueEligible(false) }}>
            {Object.entries(kinds).map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </SelectField>
          <SelectField label="Area lokasi" value={areaId} required={!user?.platformAdmin} disabled={readOnly || !can('iam.area.view') || parent !== null}
            onChange={(_, data) => { setAreaId(data.value); setSite(null) }}>
            <option value="">Pilih area…</option>
            {areaId && !choices.some(area => area.id === areaId) && <option value={areaId}>Area tersimpan (nama tidak dapat diakses)</option>}
            {choices.map(area => <option key={area.id} value={area.id}>{area.name} · {area.code}</option>)}
          </SelectField>
          <WarehousePicker<LocationChoice> label="Lokasi induk" load={locations} value={parent} disabled={readOnly} optional={kind !== 'BIN'} name={choice => choice.label}
            eligible={choice => choice.id !== row?.id && (choice.kind === undefined || ['WAREHOUSE', 'BIN'].includes(choice.kind))}
            onChange={choice => { setParent(choice); if (choice?.areaId !== undefined) setAreaId(choice.areaId ?? ''); if (choice?.siteId !== undefined && choice.siteId !== site?.id) setSite(null) }} />
          {can('iam.user.view') ? <WarehousePicker<UserChoice> label="Penanggung jawab" load={users} value={custodian} disabled={readOnly} optional={kind !== 'TECHNICIAN'} name={choice => choice.label}
            eligible={choice => choice.active !== false} onChange={setCustodian} /> : <p className="muted">{custodian?.label ?? 'Izin lihat pengguna diperlukan untuk memilih penanggung jawab.'}</p>}
          {can('network.site.view') ? <WarehousePicker<SiteChoice> label="Site / POP" load={sites} value={site} disabled={readOnly} optional name={choice => choice.label}
            eligible={choice => choice.areaId === undefined || choice.areaId === (areaId || null)} onChange={setSite} /> : <p className="muted">{site?.label ?? 'Site opsional. Izin lihat site diperlukan untuk memilihnya.'}</p>}
        </div>
        <Checkbox label="Boleh menjadi sumber pengeluaran barang" checked={issueEligible} disabled={readOnly || !canIssue(kind)} onChange={(_, data) => setIssueEligible(data.checked === true)} />
        {!user?.platformAdmin && choices.length === 0 && <div role="note"><p>Akses gudang membutuhkan area yang diberikan secara eksplisit kepada akun Anda.</p>
          {can('iam.area.view') && <p><Link to="/areas">Kelola area</Link></p>}{can('iam.user.view') && <p><Link to="/users">Atur akses area pengguna</Link></p>}</div>}
        <p className="muted">Hierarki, area, pemegang dan kelayakan lokasi yang masih memiliki stok atau referensi tidak dapat diubah.</p>
        {error && <p role="alert" className="error">{error}</p>}
      </form>
    </Modal>
    {operation && <WarehouseCommandDialog title="Simpan lokasi" confirmLabel="Simpan lokasi" command={operation} onDone={onSaved} onClose={() => setOperation(null)} onReload={onReload}
      summary={<><p><strong>{name.trim()}</strong> · {code.trim()}{row && ` · Revisi ${row.revision}`}</p><p>{kinds[kind]} · {areas.find(area => area.id === areaId)?.name ?? (areaId ? 'Area tersimpan' : 'Tanpa area')}</p>
        <p>{parent ? `Induk: ${parent.label}` : 'Lokasi utama'}</p>{custodian && <p>{custodian.label}</p>}<p>{issueEligible ? 'Dapat menjadi sumber pengeluaran' : 'Tidak menjadi sumber pengeluaran'}</p></>} />}
  </>
}
