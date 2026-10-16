import { test, expect, type Page } from '@playwright/test'

async function setup(page: Page, networkPermission = true) {
  const writes: { path: string; body: unknown; query: Record<string, string> }[] = [], errors: string[] = []
  const profile = { id: 'qa', tenantId: 'qa', tenantSlug: 'qa', email: 'qa@example.test', name: 'Rina', platformAdmin: networkPermission, permissions: ['customer.customer.view', 'customer.customer.create', 'customer.customer.update', 'customer.subscription.update', 'catalog.plan.view', 'iam.area.view'], areaIds: [], roleIds: [], twoFactorEnabled: false }
  let batchState = 'STAGED', batchMode = 'ALREADY_INSTALLED'
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'migration-test'))
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname
    let body: unknown = { content: [], totalElements: 0, totalPages: 0, page: 0, size: 25 }
    if (path === '/api/auth/refresh') body = { accessToken: 'test', refreshToken: 'migration-test', accessTokenExpiresAt: '2099-01-01T00:00:00Z', user: profile }
    else if (path === '/api/me') body = profile
    else if (path === '/api/subscription/lock') body = { locked: false }
    else if (path === '/api/catalog/plans') body = [{ id: 'plan1', name: 'Rumah 50 Mbps', price: 150000, downMbps: 50, upMbps: 25, serviceTypes: ['PPPOE'], active: true }]
    else if (path === '/api/bng/nas') body = [{ id: 'nas1', name: 'BRAS Bandung', vendor: 'MIKROTIK' }]
    else if (path === '/api/areas') body = [{ id: 'area1', name: 'Bandung', code: 'BDG' }]
    else if (path === '/api/monitoring/discovered-onus') body = [{ id: 'observed1', serialNumber: '024654000002', oltCode: 'OLT-CRB-001', ponPortLabel: 'PON1' }]
    else if (path.endsWith('/ppp-secrets')) body = [{ name: 'ani', profile: 'rumah', comment: 'Ani', disabled: false }, { name: 'budi', profile: 'rumah', comment: 'Budi', disabled: true }]
    else if (path === '/api/onboarding/import/pppoe') body = { created: 2, skipped: 0, failed: 0, rows: [{ username: 'ani', status: 'CREATED', message: null }, { username: 'budi', status: 'CREATED', message: null }] }
    else if (path.startsWith('/api/onboarding/v1/import/customers')) {
      if (req.method() === 'POST' && !path.endsWith('/commit')) batchMode = new URL(req.url()).searchParams.get('mode') || 'ALREADY_INSTALLED'
      if (path.endsWith('/commit')) batchState = 'PROCESSING'
      else if (req.method() === 'GET' && batchState === 'PROCESSING') batchState = 'COMMITTED'
      body = { id: 'batch1', sha256: 'f'.repeat(64), mode: batchMode, state: batchState, rowCount: 2, errors: [], result: batchState === 'COMMITTED' ? { created: 2, updated: 0, skipped: 0, failed: 0, rows: [{ username: 'OLD-1', status: 'CREATED', message: null }] } : null, createdAt: '2026-10-16T00:00:00Z', preview: [
        { row: 2, identity: 'OLD-1', username: 'ani', routerName: 'BRAS Bandung', name: 'Ani', packageName: 'Rumah 50 Mbps', status: 'ACTIVE', firstInvoiceOn: '2099-11-05', billingDay: 20, monthlyFee: null },
        { row: 3, identity: 'OLD-2', username: 'budi', routerName: 'BRAS Bandung', name: 'Budi', packageName: 'Rumah 50 Mbps', status: 'ACTIVE', firstInvoiceOn: '2099-11-20', billingDay: 5, monthlyFee: null },
      ] }
    } else if (path === '/api/onboarding/migrations') {
      // Keep the form visible after a rejected write, so its submission can be inspected.
      writes.push({ path, body: req.postDataJSON(), query: Object.fromEntries(new URL(req.url()).searchParams) })
      await route.fulfill({ status: 400, json: { detail: 'Contoh validasi server.' } }); return
    }
    if (req.method() !== 'GET' && path !== '/api/auth/refresh') writes.push({ path, body: req.headers()['content-type']?.includes('json') ? req.postDataJSON() : null, query: Object.fromEntries(new URL(req.url()).searchParams) })
    await route.fulfill({ json: body })
  })
  return { writes, errors }
}
for (const width of [375, 1440]) {
  test(`manual migration groups fields and leaves billing and coordinates optional at ${width}px`, async ({ page }, info) => {
    const state = await setup(page)
    await page.setViewportSize({ width, height: 1000 }); await page.goto('/customers?q=old')
    await page.getByRole('button', { name: 'Migrasi pelanggan', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'Migrasi pelanggan', exact: true })
    await panel.getByRole('button', { name: 'Berikutnya: Pelanggan' }).click()
    await panel.getByRole('textbox', { name: 'Nama', exact: true }).fill('Budi Santoso')
    await panel.getByRole('button', { name: 'Berikutnya: Layanan' }).click()
    await panel.getByRole('combobox', { name: 'Paket internet' }).selectOption('plan1')
    await panel.getByRole('combobox', { name: 'Status layanan' }).selectOption('SUSPENDED')
    await panel.getByRole('spinbutton', { name: 'Harga bulanan (Rp)' }).fill('125000')
    await page.screenshot({ path: info.outputPath('migration-service.png'), animations: 'disabled' })
    await panel.getByRole('button', { name: 'Berikutnya: Penagihan' }).click()
    await expect(panel.getByRole('combobox', { name: 'Penagihan', exact: true })).toHaveValue('later')
    await panel.getByRole('combobox', { name: 'Penagihan', exact: true }).selectOption('start')
    await panel.getByRole('textbox', { name: 'Mulai penagihan di aplikasi ini', exact: true }).fill('2099-11-05')
    await panel.getByRole('spinbutton', { name: 'Tanggal tagih setiap bulan' }).fill('20')
    await panel.getByRole('combobox', { name: 'Penagihan', exact: true }).selectOption('later')
    await panel.getByRole('button', { name: 'Berikutnya: Lokasi' }).click()
    await panel.getByRole('textbox', { name: 'Alamat', exact: true }).fill('Jl. Melati 10')
    await panel.getByRole('button', { name: 'Berikutnya: Akun jaringan' }).click()
    await panel.getByRole('button', { name: 'Berikutnya: Perangkat' }).click()
    await expect(panel.getByRole('textbox', { name: 'Username', exact: true })).toBeVisible()
    await panel.getByRole('textbox', { name: 'Username', exact: true }).fill('budi-lama')
    await panel.getByLabel(/^Password/).fill('existing-secret')
    await panel.getByRole('combobox', { name: 'BRAS', exact: true }).selectOption('nas1')
    await page.screenshot({ path: info.outputPath('migration-network.png'), animations: 'disabled' })
    await panel.getByRole('button', { name: 'Berikutnya: Perangkat' }).click()
    await panel.getByRole('combobox', { name: 'ONU terpasang' }).click()
    await page.getByRole('option', { name: /024654000002/ }).click()
    await page.screenshot({ path: info.outputPath('migration-onu.png'), animations: 'disabled' })
    await panel.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
    await expect(panel.locator('.resource-summary')).toContainText('Ditangguhkan')
    await expect(panel.locator('.resource-summary')).toContainText('Belum dikelola')
    await expect(panel.locator('.resource-summary')).toContainText('Pelanggan + akun RADIUS')
    await expect(panel.locator('.resource-summary')).not.toContainText('existing-secret')
    await panel.getByRole('button', { name: 'Migrasikan', exact: true }).click()
    await expect.poll(() => state.writes.length).toBe(1)
    expect(state.writes[0].body).toMatchObject({ prepareRadius: true, username: 'budi-lama', password: 'existing-secret', nasId: 'nas1', status: 'SUSPENDED', firstInvoiceOn: null, billingDay: null, onuSerial: '024654000002', customer: { name: 'Budi Santoso', location: null, monthlyFeeOverride: 125000 } })
    expect(state.errors).toEqual([])
    const bounds = await panel.boundingBox(); expect(bounds!.x).toBeGreaterThanOrEqual(0); expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width + 1)
  })
}

