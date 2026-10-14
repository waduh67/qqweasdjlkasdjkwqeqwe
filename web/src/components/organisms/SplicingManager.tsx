import { useCallback, useEffect, useRef, useState } from 'react'
import { Checkbox, Spinner, Tab, TabList, Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow } from '@fluentui/react-components'
import { Plus, Pencil, Unplug, RefreshCw, Cable, Trash2 } from 'lucide-react'
import { api, ApiError } from '@/api/client'
import type { ClosureKind, SpliceWorkbenchView } from '@/api/network'
import { SPLICE_METHOD_LABEL } from '@/api/network'
import { useCan } from '@/auth/useCan'
import { Button, TextField } from '@/components/atoms'
import { CommandBar } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { connectionLabel, spliceOptions } from '@/utils/spliceOptions'
import { InteractiveSplicingBoard } from './InteractiveSplicingBoard'
import { BulkSpliceForm } from './BulkSpliceForm'
import { CableAttachmentForm, EditSpliceForm, cableAttachmentLabel } from './SpliceMaintenanceForms'

type ActiveForm = 'bulk' | 'attach' | 'editCable' | 'editConnection' | null

export function SplicingManager({ closureKind, closureId, onChanged }: {
  closureKind: ClosureKind; closureId: string; onChanged?: () => void
}) {
  const { can } = useCan(), toast = useToast(), confirm = useConfirm()
  const canView = can('network.splice.view'), canManage = can('network.splice.manage')
  const [data, setData] = useState<SpliceWorkbenchView | null>(null)
  const [loading, setLoading] = useState(true), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const [requestedTab, setTab] = useState(canManage ? 'workbench' : 'connections'), [query, setQuery] = useState(''), [selected, setSelected] = useState('')
  const tab = requestedTab === 'workbench' && !canManage ? 'connections' : requestedTab
  const [form, setForm] = useState<ActiveForm>(null)
  const request = useRef({ version: 0 })
  const load = useCallback(async () => {
    const version = ++request.current.version
    setLoading(true); setError('')
    try {
      const result = await api.get<SpliceWorkbenchView>(`/api/fiber-connections/workbench?closureKind=${closureKind}&closureId=${closureId}`)
      if (version === request.current.version) setData(result)
    } catch { if (version === request.current.version) setError('Sambungan gagal dimuat.') }
    finally { if (version === request.current.version) setLoading(false) }
  }, [closureKind, closureId])
  useEffect(() => {
    const pending = request.current
    setData(null); setSelected(''); setForm(null)
    if (canView) void load()
    else setLoading(false)
    return () => { pending.version++ }
  }, [canView, load])
  const saved = () => { setForm(null); setSelected(''); void load(); onChanged?.() }
  const connection = data?.connections.find(row => row.id === selected)
  const cable = data?.cables.find(row => row.cableId === selected)
  const remove = async () => {
    if (!canManage || busy || loading || error || !data) return
    const isConnection = tab === 'connections'
    if (isConnection ? !connection : !cable || cable.terminatesHere) return
    const label = isConnection ? `${connectionLabel(connection!.a)} ↔ ${connectionLabel(connection!.b)}` : cable!.code
    if (!(await confirm({ title: isConnection ? 'Lepas sambungan' : 'Hapus catatan kabel',
      message: isConnection ? `Lepas ${label}? Layanan pada sambungan ini akan terputus.` : `Hapus catatan ${label} dari ${data.closureCode}?`,
      confirmLabel: isConnection ? 'Lepas' : 'Hapus catatan', danger: true }))) return
    setBusy(true)
    try {
      await api.del(isConnection ? `/api/fiber-connections/${connection!.id}` : `/api/cables/${cable!.cableId}/attachments/${closureId}`)
      toast.success(isConnection ? 'Sambungan dilepas' : 'Catatan kabel dihapus'); saved()
    } catch (err) { toast.error(err instanceof ApiError ? err.message : 'Perubahan gagal disimpan.') }
    finally { setBusy(false) }
  }
  if (!canView) return null
  const disabled = loading || busy || !!error || !data
  const full = data?.spliceCapacity != null && data.spliceCount >= data.spliceCapacity
  const connections = data?.connections.filter(row => `${connectionLabel(row.a)} ${connectionLabel(row.b)} ${row.workOrderCode ?? ''}`.toLowerCase().includes(query.toLowerCase())) ?? []
  const cables = data?.cables.filter(row => `${row.code} ${row.name}`.toLowerCase().includes(query.toLowerCase())) ?? []
  const available = data ? spliceOptions(data).filter(option => option.cableId) : []
  return <section className="splice-manager" aria-label="Sambungan serat" aria-busy={loading}>
    <h3>Sambungan serat</h3>
    <TabList selectedValue={tab} onTabSelect={(_, next) => { setTab(String(next.value)); setSelected(''); setQuery('') }}>
      {canManage && <Tab value="workbench" disabled={busy}>Penyambungan</Tab>}
      <Tab value="connections" disabled={busy}>Daftar sambungan</Tab><Tab value="cables" disabled={busy}>Kabel di lokasi</Tab>
    </TabList>
    {canManage && data && <div hidden={tab !== 'workbench'} inert={tab !== 'workbench' ? true : undefined}>
      <InteractiveSplicingBoard key={`${closureKind}:${closureId}`} data={data} disabled={disabled}
        onSaved={saved} onBusyChange={setBusy} onRefresh={() => void load()} onBulk={() => setForm('bulk')} />
    </div>}
    {tab !== 'workbench' && <>
    <CommandBar primary={canManage ? {
      key: 'add', label: tab === 'connections' ? 'Tambah sambungan' : 'Catat kabel melintas', icon: <Plus size={16} />,
      disabled: disabled || tab === 'connections' && full, onClick: () => tab === 'connections' ? setTab('workbench') : setForm('attach'),
    } : undefined} actions={[
      ...(canManage ? tab === 'connections' ? [
        { key: 'edit', label: 'Ubah', icon: <Pencil size={16} />, disabled: disabled || !connection, onClick: () => setForm('editConnection') },
        { key: 'remove', label: 'Lepas', icon: <Unplug size={16} />, disabled: disabled || !connection, onClick: () => void remove() },
        { key: 'bulk', label: 'Sambung core 1:1', icon: <Cable size={16} />, disabled: disabled || full, onClick: () => setForm('bulk') },
      ] : [
        { key: 'edit', label: 'Ubah catatan', icon: <Pencil size={16} />, disabled: disabled || !cable || cable.terminatesHere, onClick: () => setForm('editCable') },
        { key: 'remove', label: 'Hapus catatan', icon: <Trash2 size={16} />, disabled: disabled || !cable || cable.terminatesHere, onClick: () => void remove() },
      ] : []),
      { key: 'refresh', label: 'Muat ulang', icon: <RefreshCw size={16} />, disabled: loading || busy, onClick: () => void load() },
    ]} />
    <div className="splice-list-filter"><TextField aria-label="Cari sambungan atau kabel" placeholder={tab === 'connections' ? 'Cari sambungan' : 'Cari kabel'}
      value={query} onChange={(_, next) => setQuery(next.value)} /><span className="muted">{tab === 'connections' ? `${connections.length} sambungan` : `${cables.length} kabel`}</span>
      {full && <span>Kapasitas penuh</span>}
    </div>
    </>}
    {error && <div role="alert" className="splice-load-error">{error}<Button onClick={() => void load()}>Coba lagi</Button></div>}
    {loading && !data ? <Spinner size="small" label="Memuat sambungan" /> : data && tab !== 'workbench' && <div className="table-wrap">
      {tab === 'connections' ? <Table aria-label="Daftar sambungan"><TableHeader><TableRow>
        {canManage && <TableHeaderCell className="splice-select-column" />}
        <TableHeaderCell>Titik pertama</TableHeaderCell><TableHeaderCell>Titik kedua</TableHeaderCell><TableHeaderCell>Metode</TableHeaderCell>
        <TableHeaderCell>Redaman</TableHeaderCell><TableHeaderCell>Work order</TableHeaderCell><TableHeaderCell>Pelaksana</TableHeaderCell>
      </TableRow></TableHeader><TableBody>{connections.map(row => <TableRow key={row.id}>
        {canManage && <TableCell><Checkbox aria-label={`Pilih ${connectionLabel(row.a)} ke ${connectionLabel(row.b)}`} checked={selected === row.id}
          onChange={(_, next) => setSelected(next.checked ? row.id : '')} /></TableCell>}
        <TableCell title={connectionLabel(row.a)}>{connectionLabel(row.a)}</TableCell><TableCell title={connectionLabel(row.b)}>{connectionLabel(row.b)}</TableCell>
        <TableCell>{SPLICE_METHOD_LABEL[row.method]}</TableCell><TableCell>{row.lossDb == null ? 'Belum diukur' : `${row.lossDb.toFixed(2)} dB`}</TableCell>
        <TableCell>{row.workOrderCode ?? '—'}</TableCell><TableCell title={row.splicedById ? new Date(row.splicedAt).toLocaleString('id-ID') : undefined}>{row.splicedByName ?? '—'}</TableCell>
      </TableRow>)}</TableBody></Table> : <Table aria-label="Kabel di lokasi"><TableHeader><TableRow>
        {canManage && <TableHeaderCell className="splice-select-column" />}<TableHeaderCell>Kode kabel</TableHeaderCell><TableHeaderCell>Nama</TableHeaderCell>
        <TableHeaderCell>Kondisi</TableHeaderCell><TableHeaderCell>Jumlah core</TableHeaderCell><TableHeaderCell>Tersedia di lokasi ini</TableHeaderCell>
      </TableRow></TableHeader><TableBody>{cables.map(row => <TableRow key={row.cableId}>
        {canManage && <TableCell><Checkbox aria-label={`Pilih ${row.code}`} checked={selected === row.cableId} onChange={(_, next) => setSelected(next.checked ? row.cableId : '')} /></TableCell>}
        <TableCell>{row.code}</TableCell><TableCell>{row.name}</TableCell><TableCell>{cableAttachmentLabel(row.role)}</TableCell>
        <TableCell>{row.coreCount}</TableCell><TableCell>{available.filter(option => option.cableId === row.cableId).length}</TableCell>
      </TableRow>)}</TableBody></Table>}
      {!(tab === 'connections' ? connections : cables).length && <p className="splice-empty">{query ? 'Tidak ada hasil yang cocok.' : tab === 'connections' ? 'Belum ada sambungan.' : 'Belum ada kabel di lokasi ini.'}</p>}
    </div>}
    {canManage && data && <>
      {form === 'bulk' && <BulkSpliceForm data={data} onClose={() => setForm(null)} onSaved={saved} />}
      {(form === 'attach' || form === 'editCable' && cable) && <CableAttachmentForm data={data} cable={form === 'editCable' ? cable : undefined} onClose={() => setForm(null)} onSaved={saved} />}
      {form === 'editConnection' && connection && <EditSpliceForm row={connection} onClose={() => setForm(null)} onSaved={saved} />}
    </>}
  </section>
}
