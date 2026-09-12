import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Switch, Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  adjustStock,
  issueStock,
  listStockBalances,
  operationEnvelope,
  receiveGoods,
  registerSerialsBulk,
  requestRestock,
  requestSerialRestock,
  returnStock,
  transferStock,
  type AdjustmentKind,
  type InventoryItemMasterView,
  type OperationEnvelope,
  type StockBalanceView,
  type StockLineBody,
} from '@/api/inventory'
import { useCan } from '@/auth/useCan'
import { Button, EmptyState, SelectField, TextField, TextareaField } from '@/components/atoms'
import { useToast } from '@/system'
import type { WarehouseReference } from './WarehouseOperationsPage'
import { ADJUSTMENT_KIND_LABEL, LOCATION_KIND_LABEL } from './WarehouseLabels'

type Operation = 'receive' | 'restock' | 'transfer' | 'issue' | 'return' | 'adjust' | 'serial'

const OPERATIONS: readonly { readonly key: Operation; readonly label: string; readonly permission: string }[] = [
  { key: 'restock', label: 'Permintaan restock', permission: 'inventory.restock.request' },
  { key: 'receive', label: 'Penerimaan barang', permission: 'inventory.restock.receive' },
  { key: 'transfer', label: 'Transfer antar lokasi', permission: 'inventory.movement.transfer' },
  { key: 'issue', label: 'Pengeluaran ke teknisi', permission: 'inventory.movement.issue' },
  { key: 'return', label: 'Retur dari teknisi', permission: 'inventory.movement.return' },
  { key: 'adjust', label: 'Penyesuaian / hapus buku', permission: 'inventory.movement.adjust' },
  { key: 'serial', label: 'Daftar SN/MAC massal', permission: 'inventory.item.manage' },
]

interface LineDraft {
  readonly key: string
  itemId: string
  quantity: string
  /** Satu SN per baris. Untuk item berserial inilah yang menentukan kuantitas. */
  serials: string
}

export function WarehouseMovementPanel({ reference }: { reference: WarehouseReference }) {
  const { can } = useCan()
  const available = OPERATIONS.filter((entry) => can(entry.permission))
  const [operation, setOperation] = useState<Operation | null>(null)
  const active = operation && available.some((entry) => entry.key === operation) ? operation : available[0]?.key

  if (available.length === 0) {
    return (
      <div className="card">
        <EmptyState title="Tidak ada aksi mutasi" hint="Kamu hanya berhak membaca data gudang." />
      </div>
    )
  }

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="row wrap" role="group" aria-label="Jenis mutasi">
        {available.map((entry) => (
          <Button
            key={entry.key}
            variant={active === entry.key ? 'primary' : 'default'}
            onClick={() => setOperation(entry.key)}
          >
            {entry.label}
          </Button>
        ))}
      </div>
      {active === 'serial' ? (
        <SerialRegistrationForm reference={reference} />
      ) : (
        active && <MovementForm key={active} operation={active} reference={reference} />
      )}
    </div>
  )
}

// ————————————————————————————— formulir mutasi kuantitas —————————————————————————————