for (const width of [375, 1440]) {
  test(`CSV billing uses customer schedules and sends defaults only when selected at ${width}px`, async ({ page }, info) => {
    const state = await setup(page)
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/import-customers')
    const panel = page.getByRole('dialog', { name: 'Migrasi pelanggan', exact: true })
    await panel.getByRole('combobox', { name: 'Cakupan impor' }).selectOption('data')
    await panel.locator('input[type=file]').setInputFiles({ name: 'billing.csv', mimeType: 'text/csv', buffer: Buffer.from('name,address,package_name,customer_code,first_invoice_on,next_billing\nAni,Bandung,Rumah 50 Mbps,OLD-1,2099-11-05,5\n') })
    await panel.getByRole('button', { name: 'Berikutnya: Penagihan' }).click()
    const choice = panel.getByRole('combobox', { name: 'Jadwal penagihan', exact: true })
    await expect(choice).toHaveValue('csv')
    await expect(panel.locator('input[type=date]')).toHaveCount(0)
    await expect(panel).toContainText('next_billing')
    await page.screenshot({ path: info.outputPath('csv-billing.png'), animations: 'disabled' })
    await choice.selectOption('defaults')
    await panel.getByRole('textbox', { name: 'Mulai penagihan di aplikasi ini', exact: true }).fill('2099-11-01')
    await panel.getByRole('spinbutton', { name: 'Tanggal tagih setiap bulan' }).fill('10')
    await page.screenshot({ path: info.outputPath('csv-billing-defaults.png'), animations: 'disabled' })
    await choice.selectOption('csv')
    await panel.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
    let staged = state.writes.filter(write => write.path === '/api/onboarding/v1/import/customers')
    expect(staged).toHaveLength(1)
    expect(staged[0].query.mode).toBe('ALREADY_INSTALLED')
    expect(staged[0].query).not.toHaveProperty('firstInvoiceOn')
    expect(staged[0].query).not.toHaveProperty('billingDay')
    await expect(panel.getByRole('columnheader', { name: 'Mulai penagihan', exact: true })).toBeVisible()
    await expect(panel.getByRole('row').filter({ hasText: 'OLD-1' }).getByText('20', { exact: true })).toBeVisible()
    await panel.getByRole('button', { name: 'Sebelumnya', exact: true }).click()
    await choice.selectOption('defaults')
    await panel.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
    staged = state.writes.filter(write => write.path === '/api/onboarding/v1/import/customers')
    expect(staged).toHaveLength(2)
    expect(staged[1].query).toMatchObject({ firstInvoiceOn: '2099-11-01', billingDay: '10' })
    expect(state.errors).toEqual([])
    const bounds = await panel.boundingBox()
    expect(bounds!.x).toBeGreaterThanOrEqual(0)
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width + 1)
  })
}

