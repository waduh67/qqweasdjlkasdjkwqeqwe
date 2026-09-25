import { randomUUID } from 'node:crypto'
import { expect, type Page, type TestInfo } from '@playwright/test'
import { openAssetHistory } from './asset-journey'
import { reuseLoanForAnotherCustomer, swapLoanAndInspectOldAsset } from './loan-reuse-journey'
import { acceptNumericHandover, acknowledgeNumericJourney, assertNumericStock, completeNumericJourney, consumeAndInstallNumericJourney, prepareNumericJourney, returnNumericRemnant, switchUser, uploadNumericProof } from './numeric-journey'

export async function loanReuseScenario(page: Page, testInfo: TestInfo) {
  const fixture = await prepareNumericJourney(page, { serialPrefix: `Loan-${randomUUID().slice(0, 8)}-` })
  await acknowledgeNumericJourney(page, fixture)
  const physical = await consumeAndInstallNumericJourney(page, fixture)
  await uploadNumericProof(page, fixture)
  await acceptNumericHandover(page, fixture)
  await returnNumericRemnant(page, fixture)
  await completeNumericJourney(page, fixture)
  await assertNumericStock(page, fixture)
  const replaced = await swapLoanAndInspectOldAsset(page, fixture, physical.installation.assetId)
  await page.screenshot({ path: testInfo.outputPath('loan-old-unit-reset-and-released.png'), fullPage: true })
  const second = await reuseLoanForAnotherCustomer(page, fixture, physical.installation.assetId)
  expect(second.customer.id).not.toBe(fixture.customer!.id)
  expect(second.installed.assignmentId).not.toBe(physical.installation.assignmentId)
  await page.screenshot({ path: testInfo.outputPath('same-loan-unit-second-customer.png'), fullPage: true })
  const firstHistory = await openAssetHistory(page, replaced.work)
  const original = firstHistory.items.find((row: { asset: { id: string } }) => row.asset.id === physical.installation.assignmentId)
  const beforeReuse = replaced.history.items.find((row: { asset: { id: string } }) => row.asset.id === physical.installation.assignmentId)
  expect(original.asset).toMatchObject({ id: physical.installation.assignmentId, assetId: physical.installation.assetId,
    customerId: fixture.customer!.id, serial: beforeReuse.asset.serial, startedAt: beforeReuse.asset.startedAt, endedAt: beforeReuse.asset.endedAt,
    origin: beforeReuse.asset.origin, handoverState: 'ACCEPTED', legalOwner: 'ISP' })
  expect(firstHistory.items.filter((row: { asset: { endedAt: string | null } }) => row.asset.endedAt === null)).toHaveLength(1)
  await expect(page.getByRole('region', { name: 'Aset perangkat pelanggan', exact: true })).toContainText('Sudah dilepas')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  await page.screenshot({ path: testInfo.outputPath('first-customer-preserved-swap-history.png'), fullPage: true })
  await switchUser(page, fixture.admin)
  const response = page.waitForResponse(res => new URL(res.url()).pathname === '/api/v1/warehouse/stock' && new URL(res.url()).searchParams.get('bucket') === 'AVAILABLE')
  await page.goto('/warehouse/stock?bucket=AVAILABLE')
  const stock = await (await response).json()
  expect(stock.items.find((item: { skuId: string }) => item.skuId === fixture.cable.id)).toMatchObject({ available: { quantityBase: '917500' } })
  expect(stock.items.find((item: { skuId: string }) => item.skuId === fixture.onu.id)).toMatchObject({ available: { quantityBase: '8' } })
  await expect(page.getByRole('row').filter({ hasText: 'ONU pelanggan' })).toContainText('8 unit')
}
