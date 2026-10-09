import { integer, nullable, oneOf, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { referenceCommand, referenceTypes, referenceWorkOrder, type ReferenceWorkOrder } from './reference'
import { parameters, query } from './transport'

const root = '/api/v2/work-orders'
export function workArea(value: unknown) {
  const row = record(value)
  return { id: uuid(row.id), code: text(row.code), name: text(row.name) }
}
export type WorkArea = ReturnType<typeof workArea>
export function workCustomer(value: unknown) {
  const row = record(value)
  return { id: uuid(row.id), code: text(row.code), name: text(row.name), areaId: nullable(row.areaId, uuid, 'areaId'),
    status: oneOf(row.status, ['PROSPECT', 'ACTIVE', 'SUSPENDED', 'TERMINATED']) }
}
export type WorkCustomer = ReturnType<typeof workCustomer>
const contentPage = <T>(decode: (value: unknown) => T) => (value: unknown) => {
  const row = record(value), page = pageOf(decode)({ ...row, items: row.content })
  if (integer(row.totalPages) !== Math.ceil(page.totalElements / page.size)) throw new WarehouseDataError('workChoices.totalPages')
  return page
}
export const workAreas = (search: string, page: number) => query(root + '/areas' + parameters({ query: search, page, size: 25 }), contentPage(workArea))
export const getWorkArea = (id: string) => query(root + '/areas/' + uuid(id), workArea)
export const workCustomers = (search: string, page: number) => query('/api/customers' + parameters({ query: search, page, size: 25 }), contentPage(workCustomer))
export const getWorkCustomer = (id: string) => query('/api/customers/' + uuid(id), workCustomer)
export const workTypes = async (search: string, page: number, kind?: ReferenceWorkOrder['type']['workType']) => {
  const rows = (await referenceTypes()).filter(row => row.active && !row.deleted && (!kind || row.workType === kind) && row.name.toLocaleLowerCase('id').includes(search.toLocaleLowerCase('id')))
  return { items: rows.slice(page * 25, (page + 1) * 25), page, size: 25, totalElements: rows.length }
}
export type WorkDetailsInput = { readonly title: string; readonly description: string; readonly areaId: string;
  readonly priority: ReferenceWorkOrder['priority']; readonly customerId: string | null; readonly scheduledAt: string | null }
export const createReferenceWork = (input: WorkDetailsInput & { readonly technicianId: string; readonly typeId: string }) => referenceCommand(root, 'POST', input, referenceWorkOrder)
export const updateReferenceWork = (id: string, input: WorkDetailsInput & { readonly expectedRevision: number }) => referenceCommand(root + '/' + uuid(id), 'PUT', input, referenceWorkOrder)
export const assignReferenceWork = (id: string, input: { readonly expectedRevision: number; readonly technicianId: string }) => referenceCommand(root + '/' + uuid(id) + '/assignment', 'POST', input, referenceWorkOrder)