test('CSV migration stages for review, records the recovery URL and resumes completed results', async ({ page }) => {
  const state = await setup(page)
  await page.goto('/import-customers')
  const panel = page.getByRole('dialog', { name: 'Migrasi pelanggan', exact: true })
  await panel.locator('input[type=file]').setInputFiles({ name: 'old.csv', mimeType: 'text/csv', buffer: Buffer.from('name,address,package_name,customer_code,username,password,router_name\nAni,Bandung,Rumah 50 Mbps,OLD-1,ani,existing-secret,BRAS Bandung\n') })
  await panel.getByRole('button', { name: 'Berikutnya: Penagihan' }).click()
  await panel.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
  await expect(page).toHaveURL(/migrationBatch=batch1/)
  expect(state.writes.find(write => write.path === '/api/onboarding/v1/import/customers')?.query.mode).toBe('ALREADY_INSTALLED_RADIUS')
  await expect(panel.getByRole('columnheader', { name: 'Username', exact: true })).toBeVisible()
  await expect(panel.getByRole('columnheader', { name: 'BRAS', exact: true })).toBeVisible()
  await expect(panel).not.toContainText('existing-secret')
  await panel.getByRole('button', { name: 'Sebelumnya', exact: true }).click()
  await expect(panel.getByRole('button', { name: 'Migrasikan', exact: true })).toHaveCount(0)
  await panel.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
  await panel.getByRole('button', { name: 'Migrasikan', exact: true }).click()
  await expect(panel.getByRole('status')).toContainText('2 ditambahkan')
  await page.reload()
  await expect(page.getByRole('dialog').getByRole('status')).toContainText('2 ditambahkan')
  expect(state.writes.filter(write => write.path.endsWith('/commit'))).toHaveLength(1)
  expect(state.errors).toEqual([])
})

