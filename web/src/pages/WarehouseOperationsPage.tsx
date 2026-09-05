import { useCallback, useEffect, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  decideInventoryApproval,
  listCustody,
  listInventoryItems,
  listInventoryStock,
  listPendingApprovals,
  listReservations,
  listWarehouses,
  type InventoryApprovalRequest,
  type InventoryCustodyView,
  type InventoryItemView,
  type InventoryLocationView,
  type InventoryReservationView,
  type InventoryStockView,
} from '@/api/inventory'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, Spinner, TextareaField } from '@/components/atoms'
import { Tabs } from '@/components/molecules'
import { useToast } from '@/system'

type Tab = 'stock' | 'custody' | 'approvals'

const TABS: readonly { readonly key: Tab; readonly label: string; readonly permission: string }[] = [
  { key: 'stock', label: 'Stok gudang', permission: 'inventory.item.view' },
  { key: 'custody', label: 'Custody', permission: 'inventory.custody.view' },
  { key: 'approvals', label: 'Persetujuan', permission: 'inventory.approval.view' },
]

export function WarehouseOperationsPage() {
  const { can } = useCan()
  const toast = useToast()
  const visible = TABS.filter((tab) => can(tab.permission))
  const [tab, setTab] = useState<Tab>(visible[0]?.key ?? 'stock')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [warehouses, setWarehouses] = useState<readonly InventoryLocationView[]>([])
  const [items, setItems] = useState<readonly InventoryItemView[]>([])
  const [stock, setStock] = useState<readonly InventoryStockView[]>([])
  const [reservations, setReservations] = useState<readonly InventoryReservationView[]>([])
  const [custody, setCustody] = useState<readonly InventoryCustodyView[]>([])
  const [approvals, setApprovals] = useState<readonly InventoryApprovalRequest[]>([])

  const load = useCallback(async () => {
    try {
      setError(null)
      const [nextWarehouses, nextItems, nextStock, nextReservations, nextCustody, nextApprovals] = await Promise.all([
        can('inventory.location.view') ? listWarehouses() : Promise.resolve([]),
        can('inventory.item.view') ? listInventoryItems() : Promise.resolve([]),
        can('inventory.item.view') ? listInventoryStock() : Promise.resolve([]),
        can('inventory.custody.view') ? listReservations() : Promise.resolve([]),
        can('inventory.custody.view') ? listCustody() : Promise.resolve([]),
        can('inventory.approval.view') ? listPendingApprovals() : Promise.resolve([]),
      ])
      setWarehouses(nextWarehouses)
      setItems(nextItems)
      setStock(nextStock)
      setReservations(nextReservations)
      setCustody(nextCustody)
      setApprovals(nextApprovals)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Gagal memuat operasi gudang')
    } finally {
      setLoading(false)
    }
  }, [can])

  useEffect(() => { void load() }, [load])

  if (visible.length === 0) return <div className="card"><EmptyState title="Akses ditolak" hint="Kamu tidak punya izin operasi gudang." /></div>
  if (loading) return <div className="card" style={{ display: 'grid', placeItems: 'center', padding: '3rem' }}><Spinner /></div>
  if (error) return <div className="card stack" role="alert"><Text as="strong" className="error">Gagal memuat operasi gudang</Text><Text as="span" className="muted">{error}</Text><Button onClick={() => void load()}>Coba lagi</Button></div>

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div><Text as="h1" className="page-title" size={700} weight="semibold">Operasi gudang</Text><Text as="p" className="page-sub">Pantau stok, custody material, dan persetujuan yang menjadi tugasmu.</Text></div>
      <Tabs tabs={visible} active={tab} onChange={setTab} />
      {tab === 'stock' && <StockPanel warehouses={warehouses} items={items} stock={stock} />}
      {tab === 'custody' && <CustodyPanel custody={custody} reservations={reservations} />}
      {tab === 'approvals' && <ApprovalPanel approvals={approvals} canDecide={can('inventory.approval.decide')} onDone={() => { toast.success('Keputusan persetujuan disimpan'); void load() }} />}
    </div>
  )
}

