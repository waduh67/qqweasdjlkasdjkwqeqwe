import { api } from './client'

/**
 * Unduh arsip ZIP berisi seluruh data tenant sendiri (portabilitas data / offboarding).
 * Server men-stream isinya, jadi respons bisa berukuran besar dan tak punya Content-Length.
 */
export const downloadTenantArchive = () => api.blob('/api/tenant/export')
