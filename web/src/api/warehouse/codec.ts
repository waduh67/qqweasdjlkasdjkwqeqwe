export type Decoder<T> = (value: unknown, path?: string) => T

export class WarehouseDataError extends Error {
  readonly field: string
  constructor(field: string) {
    super('Data gudang tidak dapat dibaca. Coba muat ulang.')
    this.name = 'WarehouseDataError'
    this.field = field
  }
}

export function record(value: unknown, path = 'response'): Record<string, unknown> {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new WarehouseDataError(path)
  return value as Record<string, unknown>
}

export function text(value: unknown, path = 'text'): string {
  if (typeof value !== 'string' || value.trim() === '' || value.length > 4096) throw new WarehouseDataError(path)
  return value
}

export function uuid(value: unknown, path = 'id'): string {
  const id = text(value, path)
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) throw new WarehouseDataError(path)
  return id
}

export function digest(value: unknown, path = 'digest'): string {
  const result = text(value, path)
  if (!/^[0-9a-f]{64}$/.test(result)) throw new WarehouseDataError(path)
  return result
}

export function integer(value: unknown, path = 'revision'): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) throw new WarehouseDataError(path)
  return value
}

export function boolean(value: unknown, path = 'flag'): boolean {
  if (typeof value !== 'boolean') throw new WarehouseDataError(path)
  return value
}

export function decimal(value: unknown, path = 'quantityBase', signed = false): string {
  if (typeof value !== 'string' || value.length > 128 || !(signed ? /^-?(0|[1-9][0-9]*)$/ : /^(0|[1-9][0-9]*)$/).test(value)) {
    throw new WarehouseDataError(path)
  }
  return value
}

export function oneOf<const T extends readonly string[]>(value: unknown, values: T, path = 'state'): T[number] {
  if (typeof value !== 'string' || !values.includes(value)) throw new WarehouseDataError(path)
  return value as T[number]
}

export function nullable<T>(value: unknown, decode: Decoder<T>, path: string): T | null {
  return value === null || value === undefined ? null : decode(value, path)
}

export function array<T>(value: unknown, decode: Decoder<T>, path = 'items', max = 1000): T[] {
  if (!Array.isArray(value) || value.length > max) throw new WarehouseDataError(path)
  return value.map((item, index) => decode(item, `${path}[${index}]`))
}

export interface WarehousePage<T> { items: T[]; page: number; size: number; totalElements: number }

export function pageOf<T>(decode: Decoder<T>): Decoder<WarehousePage<T>> {
  return (value, path = 'page') => {
    const row = record(value, path)
    const page = integer(row.page, `${path}.page`)
    const size = integer(row.size, `${path}.size`)
    const totalElements = integer(row.totalElements, `${path}.totalElements`)
    const items = array(row.items, decode, `${path}.items`, 100)
    if (size < 1 || size > 100 || items.length > size || items.length > totalElements || (items.length > 0 && page * size + items.length > totalElements)) {
      throw new WarehouseDataError(path)
    }
    return { items, page, size, totalElements }
  }
}
