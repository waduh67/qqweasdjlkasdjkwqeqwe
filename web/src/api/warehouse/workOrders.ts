import { array, nullable, oneOf, pageOf, record, text, uuid } from './codec'
import { parameters, query } from './transport'

export const WORK_ORDER_STATES = ['DRAFT', 'ASSIGNED', 'IN_PROGRESS', 'DONE', 'CANCELLED'] as const
function workOrder(value: unknown, path = 'workOrder') {
  const row = record(value, path)
  return { id: uuid(row.id, path), code: text(row.code, path), title: text(row.title, path), status: oneOf(row.status, WORK_ORDER_STATES, path),
    type: oneOf(row.type, ['PSB', 'REPAIR', 'MIGRATION', 'DISMANTLE', 'PREVENTIVE'], path), customerId: nullable(row.customerId, uuid, path), customerName: nullable(row.customerName, text, path),
    assignees: array(row.assignees, (value, field = 'assignee') => { const person = record(value, field); return { id: uuid(person.id, field), name: nullable(person.name, text, field) } }, path) }
}
export type MaterialWorkOrder = ReturnType<typeof workOrder>
export const listMaterialWorkOrders = (filter: { query?: string; status?: string; page?: number; customerId?: string; type?: MaterialWorkOrder['type'] } = {}) => query(`/api/work-orders${parameters({ ...filter, size: 25 })}`, (value, path) => {
  const row = record(value, path)
  return pageOf(workOrder)({ ...row, items: row.content }, path)
})
export const getMaterialWorkOrder = (id: string) => query(`/api/work-orders/${uuid(id)}`, (value, path) => workOrder(record(value, path).workOrder, path))
