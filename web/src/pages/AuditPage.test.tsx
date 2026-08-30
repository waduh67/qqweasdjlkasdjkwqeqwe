import { describe, expect, it, vi } from 'vitest'

async function auditPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/AuditPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel jejak audit', () => {
  it('memakai presentasi resource sambil mempertahankan seluruh kolom audit sebagai teks biasa', async () => {
    const source = await auditPageSource()

    expect(source).toContain('presentation="resource"')
    expect(source).not.toContain('<Badge')
    expect(source).toContain('cell: (e) => e.action')
    expect(source).toContain("header: 'Waktu'")
    expect(source).toContain("header: 'Pelaku'")
    expect(source).toContain("header: 'Objek'")
    expect(source).toContain("header: 'Detail'")
    expect(source).toContain('flattenDetail(e.detail) || \'–\'')
  })
})
