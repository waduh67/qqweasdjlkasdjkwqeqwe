import { listLocations, listSkus, listSuppliers } from '@/api/warehouse/masters'
import type { WarehouseLocation } from '@/api/warehouse/models'

export const receiptLocations = (search: string, page: number) => listLocations({ search, page, state: 'ACTIVE' })
export const receiptSkus = (search: string, page: number) => listSkus({ search, page, state: 'ACTIVE' })
export const receiptSuppliers = (search: string, page: number) => listSuppliers({ search, page, state: 'ACTIVE' })
export const locationLabel = (row: Pick<WarehouseLocation, 'name' | 'code'>) => row.name ? row.code ? `${row.name} · ${row.code}` : row.name : row.code
