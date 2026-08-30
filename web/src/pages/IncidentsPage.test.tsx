import { describe, expect, it, vi } from 'vitest'

async function incidentsPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/IncidentsPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel Insiden', () => {
  it('memakai presentasi resource dengan sel ringkas sambil mempertahankan detail dan aksi insiden', async () => {
    const source = await incidentsPageSource()

    expect(source).toMatch(/<DataTable[\s\S]*?presentation="resource"/)
    expect(source).toContain('cell: (i) => i.severity')
    expect(source).toContain("cell: (i) => (i.status === 'ACKNOWLEDGED' ? 'Diakui' : 'Terbuka')")
    expect(source).toContain('onCellClick: (i) => void openDetail(i.id)')
    expect(source).toContain('onAcknowledge={() => void act(detail.incident.id, \'acknowledge\', \'Insiden diakui\')}')
    expect(source).toContain('onResolve={() => void act(detail.incident.id, \'resolve\', \'Insiden ditutup\', true)}')
  })
})
