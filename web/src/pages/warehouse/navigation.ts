export const WAREHOUSE_PAGES = [
  { path: 'catalog', label: 'Katalog & Lokasi', permissions: ['inventory.sku.view', 'inventory.location.view', 'inventory.receipt.view'] },
  { path: 'stock', label: 'Stok & Perangkat', permissions: ['inventory.item.view'] },
  { path: 'receipts', label: 'Penerimaan', permissions: ['inventory.receipt.view'] },
  { path: 'requests', label: 'Permintaan & Pengeluaran', permissions: ['inventory.request.view', 'inventory.issue.view'] },
  { path: 'replenishment', label: 'Pengisian Stok', permissions: ['inventory.request.view'] },
  { path: 'transfers', label: 'Transfer', permissions: ['inventory.transfer.view'] },
  { path: 'returns', label: 'Retur & Servis', permissions: ['inventory.return.view'] },
  { path: 'counts', label: 'Stock Opname', permissions: ['inventory.count.view'] },
  { path: 'approvals', label: 'Persetujuan Gudang', permissions: ['inventory.approval.view'] },
  { path: 'reports', label: 'Laporan Gudang', permissions: ['inventory.report.view'] },
  { path: 'settings', label: 'Setelan Gudang', permissions: ['inventory.approval.manage', 'inventory.provenance.view', 'inventory.location.manage'] },
] as const
export const WAREHOUSE_VIEW_PERMISSIONS: readonly string[] = [...new Set(WAREHOUSE_PAGES.flatMap(page => [...page.permissions]))]
