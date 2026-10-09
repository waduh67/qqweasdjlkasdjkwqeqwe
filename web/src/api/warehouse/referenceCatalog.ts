import { array, pageOf, record, text, uuid } from './codec'
import type { LocationInput, MasterFilter, SkuInput, SupplierInput } from './masters'
import { location, sku, supplier } from './models'
import { referenceCommand } from './reference'
import { parameters, query } from './transport'

const root = '/api/v2/warehouse'
export const listReferenceAreas = () => query(`${root}/areas`, value => array(value, item => {
  const row = record(item)
  return { id: uuid(row.id), name: text(row.name), code: text(row.code), parentId: null }
}, 'areas', 10000))
export const listReferenceSkus = (filter: MasterFilter = {}) => query(`${root}/skus${parameters({ ...filter })}`, pageOf(sku))
export const listReferenceLocations = (filter: MasterFilter = {}) => query(`${root}/locations${parameters({ ...filter })}`, pageOf(location))
export const listReferenceSuppliers = (filter: MasterFilter = {}) => query(`${root}/suppliers${parameters({ ...filter })}`, pageOf(supplier))
export const getReferenceLocation = (id: string) => query(`${root}/locations/${uuid(id)}`, location)
export const getReferenceSku = (id: string) => query(`${root}/skus/${uuid(id)}`, sku)
export const saveReferenceSku = (input: SkuInput, id?: string) => referenceCommand(`${root}/skus${id ? `/${uuid(id)}` : ''}`, id ? 'PUT' : 'POST', input, sku)
export const saveReferenceLocation = (input: LocationInput, id?: string) => referenceCommand(`${root}/locations${id ? `/${uuid(id)}` : ''}`, id ? 'PUT' : 'POST', input, location)
export const saveReferenceSupplier = (input: SupplierInput, id?: string) => referenceCommand(`${root}/suppliers${id ? `/${uuid(id)}` : ''}`, id ? 'PUT' : 'POST', input, supplier)
export const archiveReferenceSku = (id: string, revision: number) => referenceCommand(`${root}/skus/${uuid(id)}/archive`, 'POST', { expectedRevision: revision }, sku)
export const archiveReferenceLocation = (id: string, revision: number) => referenceCommand(`${root}/locations/${uuid(id)}/archive`, 'POST', { expectedRevision: revision }, location)
export const archiveReferenceSupplier = (id: string, revision: number) => referenceCommand(`${root}/suppliers/${uuid(id)}/archive`, 'POST', { expectedRevision: revision }, supplier)
