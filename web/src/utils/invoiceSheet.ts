/**
 * Lembar tagihan siap cetak — dipakai bersama konsol operator (`InvoicesPage`) dan portal
 * pelanggan (`PortalTagihanPage`). Satu template supaya kertas yang dipegang pelanggan sama
 * persis, dari mana pun ia dicetak.
 *
 * Tak ada endpoint PDF di server: dokumen HTML dirakit di klien lalu dicetak lewat iframe
 * tersembunyi — dialog cetak browser menyediakan "Simpan sebagai PDF". Semua nilai dari
 * pengguna di-escape sebelum masuk HTML.
 *
 * Ini BUKTI TAGIHAN, bukan faktur pajak: data penerbit yang kita punya cuma nama tenant
 * (tanpa alamat/NPWP), jadi kop dokumen sengaja tak mengaku-ngaku sebagai dokumen pajak.
 */

/** Satu pembayaran yang sudah masuk untuk tagihan ini. */
export interface InvoiceSheetPayment {
  /** Instant ISO atau "YYYY-MM-DD"; keduanya diterima. */
  paidAt: string
  amount: string | number
  provider: string
}

export interface InvoiceSheetData {
  /** Nama ISP penerbit; null di konsol operator (dia sudah tahu sedang di tenant mana). */
  issuerName?: string | null
  number: string
  /** Instant ISO atau "YYYY-MM-DD". */
  issuedAt: string
  dueDate: string
  statusLabel: string
  customerName: string
  customerCode?: string | null
  /** Nama paket sebagai keterangan baris layanan; null bila langganannya sudah tak ada. */
  packageName?: string | null
  periodStart: string
  periodEnd: string
  prorated: boolean
  proratedDays?: number | null
  baseAmount: string | number
  taxAmount: string | number
  totalAmount: string | number
  /** Pecahan, mis. "0.11" untuk PPN 11%. */
  taxRate?: string | number | null
  paidAt?: string | null
  payments?: InvoiceSheetPayment[]
}

/** Escape teks pengguna sebelum disisipkan ke HTML cetak (hindari HTML injection). */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function toNumber(value: string | number | null | undefined): number {
  const n = typeof value === 'string' ? Number(value) : (value ?? 0)
  return Number.isFinite(n) ? n : 0
}

function rupiah(value: string | number | null | undefined): string {
  return `Rp ${toNumber(value).toLocaleString('id-ID')}`
}

/** Menerima "YYYY-MM-DD" maupun Instant ISO — keduanya jadi "15 Jul 2026". */
function fmtDate(value: string | null | undefined): string {
  if (!value) return '—'
  const d = new Date(value.length <= 10 ? `${value}T00:00:00` : value)
  return Number.isNaN(d.getTime())
    ? value
    : d.toLocaleDateString('id-ID', { day: '2-digit', month: 'short', year: 'numeric' })
}

/** "0.11" → "11%"; nol/kosong → null supaya barisnya tak dicetak sama sekali. */
function fmtTaxRate(rate: string | number | null | undefined): string | null {
  const n = toNumber(rate)
  return n > 0 ? `${(n * 100).toFixed(2).replace(/\.?0+$/, '')}%` : null
}

const STYLES = `
  :root {
    --fontFamilyBase: 'Segoe UI', 'Segoe UI Web (West European)', -apple-system, BlinkMacSystemFont, Roboto, 'Helvetica Neue', sans-serif;
    --fontFamilyMonospace: Consolas, 'Courier New', monospace;
    --fontSizeBase200: 12px;
    --fontSizeBase300: 14px;
    --fontSizeBase400: 16px;
    --fontSizeBase500: 20px;
    --fontSizeBase600: 24px;
    --fontWeightRegular: 400;
    --fontWeightSemibold: 600;
    --fontWeightBold: 700;
    --lineHeightBase300: 20px;
  }
  * { box-sizing: border-box; }
  body { font-family: var(--fontFamilyBase); color: #1a1a1a; margin: 0; padding: 32px; font-size: var(--fontSizeBase300); }
  .head { display: flex; justify-content: space-between; align-items: flex-start; border-bottom: 2px solid #1a1a1a; padding-bottom: 12px; margin-bottom: 20px; }
  h1 { font-size: var(--fontSizeBase600); margin: 0; }
  .issuer { font-size: var(--fontSizeBase300); font-weight: var(--fontWeightSemibold); color: #555; margin-bottom: 6px; }
  .num { font-family: var(--fontFamilyMonospace); font-size: var(--fontSizeBase300); margin-top: 4px; color: #555; }
  .meta { text-align: right; font-size: var(--fontSizeBase200); color: #555; line-height: var(--lineHeightBase300); }
  .party { margin-bottom: 20px; }
  .party .h { font-size: var(--fontSizeBase200); text-transform: uppercase; color: #888; margin-bottom: 3px; }
  .party .n { font-size: var(--fontSizeBase400); font-weight: var(--fontWeightSemibold); }
  table { width: 100%; border-collapse: collapse; }
  .amt { margin-top: 8px; }
  .amt td { padding: 7px 0; border-bottom: 1px solid #eee; }
  .amt td.lbl { color: #555; }
  .amt td.val { text-align: right; font-variant-numeric: tabular-nums; }
  .amt tr.total td { border-top: 2px solid #1a1a1a; border-bottom: none; font-size: var(--fontSizeBase400); padding-top: 12px; }
  .pay { margin-top: 24px; }
  .pay .h { font-size: var(--fontSizeBase200); text-transform: uppercase; color: #888; margin-bottom: 6px; }
  .foot { margin-top: 28px; font-size: var(--fontSizeBase200); color: #999; border-top: 1px solid #eee; padding-top: 10px; }
  .status { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: var(--fontSizeBase200); font-weight: var(--fontWeightSemibold); border: 1px solid #ccc; }
  .type-semibold { font-weight: var(--fontWeightSemibold); }
  .type-bold { font-weight: var(--fontWeightBold); }
`

