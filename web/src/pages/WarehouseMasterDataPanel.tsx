import { useCallback, useEffect, useMemo, useState } from 'react'
import { Switch, Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  createInventoryLocation,
  createItemMaster,
  getMaterialTemplate,
  saveMaterialTemplate,
  setItemMasterActive,
  updateInventoryLocation,
  updateItemMaster,
  type InventoryItemCategory,
  type InventoryItemMasterView,
  type InventoryUnit,
  type LocationKind,
  type LocationView,
} from '@/api/inventory'
import { useCan } from '@/auth/useCan'
import { Badge, Button, EmptyState, SelectField, TextField, Toolbar } from '@/components/atoms'
import { Modal, SearchInput } from '@/components/molecules'
import { DataTable, type Column, type RowAction } from '@/components/organisms'
import { useToast } from '@/system'
import type { WarehouseReference } from './WarehouseOperationsPage'
import { ITEM_CATEGORY_LABEL, LOCATION_KIND_LABEL, UNIT_LABEL, WORK_ORDER_TYPES } from './WarehouseLabels'

const LOCATION_KINDS = Object.keys(LOCATION_KIND_LABEL) as LocationKind[]
const CATEGORIES = Object.keys(ITEM_CATEGORY_LABEL) as InventoryItemCategory[]
const UNITS = Object.keys(UNIT_LABEL) as InventoryUnit[]

type Section = 'locations' | 'items' | 'templates'

export function WarehouseMasterDataPanel({ reference }: { reference: WarehouseReference }) {
  const { can } = useCan()
  const [section, setSection] = useState<Section>(can('inventory.location.view') ? 'locations' : 'items')

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="row wrap" role="group" aria-label="Bagian master data">
        {can('inventory.location.view') && (
          <Button variant={section === 'locations' ? 'primary' : 'default'} onClick={() => setSection('locations')}>
            Lokasi &amp; bin
          </Button>
        )}
        {can('inventory.item.view') && (
          <Button variant={section === 'items' ? 'primary' : 'default'} onClick={() => setSection('items')}>
            Item master
          </Button>
        )}
        {can('inventory.item.view') && (
          <Button variant={section === 'templates' ? 'primary' : 'default'} onClick={() => setSection('templates')}>
            Template material WO
          </Button>
        )}
      </div>
      {section === 'locations' && can('inventory.location.view') && <LocationSection reference={reference} />}
      {section === 'items' && can('inventory.item.view') && <ItemSection reference={reference} />}
      {section === 'templates' && can('inventory.item.view') && <MaterialTemplateSection reference={reference} />}
    </div>
  )
}

// ————————————————————————————— lokasi —————————————————————————————

function LocationSection({ reference }: { reference: WarehouseReference }) {
  const { can } = useCan()
  const toast = useToast()
  const canManage = can('inventory.location.manage')
  const [query, setQuery] = useState('')
  const [editing, setEditing] = useState<LocationView | 'new' | null>(null)

  const rows = useMemo(() => {
    const term = query.trim().toLowerCase()
    return reference.locations.filter((location) => !term || location.code.toLowerCase().includes(term))
  }, [reference.locations, query])

  const columns: Column<LocationView>[] = [
    { key: 'code', header: 'Kode', sortValue: (row) => row.code, cell: (row) => <Text as="span">{row.code}</Text> },
    {
      key: 'kind',
      header: 'Jenis',
      sortValue: (row) => row.kind,
      cell: (row) => <Badge tone="accent">{LOCATION_KIND_LABEL[row.kind]}</Badge>,
    },
    {
      key: 'parent',
      header: 'Induk',
      sortValue: (row) => row.parentId,
      cell: (row) => (
        <Text as="span" className="muted">
          {row.parentId ? reference.names.location(row.parentId) : '—'}
        </Text>
      ),
    },
  ]

  const rowActions = (row: LocationView): RowAction[] => [
    { key: 'edit', label: 'Ubah', onClick: () => setEditing(row) },
  ]

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari kode lokasi…" />
        {canManage && (
          <Button variant="primary" onClick={() => setEditing('new')}>Tambah lokasi</Button>
        )}
      </Toolbar>
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(row) => row.id}
        presentation="resource"
        initialSort={{ key: 'code', dir: 'asc' }}
        rowActions={canManage ? rowActions : undefined}
        empty={<EmptyState title="Belum ada lokasi" hint="Buat gudang induk dulu, lalu rak/bin di bawahnya." />}
      />
      {editing && (
        <LocationForm
          reference={reference}
          location={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null)
            await reference.reload()
            toast.success('Lokasi disimpan')
          }}
        />
      )}
    </div>
  )
}

