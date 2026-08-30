import { describe, expect, it, vi } from 'vitest'

async function workOrdersPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/WorkOrdersPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel Work Order', () => {
  it('memakai presentasi resource dengan sel ringkas sambil mempertahankan detail dan status work order', async () => {
    const source = await workOrdersPageSource()

    expect(source).toMatch(/<DataTable[\s\S]*?presentation="resource"/)
    expect(source).not.toContain('className="badge"')
    expect(source).not.toContain('<AssigneeChips')
    expect(source).toContain('cell: (wo) => TYPE_LABEL[wo.type]')
    expect(source).toContain('cell: (wo) => PRIORITY_LABEL[wo.priority]')
    expect(source).toContain('cell: (wo) => assigneeLabel(wo)')
    expect(source).toContain('onCellClick: (wo) => navigate(`/work-orders/${wo.id}`)')
    expect(source).toContain('cell: (wo) => <WoStatusBadge status={wo.status} />')
  })
})
