import { describe, expect, it, vi } from 'vitest'

async function catalogPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/CatalogPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel katalog', () => {
  it('menyajikan daftar paket sebagai resource dengan sel teks satu baris dan aksi ubah per-baris', async () => {
    const source = await catalogPageSource()

    expect((source.match(/presentation="resource"/g) ?? [])).toHaveLength(1)
    expect(source).not.toContain('StatusBadge')
    expect(source).not.toContain('<Badge')
    expect(source).toContain("cell: (p) => (p.fupEnabled ? 'FUP' : '—')")
    expect(source).toContain("cell: (p) => p.serviceTypes.map((s) => SERVICE_TYPE_LABEL[s]).join(', ') || '—'")
    expect(source).toContain("cell: (p) => (p.active ? 'Aktif' : 'Nonaktif')")
    expect(source).toContain("inlineActions: canManage ? inlineActions : undefined")
    expect(source).toContain("key: 'edit'")
  })
})
