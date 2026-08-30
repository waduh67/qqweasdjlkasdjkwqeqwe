import { describe, expect, it, vi } from 'vitest'

async function tenantsPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/TenantsPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel Tenant', () => {
  it('memakai presentasi resource dengan slug ringkas, status, dan aksi tenant yang tetap berizin', async () => {
    const source = await tenantsPageSource()

    expect(source).toMatch(/<DataTable[\s\S]*?presentation="resource"/)
    expect(source).toContain('cell: (t) => t.slug')
    expect(source).toContain('cell: (t) => <StatusBadge status={t.status} />')
    expect(source).toContain('rowActions={canSubscription || canManageTenant || canDeleteTenant ? rowActions : undefined}')
    expect(source).toContain("key: 'subscription'")
    expect(source).toContain("key: 'toggle'")
    expect(source).toContain("key: 'delete'")
  })
})
