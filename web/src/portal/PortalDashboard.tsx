import { useEffect, useState, type FormEvent } from 'react'
import { usePortalAuth } from './PortalAuthContext'
import { PortalApiError } from './portalClient'
import {
  changePortalPassword,
  getPortalBilling,
  getPortalConnection,
  getPortalInvoicePrint,
  getPortalPlanOptions,
  getPortalProfile,
  requestPortalPlanChange,
  type PortalAccount,
  type PortalBilling,
  type PortalConnection,
  type PortalPlanChangeReceipt,
  type PortalPlanOption,
  type PortalSubscription,
} from './portalApi'
import { BantuanTab } from './PortalHelpTab'
import { payLink } from '@/api/publicPayment'
import { Button, Segmented, SelectField, TextField } from '@/components/atoms'
import { printInvoiceSheet } from '@/utils/invoiceSheet'

type Tab = 'ringkasan' | 'tagihan' | 'koneksi' | 'bantuan' | 'profil'

/** Rupiah tanpa desimal, dari nilai string BigDecimal server. */
function rupiah(amount: string | number): string {
  const n = typeof amount === 'string' ? Number(amount) : amount
  return new Intl.NumberFormat('id-ID', { style: 'currency', currency: 'IDR', maximumFractionDigits: 0 }).format(
    Number.isFinite(n) ? n : 0,
  )
}

/** Tanggal lokal (YYYY-MM-DD) → "5 Agu 2026". */
function fmtDate(value: string | null): string {
  if (!value) return '—'
  const d = new Date(value.length <= 10 ? `${value}T00:00:00` : value)
  return Number.isNaN(d.getTime())
    ? value
    : d.toLocaleDateString('id-ID', { day: 'numeric', month: 'short', year: 'numeric' })
}

const INVOICE_TONE: Record<string, string> = {
  PAID: 'var(--good-ink)',
  ISSUED: 'var(--warning-ink)',
  OVERDUE: 'var(--critical-ink)',
  VOID: 'var(--muted)',
  REFUNDED: 'var(--accent)',
}

/** Status ditulis dengan bahasa pelanggan — portal bukan tempat memamerkan nama enum. */
const INVOICE_STATUS_LABEL: Record<string, string> = {
  PAID: 'Lunas',
  ISSUED: 'Belum dibayar',
  OVERDUE: 'Jatuh tempo',
  VOID: 'Batal',
  REFUNDED: 'Dikembalikan',
}

/**
 * Dasbor PORTAL pelanggan — realm terisolasi, empat menu MVP: Ringkasan, Tagihan
 * (+ Bayar online lewat tautan hosted gateway), Koneksi (sesi PPPoE + perangkat), dan
 * Profil (paket + ganti password). Data ditarik sekali per menu, ter-scope ke pelanggan
 * yang login di server.
 */
export function PortalDashboard() {
  const { customer, logout } = usePortalAuth()
  const [tab, setTab] = useState<Tab>('ringkasan')

  const [profile, setProfile] = useState<PortalAccount | null>(null)
  const [billing, setBilling] = useState<PortalBilling | null>(null)
  const [connection, setConnection] = useState<PortalConnection | null>(null)

  const reloadBilling = () => getPortalBilling().then(setBilling).catch(() => setBilling(null))

  useEffect(() => {
    void getPortalProfile().then(setProfile).catch(() => setProfile(null))
    void reloadBilling()
    void getPortalConnection().then(setConnection).catch(() => setConnection(null))
  }, [])

  return (
    <div className="stack" style={{ gap: '1.25rem', maxWidth: 860, margin: '0 auto', padding: '1.25rem' }}>
      <header className="spread" style={{ alignItems: 'center', flexWrap: 'wrap', gap: '0.75rem' }}>
        <div>
          <h1 className="page-title" style={{ margin: 0 }}>Halo, {customer?.name ?? 'Pelanggan'}</h1>
          <p className="page-sub" style={{ margin: '0.2rem 0 0' }}>
            {customer?.code} · {customer?.tenantSlug}
          </p>
        </div>
        <Button variant="subtle" onClick={() => void logout()}>Keluar</Button>
      </header>

      <Segmented
        ariaLabel="Bagian dasbor"
        value={tab}
        onChange={setTab}
        options={[
          { value: 'ringkasan', label: 'Ringkasan' },
          { value: 'tagihan', label: 'Tagihan' },
          { value: 'koneksi', label: 'Koneksi' },
          { value: 'bantuan', label: 'Bantuan' },
          { value: 'profil', label: 'Profil' },
        ]}
      />

      {tab === 'ringkasan' && <RingkasanTab billing={billing} connection={connection} onPay={() => setTab('tagihan')} />}
      {tab === 'tagihan' && (
        <TagihanTab billing={billing} tenantSlug={customer?.tenantSlug ?? ''} onReload={reloadBilling} />
      )}
      {tab === 'koneksi' && <KoneksiTab connection={connection} />}
      {/* Bantuan menarik datanya sendiri: utasnya hidup (balas-membalas), tak cocok
          dengan pemuatan sekali-jalan milik tab lain. */}
      {tab === 'bantuan' && <BantuanTab />}
      {tab === 'profil' && <ProfilTab profile={profile} />}
    </div>
  )
}

