import { expect, test } from '@playwright/test'
import { signup } from './helpers'

test('Azure navigation supports search, groups, collapse and keyboard on each viewport', async ({ page }, testInfo) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await signup(page)
  await page.goto('/customers')
  const mobile = page.viewportSize()!.width <= 820
  const sidebar = page.locator('.sidebar')
  const header = page.locator('.topbar')
  if (mobile) await header.getByRole('button', { name: 'Buka menu', exact: true }).click()
  await expect(sidebar).toBeInViewport()
  const customer = sidebar.getByRole('link', { name: 'Pelanggan', exact: true })
  await expect(customer).toHaveAttribute('aria-current', 'page')
  await page.evaluate(() => document.fonts.ready)
  await page.screenshot({ path: testInfo.outputPath('navigation-expanded.png'), animations: 'disabled' })

  const search = sidebar.getByRole('textbox', { name: 'Cari menu', exact: true })
  await search.fill('rekonsiliasi')
  await expect(sidebar.getByRole('link', { name: 'Rekonsiliasi Data Lama' })).toBeVisible()
  await expect(customer).toHaveCount(0)
  await search.fill('tidak-ada-menu-ini')
  await expect(sidebar.getByText('Menu tidak ditemukan.')).toBeVisible()
  await search.fill('')
  const network = sidebar.getByRole('button', { name: 'Jaringan', exact: true })
  await network.focus()
  await page.keyboard.press('Enter')
  await expect(network).toHaveAttribute('aria-expanded', 'true')
  await page.keyboard.press('Space')
  await expect(network).toHaveAttribute('aria-expanded', 'false')
  await network.click()
  await sidebar.getByRole('link', { name: 'Monitoring', exact: true }).click()
  await expect(page).toHaveURL(/\/monitoring$/)
  if (mobile) await header.getByRole('button', { name: 'Buka menu', exact: true }).click()
  await expect(sidebar.getByRole('link', { name: 'Monitoring', exact: true })).toHaveAttribute('aria-current', 'page')

  // Collapsing a filtered menu must clear its now-hidden query and retain focus.
  await search.fill('Pelanggan')
  await sidebar.getByRole('button', { name: 'Ciutkan navigasi', exact: true }).click()
  const reopen = header.getByRole('button', { name: mobile ? 'Buka menu' : 'Lebarkan sidebar', exact: true })
  await expect(reopen).toBeFocused()
  await expect(search).not.toBeVisible()
  await page.screenshot({ path: testInfo.outputPath('navigation-collapsed.png'), animations: 'disabled' })
  await reopen.click()
  await expect(search).toHaveValue('')
  await expect(network).toBeVisible()

  // A desktop collapse preference must not hide labels when resized into a drawer.
  if (!mobile) {
    await sidebar.getByRole('button', { name: 'Ciutkan navigasi', exact: true }).click()
    await page.setViewportSize({ width: 375, height: 812 })
    await header.getByRole('button', { name: 'Buka menu', exact: true }).click()
    await expect(search).toBeVisible()
    await expect(network).toBeVisible()
  }
  await search.fill('Pelanggan')
  await sidebar.getByRole('link', { name: 'Pelanggan', exact: true }).click()
  await expect(page).toHaveURL(/\/customers$/)
  await expect(sidebar).not.toBeVisible()
  await expect(header.getByRole('button', { name: 'Keluar', exact: true })).toBeInViewport()
  expect(errors).toEqual([])
})