function MovementForm({ operation, reference }: { operation: Exclude<Operation, 'serial'>; reference: WarehouseReference }) {
  const toast = useToast()
  const balances = useStockBalances()
  const [lines, setLines] = useState<LineDraft[]>([emptyLine()])
  const [reason, setReason] = useState('')
  const [emergencyReason, setEmergencyReason] = useState('')
  const [locationId, setLocationId] = useState('')
  const [toLocationId, setToLocationId] = useState('')
  const [custodianId, setCustodianId] = useState('')
  const [toCustodianId, setToCustodianId] = useState('')
  const [technicianId, setTechnicianId] = useState('')
  const [technicianLocationId, setTechnicianLocationId] = useState('')
  const [adjustmentKind, setAdjustmentKind] = useState<AdjustmentKind>('CORRECTION')
  const [increase, setIncrease] = useState(false)
  const [quarantine, setQuarantine] = useState(false)
  const [busy, setBusy] = useState(false)
  const envelopeFor = useIdempotentEnvelope()

  const itemById = useMemo(
    () => new Map(reference.items.map((item) => [item.id, item] as const)),
    [reference.items],
  )
  const activeItems = useMemo(() => reference.items.filter((item) => item.active), [reference.items])

  const sourceLabel = operation === 'return' ? 'Lokasi teknisi (asal)' : operation === 'transfer' ? 'Lokasi asal' : 'Lokasi'
  const needsSecondLocation = operation === 'transfer' || operation === 'return'
  const needsTechnician = operation === 'issue' || operation === 'return'

  const payloadLines = useMemo(() => lines.map((line) => toStockLine(line, itemById.get(line.itemId))), [lines, itemById])
  const problems = useMemo(
    () => validate(operation, { lines, payloadLines, itemById, reason, locationId, toLocationId, custodianId, toCustodianId, technicianId, technicianLocationId }),
    [operation, lines, payloadLines, itemById, reason, locationId, toLocationId, custodianId, toCustodianId, technicianId, technicianLocationId],
  )

  const submit = async () => {
    setBusy(true)
    try {
      const core = { lines: payloadLines, reason: reason.trim() }
      const envelope = await envelopeFor({ operation, core, locationId, toLocationId, custodianId, toCustodianId, technicianId, technicianLocationId, adjustmentKind, increase, quarantine })
      const emergency = emergencyReason.trim() || null
      if (operation === 'receive') {
        await receiveGoods({ locationId, custodianId, ...core, ...envelope, emergencyReason: emergency })
      } else if (operation === 'restock') {
        await requestRestock({ locationId, custodianId, ...core, ...envelope, emergencyReason: emergency })
      } else if (operation === 'transfer') {
        await transferStock({ fromLocationId: locationId, fromCustodianId: custodianId, toLocationId, toCustodianId, ...core, ...envelope })
      } else if (operation === 'issue') {
        await issueStock({ fromLocationId: locationId, custodianId, technicianId, technicianLocationId, ...core, ...envelope })
      } else if (operation === 'return') {
        await returnStock({ fromLocationId: locationId, technicianId, toLocationId, custodianId, quarantine, ...core, ...envelope })
      } else {
        await adjustStock({ locationId, custodianId, kind: adjustmentKind, increase: adjustmentKind === 'CORRECTION' && increase, ...core, ...envelope, emergencyReason: emergency })
      }
      envelopeFor.reset()
      toast.success(
        operation === 'restock' || operation === 'adjust'
          ? 'Permintaan dikirim — saldo bergerak setelah disetujui'
          : 'Mutasi dibukukan',
      )
      setLines([emptyLine()])
      setReason('')
      setEmergencyReason('')
      await balances.reload()
    } catch (caught) {
      toast.error(caught instanceof ApiError ? caught.message : 'Mutasi tidak dapat disimpan')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card stack" style={{ gap: '0.85rem' }}>
      <div className="row wrap" style={{ alignItems: 'flex-end' }}>
        <LocationSelect
          label={sourceLabel}
          value={locationId}
          reference={reference}
          onChange={(next) => {
            setLocationId(next)
            setCustodianId(suggestCustodian(balances.rows, next))
          }}
        />
        <CustodianSelect
          label={operation === 'return' ? 'Pemegang gudang tujuan' : 'Pemegang custody'}
          value={custodianId}
          locationId={operation === 'return' ? toLocationId : locationId}
          balances={balances.rows}
          reference={reference}
          onChange={setCustodianId}
        />
        {needsSecondLocation && (
          <LocationSelect
            label={operation === 'transfer' ? 'Lokasi tujuan' : 'Gudang tujuan'}
            value={toLocationId}
            reference={reference}
            onChange={setToLocationId}
          />
        )}
        {operation === 'transfer' && (
          <CustodianSelect
            label="Pemegang tujuan"
            value={toCustodianId}
            locationId={toLocationId}
            balances={balances.rows}
            reference={reference}
            onChange={setToCustodianId}
          />
        )}
        {needsTechnician && (
          <SelectField label="Teknisi" value={technicianId} onChange={(_, data) => setTechnicianId(data.value)}>
            <option value="">Pilih teknisi…</option>
            {reference.users.map((user) => (
              <option key={user.id} value={user.id}>{user.name}</option>
            ))}
          </SelectField>
        )}
        {operation === 'issue' && (
          <LocationSelect
            label="Lokasi teknisi (van)"
            value={technicianLocationId}
            reference={reference}
            onChange={setTechnicianLocationId}
          />
        )}
        {operation === 'adjust' && (
          <SelectField label="Jenis penyesuaian" value={adjustmentKind} onChange={(_, data) => setAdjustmentKind(data.value as AdjustmentKind)}>
            {(Object.keys(ADJUSTMENT_KIND_LABEL) as AdjustmentKind[]).map((entry) => (
              <option key={entry} value={entry}>{ADJUSTMENT_KIND_LABEL[entry]}</option>
            ))}
          </SelectField>
        )}
      </div>

      {operation === 'adjust' && adjustmentKind === 'CORRECTION' && (
        <Switch label="Koreksi menambah stok" checked={increase} onChange={(_, data) => setIncrease(data.checked)} />
      )}
      {operation === 'return' && (
        <Switch label="Masukkan ke karantina (barang rusak)" checked={quarantine} onChange={(_, data) => setQuarantine(data.checked)} />
      )}

      <LineEditor lines={lines} items={activeItems} itemById={itemById} onChange={setLines} />

      <TextareaField label="Alasan" required rows={2} value={reason} onChange={(_, data) => setReason(data.value)} />
      {(operation === 'restock' || operation === 'receive' || operation === 'adjust') && (
        <TextField
          label="Alasan darurat (opsional)"
          value={emergencyReason}
          hint="Diisi hanya bila kontrol empat-mata dilangkahi. Tercatat di laporan override."
          onChange={(_, data) => setEmergencyReason(data.value)}
        />
      )}

      {(operation === 'restock' || operation === 'adjust') && (
        <Text as="span" className="muted" size={200}>
          Saldo BELUM bergerak sekarang: mutasi ini menunggu persetujuan lebih dulu.
        </Text>
      )}

      {problems.length > 0 && (
        <div className="stack" style={{ gap: '0.15rem' }} role="alert">
          {problems.map((problem) => (
            <Text as="span" className="error" size={200} key={problem}>{problem}</Text>
          ))}
        </div>
      )}

      <div>
        <Button variant="primary" disabled={busy || problems.length > 0} onClick={() => void submit()}>
          {busy ? 'Menyimpan…' : 'Simpan mutasi'}
        </Button>
      </div>
    </div>
  )
}

// ————————————————————————————— baris item —————————————————————————————

function LineEditor({
  lines,
  items,
  itemById,
  onChange,
}: {
  lines: LineDraft[]
  items: readonly InventoryItemMasterView[]
  itemById: ReadonlyMap<string, InventoryItemMasterView>
  onChange: (next: LineDraft[]) => void
}) {
  const update = (index: number, patch: Partial<LineDraft>) =>
    onChange(lines.map((line, position) => (position === index ? { ...line, ...patch } : line)))

  return (
    <div className="stack" style={{ gap: '0.6rem' }}>
      {lines.map((line, index) => {
        const item = itemById.get(line.itemId)
        const serialCount = countSerials(line.serials)
        return (
          <div className="stack" key={line.key} style={{ gap: '0.4rem' }}>
            <div className="row wrap" style={{ alignItems: 'flex-end' }}>
              <SelectField
                label={`Item baris ${index + 1}`}
                value={line.itemId}
                onChange={(_, data) => update(index, { itemId: data.value, serials: '' })}
              >
                <option value="">Pilih item…</option>
                {items.map((entry) => (
                  <option key={entry.id} value={entry.id}>{entry.name} ({entry.code})</option>
                ))}
              </SelectField>
              {item?.serialized ? (
                // Kuantitas item berserial DIHITUNG dari daftar SN, tidak diketik terpisah.
                // Server menolak mutasi yang jumlahnya tak sama dengan cacah serial, dan dua
                // kolom yang bisa berbeda hanya melahirkan penolakan "quantity mismatch" yang
                // tidak menunjuk kolom mana yang salah.
                <div className="stack" style={{ gap: 0 }}>
                  <Text as="span" className="muted" size={200}>Jumlah</Text>
                  <Text as="strong" className="tnum">{serialCount}</Text>
                </div>
              ) : (
                <TextField
                  label={`Jumlah baris ${index + 1}`}
                  value={line.quantity}
                  onChange={(_, data) => update(index, { quantity: data.value })}
                />
              )}
              {lines.length > 1 && (
                <Button variant="danger" onClick={() => onChange(lines.filter((_, position) => position !== index))}>
                  Hapus baris
                </Button>
              )}
            </div>
            {item?.serialized && (
              <TextareaField
                label={`Nomor seri baris ${index + 1}`}
                rows={3}
                value={line.serials}
                hint={`Satu SN per baris${item.trackMac ? ' — MAC dicatat saat pendaftaran SN, bukan di sini' : ''}.`}
                onChange={(_, data) => update(index, { serials: data.value })}
              />
            )}
          </div>
        )
      })}
      <div>
        <Button onClick={() => onChange([...lines, emptyLine()])}>Tambah baris</Button>
      </div>
    </div>
  )
}

// ————————————————————————————— pendaftaran SN/MAC massal —————————————————————————————

function SerialRegistrationForm({ reference }: { reference: WarehouseReference }) {
  const { can } = useCan()
  const toast = useToast()
  const balances = useStockBalances()
  const [itemId, setItemId] = useState('')
  const [locationId, setLocationId] = useState('')
  const [custodianId, setCustodianId] = useState('')
  const [raw, setRaw] = useState('')
  const [reason, setReason] = useState('')
  const [asRestock, setAsRestock] = useState(false)
  const [busy, setBusy] = useState(false)
  const envelopeFor = useIdempotentEnvelope()

  const serializedItems = reference.items.filter((item) => item.active && item.serialized)
  const item = serializedItems.find((entry) => entry.id === itemId)
  const serials = useMemo(() => parseSerialLines(raw), [raw])
  const duplicate = useMemo(() => findDuplicate(serials.map((entry) => entry.serialNumber)), [serials])

  const problems: string[] = []
  if (!itemId) problems.push('Pilih item berserial.')
  if (!locationId) problems.push('Pilih lokasi.')
  if (!custodianId) problems.push('Pilih pemegang custody.')
  if (serials.length === 0) problems.push('Masukkan minimal satu nomor seri.')
  if (duplicate) problems.push(`Nomor seri ${duplicate} ditulis dua kali.`)
  if (!reason.trim()) problems.push('Alasan wajib diisi.')

  const submit = async () => {
    setBusy(true)
    try {
      const envelope = await envelopeFor({ itemId, locationId, custodianId, serials, asRestock })
      const body = {
        itemId,
        locationId,
        custodianId,
        serials,
        reason: reason.trim(),
        ...envelope,
        emergencyReason: null,
      }
      const result = asRestock ? await requestSerialRestock(body) : await registerSerialsBulk(body)
      envelopeFor.reset()
      toast.success(
        result.replayed
          ? 'Permintaan yang sama sudah pernah dikirim — tidak diproses dua kali'
          : `${result.registered.length} unit tercatat`,
      )
      setRaw('')
      setReason('')
      await balances.reload()
    } catch (caught) {
      // Satu serial bermasalah membatalkan SELURUH daftar di server; pesan servernya yang
      // menyebut serial mana, jadi ia diteruskan apa adanya alih-alih diganti kalimat umum.
      toast.error(caught instanceof ApiError ? caught.message : 'Pendaftaran serial gagal')
    } finally {
      setBusy(false)
    }
  }

  const canRestock = can('inventory.restock.request')

  return (
    <div className="card stack" style={{ gap: '0.85rem' }}>
      <div className="row wrap" style={{ alignItems: 'flex-end' }}>
        <SelectField label="Item berserial" value={itemId} onChange={(_, data) => setItemId(data.value)}>
          <option value="">Pilih item…</option>
          {serializedItems.map((entry) => (
            <option key={entry.id} value={entry.id}>{entry.name} ({entry.code})</option>
          ))}
        </SelectField>
        <LocationSelect label="Lokasi" value={locationId} reference={reference} onChange={(next) => {
          setLocationId(next)
          setCustodianId(suggestCustodian(balances.rows, next))
        }} />
        <CustodianSelect
          label="Pemegang custody"
          value={custodianId}
          locationId={locationId}
          balances={balances.rows}
          reference={reference}
          onChange={setCustodianId}
        />
      </div>
      {canRestock && (
        <Switch
          label="Ajukan sebagai permintaan restock (barang belum datang)"
          checked={asRestock}
          onChange={(_, data) => setAsRestock(data.checked)}
        />
      )}
      {asRestock && (
        <Text as="span" className="muted" size={200}>
          Unit tercatat dengan status &ldquo;Menunggu barang datang&rdquo; dan TIDAK ikut menambah saldo sampai
          persetujuan selesai.
        </Text>
      )}
      <TextareaField
        label="Daftar SN / MAC"
        rows={8}
        value={raw}
        hint={
          item?.trackMac
            ? 'Satu unit per baris: NOMORSERI,MACADDRESS. Tempel langsung dari hasil scan.'
            : 'Satu nomor seri per baris. Tempel langsung dari hasil scan.'
        }
        onChange={(_, data) => setRaw(data.value)}
      />
      <Text as="span" className="muted" size={200}>{serials.length} unit terbaca</Text>
      <TextareaField label="Alasan" required rows={2} value={reason} onChange={(_, data) => setReason(data.value)} />
      {problems.length > 0 && (
        <div className="stack" style={{ gap: '0.15rem' }} role="alert">
          {problems.map((problem) => (
            <Text as="span" className="error" size={200} key={problem}>{problem}</Text>
          ))}
        </div>
      )}
      <div>
        <Button variant="primary" disabled={busy || problems.length > 0} onClick={() => void submit()}>
          {busy ? 'Menyimpan…' : asRestock ? 'Ajukan restock' : 'Daftarkan unit'}
        </Button>
      </div>
    </div>
  )
}

// ————————————————————————————— pilihan lokasi & custody —————————————————————————————

function LocationSelect({
  label,
  value,
  reference,
  onChange,
}: {
  label: string
  value: string
  reference: WarehouseReference
  onChange: (next: string) => void
}) {
  return (
    <SelectField label={label} value={value} onChange={(_, data) => onChange(data.value)}>
      <option value="">Pilih lokasi…</option>
      {reference.locations.map((location) => (
        <option key={location.id} value={location.id}>
          {location.code} · {LOCATION_KIND_LABEL[location.kind]}
        </option>
      ))}
    </SelectField>
  )
}

/**
 * Pemegang custody dipisah jadi dua kelompok, yang SUDAH memegang stok di lokasi ini lebih
 * dulu.
 *
 * Alasannya bukan kerapian: leg OUT dicocokkan server dengan `custodyOwnerId` pada saldo.
 * Petugas yang memilih nama orang yang benar tapi bukan pemegang baris saldonya akan ditolak
 * dengan "stok tidak mencukupi" pada rak yang jelas-jelas penuh — dan tidak ada di layar yang
 * menjelaskan bahwa yang salah adalah kolom pemegang, bukan jumlahnya.
 */
function CustodianSelect({
  label,
  value,
  locationId,
  balances,
  reference,
  onChange,
}: {
  label: string
  value: string
  locationId: string
  balances: readonly StockBalanceView[]
  reference: WarehouseReference
  onChange: (next: string) => void
}) {
  const holders = useMemo(() => {
    const ids = new Set(balances.filter((row) => row.locationId === locationId).map((row) => row.custodyOwnerId))
    return [...ids]
  }, [balances, locationId])
  const others = reference.users.filter((user) => !holders.includes(user.id))

  return (
    <SelectField label={label} value={value} onChange={(_, data) => onChange(data.value)}>
      <option value="">Pilih pemegang…</option>
      {holders.length > 0 && (
        <optgroup label="Sudah memegang stok di lokasi ini">
          {holders.map((holderId) => (
            <option key={holderId} value={holderId}>{reference.names.user(holderId)}</option>
          ))}
        </optgroup>
      )}
      <optgroup label="Pengguna lain">
        {others.map((user) => (
          <option key={user.id} value={user.id}>{user.name}</option>
        ))}
      </optgroup>
    </SelectField>
  )
}

// ————————————————————————————— kakas —————————————————————————————

function useStockBalances() {
  const [rows, setRows] = useState<readonly StockBalanceView[]>([])
  const reload = useCallback(async () => {
    try {
      setRows(await listStockBalances())
    } catch {
      // Saldo di sini hanya untuk MENGUSULKAN pemegang custody. Kegagalannya tidak boleh
      // mematikan formulir: petugas masih bisa memilih pemegang dari direktori pengguna.
      setRows([])
    }
  }, [])
  useEffect(() => {
    void reload()
  }, [reload])
  return { rows, reload }
}

/**
 * Kunci operasi yang BERTAHAN selama isian tidak berubah.
 *
 * Kalau setiap percobaan simpan memakai kunci baru, klik kedua di jaringan yang timeout —
 * padahal request pertama sudah sampai — menjadi pengeluaran barang kedua. Kalau kuncinya
 * malah dipakai selamanya, mutasi berikutnya yang isinya berbeda ditolak sebagai duplikat.
 * Jadi kunci diikat pada SIDIK PAYLOAD: isian yang sama persis = percobaan ulang, isian yang
 * berubah = operasi baru.
 */
function useIdempotentEnvelope() {
  const attempt = useRef<{ hash: string; key: string } | null>(null)
  const envelopeFor = useCallback(async (payload: unknown): Promise<OperationEnvelope> => {
    const fresh = await operationEnvelope(payload)
    if (attempt.current?.hash === fresh.payloadHash) {
      return { operationKey: attempt.current.key, payloadHash: fresh.payloadHash }
    }
    attempt.current = { hash: fresh.payloadHash, key: fresh.operationKey }
    return fresh
  }, [])
  const reset = useCallback(() => {
    attempt.current = null
  }, [])
  // Dimemo supaya `reset` tidak dirakit ulang — dan ditempelkan ulang ke fungsi yang sama —
  // pada SETIAP render. Keduanya stabil, jadi penggabungan ini hanya terjadi sekali.
  return useMemo(() => Object.assign(envelopeFor, { reset }), [envelopeFor, reset])
}

function emptyLine(): LineDraft {
  return { key: `line-${Math.random().toString(36).slice(2, 9)}`, itemId: '', quantity: '1', serials: '' }
}

function countSerials(raw: string): number {
  return raw.split('\n').map((entry) => entry.trim()).filter(Boolean).length
}

function toStockLine(line: LineDraft, item: InventoryItemMasterView | undefined): StockLineBody {
  const serialNumbers = item?.serialized
    ? line.serials.split('\n').map((entry) => entry.trim()).filter(Boolean)
    : []
  return {
    itemId: line.itemId,
    quantity: item?.serialized ? serialNumbers.length : Number(line.quantity) || 0,
    serialNumbers,
  }
}

function parseSerialLines(raw: string): { serialNumber: string; macAddress: string | null }[] {
  return raw
    .split('\n')
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const [serialNumber, macAddress] = entry.split(/[,;\t]/).map((part) => part.trim())
      return { serialNumber, macAddress: macAddress || null }
    })
}

