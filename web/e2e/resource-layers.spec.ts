import { expect, test } from '@playwright/test'

for (const width of [375, 1280]) {
  test(`nested warehouse forms retain focus, draft and list at ${width}px`, async ({ page }) => {
    const profile = { id: 'qa', email: 'qa@example.test', name: 'QA', tenantId: 'qa', tenantSlug: 'qa', platformAdmin: true, permissions: [], areaIds: [], roleIds: [], twoFactorEnabled: true }
    const reads: string[] = [], writes: string[] = [], errors: string[] = []
    let releaseAreas!: () => void
    const areasReady = new Promise<void>(resolve => { releaseAreas = resolve })
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'test-refresh'))
    await page.route('**/api/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname
      let body: unknown = { items: [], content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 }
      if (path === '/api/auth/refresh') body = { accessToken: 'test-access', tokenType: 'Bearer', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'test-refresh', refreshTokenExpiresAt: '2099-01-02T00:00:00Z', user: profile }
      else if (path === '/api/me') body = profile
      else if (path === '/api/subscription/lock') body = { locked: false }
      else if (path === '/api/areas') { await areasReady; body = [] }
      if (path === '/api/v1/warehouse/receipts') reads.push(request.url())
      if (request.method() !== 'GET' && path !== '/api/auth/refresh') writes.push(path)
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    })
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/warehouse/receipts')
    const filter = page.getByRole('textbox', { name: 'Serial barang', includeHidden: true })
    await filter.fill('ONU-KEEP')
    await expect.poll(() => reads.some(url => url.includes('serial=ONU-KEEP'))).toBe(true)
    const listReads = reads.length
    await page.getByRole('button', { name: 'Buat penerimaan', exact: true }).click()
    const reference = page.getByRole('textbox', { name: 'Referensi surat jalan' })
    await reference.fill('SJ-KEEP')
    const launcher = page.getByRole('button', { name: 'Tambah lokasi pemeriksaan', exact: true })
    await launcher.click()
    await expect(page.getByRole('dialog', { name: 'Lokasi', exact: true })).toBeVisible()
    releaseAreas()
    const child = page.locator('[data-resource-layer="1"]')
    await expect(child.getByRole('textbox', { name: 'Nama lokasi' })).toBeVisible()
    const parentLauncher = child.getByRole('button', { name: 'Tambah lokasi induk' })
    await parentLauncher.click()
    const grandchild = page.locator('[data-resource-layer="2"]')
    await expect(grandchild.getByRole('textbox', { name: 'Nama lokasi' })).toBeVisible()
    await grandchild.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(parentLauncher).toBeFocused()
    await child.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(launcher).toBeFocused()
    await expect(reference).toHaveValue('SJ-KEEP')
    await page.getByRole('button', { name: 'Tutup', exact: true }).click()
    await page.getByRole('button', { name: 'Buang perubahan', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(filter).toHaveValue('ONU-KEEP')
    expect(reads).toHaveLength(listReads)
    expect(writes).toEqual([])
    expect(errors).toEqual([])
  })
}
