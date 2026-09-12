import { useCallback, useEffect, useMemo, useState } from 'react'
import { ApiError } from '@/api/client'
import {
  listInventoryLocations,
  listItemMaster,
  listUserDirectory,
  type InventoryItemMasterView,
  type LocationView,
} from '@/api/inventory'
import type { User } from '@/api/types'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, SkeletonRows } from '@/components/atoms'
import { PageHeader, Tabs } from '@/components/molecules'
import { createNameBook, type WarehouseNameBook } from './WarehouseLabels'
import { WarehouseStockPanel } from './WarehouseStockPanel'
import { WarehouseMovementPanel } from './WarehouseMovementPanel'
import { WarehouseLedgerPanel } from './WarehouseLedgerPanel'
import { WarehouseApprovalPanel } from './WarehouseApprovalPanel'
import { WarehouseCountPanel } from './WarehouseCountPanel'
import { WarehouseMasterDataPanel } from './WarehouseMasterDataPanel'

/**
 * Data acuan untuk PEMILIH FORM dan panel master data gudang.
 *
 * Bukan lagi untuk menamai baris daftar: read model gudang membawa sendiri nama item, jenis
 * lokasi, dan nama orangnya, jadi tabel stok/ledger/persetujuan TIDAK bergantung pada isi
 * acuan ini sama sekali. Yang tersisa adalah daftar pilihan — item mana yang boleh diterima,
 * lokasi mana yang boleh dituju, siapa yang boleh dipilih jadi pemegang custody.
 *
 * Dimuat sekali di kulit halaman lalu diturunkan, bukan diambil ulang tiap panel: pindah tab
 * berarti tiga request master data lagi, dan di jaringan lapangan itu membuat formulir berkedip
 * kosong setiap kali petugas bolak-balik antara "Stok" dan "Mutasi".
 */
export interface WarehouseReference {
  readonly locations: readonly LocationView[]
  /** Termasuk item nonaktif — ledger lama tetap menunjuk item yang sudah dipensiunkan. */
  readonly items: readonly InventoryItemMasterView[]
  readonly users: readonly User[]
  readonly names: WarehouseNameBook
  readonly reload: () => Promise<void>
}

type Tab = 'stock' | 'movements' | 'ledger' | 'approvals' | 'counts' | 'master'

export function WarehouseOperationsPage() {
  const { can, canAny } = useCan()
  const [tab, setTab] = useState<Tab | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [locations, setLocations] = useState<readonly LocationView[]>([])
  const [items, setItems] = useState<readonly InventoryItemMasterView[]>([])
  const [users, setUsers] = useState<readonly User[]>([])

  const loadReference = useCallback(async () => {
    setError(null)
    try {
      const [nextLocations, nextItems] = await Promise.all([
        can('inventory.location.view') ? listInventoryLocations() : Promise.resolve([]),
        can('inventory.item.view') ? listItemMaster(true) : Promise.resolve([]),
      ])
      setLocations(nextLocations)
      setItems(nextItems)
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : 'Gagal memuat master data gudang')
    } finally {
      setLoading(false)
    }
    // Direktori pengguna DISENGAJA di luar Promise.all dan kegagalannya ditelan: petugas
    // gudang sering tidak punya `iam.user.view`, dan 403 di sini tidak boleh membuat seluruh
    // layar stok ikut kosong. Tanpa direktori yang hilang hanya isi pemilih "pemegang custody"
    // di formulir — daftar baca tetap bernama lengkap karena namanya datang dari read model.
    try {
      setUsers(await listUserDirectory())
    } catch {
      setUsers([])
    }
  }, [can])

  useEffect(() => {
    void loadReference()
  }, [loadReference])

  const names = useMemo(() => createNameBook(items, locations, users), [items, locations, users])
  const reference = useMemo<WarehouseReference>(
    () => ({ locations, items, users, names, reload: loadReference }),
    [locations, items, users, names, loadReference],
  )

  const visible = useMemo(
    () =>
      ALL_TABS.filter((entry) => canAny(...entry.permissions)),
    [canAny],
  )
  const active = tab && visible.some((entry) => entry.key === tab) ? tab : visible[0]?.key

  if (visible.length === 0) {
    return (
      <div className="card">
        <EmptyState title="Akses ditolak" hint="Kamu tidak punya izin operasi gudang." />
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <PageHeader
        title="Operasi gudang"
        subtitle="Master data, stok, mutasi, dan persetujuan material lapangan."
      />
      {error && (
        <div className="card stack" role="alert">
          <span className="error">{error}</span>
          <div>
            <Button onClick={() => void loadReference()}>Coba lagi</Button>
          </div>
        </div>
      )}
      <Tabs tabs={visible.map((entry) => ({ key: entry.key, label: entry.label }))} active={active as Tab} onChange={setTab} />
      {loading ? (
        <div className="card">
          <SkeletonRows rows={5} cols={5} />
        </div>
      ) : (
        <>
          {active === 'stock' && <WarehouseStockPanel reference={reference} />}
          {active === 'movements' && <WarehouseMovementPanel reference={reference} />}
          {active === 'ledger' && <WarehouseLedgerPanel reference={reference} />}
          {active === 'approvals' && <WarehouseApprovalPanel reference={reference} />}
          {active === 'counts' && <WarehouseCountPanel reference={reference} />}
          {active === 'master' && <WarehouseMasterDataPanel reference={reference} />}
        </>
      )}
    </div>
  )
}

