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
    const loadingPanel = await page.getByRole('dialog', { name: 'Tambah lokasi', exact: true }).elementHandle()
    await expect(page.getByRole('dialog', { name: 'Tambah lokasi', exact: true })).toBeVisible()
    releaseAreas()
    const child = page.locator('[data-resource-layer="1"]')
    await expect(child.getByRole('textbox', { name: 'Nama lokasi' })).toBeVisible()
    expect(await child.evaluate((element, original) => element === original, loadingPanel)).toBe(true)
    const parentLauncher = child.getByRole('button', { name: 'Tambah lokasi induk' })
    await parentLauncher.click()
    const grandchild = page.locator('[data-resource-layer="2"]')
    await expect(grandchild.getByRole('heading', { name: 'Tambah lokasi induk', exact: true })).toBeVisible()
    await expect(grandchild.getByRole('textbox', { name: 'Nama lokasi' })).toBeVisible()
    await expect(grandchild.getByRole('button', { name: 'Tambah lokasi induk', exact: true })).toHaveCount(0)
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

  test(`catalog parent creation returns to the original draft at ${width}px`, async ({ page }) => {
    const profile = { id: 'qa', email: 'qa@example.test', name: 'QA', tenantId: 'qa', tenantSlug: 'qa', platformAdmin: true, permissions: [], areaIds: [], roleIds: [], twoFactorEnabled: true }
    const areaId = '84a4943e-19b8-498d-b420-f1e6a9fda00d'
    const parentId = '797b131a-ddaf-46e4-90a0-e20c6ef3c5ea'
    const writes: { path: string; body: Record<string, unknown> }[] = [], errors: string[] = [], listReads: string[] = []
    let areaReads = 0
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'test-refresh'))
    await page.route('**/api/**', async route => {
      const request = route.request(), path = new URL(request.url()).pathname
      let body: unknown = { items: [], content: [], page: 0, size: 25, totalElements: 0, totalPages: 0 }
      if (path === '/api/auth/refresh') body = { accessToken: 'test-access', tokenType: 'Bearer', accessTokenExpiresAt: '2099-01-01T00:00:00Z', refreshToken: 'test-refresh', refreshTokenExpiresAt: '2099-01-02T00:00:00Z', user: profile }
      else if (path === '/api/me') body = profile
      else if (path === '/api/subscription/lock') body = { locked: false }
      else if (path === '/api/areas') { areaReads++; body = [{ id: areaId, code: 'TEBET', name: 'Tebet', parentId: null }] }
      if (path === '/api/v1/warehouse/locations' && request.method() === 'GET') listReads.push(request.url())
      if (request.method() !== 'GET' && path !== '/api/auth/refresh') {
        const input = request.postDataJSON()
        writes.push({ path, body: input })
        body = { ...input, id: parentId, revision: 0, state: 'ACTIVE' }
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
    })
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/warehouse/catalog')
    const filter = page.getByRole('textbox', { name: 'Cari lokasi', includeHidden: true })
    await filter.fill('RAK-KEEP')
    await expect.poll(() => listReads.some(url => url.includes('search=RAK-KEEP'))).toBe(true)
    const filteredReads = () => listReads.filter(url => url.includes('search=RAK-KEEP')).length
    const before = filteredReads()
    await page.getByRole('button', { name: 'Tambah lokasi', exact: true }).click()
    const original = page.locator('[data-resource-layer="0"]')
    await original.getByRole('textbox', { name: 'Kode lokasi' }).fill('RAK-KEEP')
    await original.getByRole('textbox', { name: 'Nama lokasi' }).fill('Rak tetap')
    await original.getByRole('combobox', { name: 'Jenis lokasi', exact: true }).selectOption('BIN')
    const launcher = original.getByRole('button', { name: 'Tambah lokasi induk', exact: true })
    await launcher.click()
    const parent = page.getByRole('dialog', { name: 'Tambah lokasi induk', exact: true })
    await expect(parent.getByRole('textbox', { name: 'Nama lokasi' })).toBeVisible()
    await expect(page.locator('[data-resource-layer]')).toHaveCount(2)
    await expect(parent.getByRole('button', { name: 'Tambah lokasi induk', exact: true })).toHaveCount(0)
    await parent.getByRole('textbox', { name: 'Kode lokasi' }).fill('GUDANG-UTAMA')
    await parent.getByRole('textbox', { name: 'Nama lokasi' }).fill('Gudang utama')
    await parent.getByRole('combobox', { name: 'Area lokasi', exact: true }).selectOption(areaId)
    await parent.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
    await expect(parent.getByText('Gudang utama', { exact: true })).toBeVisible()
    expect(writes).toEqual([])
    await parent.getByRole('button', { name: 'Simpan lokasi', exact: true }).click()
    await expect(parent).toHaveCount(0)
    await expect(launcher).toBeFocused()
    await expect(original.getByRole('textbox', { name: 'Kode lokasi' })).toHaveValue('RAK-KEEP')
    await expect(original.getByRole('textbox', { name: 'Nama lokasi' })).toHaveValue('Rak tetap')
    await expect(original.getByRole('combobox', { name: 'Lokasi induk', exact: true })).toHaveValue('Gudang utama · GUDANG-UTAMA')
    await expect(original.getByRole('combobox', { name: 'Area lokasi', exact: true })).toHaveValue(areaId)
    await launcher.click()
    await expect(parent.getByRole('textbox', { name: 'Nama lokasi' })).toBeVisible()
    await parent.getByRole('button', { name: 'Kembali ke panel sebelumnya' }).click()
    await expect(launcher).toBeFocused()
    await expect(original.getByRole('combobox', { name: 'Lokasi induk', exact: true })).toHaveValue('Gudang utama · GUDANG-UTAMA')
    await original.getByRole('button', { name: 'Tutup', exact: true }).click()
    await page.getByRole('button', { name: 'Buang perubahan', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(filter).toHaveValue('RAK-KEEP')
    expect(filteredReads()).toBe(before)
    expect(areaReads).toBe(3)
    expect(writes).toEqual([{ path: '/api/v1/warehouse/locations', body: { code: 'GUDANG-UTAMA', name: 'Gudang utama', kind: 'WAREHOUSE', areaId, parentLocationId: null, custodianId: null, siteId: null, issueEligible: true } }])
    expect(errors).toEqual([])
  })
}
