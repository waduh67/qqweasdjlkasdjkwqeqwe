import { timestamp } from './approvals'
import { nullable, oneOf, record, WarehouseDataError } from './codec'

export function draftExpiry(value: unknown, path = 'draftExpiry') {
  const row = record(value, path)
  return { deadline: timestamp(row.deadline, path), recordedAt: nullable(row.recordedAt, timestamp, path),
    reason: oneOf(row.reason, ['IDLE_DEADLINE'], path) }
}
export type DraftExpiry = ReturnType<typeof draftExpiry>

/** Old command responses omit this current-state overlay. */
export function draftExpiryFields(value: unknown, expired = false): { draftExpiry?: DraftExpiry } {
  if (value === null || value === undefined) {
    if (expired) throw new WarehouseDataError('draftExpiry')
    return {}
  }
  return { draftExpiry: draftExpiry(value) }
}
