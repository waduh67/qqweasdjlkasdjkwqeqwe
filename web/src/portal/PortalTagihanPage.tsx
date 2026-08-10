import { useState } from 'react'
import { usePortalData } from './PortalLayout'
import { getPortalInvoicePrint } from './portalApi'
import { INVOICE_STATUS_LABEL, INVOICE_TONE, Loading, Unavailable, fmtDate, rupiah } from './portalFormat'
import { payLink } from '@/api/publicPayment'
import { Badge, Button } from '@/components/atoms'
import { printInvoiceSheet } from '@/utils/invoiceSheet'

/**
 * Tagihan pelanggan. Membayar TIDAK terjadi di panel inline sini: tombol "Bayar" membuka
 * halaman bayar publik `/bayar/<slug>/<uuid>` — halaman yang sama persis dengan yang diterima
 * pelanggan lewat tautan WhatsApp, jadi hanya ada satu tampilan bayar yang perlu dipahami.
 */
export function PortalTagihanPage() {
  const { billing, ready, tenantSlug, reloadBilling } = usePortalData()
  if (!billing) return ready ? <Unavailable what="Tagihan" /> : <Loading />

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="stack" style={{ gap: '0.15rem' }}>
        <h1 className="page-title" style={{ margin: 0 }}>Tagihan</h1>
        <p className="page-sub" style={{ margin: 0 }}>Tagihan berjalan dan riwayat pembayaranmu.</p>
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <div className="spread" style={{ alignItems: 'center' }}>
          <strong style={{ fontSize: '0.95rem' }}>Tagihan</strong>
          {/* Pembayaran selesai di tab peramban lain, jadi status di sini perlu bisa ditarik ulang manual. */}
          <Button variant="subtle" onClick={() => void reloadBilling()} style={{ fontSize: '0.8rem' }}>
            Perbarui status
          </Button>
        </div>
        {billing.invoices.length === 0 ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada tagihan.</p>
        ) : (
          billing.invoices.map((inv) => (
            <div key={inv.id} className="spread" style={{ alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
              <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                <span style={{ fontWeight: 600 }}>{inv.number}</span>
                <span className="muted" style={{ fontSize: '0.8rem' }}>
                  {fmtDate(inv.periodStart)}–{fmtDate(inv.periodEnd)} · jatuh tempo {fmtDate(inv.dueDate)}
                </span>
              </div>
              <div className="row" style={{ gap: '0.6rem', alignItems: 'center' }}>
                <span className="tnum" style={{ fontWeight: 600 }}>{rupiah(inv.amount)}</span>
                <Badge tone={INVOICE_TONE[inv.status] ?? 'neutral'}>
                  {INVOICE_STATUS_LABEL[inv.status] ?? inv.status}
                </Badge>
                <PrintInvoiceButton invoiceId={inv.id} />
                {inv.payable && (
                  <Button
                    variant="primary"
                    onClick={() => window.open(payLink(tenantSlug, inv.id), '_blank', 'noopener')}
                  >
                    Bayar ↗
                  </Button>
                )}
              </div>
            </div>
          ))
        )}
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Riwayat pembayaran</strong>
        {billing.payments.length === 0 ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada pembayaran.</p>
        ) : (
          billing.payments.map((pay) => (
            <div key={pay.id} className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
              <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                {/* Nomor tagihan ikut ditulis: "Rp150.000 · xendit" saja tak menjawab
                    pertanyaan yang sebenarnya, yaitu tagihan bulan mana yang lunas. */}
                <span style={{ fontWeight: 600, fontSize: '0.88rem' }}>{pay.invoiceNumber ?? 'Pembayaran'}</span>
                <span className="muted" style={{ fontSize: '0.8rem' }}>{fmtDate(pay.paidAt)} · {pay.provider}</span>
              </div>
              <span className="tnum" style={{ fontWeight: 600, color: 'var(--good-ink)' }}>{rupiah(pay.amount)}</span>
            </div>
          ))
        )}
      </div>
    </div>
  )
}

/**
 * "Cetak" satu tagihan: lembarnya dirakit SERVER (`/invoices/{id}/print`) lalu dicetak lewat
 * template bersama `printInvoiceSheet` — sama dengan yang dipakai operator. Data ditarik saat
 * ditekan, bukan di muka, supaya membuka halaman Tagihan tak menembak N permintaan sekaligus.
 */
function PrintInvoiceButton({ invoiceId }: { invoiceId: string }) {
  const [busy, setBusy] = useState(false)

  async function onPrint() {
    setBusy(true)
    try {
      const sheet = await getPortalInvoicePrint(invoiceId)
      printInvoiceSheet({
        issuerName: sheet.issuerName,
        number: sheet.invoice.number,
        issuedAt: sheet.invoice.issuedAt,
        dueDate: sheet.invoice.dueDate,
        statusLabel: INVOICE_STATUS_LABEL[sheet.invoice.status] ?? sheet.invoice.status,
        customerName: sheet.customerName,
        customerCode: sheet.customerCode,
        packageName: sheet.packageName,
        periodStart: sheet.invoice.periodStart,
        periodEnd: sheet.invoice.periodEnd,
        prorated: sheet.prorated,
        proratedDays: sheet.proratedDays,
        baseAmount: sheet.baseAmount,
        taxAmount: sheet.taxAmount,
        totalAmount: sheet.invoice.amount,
        taxRate: sheet.taxRate,
        paidAt: sheet.invoice.paidAt,
        payments: sheet.payments.map((p) => ({ paidAt: p.paidAt, amount: p.amount, provider: p.provider })),
      })
    } catch {
      // Gagal ambil lembar cetak bukan alasan mengganggu layar tagihan; tombol cukup pulih.
    } finally {
      setBusy(false)
    }
  }

  return (
    <Button variant="subtle" onClick={() => void onPrint()} disabled={busy} style={{ fontSize: '0.8rem' }}>
      {busy ? 'Menyiapkan…' : 'Cetak'}
    </Button>
  )
}