test('MikroTik migration keeps disabled accounts selected and maps profiles before review', async ({ page }, info) => {
  const state = await setup(page)
  await page.goto('/import-pppoe')
  const panel = page.getByRole('dialog', { name: 'Migrasi pelanggan', exact: true })
  await panel.getByRole('combobox', { name: 'BRAS MikroTik' }).selectOption('nas1')
  await panel.getByRole('button', { name: 'Muat akun', exact: true }).click()
  await panel.getByRole('button', { name: 'Berikutnya: Akun' }).click()
  await expect(panel.getByRole('grid')).toContainText('Ditangguhkan')
  await panel.getByRole('button', { name: 'Berikutnya: Paket' }).click()
  await panel.getByRole('combobox', { name: 'Profil rumah' }).selectOption('plan1')
  await panel.getByRole('button', { name: 'Berikutnya: Penagihan' }).click()
  await panel.getByRole('button', { name: 'Berikutnya: Lokasi' }).click()
  await panel.getByRole('button', { name: 'Tinjau + buat', exact: true }).click()
  await page.screenshot({ path: info.outputPath('migration-mikrotik-review.png'), animations: 'disabled' })
  await panel.getByRole('button', { name: 'Migrasikan', exact: true }).click()
  await expect(panel.getByRole('status')).toContainText('2 ditambahkan')
  expect(state.writes.find(write => write.path.endsWith('/pppoe'))?.body).toMatchObject({ mode: 'ALREADY_INSTALLED_RADIUS', skipDisabled: false, onlyNames: ['ani', 'budi'], profilePlanId: { rumah: 'plan1' }, firstInvoiceOn: null })
  expect(state.errors).toEqual([])
})

test('data-only migration remains available without network management permission', async ({ page }) => {
  const state = await setup(page, false)
  await page.goto('/import-customers')
  const panel = page.getByRole('dialog', { name: 'Migrasi pelanggan', exact: true })
  const scope = panel.getByRole('combobox', { name: 'Cakupan impor' })
  await expect(scope).toHaveValue('data')
  await expect(scope.locator('option')).toHaveCount(1)
  const download = page.waitForEvent('download')
  await panel.getByRole('button', { name: 'Unduh template' }).click()
  const stream = await (await download).createReadStream()
  const chunks: Buffer[] = []
  for await (const chunk of stream!) chunks.push(Buffer.from(chunk))
  const headers = Buffer.concat(chunks).toString('utf8')
  expect(headers).toContain(',username,password,')
  expect(headers).not.toContain('mikrotik_')
  expect(state.errors).toEqual([])
})
