import { describe, expect, it, vi } from 'vitest'

async function pageSource(name: 'CustomersPage.tsx' | 'InvoicesPage.tsx') {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages', name), 'utf8')
}

describe('kontrak presentasi tabel pelanggan', () => {
  it('memakai presentasi resource dengan status teks biasa dan mempertahankan detail serta aksi per baris', async () => {
    const source = await pageSource('CustomersPage.tsx')

    expect(source).toMatch(/<DataTable[\s\S]*?presentation="resource"/)
    expect(source).not.toMatch(/cell: \(c\) => \([\s\S]*?<StatusBadge/)
    expect(source).toContain('customerStatusLabel(c.status)')
    expect(source).toContain('onCellClick: (c) => setDetailId(c.id)')
    expect(source).toContain('inlineActions: hasRowActions ? inlineActions : undefined')
    expect(source).toContain('selection={canDelete ? { selected, onChange: setSelected } : undefined}')
  })
})
