import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { dismantleAsset, repairSoldAsset, returnSoldRma } from './asset-journey'
import { loanReuseScenario } from './loan-reuse-scenario'
import { acceptNumericHandover, acknowledgeNumericJourney, assertNumericStock, completeNumericJourney, consumeAndInstallNumericJourney, prepareNumericJourney, returnNumericRemnant, uploadNumericProof } from './numeric-journey'

test('sold mixed-case receipt asset keeps customer title through physical removal, vendor repair, reset and original-customer RMA', async ({ page }, testInfo) => {
  test.setTimeout(600_000)
  const fixture = await prepareNumericJourney(page, { serialPrefix: `Browser-${randomUUID().slice(0, 8)}-` })
  await acknowledgeNumericJourney(page, fixture)
  const physical = await consumeAndInstallNumericJourney(page, fixture, 'SALE')
  await uploadNumericProof(page, fixture)
  await acceptNumericHandover(page, fixture)
  await expect(page.getByRole('region', { name: 'Aset perangkat pelanggan', exact: true })).toContainText('Milik pelanggan')
  await returnNumericRemnant(page, fixture)
  await completeNumericJourney(page, fixture)
  await assertNumericStock(page, fixture)
  const removal = await dismantleAsset(page, fixture)
  expect(removal.before.items[0].asset).toMatchObject({ assetId: physical.installation.assetId, legalOwner: 'CUSTOMER', handoverState: 'ACCEPTED' })
  const repaired = await repairSoldAsset(page, fixture, removal.removed.operationId, physical.installation.assetId)
  await page.screenshot({ path: testInfo.outputPath('sold-device-after-service-reset.png'), fullPage: true })
  const rma = await returnSoldRma(page, fixture, repaired.received.id, physical.installation.assetId)
  expect(rma.history.items.find((row: { asset: { id: string } }) => row.asset.id === physical.installation.assignmentId).asset).toMatchObject({
    serial: removal.before.items[0].asset.serial, customerId: fixture.customer!.id, assetId: physical.installation.assetId, legalOwner: 'CUSTOMER',
  })
  await expect(page.getByRole('region', { name: 'Aset perangkat pelanggan', exact: true })).toContainText('Milik pelanggan')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  await page.screenshot({ path: testInfo.outputPath('original-customer-rma-history.png'), fullPage: true })
  await assertNumericStock(page, fixture)
})

test('loan swap returns and resets the original asset before issuing that same physical unit to another customer', async ({ page }, testInfo) => {
  test.setTimeout(600_000)
  await loanReuseScenario(page, testInfo)
})
