import { expect, test, type Page } from '@playwright/test'
import { installProvisioningRoutes } from './provisioningFixtures'

async function generatePreview(page: Page, intentId: string): Promise<void> {
  await page.getByRole('combobox', { name: 'Intent layanan' }).selectOption(intentId)
  await page.getByRole('button', { name: 'Buat dan pratinjau plan' }).click()
}

async function expectNoHorizontalOverflow(page: Page): Promise<void> {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
}

test('fresh production keeps residential and enterprise dry-run visible while auto-apply is disabled', async ({ page }, testInfo) => {
  await installProvisioningRoutes(page, { autoApplyEnabled: false, previewAllowed: true })
  await page.goto('/network-provisioning')
  await page.getByRole('tab', { name: /Intent/ }).click()

  await expect(page.getByRole('heading', { name: 'Residential shared' })).toBeVisible()
  await generatePreview(page, 'intent-home')
  await expect(page.getByText('120', { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: 'Terapkan ke produksi' })).toBeDisabled()
  await expect(page.getByRole('status')).toContainText('Auto-apply produksi dinonaktifkan')
  await page.screenshot({ path: testInfo.outputPath('residential-safe-default.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 375, height: 812 })
  await page.screenshot({ path: testInfo.outputPath('residential-safe-default-mobile.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 1280, height: 720 })

  await generatePreview(page, 'intent-enterprise')
  await expect(page.getByText('3101', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('Enterprise dedicated').first()).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('enterprise-safe-default.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 375, height: 812 })
  await page.screenshot({ path: testInfo.outputPath('enterprise-safe-default-mobile.png'), fullPage: true, animations: 'disabled' })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('protected resource and provisional adapter rejection never enable apply', async ({ page }, testInfo) => {
  await installProvisioningRoutes(page, { autoApplyEnabled: true, previewAllowed: false })
  await page.goto('/network-provisioning')
  await page.getByRole('tab', { name: /Intent/ }).click()
  await generatePreview(page, 'intent-home')

  await expect(page.getByText('PROTECTED_MANAGEMENT_RESOURCE', { exact: true })).toBeVisible()
  await expect(page.getByText(/Adapter provisional dan resource manajemen dilindungi/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Terapkan ke produksi' })).toBeDisabled()
  await page.screenshot({ path: testInfo.outputPath('protected-provisional-rejection.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 375, height: 812 })
  await page.screenshot({ path: testInfo.outputPath('protected-provisional-rejection-mobile.png'), fullPage: true, animations: 'disabled' })
  await expectNoHorizontalOverflow(page)
})

test('operator lifecycle exposes suspend restore and deprovision actions from persisted intent state', async ({ page }) => {
  await installProvisioningRoutes(page, { autoApplyEnabled: true, previewAllowed: true })
  await page.goto('/network-provisioning')
  await page.getByRole('tab', { name: /Intent/ }).click()

  const suspendResponse = page.waitForResponse((response) => response.url().endsWith('/suspend'))
  await page.getByRole('button', { name: 'Tangguhkan' }).first().click()
  await suspendResponse
  const restoreResponse = page.waitForResponse((response) => response.url().endsWith('/restore'))
  await page.getByRole('button', { name: 'Pulihkan' }).click()
  await restoreResponse
  const deprovisionResponse = page.waitForResponse((response) => response.url().includes('/deprovision'))
  await page.getByRole('button', { name: 'Deprovision' }).first().click()
  await expect((await deprovisionResponse).status()).toBe(200)
  await page.getByRole('tab', { name: /Eksekusi/ }).click()
  await expect(page.getByText('execution-plan-delete')).toBeVisible()
  await expect(page.getByText('Berhasil').first()).toBeVisible()
})

test('partial failure exposes rollback denial and manual reconciliation', async ({ page }, testInfo) => {
  await installProvisioningRoutes(page, { autoApplyEnabled: true, previewAllowed: true, executionStatus: 'MANUAL_RECONCILIATION' })
  await page.goto('/network-provisioning')
  await page.getByRole('tab', { name: /Intent/ }).click()
  await generatePreview(page, 'intent-enterprise')
  const applyResponse = page.waitForResponse((response) => response.url().includes('/apply'))
  await page.getByRole('button', { name: 'Terapkan ke produksi' }).click()
  const response = await applyResponse
  await expect(response.status()).toBe(200)
  await expect(await response.json()).toEqual({ id: 'execution-generated-intent-enterprise', planId: 'generated-intent-enterprise', revision: 1, status: 'QUEUED' })
  await expect(page.getByText('Apply produksi ditolak')).toHaveCount(0)
  await page.getByRole('tab', { name: /Eksekusi/ }).click()

  await expect(page.getByText('Perlu rekonsiliasi manual')).toBeVisible()
  await expect(page.getByText('VERIFICATION_MISMATCH', { exact: true })).toBeVisible()
  await expect(page.getByText('ROLLBACK_POLICY_DENIED', { exact: true })).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('manual-reconciliation.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 375, height: 812 })
  await page.screenshot({ path: testInfo.outputPath('manual-reconciliation-mobile.png'), fullPage: true, animations: 'disabled' })
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.setViewportSize({ width: 1280, height: 720 })
  await page.getByRole('tab', { name: /Drift/ }).click()
  await expect(page.getByText('Konflik')).toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('conflicting-drift.png'), fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 375, height: 812 })
  await page.screenshot({ path: testInfo.outputPath('conflicting-drift-mobile.png'), fullPage: true, animations: 'disabled' })
  await expectNoHorizontalOverflow(page)
})

for (const intent of [
  { id: 'intent-home', planId: 'generated-intent-home' },
  { id: 'intent-enterprise', planId: 'generated-intent-enterprise' },
] as const) {
  test(`${intent.id} reaches verified success when rollout is explicitly enabled`, async ({ page }, testInfo) => {
    await installProvisioningRoutes(page, { autoApplyEnabled: true, previewAllowed: true, executionStatus: 'SUCCEEDED' })
    await page.goto('/network-provisioning')
    await page.getByRole('tab', { name: /Intent/ }).click()
    await generatePreview(page, intent.id)
    await page.getByRole('button', { name: 'Terapkan ke produksi' }).click()
    await page.getByRole('tab', { name: /Eksekusi/ }).click()
    await expect(page.getByText('Berhasil').first()).toBeVisible()
    await expect(page.getByText(`execution-${intent.planId}`)).toBeVisible()
    await expect(page.getByText('Verifikasi')).toHaveCount(3)
    await expect(page.getByText(/Binding BRAS/)).toBeVisible()
    await expect(page.getByText(/Transit switch/)).toBeVisible()
    await expect(page.getByText(/OLT \/ PON \/ ONU/)).toBeVisible()
    await page.screenshot({ path: testInfo.outputPath(`${intent.id}-verified-success.png`), fullPage: true, animations: 'disabled' })
    await page.setViewportSize({ width: 375, height: 812 })
    await page.screenshot({ path: testInfo.outputPath(`${intent.id}-verified-success-mobile.png`), fullPage: true, animations: 'disabled' })
    await expectNoHorizontalOverflow(page)
  })
}

for (const state of [
  { status: 'FAILED', label: 'Gagal' },
  { status: 'ROLLING_BACK', label: 'Sedang rollback' },
  { status: 'ROLLED_BACK', label: 'Rollback selesai' },
  { status: 'CANCELLED', label: 'Dibatalkan' },
] as const) {
  test(`execution state ${state.status} remains explicit and non-cancellable`, async ({ page }, testInfo) => {
    await installProvisioningRoutes(page, { autoApplyEnabled: true, previewAllowed: true, executionStatus: state.status })
    await page.goto('/network-provisioning')
    await page.getByRole('tab', { name: /Intent/ }).click()
    await generatePreview(page, 'intent-home')
    await page.getByRole('button', { name: 'Terapkan ke produksi' }).click()
    await page.getByRole('tab', { name: /Eksekusi/ }).click()
    await expect(page.getByText(state.label).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Batalkan eksekusi' })).toHaveCount(0)
    await page.screenshot({ path: testInfo.outputPath(`execution-${state.status.toLowerCase()}.png`), fullPage: true, animations: 'disabled' })
    await page.setViewportSize({ width: 375, height: 812 })
    await page.screenshot({ path: testInfo.outputPath(`execution-${state.status.toLowerCase()}-mobile.png`), fullPage: true, animations: 'disabled' })
    await expectNoHorizontalOverflow(page)
  })
}

test('all workspace tabs and exact certification matrix are reachable on mobile', async ({ page }, testInfo) => {
  await installProvisioningRoutes(page, { autoApplyEnabled: false, previewAllowed: true, platformAdmin: true })
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/network-provisioning')

  for (const tab of ['Topologi', 'Profil', 'Intent', 'Eksekusi', 'Drift', 'Sertifikasi']) {
    const control = page.getByRole('tab', { name: tab })
    await expect(control).toBeVisible()
    await control.click()
    await expect(control).toHaveAttribute('aria-selected', 'true')
  }
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await page.getByRole('tab', { name: 'Topologi' }).click()
  await page.screenshot({ path: testInfo.outputPath('topology-mobile.png'), fullPage: false, animations: 'disabled' })
  await expectNoHorizontalOverflow(page)
  await page.getByRole('tab', { name: 'Profil' }).click()
  await page.screenshot({ path: testInfo.outputPath('profiles-mobile.png'), fullPage: false, animations: 'disabled' })
  await expectNoHorizontalOverflow(page)
  await page.getByRole('tab', { name: 'Intent' }).click()
  await generatePreview(page, 'intent-home')
  await page.getByRole('tab', { name: 'Sertifikasi' }).click()
  await expect(page.getByText('OLT / olt-1')).toBeVisible()
  await expect(page.getByText(/Berlaku sampai/).first()).toBeVisible()
  await page.setViewportSize({ width: 1280, height: 900 })
  await page.screenshot({ path: testInfo.outputPath('certification-matrix.png'), fullPage: false, animations: 'disabled' })
  await page.setViewportSize({ width: 375, height: 2400 })
  await page.screenshot({ path: testInfo.outputPath('certification-matrix-mobile.png'), fullPage: false, animations: 'disabled' })
  await expectNoHorizontalOverflow(page)
})

for (const state of [
  { status: 'QUEUED', label: 'Menunggu antrean', cancellable: true },
  { status: 'RUNNING', label: 'Sedang diterapkan', cancellable: false },
  { status: 'VERIFYING', label: 'Sedang diverifikasi', cancellable: false },
] as const) {
  test(`active execution state ${state.status} follows queued-only cancellation`, async ({ page }, testInfo) => {
    await installProvisioningRoutes(page, { autoApplyEnabled: true, previewAllowed: true, executionStatus: state.status })
    await page.goto('/network-provisioning')
    await page.getByRole('tab', { name: /Intent/ }).click()
    await generatePreview(page, 'intent-home')
    await page.getByRole('button', { name: 'Terapkan ke produksi' }).click()
    await page.getByRole('tab', { name: /Eksekusi/ }).click()
    await expect(page.getByText(state.label).first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Batalkan eksekusi' })).toHaveCount(state.cancellable ? 1 : 0)
    await page.screenshot({ path: testInfo.outputPath(`execution-${state.status.toLowerCase()}.png`), fullPage: true, animations: 'disabled' })
    await page.setViewportSize({ width: 375, height: 812 })
    await page.screenshot({ path: testInfo.outputPath(`execution-${state.status.toLowerCase()}-mobile.png`), fullPage: true, animations: 'disabled' })
    await expectNoHorizontalOverflow(page)
  })
}
