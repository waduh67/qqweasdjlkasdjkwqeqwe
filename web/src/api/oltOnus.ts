import { api } from './client'

export interface OltDeviceOnu {
  readonly index: string
  readonly ontId: string | null
  readonly name: string | null
  readonly serialNumber: string
  readonly state: string | null
  readonly runningState: 'ONLINE' | 'OFFLINE' | 'LOS' | 'UNKNOWN' | null
  readonly configState: string | null
  readonly deviceType: string | null
  readonly rxPowerDbm: number | null
  readonly lastUpTime: string | null
  readonly lastDownTime: string | null
  readonly lastDownCause: string | null
}

export interface OltOnusSnapshot {
  readonly oltId: string
  readonly oltCode: string
  readonly vendor: string
  readonly systemDescription: string | null
  readonly readAt: string
  readonly onus: readonly OltDeviceOnu[]
  readonly warnings: readonly string[]
}

export class InvalidOltOnusResponseError extends Error {
  readonly name = 'InvalidOltOnusResponseError'

  constructor() {
    super('Respons ONU di OLT tidak sesuai kontrak')
  }
}

function isObjectRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseObject(value: unknown): Record<string, unknown> {
  if (!isObjectRecord(value)) throw new InvalidOltOnusResponseError()
  return value
}

function parseText(value: unknown): string {
  if (typeof value !== 'string') throw new InvalidOltOnusResponseError()
  return value
}

function parseNullableText(value: unknown): string | null {
  return value === null ? null : parseText(value)
}

function parseArray<T>(value: unknown, parseItem: (item: unknown) => T): T[] {
  if (!Array.isArray(value)) throw new InvalidOltOnusResponseError()
  return value.map((item: unknown) => parseItem(item))
}

function parseRunningState(value: unknown): OltDeviceOnu['runningState'] {
  switch (value) {
    case null:
    case 'ONLINE':
    case 'OFFLINE':
    case 'LOS':
    case 'UNKNOWN':
      return value
    default:
      throw new InvalidOltOnusResponseError()
  }
}

function parsePower(value: unknown): number | null {
  if (value === null || (typeof value === 'number' && Number.isFinite(value))) return value
  throw new InvalidOltOnusResponseError()
}

function parseOnu(value: unknown): OltDeviceOnu {
  const row = parseObject(value)
  return {
    index: parseText(row.index),
    ontId: parseNullableText(row.ontId),
    name: parseNullableText(row.name),
    serialNumber: parseText(row.serialNumber),
    state: parseNullableText(row.state),
    runningState: parseRunningState(row.runningState),
    configState: parseNullableText(row.configState),
    deviceType: parseNullableText(row.deviceType),
    rxPowerDbm: parsePower(row.rxPowerDbm),
    lastUpTime: parseNullableText(row.lastUpTime),
    lastDownTime: parseNullableText(row.lastDownTime),
    lastDownCause: parseNullableText(row.lastDownCause),
  }
}

function parseReadAt(value: unknown): string {
  const readAt = parseText(value)
  const isoTimestamp = /^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:[.][0-9]+)?(?:Z|[+-][0-9]{2}:[0-9]{2})$/
  if (!isoTimestamp.test(readAt) || !Number.isFinite(Date.parse(readAt))) throw new InvalidOltOnusResponseError()
  return readAt
}

export function parseOltOnus(value: unknown): OltOnusSnapshot {
  const snapshot = parseObject(value)
  return {
    oltId: parseText(snapshot.oltId),
    oltCode: parseText(snapshot.oltCode),
    vendor: parseText(snapshot.vendor),
    systemDescription: parseNullableText(snapshot.systemDescription),
    readAt: parseReadAt(snapshot.readAt),
    onus: parseArray(snapshot.onus, parseOnu),
    warnings: parseArray(snapshot.warnings, parseText),
  }
}

export async function readOltOnus(oltId: string, signal?: AbortSignal): Promise<OltOnusSnapshot> {
  const value = await api.request<unknown>('/api/monitoring/olts/' + encodeURIComponent(oltId) + '/onus', { method: 'GET', signal })
  const snapshot = parseOltOnus(value)
  if (snapshot.oltId !== oltId) throw new InvalidOltOnusResponseError()
  return snapshot
}
