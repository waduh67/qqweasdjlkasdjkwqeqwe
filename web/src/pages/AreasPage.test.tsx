import { describe, expect, it, vi } from 'vitest'

async function areasPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/AreasPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel Area', () => {
  it('memakai presentasi resource dengan sel teks biasa dan mempertahankan aksi hapus berizin', async () => {
    const source = await areasPageSource()

    expect(source).toContain('presentation="resource"')
    expect(source).not.toContain('className="badge"')
    expect(source).toContain("const canDelete = can('iam.area.delete')")
    expect(source).toContain("rowActions={canDelete ? rowActions : undefined}")
    expect(source).toContain("key: 'delete'")
  })
})
