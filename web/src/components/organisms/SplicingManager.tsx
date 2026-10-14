import { useCallback, useEffect, useRef, useState } from 'react'
import { Checkbox, Spinner, Table, TableBody, TableCell, TableHeader, TableHeaderCell, TableRow } from '@fluentui/react-components'
import { Plus, Pencil, Unplug, RefreshCw, Trash2 } from 'lucide-react'
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
  const [connectionQuery, setConnectionQuery] = useState(''), [cableQuery, setCableQuery] = useState('')
  const [selectedConnection, setSelectedConnection] = useState(''), [selectedCable, setSelectedCable] = useState('')
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
    setData(null); setSelectedConnection(''); setSelectedCable(''); setConnectionQuery(''); setCableQuery(''); setForm(null)
    if (canView) void load()
    else setLoading(false)
    return () => { pending.version++ }
  }, [canView, load])
  const saved = () => { setForm(null); setSelectedConnection(''); setSelectedCable(''); void load(); onChanged?.() }
  const connection = data?.connections.find(row => row.id === selectedConnection)
  const cable = data?.cables.find(row => row.cableId === selectedCable)
  const remove = async (kind: 'connection' | 'cable') => {
    if (!canManage || busy || loading || error || !data) return
    const isConnection = kind === 'connection'
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
  const connections = data?.connections.filter(row => `${connectionLabel(row.a)} ${connectionLabel(row.b)} ${row.workOrderCode ?? ''}`.toLowerCase().includes(connectionQuery.trim().toLowerCase())) ?? []
  const cables = data?.cables.filter(row => `${row.code} ${row.name}`.toLowerCase().includes(cableQuery.trim().toLowerCase())) ?? []
  const available = data ? spliceOptions(data).filter(option => option.cableId) : []
  return <section className="splice-manager" aria-label="Sambungan serat" aria-busy={loading}>
    <h3>Sambungan serat</h3>
    {!canManage && <CommandBar actions={[{ key: 'refresh', label: 'Muat ulang', icon: <RefreshCw size={16} />, disabled: loading || busy, onClick: () => void load() }]} />}
    {error && <div role="alert" className="splice-load-error">{error}<Button onClick={() => void load()}>Coba lagi</Button></div>}
    {loading && !data ? <Spinner size="small" label="Memuat sambungan" /> : data && <>
      {canManage && <InteractiveSplicingBoard key={`${closureKind}:${closureId}`} data={data} disabled={disabled}
        onSaved={saved} onBusyChange={setBusy} onRefresh={() => void load()} onBulk={() => setForm('bulk')} />}
      <section className="splice-list-section" aria-label="Daftar sambungan">
        <h4>Daftar sambungan</h4>
        {canManage && <CommandBar actions={[
          { key: 'edit', label: 'Ubah', icon: <Pencil size={16} />, disabled: disabled || !connection, onClick: () => setForm('editConnection'), pinned: true },
          { key: 'remove', label: 'Lepas', icon: <Unplug size={16} />, disabled: disabled || !connection, onClick: () => void remove('connection'), pinned: true },
        ]} />}
        <div className="splice-list-filter"><TextField aria-label="Cari sambungan" placeholder="Cari sambungan" value={connectionQuery}
          onChange={(_, next) => { setConnectionQuery(next.value); setSelectedConnection('') }} /><span className="muted">{connections.length} sambungan</span></div>
        <div className="table-wrap" role="region" aria-label="Tabel sambungan" tabIndex={0}>
      <Table aria-label="Daftar sambungan"><TableHeader><TableRow>
        {canManage && <TableHeaderCell className="splice-select-column" />}
        <TableHeaderCell>Titik pertama</TableHeaderCell><TableHeaderCell>Titik kedua</TableHeaderCell><TableHeaderCell>Metode</TableHeaderCell>
        <TableHeaderCell>Redaman</TableHeaderCell><TableHeaderCell>Work order</TableHeaderCell><TableHeaderCell>Pelaksana</TableHeaderCell>
      </TableRow></TableHeader><TableBody>{connections.map(row => <TableRow key={row.id}>
        {canManage && <TableCell><Checkbox aria-label={`Pilih ${connectionLabel(row.a)} ke ${connectionLabel(row.b)}`} checked={selectedConnection === row.id}
          onChange={(_, next) => setSelectedConnection(next.checked ? row.id : '')} /></TableCell>}
        <TableCell title={connectionLabel(row.a)}>{connectionLabel(row.a)}</TableCell><TableCell title={connectionLabel(row.b)}>{connectionLabel(row.b)}</TableCell>
        <TableCell>{SPLICE_METHOD_LABEL[row.method]}</TableCell><TableCell>{row.lossDb == null ? 'Belum diukur' : `${row.lossDb.toFixed(2)} dB`}</TableCell>
        <TableCell>{row.workOrderCode ?? '—'}</TableCell><TableCell title={row.splicedById ? new Date(row.splicedAt).toLocaleString('id-ID') : undefined}>{row.splicedByName ?? '—'}</TableCell>
      </TableRow>)}</TableBody></Table>
        </div>
        {!connections.length && <p className="splice-empty">{connectionQuery ? 'Tidak ada hasil yang cocok.' : 'Belum ada sambungan.'}</p>}
      </section>
      <section className="splice-list-section" aria-label="Kabel di lokasi">
        <h4>Kabel di lokasi</h4>
        {canManage && <CommandBar primary={{ key: 'add', label: 'Catat kabel melintas', icon: <Plus size={16} />, disabled, onClick: () => setForm('attach') }} actions={[
          { key: 'edit', label: 'Ubah catatan', icon: <Pencil size={16} />, disabled: disabled || !cable || cable.terminatesHere, onClick: () => setForm('editCable') },
          { key: 'remove', label: 'Hapus catatan', icon: <Trash2 size={16} />, disabled: disabled || !cable || cable.terminatesHere, onClick: () => void remove('cable') },
        ]} />}
        <div className="splice-list-filter"><TextField aria-label="Cari kabel" placeholder="Cari kabel" value={cableQuery}
          onChange={(_, next) => { setCableQuery(next.value); setSelectedCable('') }} /><span className="muted">{cables.length} kabel</span></div>
        <div className="table-wrap" role="region" aria-label="Tabel kabel" tabIndex={0}>
      <Table aria-label="Kabel di lokasi"><TableHeader><TableRow>
        {canManage && <TableHeaderCell className="splice-select-column" />}<TableHeaderCell>Kode kabel</TableHeaderCell><TableHeaderCell>Nama</TableHeaderCell>
        <TableHeaderCell>Kondisi</TableHeaderCell><TableHeaderCell>Jumlah core</TableHeaderCell><TableHeaderCell>Tersedia di lokasi ini</TableHeaderCell>
      </TableRow></TableHeader><TableBody>{cables.map(row => <TableRow key={row.cableId}>
        {canManage && <TableCell><Checkbox aria-label={`Pilih ${row.code}`} checked={selectedCable === row.cableId} onChange={(_, next) => setSelectedCable(next.checked ? row.cableId : '')} /></TableCell>}
        <TableCell>{row.code}</TableCell><TableCell>{row.name}</TableCell><TableCell>{cableAttachmentLabel(row.role)}</TableCell>
        <TableCell>{row.coreCount}</TableCell><TableCell>{available.filter(option => option.cableId === row.cableId).length}</TableCell>
      </TableRow>)}</TableBody></Table>
        </div>
        {!cables.length && <p className="splice-empty">{cableQuery ? 'Tidak ada hasil yang cocok.' : 'Belum ada kabel di lokasi ini.'}</p>}
      </section>
    </>}
    {canManage && data && <>
      {form === 'bulk' && <BulkSpliceForm data={data} onClose={() => setForm(null)} onSaved={saved} />}
      {(form === 'attach' || form === 'editCable' && cable) && <CableAttachmentForm data={data} cable={form === 'editCable' ? cable : undefined} onClose={() => setForm(null)} onSaved={saved} />}
      {form === 'editConnection' && connection && <EditSpliceForm row={connection} onClose={() => setForm(null)} onSaved={saved} />}
    </>}
  </section>
}