function RingkasanTab({
  billing,
  connection,
  onPay,
}: {
  billing: PortalBilling | null
  connection: PortalConnection | null
  onPay: () => void
}) {
  const arrears = billing ? Number(billing.outstandingAmount) : 0
  const online = connection?.session?.online ?? false
  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))' }}>
        <div className="card stat">
          <div className="stat-label">Tunggakan</div>
          <div
            className="tnum"
            style={{ fontSize: '1.4rem', fontWeight: 600, color: arrears > 0 ? 'var(--critical-ink)' : 'var(--good-ink)' }}
          >
            {rupiah(arrears)}
          </div>
          <div className="muted" style={{ fontSize: '0.82rem' }}>
            {billing && billing.outstandingCount > 0 ? `${billing.outstandingCount} tagihan jatuh tempo` : 'lunas'}
          </div>
        </div>
        <div className="card stat">
          <div className="stat-label">Status koneksi</div>
          <div style={{ fontSize: '1.4rem', fontWeight: 600, color: online ? 'var(--good-ink)' : 'var(--muted)' }}>
            {connection?.session ? (online ? 'Online' : 'Offline') : '—'}
          </div>
          <div className="muted" style={{ fontSize: '0.82rem' }}>
            {connection?.session?.framedIp ?? connection?.session?.username ?? 'belum ada sesi'}
          </div>
        </div>
      </div>
      {arrears > 0 && (
        <Button variant="primary" style={{ alignSelf: 'flex-start' }} onClick={onPay}>
          Bayar tagihan
        </Button>
      )}
    </div>
  )
}

/**
 * Tagihan pelanggan. Membayar TIDAK lagi terjadi di panel inline sini: tombol "Bayar" membuka
 * halaman bayar publik `/bayar/<slug>/<uuid>` — halaman yang sama persis dengan yang diterima
 * pelanggan lewat tautan WhatsApp, jadi hanya ada satu tampilan bayar yang perlu dipahami.
 */
