import { array, boolean, integer, nullable, pageOf, record, text, uuid, type Decoder } from './codec'
import { command, parameters, query } from './transport'

function named(value: unknown, path: string) {
  const row = record(value, path)
  return { row, id: uuid(row.id, `${path}.id`), name: text(row.name, `${path}.name`) }
}
function user(value: unknown, path = 'user') {
  const { row, ...base } = named(value, path)
  return { ...base, email: text(row.email, `${path}.email`), status: text(row.status, `${path}.status`) }
}
function area(value: unknown, path = 'area') {
  const { row, ...base } = named(value, path)
  return { ...base, code: text(row.code, `${path}.code`), parentId: nullable(row.parentId, uuid, `${path}.parentId`) }
}
function site(value: unknown, path = 'site') {
  const { row, ...base } = named(value, path)
  return { ...base, code: text(row.code, `${path}.code`), areaId: nullable(row.areaId, uuid, `${path}.areaId`) }
}
function legacyPage<T>(decode: Decoder<T>) {
  return (value: unknown) => { const row = record(value); return pageOf(decode)({ ...row, items: row.content }) }
}
export type SetupUser = ReturnType<typeof user>
export type SetupArea = ReturnType<typeof area>
export type SetupSite = ReturnType<typeof site>
export const listSetupUsers = (search: string, page: number) => query(`/api/users${parameters({ query: search, page, size: 25 })}`, legacyPage(user))
export const listSetupSites = (search: string, page: number) => query(`/api/sites${parameters({ query: search, page, size: 25 })}`, legacyPage(site))
export const listSetupAreas = () => query('/api/areas', value => array(value, area, 'areas', 10000))

function scope(value: unknown, path = 'scope') {
  const row = record(value, path)
  return { id: uuid(row.id, `${path}.id`), userId: uuid(row.userId, `${path}.userId`), locationId: uuid(row.locationId, `${path}.locationId`),
    active: boolean(row.active, `${path}.active`), revision: integer(row.revision, `${path}.revision`) }
}
export const listUserWarehouseScopes = (userId: string) => query(`/api/v1/warehouse/settings/scopes/${uuid(userId)}`, value => array(value, scope, 'scopes', 10000))
export const saveUserWarehouseScope = (userId: string, locationId: string, expectedRevision: number, active: boolean) =>
  command(`/api/v1/warehouse/settings/scopes/${uuid(userId)}/${uuid(locationId)}`, 'PUT', { expectedRevision, active }, scope)
