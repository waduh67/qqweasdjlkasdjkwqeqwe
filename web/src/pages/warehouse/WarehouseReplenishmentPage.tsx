import { useCallback, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { uuid, oneOf, integer } from '@/api/warehouse/codec'
import { listReplenishmentRequests, listReplenishmentRules, REPLENISHMENT_STATES, type ReplenishmentFilter } from '@/api/warehouse/replenishment'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, SelectField } from '@/components/atoms'
import { PageHeader, Tabs } from '@/components/molecules'
import { DataTable } from '@/components/organisms/DataTable'
import { WarehouseDenied, WarehouseState } from '@/components/organisms/warehouse/WarehouseState'
import { WarehousePagination } from '@/components/organisms/warehouse/WarehousePagination'
import { WarehouseQuantity } from '@/components/organisms/warehouse/WarehouseQuantity'
import { useWarehouseQuery } from '@/hooks/useWarehouseQuery'
import { WarehouseReplenishmentDetail } from './WarehouseReplenishmentDetail'
import { WarehouseReplenishmentRuleForm } from './WarehouseReplenishmentRuleForm'
import { replenishmentLabels, replenishmentLink } from './replenishmentPresentation'

function parse(params: URLSearchParams) {
  const allowed = ['view', 'page', 'skuId', 'locationId', 'state', 'active', 'ruleId', 'requestId']
  if ([...params].some(([key, value]) => !allowed.includes(key) || params.getAll(key).length !== 1 || !value.trim())) throw new Error()
  const view = oneOf(params.get('view') ?? 'requests', ['rules', 'requests']), filter: ReplenishmentFilter = {}
  for (const key of ['skuId', 'locationId'] as const) if (params.has(key)) filter[key] = uuid(params.get(key))
  if (params.has('page')) { if (!/^(0|[1-9][0-9]*)$/.test(params.get('page')!)) throw new Error(); filter.page = integer(Number(params.get('page'))) }
  if (params.has('ruleId') && params.has('requestId')) throw new Error()
  const ruleId = params.has('ruleId') ? uuid(params.get('ruleId')) : null, requestId = params.has('requestId') ? uuid(params.get('requestId')) : null
  const state = params.has('state') ? oneOf(params.get('state'), REPLENISHMENT_STATES) : undefined
  const active = params.has('active') ? oneOf(params.get('active'), ['true', 'false']) : undefined
  if ((view === 'rules' && state) || (view === 'requests' && active)) throw new Error()
  return { view, filter, state, active, ruleId, requestId }
}
export function WarehouseReplenishmentPage() {
  const { can } = useCan(), [params, setParams] = useSearchParams()
  if (!can('inventory.request.view')) return <WarehouseDenied />
  let parsed: ReturnType<typeof parse>
  try { parsed = parse(params) } catch { return <div role="alert" className="card stack"><p>Alamat pengisian stok tidak dikenal.</p><Link to="/warehouse/replenishment">Buka pengisian stok</Link></div> }
  function apply(values: Record<string, string>) { const next = new URLSearchParams(params); next.delete('page'); for (const [key, value] of Object.entries(values)) if (value) next.set(key, value); else next.delete(key); setParams(next) }
  return <div className="stack"><PageHeader title="Pengisian Stok" subtitle="Atur batas stok dan tindak lanjuti kebutuhan pengisian per lokasi." />
    {parsed.ruleId || parsed.requestId ? <><Link to="/warehouse/replenishment">Kembali ke pengisian stok</Link><WarehouseReplenishmentDetail key={params.toString()} kind={parsed.ruleId ? 'rules' : 'requests'} id={(parsed.ruleId ?? parsed.requestId)!} /></> : <>
      <Tabs active={parsed.view} tabs={[{ key: 'requests', label: 'Kebutuhan pengisian' }, { key: 'rules', label: 'Aturan minimum' }]} onChange={view => apply({ view, state: view === 'requests' ? 'PENDING' : '', active: view === 'rules' ? 'true' : '' })} />
      <ReplenishmentList key={params.toString()} parsed={parsed} apply={apply} page={page => { const next = new URLSearchParams(params); next.set('page', String(page)); setParams(next) }} />
    </>}
  </div>
}
function ReplenishmentList({ parsed, apply, page }: { parsed: ReturnType<typeof parse>; apply: (values: Record<string, string>) => void; page: (value: number) => void }) {
  const { can } = useCan(), [creating, setCreating] = useState(false)
  const { view, filter, active, state } = parsed
  const loader = useCallback(async () => view === 'rules' ? { view, data: await listReplenishmentRules({ ...filter, active }) } as const : { view, data: await listReplenishmentRequests({ ...filter, state }) } as const, [view, filter, active, state])
  const result = useWarehouseQuery(loader), create = can('inventory.request.manage') && can('inventory.sku.view') && can('inventory.location.view')
  const empty = <EmptyState title="Belum ada catatan pengisian sesuai filter" hint="Atur batas minimum per barang dan gudang, lalu hitung ulang kebutuhan." />
  return <>
    {view === 'rules' ? <SelectField label="Keaktifan aturan" value={active ?? ''} onChange={(_, value) => apply({ active: value.value })}><option value="">Semua aturan</option><option value="true">Aktif</option><option value="false">Diarsipkan</option></SelectField> :
      <SelectField label="Status pengisian" value={state ?? ''} onChange={(_, value) => apply({ state: value.value })}><option value="">Semua status</option>{REPLENISHMENT_STATES.map(value => <option key={value} value={value}>{replenishmentLabels[value]}</option>)}</SelectField>}
    <div className="row wrap"><Button onClick={result.reload}>Muat ulang daftar pengisian</Button>{create && <Button variant="primary" onClick={() => setCreating(true)}>Tambah aturan minimum</Button>}<Link to={replenishmentLink({ view })}>Hapus filter pengisian</Link></div>
    {creating && create && <WarehouseReplenishmentRuleForm onClose={() => setCreating(false)} reload={() => { setCreating(false); result.reload() }} onDone={rule => apply({ ruleId: rule.id })} />}
    <WarehouseState {...result}>{data => <>
      {data.view === 'rules' ? <DataTable presentation="warehouse" rows={data.data.items} rowKey={row => row.rule.id} empty={empty} columns={[
        { key: 'name', header: 'Barang / lokasi', cell: row => <><Link to={replenishmentLink({ ruleId: row.rule.id })}>{row.sku.name} · {row.sku.code}</Link><br />{row.location.name ?? row.location.code}</> },
        { key: 'minimum', header: 'Minimum / target', cell: row => <><WarehouseQuantity value={row.rule.minimumBase} unit={row.rule.baseUnit} /> / <WarehouseQuantity value={row.rule.targetBase} unit={row.rule.baseUnit} /></> },
        { key: 'active', header: 'Aturan', cell: row => <>{row.rule.active ? 'Aktif' : 'Diarsipkan'} · Revisi {row.rule.revision}</> },
      ]} /> : <DataTable presentation="warehouse" rows={data.data.items} rowKey={row => row.request.id} empty={empty} columns={[
        { key: 'name', header: 'Barang / lokasi', cell: row => <><Link to={replenishmentLink({ requestId: row.request.id })}>{row.sku.name} · {row.sku.code}</Link><br />{row.location.name ?? row.location.code}</> },
        { key: 'quantity', header: 'Kebutuhan tercatat', cell: row => <WarehouseQuantity value={row.request.quantityBase} unit={row.request.baseUnit} /> },
        { key: 'state', header: 'Status', cell: row => <>{replenishmentLabels[row.request.state]}{row.request.acceptedAt && <p>Sudah dikonfirmasi</p>}</> },
      ]} />}
      <WarehousePagination page={data.data.page} size={data.data.size} total={data.data.totalElements} onChange={page} />
    </>}</WarehouseState>
  </>
}
