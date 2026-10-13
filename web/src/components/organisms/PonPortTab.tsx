import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Checkbox } from '@fluentui/react-components'
import { Plus, RefreshCw, Search, Trash2 } from 'lucide-react'
import { api } from '@/api/client'
import type { OltView, PonPortView } from '@/api/network'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, Spinner, TextField } from '@/components/atoms'
import { CommandBar } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { CreationSummary } from './CreationReview'
import { DataTable, type Column } from './DataTable'
import { FilterBar, FilterSearch } from './ResourceFilters'
import { ResourceForm } from './ResourceForm'
import { ResourceWizard } from './ResourceWizard'
import '../../pages/network-workspaces.css'

export interface PonDiscovery {
  oltId: string; readAt: string
  ports: { label: string; onuCount: number | null; fromInterface: boolean }[]
  warnings: string[]
}
interface ImportResult { created: number; existing: number }
const statusLabels = { ACTIVE: 'Aktif', INACTIVE: 'Tidak aktif', MAINTENANCE: 'Perawatan', PLANNED: 'Rencana', ABANDONED: 'Ditinggalkan' }

export function PonPortTab({ olt, canUpdate, canDrill, onChanged, renderDetail }: {
  olt: OltView; canUpdate: boolean; canDrill: boolean; onChanged: () => void
  renderDetail: (id: string) => ReactNode
}) {
  const toast = useToast(), confirm = useConfirm(), { can } = useCan()
  const [ports, setPorts] = useState<PonPortView[]>([]), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const [panel, setPanel] = useState<'manual' | 'discover' | null>(null), [detail, setDetail] = useState<PonPortView | null>(null)
  const [query, setQuery] = useState('')
  const canDetect = canUpdate && can('monitoring.provisioning.view') && olt.vendor === 'HSGQ'
  const load = useCallback(async () => {
    setLoading(true); setError('')
    try { setPorts(await api.get<PonPortView[]>(`/api/olts/${olt.id}/pon-ports`)) }
    catch { setError('Port PON gagal dimuat.') }
    finally { setLoading(false) }
  }, [olt.id])
  useEffect(() => { void load() }, [load])
  const saved = (notice: string) => { setPanel(null); toast.success(notice); void load(); onChanged() }
  async function remove(port: PonPortView) {
    if (!await confirm({ title: `Hapus ${port.label}?`, message: 'Port ini akan dihapus dari OLT.', confirmLabel: 'Hapus', danger: true })) return
    try { await api.del(`/api/olts/pon-ports/${port.id}`); toast.success('Port PON dihapus.'); void load(); onChanged() }
    catch (caught) { toast.error(caught instanceof Error ? caught.message : 'Port PON gagal dihapus.') }
  }
  const columns: Column<PonPortView>[] = [
    { key: 'label', header: 'Port', cell: port => port.label, onCellClick: canDrill ? port => setDetail(port) : undefined },
    { key: 'status', header: 'Status', cell: port => statusLabels[port.status] },
    { key: 'odcs', header: 'ODC', cell: port => String(port.odcCount) },
    ...(canUpdate ? [{ key: 'actions', header: 'Tindakan', cell: (port: PonPortView) => <Button variant="subtle" aria-label={`Hapus ${port.label}`}
      disabled={port.odcCount > 0} onClick={() => void remove(port)}><Trash2 size={14} /></Button> }] : []),
  ]
  return <div className="network-panel">
    <div className="resource-list-controls">
      <CommandBar primary={canUpdate ? canDetect
        ? { key: 'detect', label: 'Deteksi port', icon: <Search size={16} />, onClick: () => setPanel('discover') }
        : { key: 'manual', label: 'Tambah port', icon: <Plus size={16} />, onClick: () => setPanel('manual') } : undefined}
        actions={[
          ...(canDetect ? [{ key: 'manual', label: 'Tambah manual', icon: <Plus size={16} />, onClick: () => setPanel('manual') }] : []),
          { key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, disabled: loading, onClick: () => void load() },
        ]} />
      <FilterBar label="Filter port PON" search={<FilterSearch label="Cari port PON" value={query} onChange={setQuery} />}>{null}</FilterBar>
    </div>
    {error ? <div role="alert"><p>{error}</p><Button onClick={() => void load()}>Coba lagi</Button></div> :
      <DataTable presentation="resource" columns={columns} rows={ports.filter(port => port.label.toLowerCase().includes(query.toLowerCase()))} rowKey={port => port.id} loading={loading}
        empty={<EmptyState title={query ? 'Tidak ada port yang cocok' : 'Belum ada port PON'} hint={query ? 'Ubah pencarian.' : canDetect ? 'Deteksi dari OLT atau tambahkan port secara manual.' : canUpdate ? 'Tambahkan port sesuai label pada OLT.' : undefined} />} />}
    {panel === 'manual' && <ManualPonPort oltId={olt.id} onClose={() => setPanel(null)} onSaved={saved} />}
    {panel === 'discover' && <PonDiscoveryPanel oltId={olt.id} onClose={() => setPanel(null)} onSaved={saved} />}
    {detail && <ResourceForm title={detail.label} readOnly onClose={() => setDetail(null)} onBack={() => {}}>{renderDetail(detail.id)}</ResourceForm>}
  </div>
}

