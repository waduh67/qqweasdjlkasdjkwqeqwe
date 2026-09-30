import { expect, test } from '@playwright/test'
import { captureAssetHistory } from './asset-screenshots'
import { acceptNumericHandover, acknowledgeNumericJourney, assertNumericStock, completeNumericJourney, consumeAndInstallNumericJourney, prepareNumericJourney, returnNumericRemnant, uploadNumericProof } from './numeric-journey'

test('empty tenant completes the measured cable and loan ONU journey through warehouse, technician and independent QA screens', async ({ page }, testInfo) => {
  test.setTimeout(480_000)
  const fixture = await prepareNumericJourney(page)
  await acknowledgeNumericJourney(page, fixture)
  const physical = await consumeAndInstallNumericJourney(page, fixture)
  await uploadNumericProof(page, fixture)
  await acceptNumericHandover(page, fixture)
  await captureAssetHistory(page, testInfo, 'customer-loan-accepted')
  const returned = await returnNumericRemnant(page, fixture)
  expect(returned.intake.stockIdentityId).toBe(physical.usage.lines[0].remainder.stockIdentityId)
  await completeNumericJourney(page, fixture)
  await page.screenshot({ path: testInfo.outputPath('independent-qa-material-review.png'), fullPage: true })
  await assertNumericStock(page, fixture)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
  await page.screenshot({ path: testInfo.outputPath('numeric-final-stock.png'), fullPage: true })
})
