import { expect, type Page, type TestInfo } from '@playwright/test'
import { createUser } from './helpers'
import { confirmOperation } from './fulfillment'
import { grantLocations, switchUser, type NumericJourney } from './numeric-journey'

/** Review the same physical custody before reassignment, after it, and after cancellation. */
export async function reassignAndCancelWithCustody(page: Page, fixture: NumericJourney, testInfo: TestInfo) {
  const own = `/api/v1/warehouse/my-materials/${fixture.workOrder.id}`
  async function readCustody() {
    const pending = page.waitForResponse(response => new URL(response.url()).pathname === `${own}/custody`)
    await page.goto(`/my-materials?workOrderId=${fixture.workOrder.id}`)
    const response = await pending
    expect(response.status()).toBe(200)
    return (await response.json()).items as { id: string; quantityBase: string; baseUnit: string; serial: string | null }[]
  }
  const before = await readCustody()
  expect(before).toHaveLength(2)
  const quantities = (rows: typeof before) => rows.map(({ id, quantityBase, baseUnit, serial }) =>
    ({ id, quantityBase, baseUnit, serial })).sort((left, right) => left.id.localeCompare(right.id))
  await expect(page.getByRole('button', { name: 'Catat pemakaian', exact: true })).toBeVisible()

  await switchUser(page, fixture.admin)
  const replacement = await createUser(page, 'Teknisi', {
    areas: [fixture.area.checkboxLabel], prefix: 'Pengganti', additionalRoles: ['Teknisi perangkat pelanggan'],
  })
  await grantLocations(page, replacement, [fixture.warehouse, fixture.transit, fixture.quarantine])
  await page.goto(`/work-orders/${fixture.workOrder.id}`)
  await page.getByRole('combobox', { name: 'Cari teknisi…', exact: true }).click()
  await page.getByRole('menuitemcheckbox', { name: fixture.technician.name, exact: true }).click()
  await page.getByRole('menuitemcheckbox', { name: replacement.name, exact: true }).click()
  await page.getByRole('heading', { name: 'Penugasan', exact: true }).click()
  const reassigned = await confirmOperation(page, `/api/work-orders/${fixture.workOrder.id}/assign`, 'Tugaskan ulang')
  expect(reassigned.assignees).toHaveLength(1)
  expect(reassigned.assignees[0].name).toBe(replacement.name)

  await switchUser(page, replacement)
  expect(await readCustody()).toEqual([])
  await expect(page.getByRole('region', { name: 'Barang di tangan saya', exact: true }))
    .toContainText('Tidak ada sisa di tangan Anda')
  await expect(page.getByRole('button', { name: 'Terima barang', exact: true })).toHaveCount(0)
  await page.screenshot({ path: testInfo.outputPath('reassigned-technician-no-inherited-custody.png'), fullPage: true })

  await switchUser(page, fixture.technician)
  expect(quantities(await readCustody())).toEqual(quantities(before))
  await expect(page.getByText('Anda tidak lagi ditugaskan pada WO ini. Sisa milik Anda tetap dapat dikembalikan; pemakaian baru tidak diizinkan.', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Catat pemakaian', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Kembalikan perangkat', exact: true })).toBeEnabled()
  await page.screenshot({ path: testInfo.outputPath('original-technician-retains-custody.png'), fullPage: true })

  await switchUser(page, fixture.admin)
  await page.goto(`/work-orders/${fixture.workOrder.id}`)
  await page.getByRole('textbox', { name: 'Batalkan work order', exact: true }).fill('Pekerjaan dibatalkan; barang tetap harus diterima kembali secara fisik')
  const cancelled = await confirmOperation(page, `/api/work-orders/${fixture.workOrder.id}/cancel`, 'Batalkan')
  expect(cancelled.status).toBe('CANCELLED')
  const stockRead = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/warehouse/stock'
    && new URL(response.url()).searchParams.get('bucket') === 'AVAILABLE')
  await page.goto('/warehouse/stock?bucket=AVAILABLE')
  const stockResponse = await stockRead
  expect(stockResponse.status()).toBe(200)
  const stock = await stockResponse.json()
  expect(stock.items.find((row: { skuId: string }) => row.skuId === fixture.cable.id)).toMatchObject({ available: { quantityBase: '900000' } })
  expect(stock.items.find((row: { skuId: string }) => row.skuId === fixture.onu.id)).toMatchObject({ available: { quantityBase: '9' } })

  await switchUser(page, fixture.technician)
  expect(quantities(await readCustody())).toEqual(quantities(before))
  await expect(page.getByText('Dibatalkan', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Catat pemakaian', exact: true })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Kembalikan perangkat', exact: true })).toBeEnabled()
  await page.screenshot({ path: testInfo.outputPath('cancelled-job-custody-awaiting-physical-return.png'), fullPage: true })
}
