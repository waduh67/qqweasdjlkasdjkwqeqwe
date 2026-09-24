export type BaseUnit = 'EA' | 'MM'

const MAX_BASE = 9223372036854775807n
const UNSIGNED = /^(0|[1-9][0-9]*)$/
const SIGNED = /^-?(0|[1-9][0-9]*)$/

/** User-facing metres/items -> checked server base-unit string. No floating point. */
export function quantityFromInput(input: string, unit: BaseUnit, allowZero = false): string {
  const text = input.trim()
  const pattern = unit === 'EA' ? UNSIGNED : /^(0|[1-9][0-9]*)(?:[.,]([0-9]{1,3}))?$/
  if (text.length > 32 || !pattern.test(text)) throw new Error(unit === 'EA'
    ? 'Jumlah barang harus berupa bilangan bulat.' : 'Panjang harus dalam meter, maksimal tiga angka desimal tanpa pemisah ribuan.')
  const [whole, fraction = ''] = text.replace(',', '.').split('.')
  const base = unit === 'MM' ? BigInt(whole) * 1000n + BigInt(fraction.padEnd(3, '0')) : BigInt(whole)
  if (base > MAX_BASE || (base === 0n && !allowZero)) throw new Error(base === 0n ? 'Jumlah harus lebih dari nol.' : 'Jumlah melebihi batas yang didukung.')
  return base.toString()
}

/** Aggregated report quantities may exceed the per-piece signed-64-bit mutation limit. */
export function formatBaseQuantity(base: string, unit: BaseUnit, separator: ',' | '.' = ','): string {
  if (base.length > 128 || !SIGNED.test(base)) throw new Error('Jumlah tidak dapat dibaca.')
  const value = BigInt(base)
  if (unit === 'EA') return value.toString()
  const magnitude = value < 0n ? -value : value
  return `${value < 0n ? '-' : ''}${magnitude / 1000n}${separator}${(magnitude % 1000n).toString().padStart(3, '0')}`
}

export function displayUnit(unit: BaseUnit): 'm' | 'unit' {
  return unit === 'MM' ? 'm' : 'unit'
}
