import { describe, expect, it } from 'vitest'
import { buildInvoiceSheetHtml, type InvoiceSheetData } from './invoiceSheet'

/**
 * Lembar ini adalah kertas yang disimpan pelanggan dan dipakai berdebat soal uang,
 * jadi yang diuji bukan tata letaknya melainkan angka & keterangan yang tercetak.
 */
const BASE: InvoiceSheetData = {
  number: 'INV-2026-0001',
  issuedAt: '2026-07-01',
  dueDate: '2026-07-10',
  statusLabel: 'Belum dibayar',
  customerName: 'Budi Santoso',
  customerCode: 'PLG-0001',
  packageName: 'Home 20 Mbps',
  periodStart: '2026-07-01',
  periodEnd: '2026-07-31',
  prorated: false,
  baseAmount: '250000',
  taxAmount: '0',
  totalAmount: '250000',
}

describe('buildInvoiceSheetHtml', () => {
  it('mencetak nomor, pelanggan, dan total dalam format rupiah', () => {
    const html = buildInvoiceSheetHtml(BASE)
    expect(html).toContain('INV-2026-0001')
    expect(html).toContain('Budi Santoso')
    expect(html).toContain('PLG-0001')
    expect(html).toContain('Rp 250.000')
  })

  // ISP yang tak memungut PPN tak boleh melihat baris pajak nol — pembaca akan
  // mengira ada pajak yang "kebetulan" nol, bukan tak dipungut sama sekali.
  it('menghilangkan baris PPN saat pajaknya nol', () => {
    expect(buildInvoiceSheetHtml(BASE)).not.toContain('PPN')
  })

  it('menulis persentase PPN dari pecahan tarif saat dipungut', () => {
    const html = buildInvoiceSheetHtml({
      ...BASE,
      taxAmount: '27500',
      totalAmount: '277500',
      taxRate: '0.11',
    })
    expect(html).toContain('PPN (11%)')
    expect(html).toContain('Rp 27.500')
    expect(html).toContain('Rp 277.500')
  })

  it('menyebut jumlah hari saat tagihan prorata', () => {
    const html = buildInvoiceSheetHtml({ ...BASE, prorated: true, proratedDays: 12 })
    expect(html).toContain('Prorata')
    expect(html).toContain('12 hari')
  })

  it('merinci pembayaran yang sudah masuk beserta penyedianya', () => {
    const html = buildInvoiceSheetHtml({
      ...BASE,
      paidAt: '2026-07-05T03:00:00Z',
      payments: [{ paidAt: '2026-07-05T03:00:00Z', amount: '250000', provider: 'xendit' }],
    })
    expect(html).toContain('Pembayaran diterima')
    expect(html).toContain('xendit')
    expect(html).toContain('Lunas pada')
  })

  it('menerima tanggal berformat YYYY-MM-DD maupun instant ISO', () => {
    const html = buildInvoiceSheetHtml({ ...BASE, issuedAt: '2026-07-01T10:00:00Z' })
    expect(html).toContain('Jul 2026')
  })

  // Nama pelanggan datang dari isian bebas operator; tanpa escape, satu nama nakal
  // bisa menyuntikkan markup ke dokumen yang dirakit dengan penggabungan string.
  it('meng-escape nilai dari pengguna, bukan menyisipkannya mentah', () => {
    const html = buildInvoiceSheetHtml({ ...BASE, customerName: '<script>alert(1)</script>' })
    expect(html).not.toContain('<script>alert(1)</script>')
    expect(html).toContain('&lt;script&gt;')
  })

  it('menyatakan diri bukan faktur pajak — data penerbit yang kita punya cuma nama', () => {
    expect(buildInvoiceSheetHtml(BASE)).toContain('Bukan faktur pajak')
  })

  it('mencantumkan nama ISP hanya bila diberikan (portal), bukan di konsol operator', () => {
    expect(buildInvoiceSheetHtml({ ...BASE, issuerName: 'ISP Demo' })).toContain('ISP Demo')
    expect(buildInvoiceSheetHtml(BASE)).not.toContain('class="issuer"')
  })
})
