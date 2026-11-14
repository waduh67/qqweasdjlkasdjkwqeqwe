import { expect, test } from '@playwright/test'
import { record, uuid } from '../../src/api/warehouse/codec'
import { login } from './helpers'
import { captureReference, referenceTenant } from './reference-setup'

test('subnet lists its reserved addresses, manual owner and server ping with monitoring settings', async ({ page }, info) => {
  const f = await referenceTenant(page, 'ip-blocks')
  const admin = await f.createMember('Admin')
  const blockId = uuid(record(await f.command('/api/v1/network-inventory/blocks', { cidr: '127.0.0.0/30' }, admin.headers)).id)
  await login(page, admin.account)
  await page.goto('/network-ips')
  const blocks = page.getByRole('region', { name: 'Subnet IP public', exact: true })
  await blocks.getByRole('button', { name: '127.0.0.0/30', exact: true }).click()
  await expect(page.getByText('Network', { exact: true })).toBeVisible()
  await expect(page.getByText('Broadcast', { exact: true })).toBeVisible()
  await page.getByText('127.0.0.1', { exact: true }).click()
  await expect(page.getByRole('textbox', { name: 'Alamat IP public', exact: true })).toBeDisabled()
  await page.getByRole('textbox', { name: 'Dipakai oleh', exact: true }).fill('Router PT QA')
  await page.getByRole('button', { name: 'Tinjau + simpan', exact: true }).click()
  await page.getByRole('button', { name: 'Simpan', exact: true }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByText('Router PT QA', { exact: true })).toBeVisible()
  await blocks.getByRole('button', { name: 'Aksi baris', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Ping semua host', exact: true }).click()
  const progress = page.getByRole('region', { name: 'Progres ping', exact: true })
  await expect(progress.getByRole('status')).toContainText('Pemeriksaan selesai')
  await expect(progress.getByText('UP 2 · DOWN 0 · Error 0', { exact: true })).toBeVisible()
  const addresses = record(await f.get('/api/v1/network-inventory/ips?blockId=' + blockId)).content
  expect(Array.isArray(addresses)).toBeTruthy()
  expect(addresses).toHaveLength(4)
  await blocks.getByRole('button', { name: 'Aksi baris', exact: true }).click()
  await page.getByRole('menuitem', { name: 'Aktifkan monitoring', exact: true }).click()
  await page.getByRole('button', { name: 'Simpan monitoring', exact: true }).click()
  await expect(blocks.getByText('Aktif · tiap 5 menit', { exact: true })).toBeVisible()
  await captureReference(page, info, 'public-ip-monitoring')
})
