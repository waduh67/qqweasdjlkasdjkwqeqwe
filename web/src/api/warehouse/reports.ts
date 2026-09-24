import { api } from '../client'
import { integer, oneOf, pageOf, uuid, WarehouseDataError } from './codec'
import { stockRow } from './models'
import { unknownStock, type StockFilter } from './stock'
import { parameters, query } from './transport'
import { reportAssignment, reportCard, reportCosts, reportCustody, reportMovement, reportPrint } from './reportModels'

export const REPORT_KINDS = ['stock', 'unknown-stock', 'stock-card', 'custody-aging', 'transit-backlog', 'loan-assets', 'sold-assets', 'work-order-costs', 'movements'] as const
export type ReportKind = typeof REPORT_KINDS[number]
export interface ReportFilter extends StockFilter { workOrderId?: string }
export const historyReport = (kind: ReportKind) => ['stock-card', 'movements', 'work-order-costs'].includes(kind)
const root = '/api/v1/warehouse/reports'
export async function listReport(kind: ReportKind, filter: ReportFilter = {}) {
  const path = `${root}/${oneOf(kind, REPORT_KINDS)}${parameters({ ...filter })}`
  switch (kind) {
    case 'stock': return { kind, page: await query(path, pageOf(stockRow)) } as const
    case 'unknown-stock': return { kind, page: await query(path, pageOf(unknownStock)) } as const
    case 'stock-card': return { kind, page: await query(path, pageOf(reportCard)) } as const
    case 'movements': return { kind, page: await query(path, pageOf(reportMovement)) } as const
    case 'custody-aging': case 'transit-backlog': return { kind, page: await query(path, pageOf(reportCustody)) } as const
    case 'loan-assets': case 'sold-assets': return { kind, page: await query(path, pageOf(reportAssignment)) } as const
    case 'work-order-costs': return { kind, page: await query(path, reportCosts) } as const
  }
}
export type WarehouseReport = Awaited<ReturnType<typeof listReport>>
export async function exportReport(kind: ReportKind, filter: ReportFilter = {}) {
  const values = { ...filter }; delete values.page; delete values.size
  const blob = await api.blob(`${root}/${oneOf(kind, REPORT_KINDS)}/export.csv${parameters(values)}`)
  if (blob.type.split(';')[0] !== 'text/csv') throw new WarehouseDataError('export')
  return blob
}
export async function getReportPrint(id: string, revision: number) {
  const data = await query(`${root}/documents/${uuid(id)}/revisions/${integer(revision)}/print`, reportPrint)
  if (data.documentId !== id || data.documentRevision !== revision) throw new WarehouseDataError('print.binding')
  return data
}

/** Reject malformed deep links before a broad request can replace the intended restricted query. */
export function reportParams(params: URLSearchParams): { kind: ReportKind; filter: ReportFilter; print?: { id: string; revision: number } } {
  const keys = ['kind', 'page', 'skuId', 'serial', 'locationId', 'status', 'condition', 'owner', 'from', 'until', 'sort', 'direction', 'workOrderId', 'documentId', 'revision']
  if ([...params].some(([key, value]) => !keys.includes(key) || params.getAll(key).length !== 1 || !value.trim() || value.length > 128)) throw new WarehouseDataError('filters')
  const kind = oneOf(params.get('kind') ?? 'movements', REPORT_KINDS), filter: ReportFilter = {}
  for (const key of ['skuId', 'locationId', 'workOrderId'] as const) if (params.has(key)) filter[key] = uuid(params.get(key))
  if (filter.workOrderId && kind !== 'work-order-costs') throw new WarehouseDataError('workOrderId')
  for (const key of ['serial', 'status', 'condition', 'owner', 'from', 'until'] as const) if (params.has(key)) filter[key] = params.get(key)!
  if (params.has('sort')) filter.sort = oneOf(params.get('sort'), historyReport(kind) ? ['createdAt', 'id'] : ['name', 'createdAt', 'id'])
  if (params.has('direction')) filter.direction = oneOf(params.get('direction'), ['asc', 'desc'])
  const numeric = (key: string) => { const v = params.get(key)!; if (!/^(0|[1-9][0-9]*)$/.test(v)) throw new WarehouseDataError(key); return integer(Number(v)) }
  if (params.has('page')) filter.page = numeric('page')
  if (Boolean(filter.from) !== Boolean(filter.until) || (filter.from && filter.until && (!Number.isFinite(Date.parse(filter.from)) || !Number.isFinite(Date.parse(filter.until)) || Date.parse(filter.from) >= Date.parse(filter.until) || Date.parse(filter.until) - Date.parse(filter.from) > 366 * 86400000))) throw new WarehouseDataError('dates')
  if (params.has('documentId') !== params.has('revision')) throw new WarehouseDataError('print')
  return { kind, filter, ...(params.has('documentId') ? { print: { id: uuid(params.get('documentId')), revision: numeric('revision') } } : {}) }
}
