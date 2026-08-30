import { describe, expect, it, vi } from 'vitest'

async function platformJobsPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/PlatformJobsPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel pekerjaan latar', () => {
  it('memakai presentasi resource dengan status operasional dan galat tetap terbaca sebagai teks', async () => {
    const source = await platformJobsPageSource()

    expect(source).toContain('presentation="resource"')
    expect(source).not.toContain('<Badge')
    expect(source).toContain("? 'Macet'")
    expect(source).toContain("? 'Ronde terakhir gagal'")
    expect(source).toContain(": 'Sehat'")
    expect(source).toContain("header: 'Galat terakhir'")
    expect(source).toContain("cell: (j) => j.lastError == null ? '—' : j.lastError")
    expect(source).toContain("header: 'Ronde'")
    expect(source).toContain('j.failures.toLocaleString(\'id-ID\')')
  })
})
