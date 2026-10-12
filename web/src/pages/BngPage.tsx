import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Pencil, Plus, RefreshCw, Trash2 } from 'lucide-react'
import { api, ApiError } from '@/api/client'
import { deleteNas, getRadiusEndpoint, listNas, NAS_REACHABILITY_HINT, NAS_REACHABILITY_LABEL, NAS_VENDOR_LABEL, NAS_VENDORS, type NasVendor, type NasView } from '@/api/bng'
import type { Area } from '@/api/types'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState } from '@/components/atoms'
import { CommandBar, PageHeader } from '@/components/molecules'
import { DataTable, type Column } from '@/components/organisms'
import { FilterBar, FilterSearch, FilterSelect } from '@/components/organisms/ResourceFilters'
import { useConfirm, useToast } from '@/system'
import { BngEditor } from './BngEditor'
import type { BngRadiusState } from './BngRadiusStatus'
import './network-workspaces.css'

export function BngPage() {
  const { can } = useCan()
  return can('bng.nas.view') ? <BrasWorkspace /> : <EmptyState title="Akses ditolak" hint="Anda tidak memiliki izin melihat BRAS." />
}

function BrasWorkspace() {
  const { can } = useCan(), toast = useToast(), confirm = useConfirm()
  const canManage = can('bng.nas.manage'), canViewAreas = can('iam.area.view')
  const [items, setItems] = useState<NasView[]>([]), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const [radius, setRadius] = useState<BngRadiusState>({ status: 'loading' })
  const radiusRequest = useRef(0)
  const [areas, setAreas] = useState<Area[]>([]), [areasFailed, setAreasFailed] = useState(false)
  const [editor, setEditor] = useState<{ nas: NasView | null } | null>(null)
  const [query, setQuery] = useState(''), [vendor, setVendor] = useState<NasVendor | ''>(''), [status, setStatus] = useState('')
  const reload = useCallback(async () => {
    setLoading(true); setError('')
    try { setItems(await listNas()) }
    catch (caught) { setError(caught instanceof ApiError ? caught.message : 'Daftar BRAS gagal dimuat.') }
    finally { setLoading(false) }
  }, [])
  const loadAreas = useCallback(async () => {
    if (!canViewAreas) return
    try { setAreas(await api.get<Area[]>('/api/areas')); setAreasFailed(false) }
    catch { setAreasFailed(true) }
  }, [canViewAreas])
  const loadRadius = useCallback(async () => {
    const request = ++radiusRequest.current
    setRadius({ status: 'loading' })
    try {
      const endpoint = await getRadiusEndpoint()
      if (request === radiusRequest.current) setRadius({ status: 'ready', endpoint })
    } catch (caught) {
      if (request === radiusRequest.current) setRadius({ status: 'error', message: caught instanceof ApiError && caught.status === 409
        ? caught.message : caught instanceof ApiError && caught.status === 403
          ? 'Anda tidak memiliki akses ke konfigurasi RADIUS.' : 'Informasi server RADIUS gagal dimuat.' })
    }
  }, [])
  useEffect(() => { void reload(); void loadRadius(); return () => { radiusRequest.current += 1 } }, [reload, loadRadius])
  useEffect(() => { void loadAreas() }, [loadAreas])
  const rows = useMemo(() => items.filter(nas => {
    if (vendor && nas.vendor !== vendor) return false
    if (status && nas.enabled !== (status === 'enabled')) return false
    return [nas.name, nas.address, nas.nasIdentifier, NAS_VENDOR_LABEL[nas.vendor]].some(value => value?.toLowerCase().includes(query.trim().toLowerCase()))
  }), [items, query, vendor, status])
  async function remove(nas: NasView) {
    if (!await confirm({ title: `Hapus BRAS ${nas.name}?`, message: 'BRAS yang dihapus tidak dapat dipulihkan.', confirmLabel: 'Hapus', danger: true })) return
    try { await deleteNas(nas.id); toast.success('BRAS dihapus.'); await reload() }
    catch (caught) { toast.error(caught instanceof ApiError ? caught.message : 'Gagal menghapus BRAS.') }
  }
  const columns: Column<NasView>[] = [
    { key: 'name', header: 'Nama', sortValue: nas => nas.name, cell: nas => nas.name,
      onCellClick: canManage ? nas => setEditor({ nas }) : undefined,
      inlineActions: canManage ? nas => [
        { key: 'edit', label: 'Ubah', icon: <Pencil size={16} />, onClick: () => setEditor({ nas }) },
        { key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => void remove(nas) },
      ] : undefined },
    { key: 'vendor', header: 'Vendor', sortValue: nas => NAS_VENDOR_LABEL[nas.vendor], cell: nas => NAS_VENDOR_LABEL[nas.vendor] },
    { key: 'address', header: 'Alamat manajemen', sortValue: nas => nas.address ?? '', cell: nas => nas.address || '—' },
    { key: 'identifier', header: 'NAS-Identifier', sortValue: nas => nas.nasIdentifier ?? '', cell: nas => nas.nasIdentifier || '—' },
    { key: 'reachability', header: 'Kendali sesi', sortValue: nas => NAS_REACHABILITY_LABEL[nas.reachability], cell: nas => <span title={NAS_REACHABILITY_HINT[nas.reachability]}>{NAS_REACHABILITY_LABEL[nas.reachability]}</span> },
    { key: 'secret', header: 'Shared secret', sortValue: nas => nas.hasCoaSecret ? 1 : 0, cell: nas => nas.hasCoaSecret ? 'Terisi' : 'Belum diisi' },
    { key: 'status', header: 'Status', sortValue: nas => nas.enabled ? 1 : 0, cell: nas => nas.enabled ? 'Aktif' : 'Nonaktif' },
  ]
  return <div className="network-page">
    <PageHeader title="BRAS & RADIUS" />
    <div className="network-panel">
      <div className="resource-list-controls">
        <CommandBar primary={canManage ? { key: 'create', label: 'Tambah BRAS', icon: <Plus size={16} />, onClick: () => setEditor({ nas: null }) } : undefined}
          actions={[{ key: 'refresh', label: 'Segarkan', icon: <RefreshCw size={16} />, disabled: loading, onClick: () => { void reload(); void loadRadius() } }]} />
        <FilterBar label="Filter BRAS" search={<FilterSearch label="Cari BRAS" value={query} onChange={setQuery} />}>
          <FilterSelect label="Vendor" value={vendor} onChange={value => setVendor(value as NasVendor | '')}>
            <option value="">Semua</option>{NAS_VENDORS.map(value => <option key={value} value={value}>{NAS_VENDOR_LABEL[value]}</option>)}
          </FilterSelect>
          <FilterSelect label="Status" value={status} onChange={setStatus}><option value="">Semua</option><option value="enabled">Aktif</option><option value="disabled">Nonaktif</option></FilterSelect>
        </FilterBar>
      </div>
      {error ? <div className="network-error" role="alert"><p>{error}</p><Button onClick={() => void reload()}>Coba lagi</Button></div> : <>
        <p className="network-count">{rows.length} dari {items.length} BRAS</p>
        <DataTable presentation="resource" columns={columns} rows={rows} rowKey={nas => nas.id} loading={loading} initialSort={{ key: 'name', dir: 'asc' }}
          empty={<EmptyState title={query || vendor || status ? 'Tidak ada BRAS yang cocok' : 'Belum ada BRAS'} hint={query || vendor || status ? 'Ubah pencarian atau filter.' : 'Tambahkan router untuk mengelola akses pelanggan.'} />} />
      </>}
    </div>
    {editor && <BngEditor nas={editor.nas} radius={radius} onReloadRadius={() => void loadRadius()} areas={areas} canViewAreas={canViewAreas} areasFailed={areasFailed} onReloadAreas={() => void loadAreas()}
      onClose={() => setEditor(null)} onSaved={() => { toast.success(editor.nas ? 'Perubahan BRAS disimpan.' : 'BRAS ditambahkan.'); setEditor(null); void reload() }} />}
  </div>
}
