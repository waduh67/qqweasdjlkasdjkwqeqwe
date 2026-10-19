import { api } from './client'
import { array, boolean, integer, nullable, oneOf, plainText, record, text, timestamp, uuid, WarehouseDataError } from './warehouse/codec'
import { captureCommandSession, command, parameters, query, type WarehouseCommand } from './warehouse/transport'

export const FIELD_TEAMS = ['NE', 'FO'] as const
export const REPORT_SLOTS = ['MORNING', 'EVENING'] as const
export const SLOT_LABELS = { MORNING: 'Pagi', EVENING: 'Sore' } as const
const base = '/api/v1/field'
function technician(value: unknown) { const r = record(value); return { id: uuid(r.id), name: text(r.name), team: oneOf(r.team, FIELD_TEAMS) } }
export function fieldSettings(value: unknown) {
  const r = record(value)
  return { revision: integer(r.revision), saturdayOff: boolean(r.saturdayOff), foOverdueLimit: integer(r.foOverdueLimit), foPointTarget: integer(r.foPointTarget) }
}
export function workCalendar(value: unknown) {
  const r = record(value)
  return { year: integer(r.year), revision: integer(r.revision), saturdayOff: boolean(r.saturdayOff), refreshedAt: nullable(r.refreshedAt, timestamp, 'refreshedAt'), syncError: nullable(r.syncError, text, 'syncError'),
    holidays: array(r.holidays, v => { const h = record(v); return { date: text(h.date), name: text(h.name), source: oneOf(h.source, ['IMPORTED', 'MANUAL']) } }) }
}
export function dailyReport(value: unknown) {
  const r = record(value)
  return { id: uuid(r.id), technicianId: uuid(r.technicianId), technicianName: text(r.technicianName), date: text(r.date), slot: oneOf(r.slot, REPORT_SLOTS), revision: integer(r.revision), revisionId: uuid(r.revisionId),
    notes: plainText(r.notes), submittedAt: timestamp(r.submittedAt), photos: array(r.photos, v => { const p = record(v); return { id: uuid(p.id), contentType: oneOf(p.contentType, ['image/jpeg', 'image/png']), sizeBytes: integer(p.sizeBytes) } }, 'photos', 5) }
}
function completeness(value: unknown) {
  const r = record(value)
  return { date: text(r.date), workingDay: boolean(r.workingDay), total: integer(r.total), morning: integer(r.morning), evening: integer(r.evening), incomplete: integer(r.incomplete), calendarError: nullable(r.calendarError, text, 'calendarError'),
    technicians: array(r.technicians, technician), rows: array(r.rows, v => { const row = record(v); return { technician: technician(row.technician), morning: nullable(row.morning, dailyReport, 'morning'), evening: nullable(row.evening, dailyReport, 'evening') } }) }
}
function percentage(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0 || value > 100) throw new WarehouseDataError('percentage')
  return value
}
export function technicianKpi(value: unknown) {
  const r = record(value)
  return { month: text(r.month), workingDays: integer(r.workingDays), saturdayOff: boolean(r.saturdayOff), average: percentage(r.average), calendarError: nullable(r.calendarError, text, 'calendarError'), technicians: array(r.technicians, technician),
    rows: array(r.rows, v => {
      const row = record(v)
      const metrics = array(row.metrics, m => { const metric = record(m); return { key: text(metric.key), label: text(metric.label), actual: integer(metric.actual), target: integer(metric.target), percentage: percentage(metric.percentage) } }, 'metrics', 4)
      if (metrics.length !== 4) throw new WarehouseDataError('metrics')
      return { technician: technician(row.technician), percentage: percentage(row.percentage), metrics,
        overdue: array(row.overdue, v => { const wo = record(v); return { id: uuid(wo.id), code: text(wo.code), title: text(wo.title), overdueAt: timestamp(wo.overdueAt) } }),
        completed: array(row.completed, v => { const wo = record(v); return { id: uuid(wo.id), code: text(wo.code), title: text(wo.title), completedAt: timestamp(wo.completedAt), category: oneOf(wo.category, ['NONE', 'PSB', 'PROJECT']), points: integer(wo.points) } }),
        clients: array(row.clients, v => { const c = record(v); return { id: uuid(c.id), name: text(c.name), visits: integer(c.visits), target: integer(c.target) } }) }
    }) }
}
export type FieldSettings = ReturnType<typeof fieldSettings>
export type WorkCalendar = ReturnType<typeof workCalendar>
export type WorkHoliday = WorkCalendar['holidays'][number]
export type DailyReport = ReturnType<typeof dailyReport>
export type ReportSlot = typeof REPORT_SLOTS[number]
export type TechnicianKpi = ReturnType<typeof technicianKpi>
export type KpiRow = TechnicianKpi['rows'][number]
export type FieldTechnician = ReturnType<typeof technician>
export type FieldSettingsInput = Omit<FieldSettings, 'revision'> & { readonly expectedRevision: number }
export type HolidayInput = { readonly date: string; readonly name: string; readonly previousDate: string | null; readonly expectedRevision: number }
export type DailyReportInput = { readonly date: string; readonly slot: ReportSlot; readonly notes: string; readonly expectedRevision: number }
function captured<T>(operation: WarehouseCommand<T>): WarehouseCommand<T> {
  const assertSession = captureCommandSession()
  return { ...operation, execute() { assertSession(); return operation.execute() } }
}
export const field = {
  settings: () => query(base + '/settings', fieldSettings),
  saveSettings: (input: FieldSettingsInput) => captured(command(base + '/settings', 'PUT', input, fieldSettings)),
  calendar: (year: number) => query(base + '/calendar/' + year, workCalendar),
  saveHoliday: (year: number, input: HolidayInput) => captured(command(base + '/calendar/' + year + '/holidays', 'POST', input, workCalendar)),
  deleteHoliday: (calendar: WorkCalendar, date: string) => captured(command(base + '/calendar/' + calendar.year + '/holidays/' + date, 'DELETE', { expectedRevision: calendar.revision }, workCalendar)),
  sync: (calendar: WorkCalendar) => captured(command(base + '/calendar/' + calendar.year + '/sync', 'POST', { expectedRevision: calendar.revision }, workCalendar)),
  kpi: (month: string, team: string, technicianId: string) => query(base + '/kpi' + parameters({ month, team, technicianId }), technicianKpi),
  completeness: (date: string, technicianId: string) => query(base + '/reports/completeness' + parameters({ date, technicianId }), completeness),
  async history(month: string, technicianId: string, page: number) {
    const r = record(await api.get<unknown>(base + '/reports' + parameters({ month, technicianId, page, size: 20 })))
    const result = { content: array(r.content, dailyReport, 'content', 20), page: integer(r.page), size: integer(r.size), totalElements: integer(r.totalElements) }
    if (result.page !== page || result.size !== 20 || result.content.length > result.totalElements || (result.content.length > 0 && result.page * result.size + result.content.length > result.totalElements)) throw new WarehouseDataError('report history')
    return result
  },
  timeline: (id: string) => query(base + '/reports/' + uuid(id) + '/revisions', v => array(v, dailyReport)),
  photo: (report: DailyReport, id: string) => api.blob(base + '/reports/' + report.id + '/revisions/' + report.revision + '/photos/' + uuid(id)),
  report(input: DailyReportInput, photos: readonly File[]): WarehouseCommand<DailyReport> {
    const key = crypto.randomUUID(), path = base + '/reports', assertSession = captureCommandSession()
    const files = photos.map(f => ({ name: f.name, bytes: f.slice(0, f.size, f.type) }))
    const body = JSON.stringify({ ...input, files: files.map(f => ({ name: f.name, size: f.bytes.size, type: f.bytes.type })) })
    let pending: Promise<DailyReport> | null = null
    return { key, path, body, execute() {
      assertSession()
      if (pending) return pending
      const data = new FormData()
      data.append('date', input.date); data.append('slot', input.slot); data.append('notes', input.notes); data.append('expectedRevision', String(input.expectedRevision))
      files.forEach(f => data.append('photos', f.bytes, f.name))
      pending = api.request<unknown>(path, { method: 'POST', body: data, headers: { 'Idempotency-Key': key } }).then(dailyReport).finally(() => { pending = null })
      return pending
    } }
  },
}
