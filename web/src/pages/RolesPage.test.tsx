import { describe, expect, it, vi } from 'vitest'

async function rolesPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/RolesPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel role', () => {
  it('memakai presentasi resource dengan sel satu baris sambil mempertahankan aksi role per-baris', async () => {
    const source = await rolesPageSource()

    expect(source).toMatch(/<DataTable[\s\S]*?presentation="resource"/)
    expect(source).toContain("cell: (r) => r.systemRole ? `${r.name} (sistem)` : r.name")
    expect(source).not.toContain('<Badge>sistem</Badge>')
    expect(source).toContain("key: 'edit'")
    expect(source).toContain("key: 'delete'")
    expect(source).toContain('rowActions={rowActions}')
  })
})
