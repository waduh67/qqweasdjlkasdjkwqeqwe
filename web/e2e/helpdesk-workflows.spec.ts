import { expect, test, type Locator, type Page } from '@playwright/test'

async function setup(page: Page) {
  const profile = { id: 'qa', email: 'qa@example.test', name: 'Rina', tenantId: 'qa', tenantSlug: 'qa', platformAdmin: false,
    permissions: ['helpdesk.ticket.view', 'helpdesk.ticket.manage', 'helpdesk.ticket.reply', 'iam.user.view', 'customer.customer.view',
      'customer.customer.create', 'customer.subscription.update', 'bng.access.manage', 'workorder.order.create', 'catalog.plan.view'], areaIds: [], roleIds: [], twoFactorEnabled: true }
  const ticket = { id: 'ticket-1', code: 'TKT-000001', customerId: 'customer-1', customerName: 'Budi Santoso', category: 'KONEKSI_PUTUS',
    subject: 'Internet terputus sejak pagi', status: 'OPEN', priority: 'HIGH', assigneeId: null, assigneeName: null, workOrderId: null as string | null, workOrderCode: null as string | null,
    openedAt: '2026-10-11T09:00:00Z', lastActivityAt: '2026-10-11T10:00:00Z', firstResponseAt: null, responseDueAt: '2026-10-11T11:00:00Z',
    resolutionDueAt: '2026-10-12T09:00:00Z', responseOverdue: true, resolutionOverdue: false, resolvedAt: null, closedAt: null }
  const messages = [{ author: 'OPERATOR', authorName: 'Rina', body: 'Kami sedang memeriksa sambungan dari jaringan pusat.', at: '2026-10-11T10:00:00Z' }]
  const detail = () => ({ ticket, description: 'Lampu LOS menyala merah. Koneksi belum kembali setelah modem dinyalakan ulang.', messages })
  const writes: { path: string; body: Record<string, unknown> }[] = [], errors: string[] = []
  let rejectReply = true
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(() => localStorage.setItem('ftth.refreshToken', 'test-refresh'))
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    let body: unknown = { content: [], items: [], totalElements: 0, totalPages: 0, page: 0, size: 25 }
    if (path === '/api/auth/refresh') body = { accessToken: 'test-access', refreshToken: 'test-refresh', accessTokenExpiresAt: '2099-01-01T00:00:00Z', user: profile }
    else if (path === '/api/me') body = profile
    else if (path === '/api/subscription/lock') body = { locked: false }
    else if (path === '/api/v2/warehouse/workflow') body = { snapshot: { tenantId: '00000000-0000-4000-8000-000000000001', epoch: 1, state: 'ENFORCED', workflow: 'REFERENCE' }, owner: true }
    else if (path === '/api/users') body = { content: [{ id: 'qa', name: 'Rina', status: 'ACTIVE' }], totalElements: 1 }
    else if (path === '/api/catalog/plans' || path === '/api/bng/nas' || path === '/api/areas') body = []
    else if (path === '/api/helpdesk/tickets/summary') body = { open: 1, inProgress: 1, resolved: 0, unassigned: 1, overdue: 1 }
    else if (path === '/api/helpdesk/tickets') {
      const items = [ticket, { ...ticket, id: 'ticket-2', code: 'TKT-000002', customerName: 'Siti Aminah', subject: 'Internet lambat pada malam hari', category: 'KONEKSI_LAMBAT', status: 'IN_PROGRESS', priority: 'NORMAL', responseOverdue: false, assigneeId: 'qa', assigneeName: 'Rina' }]
        .filter(item => (!url.searchParams.get('status') || item.status === url.searchParams.get('status')) && (!url.searchParams.get('category') || item.category === url.searchParams.get('category')) && (!url.searchParams.get('unassigned') || !item.assigneeId))
      body = { content: items, totalElements: items.length, totalPages: 1, page: 0, size: 25 }
    } else if (path === '/api/helpdesk/tickets/ticket-1') body = detail()
    else if (path.endsWith('/replies')) {
      writes.push({ path, body: request.postDataJSON() })
      if (rejectReply) { rejectReply = false; await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ detail: 'Layanan sementara tidak tersedia.' }) }); return }
      messages.push({ author: 'OPERATOR', authorName: 'Rina', body: request.postDataJSON().body, at: '2026-10-11T13:00:00Z' }); body = detail()
    } else if (path.endsWith('/escalate')) {
      writes.push({ path, body: request.postDataJSON() }); ticket.workOrderId = 'wo-1'; ticket.workOrderCode = 'WO-000001'; body = detail()
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) })
  })
  return { writes, errors }
}
async function command(page: Page, panel: Locator, name: string) {
  const button = panel.getByRole('button', { name, exact: true })
  if (await button.isVisible()) await button.click()
  else { await panel.getByRole('button', { name: 'Aksi lainnya' }).click(); await page.getByRole('menuitem', { name, exact: true }).click() }
}
for (const width of [375, 1280]) {
  test(`Helpdesk filters, retained ticket and nested work order at ${width}px`, async ({ page }, testInfo) => {
    const { writes, errors } = await setup(page)
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/helpdesk?keep=1')
    await expect(page.getByRole('button', { name: 'TKT-000001', exact: true })).toBeVisible()
    const controls = page.locator('.resource-list-controls'), bar = await controls.getByRole('toolbar').boundingBox(), filters = await controls.locator('.resource-filter-bar').boundingBox()
    expect(filters!.y - bar!.y - bar!.height).toBeLessThanOrEqual(2)
    const cells = page.locator('.fui-DataGridCell')
    expect(await cells.count()).toBeGreaterThan(0)
    expect(await cells.locator('.card,.badge,code').count()).toBe(0)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: testInfo.outputPath('helpdesk-tickets.png'), fullPage: true, animations: 'disabled' })
    await page.getByRole('button', { name: 'Filter Status' }).click()
    await page.getByRole('combobox', { name: 'Status', exact: true }).selectOption('OPEN')
    await expect(page.getByRole('button', { name: 'TKT-000002', exact: true })).toHaveCount(0)
    await page.getByRole('tab', { name: 'Ringkasan', exact: true }).click()
    await page.screenshot({ path: testInfo.outputPath('helpdesk-overview.png'), fullPage: true, animations: 'disabled' })
    await page.getByRole('tab', { name: 'Tiket', exact: true }).click()
    await expect(page.getByRole('button', { name: 'Filter Status' })).toContainText('Baru')
    const document = await page.evaluateHandle(() => window.document)
    await page.getByRole('button', { name: 'TKT-000001', exact: true }).click()
    const panel = page.getByRole('dialog', { name: 'TKT-000001', exact: true })
    await expect(panel).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath('helpdesk-conversation.png'), fullPage: true, animations: 'disabled' })
    await panel.getByRole('tab', { name: 'Detail', exact: true }).click()
    await expect(panel.getByRole('combobox', { name: 'Prioritas' })).toHaveValue('HIGH')
    await page.screenshot({ path: testInfo.outputPath('helpdesk-details.png'), fullPage: true, animations: 'disabled' })
    await panel.getByRole('tab', { name: 'Percakapan', exact: true }).click()
    await panel.getByRole('textbox', { name: 'Balasan' }).fill('Teknisi akan memeriksa sambungan di lokasi.')
    await command(page, panel, 'Buat work order')
    const form = page.getByRole('dialog', { name: 'Buat work order', exact: true })
    await expect(form).toHaveAttribute('data-resource-layer', '1')
    await form.getByRole('textbox', { name: 'Catatan untuk teknisi' }).fill('Periksa kabel drop dan konektor ONT.')
    await page.screenshot({ path: testInfo.outputPath('helpdesk-work-order.png'), fullPage: true, animations: 'disabled' })
    await form.getByRole('tab', { name: 'Tinjau + buat' }).click()
    await form.getByRole('button', { name: 'Buat work order', exact: true }).click()
    await expect(form).toHaveCount(0)
    await expect(panel.getByRole('textbox', { name: 'Balasan' })).toHaveValue('Teknisi akan memeriksa sambungan di lokasi.')
    await panel.getByRole('button', { name: 'Kirim balasan' }).click()
    await expect(panel.getByRole('alert')).toContainText('Layanan sementara tidak tersedia.')
    await expect(panel.getByRole('textbox', { name: 'Balasan' })).toHaveValue('Teknisi akan memeriksa sambungan di lokasi.')
    await panel.getByRole('button', { name: 'Kirim balasan' }).click()
    await expect(panel.getByRole('textbox', { name: 'Balasan' })).toHaveValue('')
    await panel.getByRole('button', { name: 'Tutup', exact: true }).click()
    await expect(panel).toHaveCount(0)
    await expect(page.getByRole('button', { name: 'Filter Status' })).toContainText('Baru')
    await expect(page).toHaveURL('/helpdesk?keep=1')
    expect(await page.evaluate(original => window.document === original, document)).toBe(true)
    expect(writes).toHaveLength(3)
    expect(writes[0].body).toMatchObject({ priority: 'HIGH', note: 'Periksa kabel drop dan konektor ONT.' })
    expect(errors).toEqual([])
  })
  test(`PSB explains setup to a tenant owner at ${width}px`, async ({ page }, testInfo) => {
    const { errors } = await setup(page)
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/express-psb')
    await expect(page.getByText('Belum ada paket internet aktif', { exact: true })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Buka paket internet' })).toHaveAttribute('href', '/catalog')
    await expect(page.getByText('Akses PSB dibatasi', { exact: true })).toHaveCount(0)
    await expect(page.getByText('Data belum berhasil dimuat', { exact: true })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath('psb-prerequisite.png'), fullPage: true })
    expect(errors).toEqual([])
  })
}
