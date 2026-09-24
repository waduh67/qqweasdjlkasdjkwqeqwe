export const replenishmentLabels = { PENDING: 'Menunggu pengisian', FULFILLED: 'Kebutuhan terpenuhi', CANCELLED: 'Dibatalkan' }
export const replenishmentLink = (values: Record<string, string>) => `/warehouse/replenishment?${new URLSearchParams(values)}`