/**
 * Satu tab tampil bila pengguna punya SALAH SATU izinnya. Dipakai `canAny`, bukan `can`
 * tunggal, karena tab "Mutasi" memang dihuni enam aksi dengan izin berbeda-beda: gudang
 * sungguhan memisahkan orang yang menggeser barang antar rak dari orang yang menyerahkannya
 * ke teknisi, dan menyembunyikan seluruh tab hanya karena satu izin kurang akan mengunci
 * petugas dari aksi yang sebenarnya boleh ia lakukan.
 */
const ALL_TABS: readonly { readonly key: Tab; readonly label: string; readonly permissions: readonly string[] }[] = [
  { key: 'stock', label: 'Stok', permissions: ['inventory.movement.view'] },
  {
    key: 'movements',
    label: 'Mutasi',
    permissions: [
      'inventory.restock.request',
      'inventory.restock.receive',
      'inventory.movement.transfer',
      'inventory.movement.issue',
      'inventory.movement.return',
      'inventory.movement.adjust',
    ],
  },
  { key: 'ledger', label: 'Riwayat mutasi', permissions: ['inventory.movement.view'] },
  { key: 'approvals', label: 'Persetujuan', permissions: ['inventory.approval.view'] },
  // `inventory.count.view` dulu tidak ada: daftar opname dijaga `.perform`, sehingga pemegang
  // `.approve` saja melihat tabnya tapi kena 403 di isinya. Ketiganya didaftarkan karena izin
  // di repo ini di-seed dari kode dan peran rakitan tangan TIDAK di-backfill — menghapus dua
  // yang lama justru akan menutup tab bagi petugas yang hari ini memakainya.
  { key: 'counts', label: 'Stock opname', permissions: ['inventory.count.view', 'inventory.count.perform', 'inventory.count.approve'] },
  { key: 'master', label: 'Master data', permissions: ['inventory.location.view', 'inventory.item.view'] },
]

/**
 * Izin yang membuka pintu halaman ini — DITURUNKAN dari [ALL_TABS], bukan diketik ulang.
 *
 * Penjaga rutenya dulu satu izin tetap, `inventory.item.view`. Bentuk itu mengunci keluar
 * petugas stok yang hanya punya `inventory.movement.view`: ia ditolak di depan pintu padahal
 * ada dua tab yang sebenarnya boleh ia buka. Diturunkan begini supaya kesalahan itu tidak bisa
 * lahir lagi — menambah tab baru otomatis melebarkan penjaganya, dan tidak ada daftar kedua
 * yang bisa ketinggalan.
 */
export const WAREHOUSE_VIEW_PERMISSIONS: string[] = [
  ...new Set(ALL_TABS.flatMap((entry) => entry.permissions)),
]
