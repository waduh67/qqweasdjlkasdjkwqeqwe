import { useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import {
  cancelTenantSubscription,
  configureTenantSubscription,
  generateSubscriptionInvoice,
  getTenantSubscription,
  INVOICE_STATUS_LABEL,
  paySubscriptionInvoice,
  SUBSCRIPTION_STATUS_LABEL,
  voidSubscriptionInvoice,
  type SubscriptionInvoiceView,
  type SubscriptionStatus,
  type TenantSubscriptionDetailView,
} from '../api/platformBilling'
import { useCan } from '../auth/useCan'
import { Badge, Modal, useToast } from '../components/ui'
import type { Tone } from '../components/ui'

/**
 * Panel kelola langganan SaaS satu tenant (untuk super-admin): atur biaya bulanan flat, lihat status,
 * terbitkan/void tagihan, catat pelunasan manual, dan hentikan langganan. Dibuka dari `TenantsPage`.
 * Server tetap penegak izin (`platform.subscription.*`); `manage` di sini hanya untuk UX.
 */

const STATUS_TONE: Record<SubscriptionStatus, Tone> = {
  ACTIVE: 'good',
  PAST_DUE: 'warning',
  SUSPENDED: 'serious',
  CANCELLED: 'neutral',
}

const INVOICE_TONE: Record<SubscriptionInvoiceView['status'], Tone> = {
  ISSUED: 'accent',
  PAID: 'good',
  OVERDUE: 'critical',
  VOID: 'neutral',
}

const fmtIdr = (n: number) => `Rp ${n.toLocaleString('id-ID')}`
const fmtDate = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString('id-ID', { day: '2-digit', month: 'short', year: 'numeric' }) : '—')

