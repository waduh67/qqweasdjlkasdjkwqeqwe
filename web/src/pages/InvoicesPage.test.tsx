import { describe, expect, it, vi } from 'vitest'

async function invoicePageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>('node:fs/promises')
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/InvoicesPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel tagihan', () => {
  it('memakai presentasi resource dengan status teks biasa dan mempertahankan detail serta operasi pembayaran', async () => {
    const source = await invoicePageSource()

    expect(source).toMatch(/<DataTable[\s\S]*?presentation="resource"/)
    expect(source).not.toMatch(/cell: \(i\) => \([\s\S]*?<Badge tone=\{INVOICE_TONE\[i.status\]\}/)
    expect(source).toContain('`${INVOICE_LABEL[i.status]}${i.prorated ? \' · Prorata\' : \'\'}`')
    expect(source).toContain('onCellClick: openDetail')
    expect(source).toContain('inlineActions: rowActions')
    expect(source).toContain("key: 'pay'")
    expect(source).toContain("key: 'refund'")
  })
})