function StockPanel({ warehouses, items, stock }: { warehouses: readonly InventoryLocationView[]; items: readonly InventoryItemView[]; stock: readonly InventoryStockView[] }) {
  return <div className="stack"><div className="stat-grid"><Metric label="Lokasi" value={warehouses.length} /><Metric label="Aset serial" value={items.length} /><Metric label="Posisi stok" value={stock.length} /></div><div className="card stack">{stock.length === 0 ? <EmptyState title="Belum ada stok" hint="Posisi stok akan muncul setelah penerimaan material." /> : stock.map((row) => <div className="spread wrap" key={`${row.skuId}:${row.locationId}`}><Text as="span" className="tnum">SKU {row.skuId}</Text><Text as="span">{Object.entries(row.quantities).map(([status, quantity]) => `${status}: ${quantity}`).join(' · ')}</Text></div>)}</div></div>
}

function CustodyPanel({ custody, reservations }: { custody: readonly InventoryCustodyView[]; reservations: readonly InventoryReservationView[] }) {
  return <div className="stack"><div className="stat-grid"><Metric label="Custody aktif" value={custody.length} /><Metric label="Reservasi" value={reservations.length} /></div><div className="card stack">{custody.length === 0 ? <EmptyState title="Tidak ada custody aktif" hint="Material yang diterbitkan akan terlihat di sini." /> : custody.map((row) => <div className="spread wrap" key={row.assetId}><Text as="span" className="tnum">Aset {row.assetId}</Text><Badge tone="accent">{row.status}</Badge><Text as="span" className="muted">{row.ownerKind}</Text></div>)}</div></div>
}

function ApprovalPanel({ approvals, canDecide, onDone }: { approvals: readonly InventoryApprovalRequest[]; canDecide: boolean; onDone: () => void }) {
  const toast = useToast()
  const [notes, setNotes] = useState<Record<string, string>>({})
  const [busy, setBusy] = useState<string | null>(null)
  const decide = async (approval: InventoryApprovalRequest, decision: 'APPROVE' | 'REJECT') => {
    if (decision === 'REJECT' && !(notes[approval.approvalId] ?? '').trim()) return
    setBusy(approval.approvalId)
    try {
      const reason = notes[approval.approvalId]?.trim() || null
      const operationKey = crypto.randomUUID()
      const payload = JSON.stringify({ decision, reason, movementId: null })
      const bytes = new TextEncoder().encode(payload)
      const digest = await crypto.subtle.digest('SHA-256', bytes)
      const operationHash = Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
      await decideInventoryApproval(approval.approvalId, decision, reason, operationKey, operationHash)
      onDone()
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Keputusan tidak dapat disimpan')
    } finally { setBusy(null) }
  }
  return <div className="stack">{approvals.length === 0 ? <div className="card"><EmptyState title="Tidak ada persetujuan" hint="Antrean hanya memuat permintaan yang boleh kamu tinjau." /></div> : approvals.map((approval) => <article className="card stack" key={approval.approvalId}><div className="spread wrap"><Text as="strong">{approval.type}</Text><Badge tone="warning">Menunggu keputusan</Badge></div><Text as="span" className="muted">Jumlah: {approval.amount} · berakhir {new Date(approval.expiresAt).toLocaleString('id-ID')}</Text><TextareaField label="Catatan keputusan" value={notes[approval.approvalId] ?? ''} onChange={(_, data) => setNotes((current) => ({ ...current, [approval.approvalId]: data.value }))} rows={2} disabled={!canDecide || busy === approval.approvalId} /><div className="row wrap"><Button disabled={!canDecide || busy === approval.approvalId} onClick={() => void decide(approval, 'APPROVE')}>Setujui</Button><Button variant="danger" disabled={!canDecide || busy === approval.approvalId || !(notes[approval.approvalId] ?? '').trim()} onClick={() => void decide(approval, 'REJECT')}>Tolak</Button></div></article>)}</div>
}

function Metric({ label, value }: { label: string; value: number }) { return <div className="card stack" style={{ gap: '0.25rem' }}><Text as="span" className="muted" size={200}>{label}</Text><Text as="strong" size={600}>{value}</Text></div> }
