import { describe, expect, it, vi } from 'vitest'

async function usersPageSource() {
  const { readFile } = await vi.importActual<{ readFile: (path: string, encoding: string) => Promise<string> }>(
    'node:fs/promises',
  )
  const { resolve } = await vi.importActual<{ resolve: (...paths: string[]) => string }>('node:path')
  return readFile(resolve('src/pages/UsersPage.tsx'), 'utf8')
}

describe('kontrak presentasi tabel pengguna', () => {
  it('memakai presentasi resource dengan sel status teks dan mempertahankan aksi serta izin pengguna', async () => {
    const source = await usersPageSource()

    expect(source).toMatch(/<DataTable[\s\S]*?presentation="resource"/)
    expect(source).not.toContain('StatusBadge')
    expect(source).not.toContain('<Badge')
    expect(source).toContain("cell: (u) => (u.status === 'ACTIVE' ? 'Aktif' : 'Tidak aktif')")
    expect(source).toContain("cell: (u) => (u.twoFactorEnabled ? 'Aktif' : '–')")
    expect(source).toContain('rowActions={hasRowActions ? rowActions : undefined}')
    expect(source).toContain('selection={canDelete ? { selected, onChange: setSelected } : undefined}')
    expect(source).toContain("const canAssign = can('iam.user.assign')")
    expect(source).toContain("const canUpdate = can('iam.user.update')")
    expect(source).toContain("const canDelete = can('iam.user.delete')")
  })
})
