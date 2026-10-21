import { array, decimal, integer, oneOf, record, text, uuid } from './codec'
import { query } from './transport'
import { referenceRequest, REQUEST_STATES } from './referenceRequests'

export const getRequestOverview = () => query('/api/v2/warehouse/requests/overview', value => {
  const row = record(value), states = record(row.states)
  return { states: REQUEST_STATES.map(state => ({ state, count: integer(states[state]) })), actionable: integer(row.actionable), latest: array(row.latest, referenceRequest, 'latest', 5) }
})
export const getStockOverview = () => query('/api/v2/warehouse/stock/overview', value => {
  const row = record(value)
  return { safe: integer(row.safe), low: integer(row.low), empty: integer(row.empty), attention: array(row.attention, value => {
    const item = record(value)
    return { skuId: uuid(item.skuId), code: text(item.code), name: text(item.name), warehouseId: uuid(item.warehouseId), warehouseName: text(item.warehouseName), baseUnit: oneOf(item.baseUnit, ['EA', 'MM']), availableBase: decimal(item.availableBase), minimumBase: decimal(item.minimumBase) }
  }, 'attention', 5) }
})