function findDuplicate(values: readonly string[]): string | null {
  const seen = new Set<string>()
  for (const value of values) {
    const normalized = value.toUpperCase()
    if (seen.has(normalized)) return value
    seen.add(normalized)
  }
  return null
}

function validate(
  operation: Exclude<Operation, 'serial'>,
  input: {
    lines: readonly LineDraft[]
    payloadLines: readonly StockLineBody[]
    itemById: ReadonlyMap<string, InventoryItemMasterView>
    reason: string
    locationId: string
    toLocationId: string
    custodianId: string
    toCustodianId: string
    technicianId: string
    technicianLocationId: string
  },
): string[] {
  const problems: string[] = []
  if (!input.locationId) problems.push('Lokasi wajib dipilih.')
  if (!input.custodianId) problems.push('Pemegang custody wajib dipilih.')
  if ((operation === 'transfer' || operation === 'return') && !input.toLocationId) problems.push('Lokasi tujuan wajib dipilih.')
  if (operation === 'transfer' && !input.toCustodianId) problems.push('Pemegang tujuan wajib dipilih.')
  if (operation === 'transfer' && input.locationId && input.locationId === input.toLocationId) {
    problems.push('Lokasi asal dan tujuan tidak boleh sama.')
  }
  if ((operation === 'issue' || operation === 'return') && !input.technicianId) problems.push('Teknisi wajib dipilih.')
  if (operation === 'issue' && !input.technicianLocationId) problems.push('Lokasi teknisi wajib dipilih.')
  if (!input.reason.trim()) problems.push('Alasan wajib diisi.')

  input.payloadLines.forEach((line, index) => {
    const item = input.itemById.get(line.itemId)
    if (!item) {
      problems.push(`Baris ${index + 1}: item belum dipilih.`)
      return
    }
    if (line.quantity <= 0) {
      problems.push(
        item.serialized
          ? `Baris ${index + 1}: masukkan minimal satu nomor seri.`
          : `Baris ${index + 1}: jumlah harus lebih dari nol.`,
      )
    }
    const duplicate = findDuplicate(line.serialNumbers)
    if (duplicate) problems.push(`Baris ${index + 1}: nomor seri ${duplicate} ditulis dua kali.`)
  })

  const usedItems = input.payloadLines.map((line) => line.itemId).filter(Boolean)
  const duplicateItem = findDuplicate(usedItems)
  if (duplicateItem) problems.push('Satu item hanya boleh muncul di satu baris.')

  return problems
}

/** Kalau lokasi hanya punya satu pemegang, memilihkannya menghemat langkah yang selalu sama. */
function suggestCustodian(balances: readonly StockBalanceView[], locationId: string): string {
  const holders = [...new Set(balances.filter((row) => row.locationId === locationId).map((row) => row.custodyOwnerId))]
  return holders.length === 1 ? holders[0] : ''
}
