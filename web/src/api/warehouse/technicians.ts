import { integer, pageOf, record, text, uuid, WarehouseDataError } from './codec'
import { parameters, query } from './transport'

function technician(value: unknown) {
  const row = record(value)
  return { id: uuid(row.id), name: text(row.name), email: text(row.email) }
}
export type TechnicianChoice = ReturnType<typeof technician>
export function technicianPage(value: unknown) {
  const row = record(value)
  const page = pageOf(technician)({ ...row, items: row.content })
  if (integer(row.totalPages) !== Math.ceil(page.totalElements / page.size)) throw new WarehouseDataError('technicians.totalPages')
  return page
}
export const requestTechnicians = (queryText = '', page = 0) => query('/api/technicians' + parameters({ query: queryText, page }), technicianPage)
export const assignmentTechnicians = (queryText = '', page = 0) => query('/api/technicians' + parameters({ query: queryText, page, pureOnly: 'true' }), technicianPage)