export function TenantSubscriptionModal({
  tenantId,
  tenantName,
  onClose,
}: {
  tenantId: string
  tenantName: string
  onClose: () => void
}) {
  const { can } = useCan()
  const toast = useToast()
  const manage = can('platform.subscription.manage')

  const [sub, setSub] = useState<TenantSubscriptionDetailView | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)

  // Form biaya bulanan (create/ubah). Diisi dari langganan bila ada.
  const [fee, setFee] = useState('')
  const [billingDay, setBillingDay] = useState('')
  const [graceDays, setGraceDays] = useState('')

  const applySub = (s: TenantSubscriptionDetailView | null) => {
    setSub(s)
    setFee(s ? String(s.monthlyFee) : '')
    setBillingDay(s?.billingDay != null ? String(s.billingDay) : '')
    setGraceDays(s?.graceDays != null ? String(s.graceDays) : '')
  }

  useEffect(() => {
    getTenantSubscription(tenantId)
      .then(applySub)
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Gagal memuat langganan'))
      .finally(() => setLoading(false))
  }, [tenantId, toast])

  async function run(action: () => Promise<TenantSubscriptionDetailView | SubscriptionInvoiceView>, ok: string) {
    if (busy) return
    setBusy(true)
    try {
      const result = await action()
      // configure/cancel balas detail; invoice ops balas satu tagihan → reload penuh.
      if ('invoices' in result) applySub(result)
      else applySub(await getTenantSubscription(tenantId))
      toast.success(ok)
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
    } finally {
      setBusy(false)
    }
  }

  const saveFee = () => {
    const monthlyFee = Number(fee)
    if (!Number.isFinite(monthlyFee) || monthlyFee < 0) {
      toast.error('Biaya bulanan tidak valid')
      return
    }
    void run(
      () =>
        configureTenantSubscription(tenantId, {
          monthlyFee,
          billingDay: billingDay.trim() ? Number(billingDay) : null,
          graceDays: graceDays.trim() ? Number(graceDays) : null,
        }),
      sub ? 'Langganan diperbarui' : 'Langganan dibuat',
    )
  }

  const payInvoice = (inv: SubscriptionInvoiceView) => {
    const note = window.prompt(`Catat pelunasan manual tagihan ${inv.number}?\n(Opsional: catatan)`, '')
    if (note === null) return // batal
    void run(
      () => paySubscriptionInvoice(tenantId, inv.id, { amount: null, note: note.trim() || null }),
      `Tagihan ${inv.number} ditandai lunas`,
    )
  }

  const voidInvoice = (inv: SubscriptionInvoiceView) => {
    if (!window.confirm(`Batalkan tagihan ${inv.number}? Aksi ini tidak bisa dibatalkan.`)) return
    void run(() => voidSubscriptionInvoice(tenantId, inv.id), `Tagihan ${inv.number} dibatalkan`)
  }

  const cancelSub = () => {
    if (!window.confirm('Hentikan langganan tenant ini? Tenant berhenti ditagih.')) return
    void run(() => cancelTenantSubscription(tenantId), 'Langganan dihentikan')
  }

  const feeDirty =
    !sub ||
    Number(fee) !== sub.monthlyFee ||
    (billingDay.trim() ? Number(billingDay) : null) !== sub.billingDay ||
    (graceDays.trim() ? Number(graceDays) : null) !== sub.graceDays

  return (
    <Modal title={`Langganan — ${tenantName}`} onClose={onClose} wide>
      {loading ? (
        <p className="muted">Memuat langganan…</p>
      ) : (
        <div className="stack" style={{ gap: '1rem' }}>
          {/* Status ringkas */}
          {sub && (
            <div className="row" style={{ gap: '0.5rem', flexWrap: 'wrap', alignItems: 'center' }}>
              <Badge tone={STATUS_TONE[sub.status]}>{SUBSCRIPTION_STATUS_LABEL[sub.status]}</Badge>
              <span className="muted" style={{ fontSize: '0.85rem' }}>
                {fmtIdr(sub.monthlyFee)}/bln · tagih berikutnya {fmtDate(sub.nextInvoiceAt)}
              </span>
            </div>
          )}

          {/* Form biaya bulanan */}
          <div className="card stack" style={{ gap: '0.75rem' }}>
            <h4 style={{ margin: 0 }}>{sub ? 'Ubah biaya bulanan' : 'Aktifkan langganan'}</h4>
            <div className="row" style={{ gap: '0.75rem', flexWrap: 'wrap', alignItems: 'flex-start' }}>
              <label style={{ flex: 2, minWidth: 160 }}>
                <span>Biaya bulanan (Rp)</span>
                <input
                  type="number"
                  min={0}
                  value={fee}
                  onChange={(e) => setFee(e.target.value)}
                  placeholder="mis. 250000"
                  disabled={!manage}
                />
              </label>
              <label style={{ flex: 1, minWidth: 110 }}>
                <span>Tanggal tagih</span>
                <input
                  type="number"
                  min={1}
                  max={28}
                  value={billingDay}
                  onChange={(e) => setBillingDay(e.target.value)}
                  placeholder="global"
                  disabled={!manage}
                />
              </label>
              <label style={{ flex: 1, minWidth: 110 }}>
                <span>Masa tenggang</span>
                <input
                  type="number"
                  min={0}
                  max={90}
                  value={graceDays}
                  onChange={(e) => setGraceDays(e.target.value)}
                  placeholder="global"
                  disabled={!manage}
                />
              </label>
            </div>
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              Tanggal tagih &amp; masa tenggang kosong = pakai default global. Menyimpan tenant baru langsung
              menjadwalkan tagihan pertama.
            </span>
            {manage && (
              <div className="spread" style={{ alignItems: 'center' }}>
                {sub && sub.status !== 'CANCELLED' ? (
                  <button className="ghost" onClick={cancelSub} disabled={busy}>
                    Hentikan langganan
                  </button>
                ) : (
                  <span />
                )}
                <button className="primary" onClick={saveFee} disabled={!feeDirty || busy}>
                  {sub ? 'Simpan' : 'Aktifkan'}
                </button>
              </div>
            )}
          </div>

          {/* Tagihan */}
          {sub && (
            <div className="stack" style={{ gap: '0.6rem' }}>
              <div className="spread" style={{ alignItems: 'center' }}>
                <h4 style={{ margin: 0 }}>Tagihan</h4>
                {manage && sub.status !== 'CANCELLED' && (
                  <button
                    onClick={() =>
                      void run(() => generateSubscriptionInvoice(tenantId), 'Tagihan diterbitkan')
                    }
                    disabled={busy}
                  >
                    Terbitkan tagihan
                  </button>
                )}
              </div>
              {sub.invoices.length === 0 ? (
                <p className="muted" style={{ margin: 0 }}>Belum ada tagihan.</p>
              ) : (
                <div className="stack" style={{ gap: '0.4rem' }}>
                  {sub.invoices.map((inv) => (
                    <InvoiceRow
                      key={inv.id}
                      inv={inv}
                      manage={manage}
                      busy={busy}
                      onPay={() => payInvoice(inv)}
                      onVoid={() => voidInvoice(inv)}
                    />
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </Modal>
  )
}

function InvoiceRow({
  inv,
  manage,
  busy,
  onPay,
  onVoid,
}: {
  inv: SubscriptionInvoiceView
  manage: boolean
  busy: boolean
  onPay: () => void
  onVoid: () => void
}) {
  const outstanding = inv.status === 'ISSUED' || inv.status === 'OVERDUE'
  return (
    <div
      className="row"
      style={{
        gap: '0.6rem',
        alignItems: 'center',
        flexWrap: 'wrap',
        padding: '0.5rem 0.6rem',
        borderRadius: 'var(--radius-sm)',
        border: '1px solid var(--border, #2a3340)',
      }}
    >
      <div className="stack" style={{ gap: '0.15rem', flex: 1, minWidth: 180 }}>
        <span className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
          <strong style={{ fontSize: '0.85rem', fontFamily: 'monospace' }}>{inv.number}</strong>
          <Badge tone={INVOICE_TONE[inv.status]}>{INVOICE_STATUS_LABEL[inv.status]}</Badge>
        </span>
        <span className="muted" style={{ fontSize: '0.78rem' }}>
          {fmtDate(inv.periodStart)}–{fmtDate(inv.periodEnd)} · jatuh tempo {fmtDate(inv.dueDate)}
          {inv.paidAt && ` · lunas ${fmtDate(inv.paidAt)}`}
        </span>
      </div>
      <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>{fmtIdr(inv.amount)}</span>
      <div className="row" style={{ gap: '0.35rem', justifyContent: 'flex-end' }}>
        {inv.payUrl && outstanding && (
          <a
            href={inv.payUrl}
            target="_blank"
            rel="noreferrer"
            style={{ fontSize: '0.82rem', fontWeight: 600, alignSelf: 'center', whiteSpace: 'nowrap' }}
          >
            Tautan bayar ↗
          </a>
        )}
        {manage && outstanding && (
          <>
            <button onClick={onPay} disabled={busy}>
              Tandai lunas
            </button>
            <button className="ghost" onClick={onVoid} disabled={busy}>
              Batalkan
            </button>
          </>
        )}
      </div>
    </div>
  )
}