function LocationForm({
  reference,
  location,
  onClose,
  onSaved,
}: {
  reference: WarehouseReference
  location: LocationView | null
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const toast = useToast()
  const [code, setCode] = useState(location?.code ?? '')
  const [kind, setKind] = useState<LocationKind>(location?.kind ?? 'WAREHOUSE')
  const [parentId, setParentId] = useState(location?.parentId ?? '')
  const [busy, setBusy] = useState(false)

  // Lokasi TIDAK BOLEH jadi induk dirinya sendiri: hierarki yang menunjuk balik membuat
  // penelusuran rak berputar tanpa henti saat halaman menggambar jalur induknya.
  const parentOptions = reference.locations.filter((entry) => entry.id !== location?.id)

  const submit = async () => {
    setBusy(true)
    try {
      if (location) await updateInventoryLocation(location.id, { code: code.trim(), parentId: parentId || null })
      else await createInventoryLocation({ code: code.trim(), kind, parentId: parentId || null })
      await onSaved()
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Lokasi tidak dapat disimpan')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      title={location ? `Ubah lokasi ${location.code}` : 'Tambah lokasi'}
      onClose={onClose}
      footer={
        <>
          <Button variant="subtle" onClick={onClose} disabled={busy}>Batal</Button>
          <Button variant="primary" disabled={busy || !code.trim()} onClick={() => void submit()}>
            {busy ? 'Menyimpan…' : 'Simpan'}
          </Button>
        </>
      }
    >
      <div className="stack" style={{ gap: '0.75rem' }}>
        <TextField label="Kode" required value={code} onChange={(_, data) => setCode(data.value)} placeholder="WH-PUSAT" />
        <SelectField
          label="Jenis"
          value={kind}
          disabled={location !== null}
          hint={location ? 'Jenis lokasi tidak bisa diubah — saldo lama sudah terikat padanya.' : undefined}
          onChange={(_, data) => setKind(data.value as LocationKind)}
        >
          {LOCATION_KINDS.map((entry) => (
            <option key={entry} value={entry}>{LOCATION_KIND_LABEL[entry]}</option>
          ))}
        </SelectField>
        <SelectField label="Induk" value={parentId} onChange={(_, data) => setParentId(data.value)}>
          <option value="">Tanpa induk</option>
          {parentOptions.map((entry) => (
            <option key={entry.id} value={entry.id}>
              {entry.code} · {LOCATION_KIND_LABEL[entry.kind]}
            </option>
          ))}
        </SelectField>
      </div>
    </Modal>
  )
}

// ————————————————————————————— item master —————————————————————————————

function ItemSection({ reference }: { reference: WarehouseReference }) {
  const { can } = useCan()
  const toast = useToast()
  const canManage = can('inventory.item.manage')
  const [query, setQuery] = useState('')
  const [showInactive, setShowInactive] = useState(false)
  const [editing, setEditing] = useState<InventoryItemMasterView | 'new' | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const rows = useMemo(() => {
    const term = query.trim().toLowerCase()
    return reference.items.filter((item) => {
      if (!showInactive && !item.active) return false
      if (!term) return true
      return item.name.toLowerCase().includes(term) || item.code.toLowerCase().includes(term)
    })
  }, [reference.items, query, showInactive])

  const toggleActive = async (item: InventoryItemMasterView) => {
    setBusyId(item.id)
    try {
      await setItemMasterActive(item.id, !item.active)
      await reference.reload()
      toast.success(item.active ? 'Item dinonaktifkan' : 'Item diaktifkan')
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Status item tidak dapat diubah')
    } finally {
      setBusyId(null)
    }
  }

  const columns: Column<InventoryItemMasterView>[] = [
    {
      key: 'name',
      header: 'Nama',
      sortValue: (row) => row.name,
      cell: (row) => (
        <div className="stack" style={{ gap: 0 }}>
          <Text as="span">{row.name}</Text>
          <Text as="span" className="muted" size={200}>{row.code}</Text>
        </div>
      ),
    },
    {
      key: 'category',
      header: 'Kategori',
      sortValue: (row) => row.category,
      cell: (row) => <Text as="span">{ITEM_CATEGORY_LABEL[row.category]}</Text>,
    },
    {
      key: 'unit',
      header: 'Satuan',
      sortValue: (row) => row.unit,
      cell: (row) => <Text as="span">{UNIT_LABEL[row.unit]}</Text>,
    },
    {
      key: 'serialized',
      header: 'Berserial',
      sortValue: (row) => (row.serialized ? 1 : 0),
      cell: (row) =>
        row.serialized ? (
          <Badge tone="accent">SN{row.trackMac ? ' + MAC' : ''}</Badge>
        ) : (
          <Text as="span" className="muted">Curah</Text>
        ),
    },
    {
      key: 'reorder',
      header: 'Titik pesan ulang',
      align: 'right',
      sortValue: (row) => row.reorderPoint,
      cell: (row) => <Text as="span" className="tnum">{row.reorderPoint ?? '—'}</Text>,
    },
    {
      key: 'active',
      header: 'Status',
      sortValue: (row) => (row.active ? 1 : 0),
      cell: (row) => <Badge tone={row.active ? 'good' : 'neutral'}>{row.active ? 'Aktif' : 'Nonaktif'}</Badge>,
    },
  ]

  const rowActions = (row: InventoryItemMasterView): RowAction[] => [
    { key: 'edit', label: 'Ubah', onClick: () => setEditing(row) },
    {
      key: 'active',
      // Kata "Hapus" SENGAJA tidak dipakai: server tidak menyediakan DELETE sama sekali, dan
      // ledger serta aset lama tetap menunjuk id item ini. Label "Hapus" akan membuat petugas
      // mengira riwayat mutasinya ikut lenyap.
      label: row.active ? 'Nonaktifkan' : 'Aktifkan',
      disabled: busyId === row.id,
      onClick: () => void toggleActive(row),
    },
  ]

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama atau kode item…" />
        <Switch
          label="Tampilkan nonaktif"
          checked={showInactive}
          onChange={(_, data) => setShowInactive(data.checked)}
        />
        {canManage && <Button variant="primary" onClick={() => setEditing('new')}>Tambah item</Button>}
      </Toolbar>
      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(row) => row.id}
        presentation="resource"
        initialSort={{ key: 'name', dir: 'asc' }}
        rowActions={canManage ? rowActions : undefined}
        empty={<EmptyState title="Belum ada item" hint="Daftarkan ONU/ONT, dropcore, dan patch cord di sini." />}
      />
      {editing && (
        <ItemForm
          item={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
          onSaved={async () => {
            setEditing(null)
            await reference.reload()
            toast.success('Item disimpan')
          }}
        />
      )}
    </div>
  )
}

function ItemForm({
  item,
  onClose,
  onSaved,
}: {
  item: InventoryItemMasterView | null
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const toast = useToast()
  const [code, setCode] = useState(item?.code ?? '')
  const [name, setName] = useState(item?.name ?? '')
  const [category, setCategory] = useState<InventoryItemCategory>(item?.category ?? 'ONT')
  const [unit, setUnit] = useState<InventoryUnit>(item?.unit ?? 'PCS')
  const [serialized, setSerialized] = useState(item?.serialized ?? false)
  const [trackMac, setTrackMac] = useState(item?.trackMac ?? false)
  const [reorderPoint, setReorderPoint] = useState(item?.reorderPoint == null ? '' : String(item.reorderPoint))
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true)
    const parsedReorder = reorderPoint.trim() ? Number(reorderPoint) : null
    try {
      if (item) {
        await updateItemMaster(item.id, { name: name.trim(), category, reorderPoint: parsedReorder, trackMac })
      } else {
        await createItemMaster({
          code: code.trim(),
          name: name.trim(),
          category,
          unit,
          serialized,
          // MAC hanya masuk akal untuk barang berserial; mengirim trackMac pada barang curah
          // membuat master data menjanjikan kolom MAC yang tidak akan pernah terisi apa pun.
          trackMac: serialized && trackMac,
          reorderPoint: parsedReorder,
        })
      }
      await onSaved()
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Item tidak dapat disimpan')
    } finally {
      setBusy(false)
    }
  }

  const invalidReorder = reorderPoint.trim() !== '' && Number.isNaN(Number(reorderPoint))

  return (
    <Modal
      title={item ? `Ubah item ${item.code}` : 'Tambah item'}
      onClose={onClose}
      footer={
        <>
          <Button variant="subtle" onClick={onClose} disabled={busy}>Batal</Button>
          <Button
            variant="primary"
            disabled={busy || !name.trim() || (!item && !code.trim()) || invalidReorder}
            onClick={() => void submit()}
          >
            {busy ? 'Menyimpan…' : 'Simpan'}
          </Button>
        </>
      }
    >
      <div className="stack" style={{ gap: '0.75rem' }}>
        {!item && (
          <TextField label="Kode" required value={code} onChange={(_, data) => setCode(data.value)} placeholder="ONT-F660" />
        )}
        <TextField label="Nama" required value={name} onChange={(_, data) => setName(data.value)} placeholder="ONT ZTE F660" />
        <SelectField label="Kategori" value={category} onChange={(_, data) => setCategory(data.value as InventoryItemCategory)}>
          {CATEGORIES.map((entry) => (
            <option key={entry} value={entry}>{ITEM_CATEGORY_LABEL[entry]}</option>
          ))}
        </SelectField>
        <SelectField
          label="Satuan"
          value={unit}
          disabled={item !== null}
          hint={item ? 'Satuan tidak bisa diubah setelah item dipakai di ledger.' : undefined}
          onChange={(_, data) => setUnit(data.value as InventoryUnit)}
        >
          {UNITS.map((entry) => (
            <option key={entry} value={entry}>{UNIT_LABEL[entry]}</option>
          ))}
        </SelectField>
        <Switch
          label="Barang berserial (punya nomor seri per unit)"
          checked={serialized}
          // Mengubah `serialized` pada item yang sudah punya ratusan unit akan membuat saldo dan
          // daftar aset bercerita berbeda tanpa satu pun mutasi yang menjelaskannya — karena itu
          // server pun tidak menerimanya di jalur ubah.
          disabled={item !== null}
          onChange={(_, data) => setSerialized(data.checked)}
        />
        <Switch
          label="Catat alamat MAC"
          checked={trackMac}
          disabled={!serialized}
          onChange={(_, data) => setTrackMac(data.checked)}
        />
        <TextField
          label="Titik pesan ulang"
          value={reorderPoint}
          onChange={(_, data) => setReorderPoint(data.value)}
          placeholder="Kosongkan bila tidak dipantau"
          validationState={invalidReorder ? 'error' : undefined}
          validationMessage={invalidReorder ? 'Titik pesan ulang harus angka' : undefined}
        />
      </div>
    </Modal>
  )
}

