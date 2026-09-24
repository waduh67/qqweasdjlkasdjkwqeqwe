import { decimal, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { parameters, query } from './transport'
export function stockShortage(value: unknown, path = 'shortage') {
  const row = record(value, path)
  const result = { id: uuid(row.id, path), skuId: uuid(row.skuId, path), skuCode: text(row.skuCode, path), name: text(row.name, path), baseUnit: oneOf(row.baseUnit, ['EA', 'MM'], path),
    availableBase: decimal(row.availableBase, path), minimumBase: decimal(row.minimumBase, path), shortageBase: decimal(row.shortageBase, path) }
  if (result.id !== result.skuId || BigInt(result.shortageBase) <= 0n || BigInt(result.minimumBase) - BigInt(result.availableBase) !== BigInt(result.shortageBase)) throw new WarehouseDataError(path)
  return result
}
export const listStockShortages = (filter: { page?: number; size?: number; skuId?: string; locationId?: string } = {}) => query(`/api/v1/warehouse/stock/shortages${parameters(filter)}`, pageOf(stockShortage))