function ManualPonPort({ oltId, onClose, onSaved }: { oltId: string; onClose: () => void; onSaved: (notice: string) => void }) {
  const [label, setLabel] = useState('')
  return <ResourceWizard title="Tambah port PON" dirty={Boolean(label)} onClose={onClose}
    steps={[{ key: 'basics', label: 'Dasar', validate: () => /^(PON\d{1,3}|\d{1,3}(\/\d{1,3}){1,3})$/i.test(label.trim()) ? null : 'Gunakan label port seperti PON1 atau 0/1/1.',
      content: <TextField label="Label port" required maxLength={30} value={label} onChange={event => setLabel(event.target.value)} placeholder="PON1" hint="Samakan dengan label port pada OLT." /> }]}
    summary={<CreationSummary rows={[[ 'Label port', label.trim() ]]} />}
    onSave={async () => { await api.post(`/api/olts/${oltId}/pon-ports`, { label: label.trim() }); onSaved('Port PON ditambahkan.') }} />
}

export function PonDiscoveryPanel({ oltId, onClose, onSaved }: { oltId: string; onClose: () => void; onSaved: (notice: string) => void }) {
  const [data, setData] = useState<PonDiscovery | null>(null), [existing, setExisting] = useState<Set<string>>(new Set())
  const [selected, setSelected] = useState<Set<string>>(new Set()), [loading, setLoading] = useState(true), [error, setError] = useState(''), [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let active = true
    setLoading(true); setError(''); setData(null); setSelected(new Set())
    void Promise.all([api.get<PonDiscovery>(`/api/monitoring/olts/${oltId}/pon-ports`), api.get<PonPortView[]>(`/api/olts/${oltId}/pon-ports`)])
      .then(([snapshot, ports]) => {
        if (!active) return
        const known = new Set(ports.map(port => port.label))
        setExisting(known); setData(snapshot); setSelected(new Set(snapshot.ports.filter(port => !known.has(port.label)).map(port => port.label)))
      }).catch(caught => { if (active) setError(caught instanceof Error ? caught.message : 'Deteksi port gagal. Coba lagi.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [oltId, attempt])
  const available = data?.ports.filter(port => !existing.has(port.label)) ?? []
  const columns: Column<PonDiscovery['ports'][number]>[] = [
    { key: 'select', minWidth: 40, header: <Checkbox aria-label="Pilih semua port baru" disabled={!available.length} checked={selected.size > 0 && selected.size === available.length ? true : selected.size > 0 ? 'mixed' : false}
      onChange={(_, event) => setSelected(new Set(event.checked ? available.map(port => port.label) : []))} />,
      cell: port => <Checkbox aria-label={`Pilih ${port.label}`} disabled={existing.has(port.label)} checked={selected.has(port.label)}
        onChange={(_, event) => setSelected(current => { const next = new Set(current); if (event.checked) next.add(port.label); else next.delete(port.label); return next })} /> },
    { key: 'label', header: 'Port', cell: port => port.label },
    { key: 'onus', header: 'ONU terdeteksi', cell: port => port.onuCount === null ? '—' : String(port.onuCount) },
    { key: 'status', header: 'Status', cell: port => existing.has(port.label) ? 'Sudah terdaftar' : 'Baru' },
  ]
  return <ResourceWizard title="Deteksi port PON" dirty={() => false} onClose={onClose}
    steps={[{ key: 'ports', label: 'Pilih port', validate: () => loading ? 'Deteksi port masih berlangsung.' : !data ? 'Jalankan deteksi port terlebih dahulu.' : selected.size === 0 ? 'Pilih port yang ingin ditambahkan.' : null,
      content: <>
        {loading ? <p role="status"><span aria-hidden="true"><Spinner /></span> Mendeteksi port PON…</p> : error ? <div role="alert"><p>{error}</p><Button onClick={() => setAttempt(value => value + 1)}>Coba lagi</Button></div> : <>
          <div><Button onClick={() => setAttempt(value => value + 1)}>Deteksi ulang</Button></div>
          {data?.warnings.map(warning => <p className="network-note" key={warning}>{warning}</p>)}
          <DataTable presentation="resource" columns={columns} rows={data?.ports ?? []} rowKey={port => port.label}
            empty={<EmptyState title="Belum ada port terdeteksi" hint="Periksa pengaturan SNMP atau tambahkan port secara manual." />} />
          <p className="network-note">{selected.size} port dipilih</p>
        </>}
      </> }]}
    summary={<CreationSummary rows={[[ 'Port yang ditambahkan', [...selected].join(', ') ], [ 'Jumlah port', String(selected.size) ]]} />}
    onSave={async () => {
      if (!data || selected.size === 0) throw new Error('Pilih port yang ingin ditambahkan.')
      const result = await api.post<ImportResult>(`/api/olts/${oltId}/pon-ports/import`, { labels: [...selected] })
      onSaved(result.created > 0 ? `${result.created} port PON ditambahkan.` : 'Port yang dipilih sudah terdaftar.')
    }} />
}
