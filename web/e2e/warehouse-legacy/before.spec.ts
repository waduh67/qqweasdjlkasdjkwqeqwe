import { expect, test } from '@playwright/test'
import { randomUUID } from 'node:crypto'
import { signup } from '../warehouse/helpers'
import { confirmOperation } from '../warehouse/fulfillment'
import { legacyHead, legacyPhase, saveLegacyFixture } from './fixture'

test('the historical V172 application creates a customer and ONU entirely through its UI', async ({ page }, testInfo) => {
  expect(legacyPhase).toBe('before')
  const admin = await signup(page)
  const name = `Pelanggan lama ${randomUUID().slice(0, 8)}`
  await page.goto('/customers')
  await page.getByRole('button', { name: 'Tambah pelanggan', exact: true }).click()
  await page.getByRole('textbox', { name: 'Nama', exact: true }).fill(name)
  await page.getByRole('textbox', { name: 'Alamat', exact: true }).fill('Alamat tersimpan sebelum migrasi gudang')
  await page.getByRole('textbox', { name: 'Longitude', exact: true }).fill('106.82')
  await page.getByRole('textbox', { name: 'Latitude', exact: true }).fill('-6.18')
  const customer = await confirmOperation(page, '/api/customers', 'Simpan')
  expect(customer).toMatchObject({ name, onus: [] })
  await page.getByRole('grid', { name: 'Tabel data', exact: true }).getByRole('button', { name, exact: true }).click()
  const enteredSerial = `Legacy-Mixed-${randomUUID().slice(0, 8)}`
  await page.getByPlaceholder('Serial ONU baru, mis. ZTEG-C0FFEE01', { exact: true }).fill(enteredSerial)
  const onu = await confirmOperation(page, `/api/customers/${customer.id}/onus`, 'Daftarkan ONU')
  expect(onu).toMatchObject({ serialNumber: enteredSerial.toUpperCase() })
  await expect(page.getByRole('dialog')).toContainText(onu.serialNumber)
  await page.screenshot({ path: testInfo.outputPath('historical-customer-and-onu.png'), fullPage: true })
  saveLegacyFixture(testInfo.project.name, { marker: process.env.WAREHOUSE_ENVIRONMENT_MARKER!, schema: process.env.WAREHOUSE_LEGACY_SCHEMA!,
    database: process.env.WAREHOUSE_E2E_DATABASE!, head: legacyHead, admin, customer: { id: customer.id, name }, onu: { id: onu.id, serialNumber: onu.serialNumber } })
})
