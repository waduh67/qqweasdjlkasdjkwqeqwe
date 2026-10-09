import { decimal, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { parameters, query } from './transport'

export interface RequestStockDestination { readonly requestId?: string; readonly technicianId?: string; readonly warehouseId?: string }
export function requestStockPreview(value: unknown) {
  const row = record(value)
  const result = { skuId: uuid(row.skuId), skuName: text(row.skuName), baseUnit: oneOf(row.baseUnit, ['EA', 'MM']),
    totalWarehouseBase: decimal(row.totalWarehouseBase), technicianId: nullable(row.technicianId, uuid, 'technicianId'),
    technicianName: nullable(row.technicianName, text, 'technicianName'), technicianQuantityBase: nullable(row.technicianQuantityBase, decimal, 'technicianQuantityBase'),
    warehouses: pageOf(value => {
      const warehouse = record(value)
      return { warehouseId: uuid(warehouse.warehouseId), warehouseName: text(warehouse.warehouseName), quantityBase: decimal(warehouse.quantityBase) }
    })(row.warehouses) }
  if (result.technicianId === null ? result.technicianName !== null || result.technicianQuantityBase !== null : result.technicianName === null || result.technicianQuantityBase === null)
    throw new WarehouseDataError('requestStock.technician')
  return result
}
export const getRequestStockPreview = (skuId: string, destination: RequestStockDestination, page: number) =>
  query('/api/v2/warehouse/requests/stock-preview' + parameters({ skuId: uuid(skuId), ...destination, page }), requestStockPreview)
