import { pageOf, uuid } from './codec'
import { identityLookup, location, sku, stockRow, supplier, type WarehouseLocation, type WarehouseSku } from './models'
import { command, parameters, query } from './transport'
import type { BaseUnit } from './quantity'

export interface MasterFilter { page?: number; size?: number; search?: string; state?: 'ACTIVE' | 'ARCHIVED'; sort?: 'name' | 'code'; direction?: 'asc' | 'desc' }
export interface SkuInput {
  code: string; name: string; tracking: WarehouseSku['tracking']; baseUnit: BaseUnit;
  category?: string | null; model?: string | null; allowedOwnershipModes: WarehouseSku['allowedOwnershipModes'];
  inspectionRequired: boolean; minimumQuantityBase: string; expectedRevision?: number;
}
export interface LocationInput {
  code: string; name: string; kind: WarehouseLocation['kind']; parentLocationId?: string | null;
  areaId?: string | null; siteId?: string | null; custodianId?: string | null; issueEligible: boolean; expectedRevision?: number;
}
export interface SupplierInput { code: string; name: string; contactReference?: string | null; expectedRevision?: number }

export const listSkus = (filter: MasterFilter = {}) => query(`/api/v1/warehouse/skus${parameters({ ...filter })}`, pageOf(sku))
export const listLocations = (filter: MasterFilter = {}) => query(`/api/v1/warehouse/locations${parameters({ ...filter })}`, pageOf(location))
export const listSuppliers = (filter: MasterFilter = {}) => query(`/api/v1/warehouse/suppliers${parameters({ ...filter })}`, pageOf(supplier))
export const saveSku = (input: SkuInput, id?: string) => command(`/api/v1/warehouse/skus${id ? `/${uuid(id)}` : ''}`, id ? 'PUT' : 'POST', input, sku)
export const saveLocation = (input: LocationInput, id?: string) => command(`/api/v1/warehouse/locations${id ? `/${uuid(id)}` : ''}`, id ? 'PUT' : 'POST', input, location)
export const saveSupplier = (input: SupplierInput, id?: string) => command(`/api/v1/warehouse/suppliers${id ? `/${uuid(id)}` : ''}`, id ? 'PUT' : 'POST', input, supplier)
export const archiveSku = (id: string, revision: number) => command(`/api/v1/warehouse/skus/${uuid(id)}/archive`, 'POST', { expectedRevision: revision }, sku)
export const archiveLocation = (id: string, revision: number) => command(`/api/v1/warehouse/locations/${uuid(id)}/archive`, 'POST', { expectedRevision: revision }, location)
export const archiveSupplier = (id: string, revision: number) => command(`/api/v1/warehouse/suppliers/${uuid(id)}/archive`, 'POST', { expectedRevision: revision }, supplier)
export const listStock = (filter: { page?: number; size?: number; skuId?: string; locationId?: string; serial?: string } = {}) =>
  query(`/api/v1/warehouse/stock${parameters({ ...filter })}`, pageOf(stockRow))
export const lookupIdentity = (value: string) => query(`/api/v1/warehouse/assets/lookup${parameters({ value: value.trim() })}`, identityLookup)