function TagihanTab({
  billing,
  tenantSlug,
  onReload,
}: {
  billing: PortalBilling | null
  tenantSlug: string
  onReload: () => Promise<unknown>
}) {
  if (!billing) return <Loading />
  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="card stack" style={{ gap: '0.6rem' }}>
        <div className="spread" style={{ alignItems: 'center' }}>
          <strong style={{ fontSize: '0.95rem' }}>Tagihan</strong>
          {/* Pembayaran selesai di tab lain, jadi status di sini perlu bisa ditarik ulang manual. */}
          <Button variant="subtle" onClick={() => void onReload()} style={{ fontSize: '0.8rem' }}>
            Perbarui status
          </Button>
        </div>
        {billing.invoices.length === 0 ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada tagihan.</p>
        ) : (
          billing.invoices.map((inv) => (
            <div key={inv.id} className="stack" style={{ gap: '0.6rem' }}>
              <div className="spread" style={{ alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
                <div className="stack" style={{ gap: 2, minWidth: 0 }}>
                  <span style={{ fontWeight: 600 }}>{inv.number}</span>
                  <span className="muted" style={{ fontSize: '0.8rem' }}>
                    {fmtDate(inv.periodStart)}–{fmtDate(inv.periodEnd)} · jatuh tempo {fmtDate(inv.dueDate)}
                  </span>
                </div>
                <div className="row" style={{ gap: '0.6rem', alignItems: 'center' }}>
                  <span className="tnum" style={{ fontWeight: 600 }}>{rupiah(inv.amount)}</span>
                  <span className="badge" style={{ color: INVOICE_TONE[inv.status] ?? undefined }}>
                    {INVOICE_STATUS_LABEL[inv.status] ?? inv.status}
                  </span>
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
 * ditekan, bukan di muka, supaya membuka tab Tagihan tak menembak N permintaan sekaligus.
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

function KoneksiTab({ connection }: { connection: PortalConnection | null }) {
  if (!connection) return <Loading />
  const s = connection.session
  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Sesi internet</strong>
        {!s ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada sesi PPPoE.</p>
        ) : (
          <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(140px, 1fr))' }}>
            <Field label="Status" value={s.online ? 'Online' : 'Offline'} tone={s.online ? 'var(--good-ink)' : 'var(--muted)'} />
            <Field label="Username" value={s.username} />
            <Field label="IP" value={s.framedIp ?? '—'} />
            <Field label="Paket" value={s.planName ?? '—'} />
          </div>
        )}
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Perangkat</strong>
        {connection.devices.length === 0 ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Tak ada perangkat terpantau.</p>
        ) : (
          connection.devices.map((d) => (
            <div key={d.deviceId} className="spread" style={{ alignItems: 'center' }}>
              <div className="stack" style={{ gap: 2 }}>
                <span style={{ fontWeight: 600 }}>{[d.manufacturer, d.model].filter(Boolean).join(' ') || d.serialNumber}</span>
                <span className="muted tnum" style={{ fontSize: '0.8rem' }}>{d.serialNumber}</span>
              </div>
              <span className="badge" style={{ color: d.online ? 'var(--good-ink)' : 'var(--muted)' }}>
                {d.online ? 'online' : 'offline'}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  )
}

function ProfilTab({ profile }: { profile: PortalAccount | null }) {
  if (!profile) return <Loading />
  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Profil</strong>
        <div className="stat-grid" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(160px, 1fr))' }}>
          <Field label="Nama" value={profile.name} />
          <Field label="Kode pelanggan" value={profile.code} />
          <Field label="Telepon" value={profile.phone ?? '—'} />
          <Field label="Status" value={profile.status} />
        </div>
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Paket berlangganan</strong>
        {profile.subscriptions.length === 0 ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada langganan.</p>
        ) : (
          profile.subscriptions.map((sub) => (
            <div key={sub.subscriptionId} className="spread" style={{ alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
              <div className="stack" style={{ gap: 2 }}>
                <span style={{ fontWeight: 600 }}>{sub.packageName}</span>
                <span className="muted" style={{ fontSize: '0.8rem' }}>
                  {sub.downMbps && sub.upMbps ? `${sub.downMbps}/${sub.upMbps} Mbps` : `${sub.bandwidthMbps} Mbps`}
                  {sub.fupEnabled ? ' · FUP' : ''}
                </span>
              </div>
              <div className="row" style={{ gap: '0.6rem', alignItems: 'center' }}>
                {sub.monthlyFee && <span className="tnum" style={{ fontWeight: 600 }}>{rupiah(sub.monthlyFee)}/bln</span>}
                <span className="badge">{sub.status}</span>
              </div>
            </div>
          ))
        )}
      </div>

      {profile.subscriptions.length > 0 && <RequestPlanChange subscriptions={profile.subscriptions} />}

      <ChangePassword />
    </div>
  )
}

/**
 * Ajuan pindah paket. Sengaja TIDAK mengubah langganan seketika: paket berubah berarti harga,
 * profil bandwidth, dan kadang kunjungan teknisi — jadi ajuannya masuk sebagai laporan
 * berkategori "Ajuan ganti paket" yang ditinjau operator, dan pelanggan mengikutinya di menu
 * Bantuan seperti laporan lain. Harga yang dikutip ke operator diambil server dari katalog,
 * bukan dari angka yang tampil di layar ini.
 */
function RequestPlanChange({ subscriptions }: { subscriptions: PortalSubscription[] }) {
  const [options, setOptions] = useState<PortalPlanOption[] | null>(null)
  const [subscriptionId, setSubscriptionId] = useState(subscriptions[0]?.subscriptionId ?? '')
  const [planId, setPlanId] = useState('')
  const [note, setNote] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [receipt, setReceipt] = useState<PortalPlanChangeReceipt | null>(null)

  useEffect(() => {
    let alive = true
    void getPortalPlanOptions()
      .then((list) => alive && setOptions(list))
      .catch(() => alive && setOptions([]))
    return () => {
      alive = false
    }
  }, [])

  // Paket yang sedang dipakai tak perlu ditawarkan — mengajukannya pasti ditolak server.
  const choices = (options ?? []).filter((p) => !p.current)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      setReceipt(await requestPortalPlanChange(subscriptionId, planId, note))
    } catch (err) {
      setError(err instanceof PortalApiError ? err.message : 'Ajuan gagal dikirim')
    } finally {
      setBusy(false)
    }
  }

  if (options === null) return null
  if (choices.length === 0) return null

  if (receipt) {
    return (
      <div className="card stack" style={{ gap: '0.4rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Ajuan ganti paket terkirim</strong>
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Nomor ajuan <strong className="tnum">{receipt.ticketCode}</strong>. Tim kami akan menghubungimu untuk
          memastikan jadwal dan biayanya — perkembangannya bisa kamu ikuti di menu <strong>Bantuan</strong>.
        </p>
      </div>
    )
  }

  return (
    <form className="card stack" style={{ gap: '0.6rem' }} onSubmit={onSubmit}>
      <strong style={{ fontSize: '0.95rem' }}>Ajukan ganti paket</strong>
      <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
        Paket tidak langsung berubah: ajuanmu ditinjau dulu oleh tim, termasuk biaya dan jadwalnya.
      </p>
      {subscriptions.length > 1 && (
        <SelectField
          label="Langganan yang mau diganti"
          value={subscriptionId}
          onChange={(_, data) => setSubscriptionId(data.value)}
        >
          {subscriptions.map((s) => (
            <option key={s.subscriptionId} value={s.subscriptionId}>{s.packageName}</option>
          ))}
        </SelectField>
      )}
      <SelectField label="Paket yang diinginkan" value={planId} onChange={(_, data) => setPlanId(data.value)} required>
        <option value="">— pilih paket —</option>
        {choices.map((p) => (
          <option key={p.planId} value={p.planId}>
            {p.name} · {p.downMbps && p.upMbps ? `${p.downMbps}/${p.upMbps}` : p.bandwidthMbps} Mbps ·{' '}
            {rupiah(p.monthlyFee)}/bln
          </option>
        ))}
      </SelectField>
      <TextField
        label="Catatan (opsional)"
        value={note}
        onChange={(_, data) => setNote(data.value)}
        maxLength={1000}
        placeholder="Mis. mulai bulan depan saja"
      />
      {error && <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>{error}</p>}
      <Button variant="primary" type="submit" disabled={busy || !planId} style={{ alignSelf: 'flex-start' }}>
        {busy ? 'Mengirim…' : 'Kirim ajuan'}
      </Button>
    </form>
  )
}

/** Ganti password mandiri; sukses = seluruh sesi berakhir → pelanggan login ulang. */
function ChangePassword() {
  const { logout } = usePortalAuth()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [done, setDone] = useState(false)

  async function onSubmit(event: FormEvent) {
    event.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await changePortalPassword(currentPassword, newPassword)
      setDone(true)
      // Server mencabut semua sesi; keluar agar pelanggan login ulang dengan password baru.
      setTimeout(() => void logout(), 1500)
    } catch (err) {
      setError(err instanceof PortalApiError ? err.message : 'Gagal mengganti password')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card stack" style={{ gap: '0.6rem' }} onSubmit={onSubmit}>
      <strong style={{ fontSize: '0.95rem' }}>Ganti password</strong>
      {done ? (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Password diganti — kamu akan diminta masuk ulang.
        </p>
      ) : (
        <>
          <TextField
            label="Password saat ini"
            type="password"
            value={currentPassword}
            onChange={(_, data) => setCurrentPassword(data.value)}
            required
            autoComplete="current-password"
          />
          <TextField
            label="Password baru (min. 8 karakter)"
            type="password"
            value={newPassword}
            onChange={(_, data) => setNewPassword(data.value)}
            required
            minLength={8}
            autoComplete="new-password"
          />
          {error && <p className="error" style={{ margin: 0, fontSize: '0.85rem' }}>{error}</p>}
          <Button variant="primary" type="submit" disabled={busy} style={{ alignSelf: 'flex-start' }}>
            {busy ? 'Menyimpan…' : 'Simpan'}
          </Button>
        </>
      )}
    </form>
  )
}

function Field({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div className="stat">
      <div className="stat-label">{label}</div>
      <div style={{ fontSize: '0.9rem', color: tone ?? 'var(--text-2)', fontWeight: tone ? 600 : 400, wordBreak: 'break-word' }}>
        {value}
      </div>
    </div>
  )
}

function Loading() {
  return (
    <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 120 }}>
      <span className="muted">Memuat…</span>
    </div>
  )
}