// ————————————————————————————— template material WO —————————————————————————————

interface TemplateDraft {
  readonly itemId: string
  plannedQuantity: string
  note: string
}

/**
 * Daftar material yang OTOMATIS mempra-isi rencana pemakaian tiap jenis work order.
 *
 * Template ini pra-isi, BUKAN pagar: teknisi tetap boleh memakai barang di luar daftar, dan
 * selisihnya yang harus terlihat di laporan. Karena itu layar ini tidak punya kolom "wajib".
 *
 * Simpan mengganti SELURUH daftar jenis WO ini sekali jalan — server memang begitu. Kalau
 * disunting per baris, item yang dicoret administrator tidak pernah punya request hapus: ia
 * hilang dari layar tapi tetap hidup di server dan terus mempra-isi rencana dengan barang
 * yang sudah tidak dipakai lagi.
 */
function MaterialTemplateSection({ reference }: { reference: WarehouseReference }) {
  const { can } = useCan()
  const toast = useToast()
  const canManage = can('inventory.item.manage')
  const [workOrderType, setWorkOrderType] = useState(WORK_ORDER_TYPES[0])
  const [lines, setLines] = useState<TemplateDraft[]>([])
  const [pick, setPick] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const template = await getMaterialTemplate(workOrderType)
      setLines(
        template.map((line) => ({
          itemId: line.itemId,
          plannedQuantity: String(line.plannedQuantity),
          note: line.note ?? '',
        })),
      )
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Gagal memuat template material')
      setLines([])
    } finally {
      setLoading(false)
    }
  }, [toast, workOrderType])

  useEffect(() => {
    void load()
  }, [load])

  const addable = reference.items.filter(
    (item) => item.active && !lines.some((line) => line.itemId === item.id),
  )
  const invalid = lines.some((line) => !(Number(line.plannedQuantity) > 0))

  const save = async () => {
    setBusy(true)
    try {
      await saveMaterialTemplate(
        workOrderType,
        lines.map((line) => ({
          itemId: line.itemId,
          plannedQuantity: Number(line.plannedQuantity),
          note: line.note.trim() || null,
        })),
      )
      toast.success('Template material disimpan')
      await load()
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Template material tidak dapat disimpan')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="stack" style={{ gap: '0.85rem' }}>
      <Toolbar>
        <SelectField
          label="Jenis work order"
          aria-label="Jenis work order"
          value={workOrderType}
          onChange={(_, data) => setWorkOrderType(data.value)}
        >
          {WORK_ORDER_TYPES.map((type) => (
            <option key={type} value={type}>{type}</option>
          ))}
        </SelectField>
      </Toolbar>

      <div className="card stack" style={{ gap: '0.6rem' }} aria-label={`Template ${workOrderType}`}>
        {loading ? (
          <Text as="span" className="muted">Memuat template…</Text>
        ) : lines.length === 0 ? (
          <EmptyState title="Template kosong" hint="Rencana material WO jenis ini diisi manual oleh teknisi." />
        ) : (
          lines.map((line, index) => (
            <div className="row wrap" key={line.itemId} style={{ alignItems: 'flex-end' }}>
              <div className="stack" style={{ gap: 0, minWidth: '14rem' }}>
                <Text as="span">{reference.names.itemName(line.itemId)}</Text>
                <Text as="span" className="muted" size={200}>{reference.names.item(line.itemId)}</Text>
              </div>
              <TextField
                label="Rencana"
                aria-label={`Rencana ${reference.names.itemName(line.itemId)}`}
                value={line.plannedQuantity}
                disabled={!canManage}
                onChange={(_, data) =>
                  setLines((current) =>
                    current.map((entry, position) =>
                      position === index ? { ...entry, plannedQuantity: data.value } : entry,
                    ),
                  )
                }
              />
              <TextField
                label="Catatan"
                aria-label={`Catatan ${reference.names.itemName(line.itemId)}`}
                value={line.note}
                disabled={!canManage}
                onChange={(_, data) =>
                  setLines((current) =>
                    current.map((entry, position) => (position === index ? { ...entry, note: data.value } : entry)),
                  )
                }
              />
              {canManage && (
                <Button
                  variant="danger"
                  aria-label={`Keluarkan ${reference.names.itemName(line.itemId)} dari template`}
                  onClick={() => setLines((current) => current.filter((_, position) => position !== index))}
                >
                  Keluarkan
                </Button>
              )}
            </div>
          ))
        )}

        {canManage && (
          <div className="row wrap" style={{ alignItems: 'flex-end' }}>
            <SelectField label="Tambah item" value={pick} onChange={(_, data) => setPick(data.value)}>
              <option value="">Pilih item…</option>
              {addable.map((item) => (
                <option key={item.id} value={item.id}>{item.name} ({item.code})</option>
              ))}
            </SelectField>
            <Button
              disabled={!pick}
              onClick={() => {
                setLines((current) => [...current, { itemId: pick, plannedQuantity: '1', note: '' }])
                setPick('')
              }}
            >
              Tambah
            </Button>
            <Button variant="primary" disabled={busy || invalid} onClick={() => void save()}>
              {busy ? 'Menyimpan…' : 'Simpan template'}
            </Button>
          </div>
        )}
        {invalid && (
          <Text as="span" className="error" size={200} role="alert">
            Rencana tiap baris harus lebih dari nol.
          </Text>
        )}
      </div>
    </div>
  )
}