/**
 * Merakit dokumen HTML lembar tagihan. Dipisah dari [printInvoiceSheet] dan diekspor
 * karena inilah bagian yang berisi keputusan (escape, prorata, baris PPN yang muncul
 * hanya bila dipungut) — bisa diperiksa sebagai string, tanpa dialog cetak.
 */
export function buildInvoiceSheetHtml(data: InvoiceSheetData): string {
  const taxPct = fmtTaxRate(data.taxRate)
  const tax = toNumber(data.taxAmount)
  const row = (label: string, value: string, cls = '') =>
    `<tr class="${cls}"><td class="lbl">${label}</td><td class="val">${value}</td></tr>`
  const payments = data.payments ?? []

  return `<!doctype html><html lang="id"><head><meta charset="utf-8">
<title>Tagihan ${escapeHtml(data.number)}</title>
<style>${STYLES}</style></head><body>
  <div class="head">
    <div>
      ${data.issuerName ? `<div class="issuer">${escapeHtml(data.issuerName)}</div>` : ''}
      <h1>TAGIHAN</h1>
      <div class="num">${escapeHtml(data.number)}</div>
    </div>
    <div class="meta">
      <div>Tanggal terbit: <strong>${fmtDate(data.issuedAt)}</strong></div>
      <div>Jatuh tempo: <strong>${fmtDate(data.dueDate)}</strong></div>
      <div>Status: <span class="status">${escapeHtml(data.statusLabel)}</span></div>
    </div>
  </div>
  <div class="party">
    <div class="h">Ditagihkan kepada</div>
    <div class="n">${escapeHtml(data.customerName)}</div>
    ${data.customerCode ? `<div style="color:#555">${escapeHtml(data.customerCode)}</div>` : ''}
  </div>
  <table><tbody>
    ${data.packageName ? row('Layanan', escapeHtml(data.packageName)) : ''}
    ${row('Periode layanan', `${fmtDate(data.periodStart)} – ${fmtDate(data.periodEnd)}`)}
    ${data.prorated ? row('Prorata', `${data.proratedDays ?? '—'} hari`) : ''}
  </tbody></table>
  <table class="amt"><tbody>
    ${row('Dasar pengenaan (DPP)', rupiah(data.baseAmount))}
    ${tax > 0 ? row(`PPN${taxPct ? ` (${taxPct})` : ''}`, rupiah(tax)) : ''}
    <tr class="total"><td class="lbl">Total tagihan</td><td class="val type-bold">${rupiah(data.totalAmount)}</td></tr>
  </tbody></table>
  ${
    payments.length > 0
      ? `<div class="pay"><div class="h">Pembayaran diterima</div><table class="amt"><tbody>${payments
          .map((p) => row(`${fmtDate(p.paidAt)} · ${escapeHtml(p.provider)}`, rupiah(p.amount)))
          .join('')}</tbody></table></div>`
      : ''
  }
  ${data.paidAt ? `<p class="type-semibold" style="margin-top:16px;color:#128a3a">Lunas pada ${fmtDate(data.paidAt)}</p>` : ''}
  <div class="foot">
    Dokumen ini dibuat otomatis oleh sistem dan sah tanpa tanda tangan. Bukan faktur pajak.
    Nomor tagihan: ${escapeHtml(data.number)}.
  </div>
</body></html>`
}

/**
 * Merakit lembar tagihan lalu membuka dialog cetak. Memakai iframe tersembunyi (bukan
 * `window.open`) agar tak tertahan pemblokir pop-up dan tak mengganggu halaman yang sedang dibuka.
 */
export function printInvoiceSheet(data: InvoiceSheetData): void {
  const iframe = document.createElement('iframe')
  iframe.setAttribute('aria-hidden', 'true')
  iframe.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;'
  iframe.srcdoc = buildInvoiceSheetHtml(data)
  iframe.onload = () => {
    const win = iframe.contentWindow
    if (!win) return
    win.focus()
    win.print()
    // Bersihkan setelah dialog cetak selesai; timeout jadi jaring pengaman lintas-browser.
    win.onafterprint = () => iframe.remove()
    setTimeout(() => {
      if (document.body.contains(iframe)) iframe.remove()
    }, 60_000)
  }
  document.body.appendChild(iframe)
}
