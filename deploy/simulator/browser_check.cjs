const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const { chromium } = createRequire(path.resolve(__dirname, '../../web/package.json'))('@playwright/test');
const env = Object.fromEntries(fs.readFileSync(process.argv[2], 'utf8').split('\n')
  .filter(line => line && !line.startsWith('#')).map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
let browser;
(async () => {
  browser = await chromium.launch({ headless: true });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => { if (response.status() >= 500) errors.push('HTTP ' + response.status()); });
  const base = 'https://' + env.FTTH_SIM_DOMAIN;
  await page.goto(base + '/login');
  await page.getByLabel('Email').fill(env.FTTH_SIM_ADMIN_EMAIL);
  await page.getByLabel('Password').fill(env.FTTH_SIM_ADMIN_PASSWORD);
  await page.getByRole('button', { name: 'Masuk', exact: true }).click();
  await page.waitForURL(url => !url.pathname.includes('login'));
  for (const route of ['/customers', '/bras', '/acs', '/warehouse/stock']) {
    await page.goto(base + route);
    await page.locator('main').waitFor();
    await page.getByRole('heading').first().waitFor();
    if (!(await page.locator('.topbar').innerText()).includes('simulator')) throw new Error('Wrong tenant');
  }
  await page.goto(base + '/inventory');
  await page.getByRole('tab', { name: 'OLT', exact: true }).click();
  await page.getByText('OLT Lab 1 (simulator)', { exact: true }).first().click();
  await page.getByRole('tab', { name: 'ONU di OLT', exact: true }).click();
  await page.getByText('16 dari 16 ONU pada hasil baca ini', { exact: true }).waitFor();
  if (errors.length) throw new Error('Browser check encountered ' + errors.length + ' runtime/server errors');
  console.log('PASS: public login, customers, BRAS, ACS, warehouse and 16 live OLT readings');
})().catch(error => {
  console.error(String(error.message).replaceAll(env.FTTH_SIM_ADMIN_PASSWORD, '[redacted]'));
  process.exitCode = 1;
}).finally(async () => { if (browser) await browser.close(); });
