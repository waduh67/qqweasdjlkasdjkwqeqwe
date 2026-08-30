import { describe, expect, it, vi } from 'vitest'

async function monitoringPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/MonitoringPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel Monitoring', () => {
  it('memakai presentasi resource dengan status teks biasa dan mempertahankan aksi collector serta alarm per-baris', async () => {
    const source = await monitoringPageSource()

    expect((source.match(/presentation="resource"/g) ?? [])).toHaveLength(2)
    expect(source).not.toContain('StatusBadge')
    expect(source).toContain("inlineActions: canManageCollector ? collectorActions : undefined")
    expect(source).toContain("key: 'delete'")
    expect(source).toContain('inlineActions: alarmActions')
    expect(source).toContain("key: 'history'")
    expect(source).toContain("key: 'ack'")
    expect(source).toContain("key: 'clear'")
  })
})
