import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { expect, test, type Page } from '@playwright/test'
import { installOltInventoryRoutes, olt } from './oltInventoryFixtures'

const evidenceDir = path.resolve(process.cwd(), '../.omo/evidence/manual-snmp-poll')

async function openInventoryDetail(page: Page) {
  await page.goto('/inventory')
  await page.getByRole('tab', { name: 'OLT', exact: true }).click()
  await page.getByText(olt.name, { exact: true }).click()
}

async function openMapDetail(page: Page) {
  await page.goto('/olts/' + olt.id)
  await page.getByRole('button', { name: 'Lihat di peta' }).click()
  await expect(page).toHaveURL(new RegExp('/map$'))
  await page.getByRole('button', { name: 'Buka detail', exact: true }).click()
}

async function expectNoOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await expect.poll(() => page.locator('.blade-detail .blade-body').evaluateAll((elements) =>
    elements.every((element) => element.scrollWidth <= element.clientWidth),
  )).toBe(true)
}

async function capture(page: Page, name: string) {
  await mkdir(evidenceDir, { recursive: true })
  await page.screenshot({ path: path.join(evidenceDir, name), animations: 'disabled' })
}

for (const width of [375, 768, 1280]) {
  test('Inventory polls immediately and keeps its list mounted at ' + width + 'px', async ({ page }) => {
    const runtimeErrors: string[] = []
    page.on('pageerror', (error) => runtimeErrors.push(error.message))
    page.on('console', (message) => { if (message.type() === 'error') runtimeErrors.push(message.text()) })
    const fixture = await installOltInventoryRoutes(page)
    await page.setViewportSize({ width, height: 1000 })
    await openInventoryDetail(page)
    await page.getByRole('tab', { name: 'ONU di OLT', exact: true }).click()
    await expect(page.getByText('TEST001122AA', { exact: true })).toBeVisible()
    const releasePoll = fixture.deferNextPoll()
    const pollButton = page.getByRole('button', { name: 'Cek SNMP', exact: true })

    await pollButton.evaluate((button) => { button.click(); button.click() })

    await expect(page.getByRole('button', { name: 'Memeriksa…', exact: true })).toBeDisabled()
    expect(fixture.pollCount()).toBe(1)
    releasePoll()
    await expect(page.getByText('SNMP OLT-QA selesai · 2 ONU terbaca.', { exact: true })).toBeVisible()
    await expect(page.getByText('AFTERPOLL001', { exact: true })).toBeVisible()
    expect(fixture.oltListReadCount()).toBe(1)
    expect(fixture.oltDetailReadCount()).toBe(2)
    expect(fixture.readCount()).toBe(2)
    await expect(page.locator('.blade-detail .badge').filter({ hasText: /^Active$/ }).first()).toBeVisible()
    await expectNoOverflow(page)
    await capture(page, `task-15-manual-snmp-poll-inventory-${width}.png`)
    expect(runtimeErrors).toEqual([])
  })

  test('Map polls immediately and refreshes only its impacted overlay at ' + width + 'px', async ({ page }) => {
    const runtimeErrors: string[] = []
    page.on('pageerror', (error) => runtimeErrors.push(error.message))
    page.on('console', (message) => { if (message.type() === 'error') runtimeErrors.push(message.text()) })
    const fixture = await installOltInventoryRoutes(page)
    fixture.setPollMode('unreachable')
    await page.setViewportSize({ width, height: 1000 })
    const focusedMapTilesLoaded = page.waitForResponse(/\/api\/gis\/tiles\/17\//)
    await openMapDetail(page)
    await focusedMapTilesLoaded
    await page.waitForLoadState('networkidle')
    await expect.poll(() => fixture.impactedReadCount()).toBeGreaterThan(0)
    const impactedBefore = fixture.impactedReadCount()
    const tilesBefore = fixture.tileReadCount()
    const pollButton = page.getByRole('button', { name: 'Cek SNMP', exact: true })

    await pollButton.focus()
    await page.keyboard.press('Enter')

    await expect(page.getByText('OLT-QA tidak merespons SNMP.', { exact: true })).toBeVisible()
    await expect.poll(() => fixture.impactedReadCount()).toBe(impactedBefore + 1)
    expect(fixture.pollCount()).toBe(1)
    expect(fixture.tileReadCount()).toBe(tilesBefore)
    expect(fixture.oltListReadCount()).toBe(0)
    await expect(page.locator('.blade-detail .badge').filter({ hasText: /^Active$/ }).first()).toBeVisible()
    await expect(page.getByText('Tidak terjangkau', { exact: true })).toHaveCount(0)
    await expectNoOverflow(page)
    await capture(page, `task-15-manual-snmp-poll-map-${width}.png`)
    expect(runtimeErrors).toEqual([])
  })
}

test('manual poll permission and readiness control the shared action', async ({ page }) => {
  await installOltInventoryRoutes(page, true, false)
  await openInventoryDetail(page)
  await expect(page.getByRole('button', { name: 'Cek SNMP', exact: true })).toHaveCount(0)

  await page.unrouteAll({ behavior: 'wait' })
  const fixture = await installOltInventoryRoutes(page, true, true, { pollable: false })
  await openInventoryDetail(page)
  await expect(page.getByRole('button', { name: 'Cek SNMP', exact: true })).toBeDisabled()
  expect(fixture.pollCount()).toBe(0)
})

test('poll errors restore the action without refreshing local or host data', async ({ page }) => {
  const fixture = await installOltInventoryRoutes(page)
  fixture.setPollMode('error')
  await openInventoryDetail(page)
  await page.getByRole('button', { name: 'Cek SNMP', exact: true }).click()
  await expect(page.getByText('Polling SNMP sedang berjalan', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Cek SNMP', exact: true })).toBeEnabled()
  expect(fixture.pollCount()).toBe(1)
  expect(fixture.oltListReadCount()).toBe(1)
  expect(fixture.oltDetailReadCount()).toBe(1)
  expect(fixture.readCount()).toBe(0)
})
