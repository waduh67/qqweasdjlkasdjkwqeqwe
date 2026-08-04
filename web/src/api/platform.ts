/**
 * Endpoint level-platform untuk area Platform admin (SaaS). Daftar tenant dipakai
 * dashboard platform untuk ringkasan (total/aktif/suspended). Semua di-gate izin
 * `platform.*`; platform admin lolos via flag.
 */

import { api } from './client'
import type { PageResponse } from './types'

export interface Tenant {
  id: string
  slug: string
  name: string
  status: string
}

/** Daftar tenant (paginasi). `size` besar dipakai dashboard untuk hitung ringkasan. */
export function listTenants(size = 200): Promise<PageResponse<Tenant>> {
  return api.get(`/api/platform/tenants?size=${size}`)
}
