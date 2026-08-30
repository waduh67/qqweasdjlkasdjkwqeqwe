import { describe, expect, it, vi } from 'vitest'

async function helpdeskPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/HelpdeskPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel Helpdesk', () => {
  it('memakai presentasi resource dengan sel ringkas serta mempertahankan detail tiket dan tautan operasional', async () => {
    const source = await helpdeskPageSource()

    expect(source).toMatch(/<DataTable[\s\S]*?presentation="resource"/)
    expect(source).toContain('cell: (t) => TICKET_CATEGORY_LABEL[t.category] ?? t.category')
    expect(source).toContain('cell: (t) => TICKET_STATUS_LABEL[t.status]')
    expect(source).toContain('onCellClick: (t) => void openDetail(t.id)')
    expect(source).toContain('to={`/customers/${t.customerId}`}')
    expect(source).toContain('to={`/work-orders/${t.workOrderId}`}')
  })
})
