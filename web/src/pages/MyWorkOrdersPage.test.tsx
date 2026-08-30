import { describe, expect, it, vi } from 'vitest'

async function myWorkOrdersPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/MyWorkOrdersPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel Tugas Saya', () => {
  it('memakai presentasi resource dengan sel ringkas serta mempertahankan detail dan status work order', async () => {
    const source = await myWorkOrdersPageSource()

    expect(source).toMatch(/<DataTable[\s\S]*?presentation="resource"/)
    expect(source).not.toContain('className="badge"')
    expect(source).not.toContain('<AssigneeChips')
    expect(source).toContain('cell: (wo) => PRIORITY_LABEL[wo.priority]')
    expect(source).toContain('cell: (wo) => assigneeLabel(wo)')
    expect(source).toContain('onCellClick: (wo) => navigate(`/my-work-orders/${wo.id}`)')
    expect(source).toContain('cell: (wo) => <WoStatusBadge status={wo.status} />')
  })
})
