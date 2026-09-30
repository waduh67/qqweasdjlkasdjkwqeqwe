import type { WarehouseSku } from '@/api/warehouse/models'
import type { ReceiptDraftLineInput, ReceiptSerialInput, WarehouseReceipt } from '@/api/warehouse/receipts'
import { formatBaseQuantity, quantityFromInput } from '@/api/warehouse/quantity'

export type ReceiptSkuChoice = Pick<WarehouseSku, 'id' | 'name' | 'code' | 'tracking' | 'baseUnit'>
export interface ReceiptDraftRow {
  key: string; sku: ReceiptSkuChoice | null; quantity: string; serials: string; lotCode: string;
  useConversion: boolean; numerator: string; denominator: string; packageQuantity: string;
  useCost: boolean; totalMinor: string; currency: string;
}
export const emptyReceiptRow = (): ReceiptDraftRow => ({ key: crypto.randomUUID(), sku: null, quantity: '', serials: '', lotCode: '',
  useConversion: false, numerator: '', denominator: '1', packageQuantity: '1', useCost: false, totalMinor: '', currency: 'IDR' })

export function parseReceiptSerials(input: string): ReceiptSerialInput[] {
  if (input.length > 100000) throw new Error('Daftar serial terlalu panjang.')
  const serials = new Set<string>(), macs = new Set<string>()
  const rows = input.split(/\r?\n/).filter(row => row.trim() !== '')
  if (rows.length > 500) throw new Error('Maksimal 500 serial per penerimaan.')
  return rows.map(row => {
    const fields = row.split(',').map(s => s.trim()), [serial, rawMac] = fields
    if (!serial || serial.length > 128 || [...serial].some(char => char.charCodeAt(0) < 32 || char.charCodeAt(0) === 127) || fields.length > 2) throw new Error('Isi satu serial per baris. MAC opsional dipisahkan dengan koma.')
    const canonical = serial.toUpperCase()
    if (serials.has(canonical)) throw new Error(`Serial ganda: ${serial}.`)
    serials.add(canonical)
    if (rawMac) {
      const mac = rawMac.replace(/[-:.]/g, '').toUpperCase()
      if (!/^[0-9A-F]{12}$/.test(mac)) throw new Error(`MAC tidak valid untuk serial ${serial}.`)
      if (macs.has(mac)) throw new Error(`MAC ganda pada serial ${serial}.`)
      macs.add(mac)
    }
    return { serial, ...(rawMac ? { mac: rawMac } : {}) }
  })
}

export function buildReceiptLines(rows: ReceiptDraftRow[], includeCost: boolean): ReceiptDraftLineInput[] {
  if (rows.length < 1 || rows.length > 100) throw new Error('Penerimaan membutuhkan 1–100 baris barang.')
  const allSerials: string[] = [], allMacs: string[] = []
  let physicalLines = 0
  const lines = rows.map((row, index) => {
    if (!row.sku) throw new Error(`Pilih barang pada baris ${index + 1}.`)
    const quantityBase = quantityFromInput(row.quantity, row.sku.baseUnit)
    const serials = row.sku.tracking === 'SERIAL' ? parseReceiptSerials(row.serials) : []
    if (row.sku.tracking === 'SERIAL' && BigInt(serials.length) !== BigInt(quantityBase)) throw new Error(`Jumlah serial ${row.sku.name} harus sama dengan jumlah barang.`)
    if (row.sku.tracking !== 'SERIAL' && (!row.lotCode.trim() || row.lotCode.length > 120)) throw new Error(`Isi kode lot / reel ${row.sku.name}.`)
    allSerials.push(...serials.map(s => s.serial.toUpperCase()))
    allMacs.push(...serials.flatMap(s => s.mac ? [s.mac.replace(/[-:.]/g, '').toUpperCase()] : []))
    physicalLines += row.sku.tracking === 'SERIAL' ? serials.length : 1
    const conversion = row.useConversion ? { numerator: quantityFromInput(row.numerator, 'EA'), denominator: quantityFromInput(row.denominator, 'EA'), packageQuantity: quantityFromInput(row.packageQuantity, 'EA') } : null
    if (conversion && BigInt(conversion.numerator) * BigInt(conversion.packageQuantity) !== BigInt(quantityBase) * BigInt(conversion.denominator)) throw new Error(`Konversi kemasan ${row.sku.name} harus tepat sama dengan jumlah aktual.`)
    const cost = includeCost && row.useCost ? { totalMinor: quantityFromInput(row.totalMinor, 'EA', true), currency: row.currency.trim().toUpperCase() } : null
    if (cost && !/^[A-Z]{3}$/.test(cost.currency)) throw new Error('Mata uang harus berupa kode tiga huruf, misalnya IDR.')
    return { skuId: row.sku.id, quantityBase, serials, lotCode: row.sku.tracking === 'SERIAL' ? null : row.lotCode.trim(), conversion, cost }
  })
  if (new Set(allSerials).size !== allSerials.length || new Set(allMacs).size !== allMacs.length) throw new Error('Serial atau MAC ganda ditemukan antarbaris barang.')
  if (physicalLines > 500) throw new Error('Maksimal 500 unit serial / baris fisik per penerimaan.')
  return lines
}

/** The server expands serial groups; restore each group once, including its original cost basis. */
export function rowsFromReceipt(receipt: WarehouseReceipt): ReceiptDraftRow[] {
  const groups = new Map<number, WarehouseReceipt['lines']>()
  for (const line of receipt.lines) groups.set(line.inputLineNumber, [...(groups.get(line.inputLineNumber) ?? []), line])
  return [...groups.entries()].sort(([a], [b]) => a - b).map(([, lines]) => {
    const first = lines[0]
    if (lines.some(line => line.skuId !== first.skuId || line.baseUnit !== first.baseUnit || JSON.stringify(line.cost) !== JSON.stringify(first.cost) || JSON.stringify(line.conversion) !== JSON.stringify(first.conversion))) throw new Error('Kelompok barang tidak dapat dibaca. Muat ulang dokumen.')
    return { ...emptyReceiptRow(), sku: { id: first.skuId, name: first.skuName, code: first.skuCode, tracking: first.tracking, baseUnit: first.baseUnit },
      quantity: formatBaseQuantity(lines.reduce((sum, line) => sum + BigInt(line.quantityBase), 0n).toString(), first.baseUnit),
      serials: lines.flatMap(line => line.serial ? [[line.serial, line.mac].filter(Boolean).join(', ')] : []).join('\n'), lotCode: first.lotCode ?? '',
      useConversion: first.conversion !== null, numerator: first.conversion?.numerator ?? '', denominator: first.conversion?.denominator ?? '1', packageQuantity: first.conversion?.packageQuantity ?? '1',
      useCost: first.cost !== null, totalMinor: first.cost?.totalMinor ?? '', currency: first.cost?.currency ?? 'IDR' }
  })
}
