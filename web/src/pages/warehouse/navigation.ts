import { Library, Boxes, PackagePlus, ClipboardList, PackageOpen, ArrowLeftRight, Wrench, ClipboardCheck, BadgeCheck, ChartNoAxesCombined, History, Settings2 } from 'lucide-react'

export const WAREHOUSE_PAGES = [
  { path: 'catalog', icon: Library, label: 'Katalog & Lokasi', permissions: ['inventory.sku.view', 'inventory.location.view', 'inventory.receipt.view'] },
  { path: 'stock', icon: Boxes, label: 'Stok & Perangkat', permissions: ['inventory.item.view'] },
  { path: 'receipts', icon: PackagePlus, label: 'Penerimaan', permissions: ['inventory.receipt.view'] },
  { path: 'requests', icon: ClipboardList, label: 'Permintaan & Pengeluaran', permissions: ['inventory.request.view', 'inventory.issue.view'] },
  { path: 'replenishment', icon: PackageOpen, label: 'Pengisian Stok', permissions: ['inventory.request.view'] },
  { path: 'transfers', icon: ArrowLeftRight, label: 'Transfer', permissions: ['inventory.transfer.view'] },
  { path: 'returns', icon: Wrench, label: 'Retur & Servis', permissions: ['inventory.return.view'] },
  { path: 'counts', icon: ClipboardCheck, label: 'Stock Opname', permissions: ['inventory.count.view'] },
  { path: 'approvals', icon: BadgeCheck, label: 'Persetujuan Gudang', permissions: ['inventory.approval.view'] },
  { path: 'reports', icon: ChartNoAxesCombined, label: 'Laporan Gudang', permissions: ['inventory.report.view'] },
  { path: 'provenance', icon: History, label: 'Rekonsiliasi Gudang Lama', permissions: ['inventory.provenance.manage'] },
  { path: 'settings', icon: Settings2, label: 'Setelan Gudang', permissions: ['inventory.approval.view', 'inventory.approval.manage', 'inventory.provenance.view', 'inventory.location.manage'] },
] as const
export const WAREHOUSE_VIEW_PERMISSIONS: readonly string[] = [...new Set(WAREHOUSE_PAGES.flatMap(page => [...page.permissions]))]

export const REFERENCE_WAREHOUSE_PAGES = [
  { path: 'catalog', label: 'Barang, Gudang & Pemasok', permissions: ['warehouse.catalog.view'] },
  { path: 'stock', label: 'Stok & Riwayat', permissions: ['warehouse.stock.view'] },
  { path: 'receipts', label: 'Penerimaan', permissions: ['warehouse.stock.view'] },
  { path: 'transfers', label: 'Transfer', permissions: ['warehouse.stock.view'] },
  { path: 'requests', label: 'Permintaan Material', permissions: ['warehouse.request.view', 'warehouse.request.own'] },
  { path: 'settings', label: 'Setelan Gudang', permissions: ['warehouse.request.view'], ownerOnly: true },
] as const

export function warehousePages(workflow: 'LEGACY' | 'DRAINING' | 'REFERENCE', owner = false) {
  return workflow === 'REFERENCE' ? REFERENCE_WAREHOUSE_PAGES.filter(page => !('ownerOnly' in page) || owner) : WAREHOUSE_PAGES
}
