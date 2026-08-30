import { describe, expect, it, vi } from 'vitest'

async function hotspotPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/HotspotPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel Hotspot', () => {
  it('memigrasikan daftar situs dan voucher sebagai resource sambil mempertahankan batch operasional serta aksi voucher', async () => {
    const source = await hotspotPageSource()

    expect((source.match(/presentation="resource"/g) ?? [])).toHaveLength(2)
    expect(source).not.toContain('StatusBadge')
    expect(source).toContain("cell: (site) => PORTAL_MODE_LABEL[site.portalMode]")
    expect(source).toContain("cell: (voucher) => STATUS_LABEL[voucher.status]")
    expect(source).toContain("cell: (batch) => batch.status")
    expect(source).toContain('rowActions={siteActions}')
    expect(source).toContain('rowActions={voucherActions}')
    expect(source).toContain("key: 'edit'")
    expect(source).toContain("key: 'revoke'")
  })
})
