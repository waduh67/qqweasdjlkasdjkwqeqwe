import { useEffect, useState, type FormEvent, type ReactNode } from 'react'
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
import {
  Badge,
  BrandMark,
  Button,
  EmptyState,
  IconLogout,
  IconReceipt,
  IconWifi,
  SelectField,
  Spinner,
  StatusBadge,
  TextField,
  ThemeToggle,
  type Tone,
} from '@/components/atoms'
import { Ess, Tabs } from '@/components/molecules'
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

/** Lama sesi menyala, dibulatkan ke satuan yang wajar diucapkan ("3 hari 4 jam"). */
function fmtUptime(seconds: number | null): string {
  if (seconds == null || seconds <= 0) return '—'
  const d = Math.floor(seconds / 86400)
  const h = Math.floor((seconds % 86400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  if (d > 0) return `${d} hari ${h} jam`
  if (h > 0) return `${h} jam ${m} menit`
  return `${m} menit`
}

/**
 * Status tagihan bukan status domain yang dikenal `StatusBadge` (PAID/ISSUED/… tak ada di
 * `STATUS_TONE`), jadi nadanya dipetakan di sini — tetap lewat `<Badge tone>` supaya warnanya
 * datang dari token yang sama, bukan gaya sebaris.
 */
const INVOICE_TONE: Record<string, Tone> = {
  PAID: 'good',
  ISSUED: 'warning',
  OVERDUE: 'critical',
  VOID: 'neutral',
  REFUNDED: 'accent',
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
 * Dasbor PORTAL pelanggan — realm terisolasi, lima menu: Ringkasan, Tagihan (+ Bayar online
 * lewat tautan hosted gateway), Koneksi (sesi PPPoE + perangkat), Bantuan, dan Profil (paket +
 * ganti password). Data ditarik sekali di sini lalu dibagikan ke menu, ter-scope ke pelanggan
 * yang login di server.
 *
 * Kerangkanya sengaja meniru konsol operator (bar aksen di puncak + strip tab di bawahnya),
 * bukan halaman melayang tanpa identitas: portal adalah wajah ISP ke pelanggannya, jadi ia
 * harus terasa bagian dari produk yang sama — hanya tanpa sidebar, karena lima menu tak
 * membutuhkannya dan sebagian besar pelanggan membukanya dari ponsel.
 */
export function PortalDashboard() {
  const { customer, logout } = usePortalAuth()
  const [tab, setTab] = useState<Tab>('ringkasan')

  const [profile, setProfile] = useState<PortalAccount | null>(null)
  const [billing, setBilling] = useState<PortalBilling | null>(null)
  const [connection, setConnection] = useState<PortalConnection | null>(null)
  // Dibedakan dari "data masih null": permintaan yang GAGAL juga berakhir null, dan layar
  // yang menulis "Memuat…" selamanya lebih membingungkan ketimbang mengaku tak dapat data.
  const [ready, setReady] = useState(false)

  const reloadBilling = () => getPortalBilling().then(setBilling).catch(() => setBilling(null))

  useEffect(() => {
    void Promise.allSettled([
      getPortalProfile().then(setProfile).catch(() => setProfile(null)),
      reloadBilling(),
      getPortalConnection().then(setConnection).catch(() => setConnection(null)),
    ]).then(() => setReady(true))
  }, [])

  const initials = (customer?.name ?? '?')
    .split(' ')
    .slice(0, 2)
    .map((s) => s[0]?.toUpperCase())
    .join('')

  const tenantSlug = customer?.tenantSlug ?? ''

  return (
    <div className="portal-shell">
      <header className="portal-topbar">
        <div className="row" style={{ gap: '0.6rem', minWidth: 0 }}>
          <BrandMark size={22} />
          <div style={{ lineHeight: 1.2, minWidth: 0 }}>
            <div style={{ fontWeight: 650, fontSize: '0.9rem' }}>Portal Pelanggan</div>
            <div className="muted" style={{ fontSize: '0.74rem' }}>{tenantSlug}</div>
          </div>
        </div>
        <div className="row" style={{ gap: '0.5rem' }}>
          <ThemeToggle />
          {/* Identitas yang dipakai: pelanggan perlu yakin sedang melihat akunnya sendiri —
              nama saja tak cukup bila satu keluarga punya beberapa titik langganan. */}
          <span className="user-chip">
            <span className="avatar" aria-hidden>{initials}</span>
            <div className="portal-user-name" style={{ lineHeight: 1.2 }}>
              <div style={{ fontWeight: 600, fontSize: '0.85rem' }}>{customer?.name}</div>
              <div className="muted tnum" style={{ fontSize: '0.74rem' }}>{customer?.code}</div>
            </div>
          </span>
          <Button
            variant="subtle"
            icon={<IconLogout size={18} />}
            onClick={() => void logout()}
            aria-label="Keluar"
            title="Keluar"
          />
        </div>
      </header>

      <nav className="portal-nav" aria-label="Menu portal">
        <Tabs
          active={tab}
          onChange={setTab}
          tabs={[
            { key: 'ringkasan' as Tab, label: 'Ringkasan' },
            {
              key: 'tagihan' as Tab,
              label: 'Tagihan',
              // Angka di tab = tagihan yang belum dibayar (termasuk yang belum jatuh tempo);
              // itulah alasan paling sering pelanggan membuka portal.
              badge: billing && billing.unpaidCount > 0 ? billing.unpaidCount : undefined,
            },
            { key: 'koneksi' as Tab, label: 'Koneksi' },
            { key: 'bantuan' as Tab, label: 'Bantuan' },
            { key: 'profil' as Tab, label: 'Profil' },
          ]}
        />
      </nav>

      <main className="portal-content stack" style={{ gap: '1.1rem' }}>
        {tab === 'ringkasan' && (
          <RingkasanTab
            profile={profile}
            billing={billing}
            connection={connection}
            ready={ready}
            tenantSlug={tenantSlug}
            customerName={customer?.name ?? 'Pelanggan'}
            onGo={setTab}
          />
        )}
        {tab === 'tagihan' && (
          <TagihanTab billing={billing} ready={ready} tenantSlug={tenantSlug} onReload={reloadBilling} />
        )}
        {tab === 'koneksi' && <KoneksiTab connection={connection} ready={ready} />}
        {/* Bantuan menarik datanya sendiri: utasnya hidup (balas-membalas), tak cocok
            dengan pemuatan sekali-jalan milik tab lain. */}
        {tab === 'bantuan' && <BantuanTab />}
        {tab === 'profil' && <ProfilTab profile={profile} ready={ready} />}
      </main>
    </div>
  )
}

/**
 * Ringkasan = jawaban atas tiga pertanyaan yang membawa pelanggan ke portal: "berapa yang harus
 * saya bayar?", "kenapa internet saya begini?", dan "saya berlangganan apa?". Karena itu isinya
 * kartu sambutan + metrik + tagihan terdekat + keadaan sambungan — bukan sekadar dua angka.
 */
function RingkasanTab({
  profile,
  billing,
  connection,
  ready,
  tenantSlug,
  customerName,
  onGo,
}: {
  profile: PortalAccount | null
  billing: PortalBilling | null
  connection: PortalConnection | null
  ready: boolean
  tenantSlug: string
  customerName: string
  onGo: (tab: Tab) => void
}) {
  if (!ready) return <Loading />

  // `outstandingAmount` server = yang MENUNGGAK saja (lewat jatuh tempo); tagihan bulan
  // berjalan yang belum jatuh tempo tak masuk hitungan itu — karenanya dijumlah sendiri,
  // supaya portal tak pernah menulis "lunas" sementara ada tagihan terbuka di bawahnya.
  const arrears = billing ? Number(billing.outstandingAmount) : 0
  const unpaidCount = billing?.unpaidCount ?? 0
  const session = connection?.session ?? null
  const online = session?.online ?? false
  // Langganan yang berlaku; bila pelanggan punya beberapa, yang aktif yang diwakilkan.
  const sub = profile?.subscriptions.find((s) => s.status === 'ACTIVE') ?? profile?.subscriptions[0] ?? null
  const speed = sub ? (sub.downMbps && sub.upMbps ? `${sub.downMbps}/${sub.upMbps} Mbps` : `${sub.bandwidthMbps} Mbps`) : '—'
  const open = (billing?.invoices ?? []).filter((i) => i.payable)
  const openTotal = open.reduce((sum, i) => sum + Number(i.amount), 0)
  // Tagihan terbuka yang paling dekat jatuh temponya — itulah yang dicari lebih dulu.
  const due = [...open].sort((a, b) => a.dueDate.localeCompare(b.dueDate))[0]

  return (
    <div className="stack" style={{ gap: '1.1rem' }}>
      <section className="card portal-hero stack" style={{ gap: '0.9rem' }}>
        <div className="spread" style={{ alignItems: 'flex-start', gap: '0.75rem', flexWrap: 'wrap' }}>
          <div className="stack" style={{ gap: '0.15rem' }}>
            <h1 className="page-title" style={{ margin: 0 }}>Halo, {customerName}</h1>
            <p className="page-sub" style={{ margin: 0 }}>
              {sub ? `${sub.packageName} · ${speed}` : 'Belum ada paket aktif di akun ini'}
            </p>
          </div>
          {sub && <StatusBadge status={sub.status} />}
        </div>
        <div className="row wrap" style={{ gap: '0.5rem' }}>
          <Button variant="primary" onClick={() => onGo('tagihan')}>
            {due ? 'Bayar tagihan' : 'Lihat tagihan'}
          </Button>
          <Button variant="subtle" onClick={() => onGo('bantuan')}>Lapor gangguan</Button>
        </div>
      </section>

      <div className="stat-grid">
        {arrears > 0 ? (
          <Stat
            label="Tunggakan"
            value={rupiah(arrears)}
            valueColor="var(--critical-ink)"
            tone="crit"
            note={`${billing?.outstandingCount ?? 0} tagihan lewat jatuh tempo`}
          />
        ) : unpaidCount > 0 ? (
          <Stat
            label="Belum dibayar"
            value={rupiah(openTotal)}
            tone="warn"
            note={`${unpaidCount} tagihan, belum jatuh tempo`}
          />
        ) : (
          <Stat
            label="Tagihan"
            value="Lunas"
            valueColor="var(--good-ink)"
            tone="good"
            note="Tak ada tagihan terbuka"
          />
        )}
        <Stat
          label="Status koneksi"
          value={session ? (online ? 'Online' : 'Offline') : '—'}
          valueColor={session ? (online ? 'var(--good-ink)' : 'var(--critical-ink)') : 'var(--muted)'}
          tone={session ? (online ? 'good' : 'crit') : undefined}
          note={session ? (session.framedIp ?? session.username) : 'Belum ada sesi tercatat'}
        />
        <Stat
          label="Kecepatan paket"
          value={speed}
          note={sub ? sub.packageName : 'Belum ada langganan'}
        />
        {due ? (
          <Stat
            label="Jatuh tempo terdekat"
            value={fmtDate(due.dueDate)}
            tone={arrears > 0 ? 'crit' : 'warn'}
            note={`Tagihan ${due.number}`}
          />
        ) : (
          <Stat
            label="Terakhir bayar"
            value={fmtDate(billing?.lastPaidAt ?? null)}
            note={billing?.lastPaidAt ? 'Pembayaran terakhir diterima' : 'Belum ada pembayaran'}
          />
        )}
      </div>

      <section className="card stack" style={{ gap: '0.7rem' }}>
        <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
          <strong style={{ fontSize: '0.95rem' }}>Tagihan berjalan</strong>
          <Button variant="subtle" onClick={() => onGo('tagihan')} style={{ fontSize: '0.8rem' }}>
            Semua tagihan
          </Button>
        </div>
        {!due ? (
          <EmptyState
            title="Tak ada tagihan terbuka"
            hint="Semua tagihanmu sudah lunas — terima kasih."
            icon={<IconReceipt size={32} />}
          />
        ) : (
          <div className="spread" style={{ alignItems: 'center', gap: '0.6rem', flexWrap: 'wrap' }}>
            <div className="stack" style={{ gap: 2, minWidth: 0 }}>
              <span style={{ fontWeight: 600 }}>{due.number}</span>
              <span className="muted" style={{ fontSize: '0.8rem' }}>
                {fmtDate(due.periodStart)}–{fmtDate(due.periodEnd)} · jatuh tempo {fmtDate(due.dueDate)}
              </span>
            </div>
            <div className="row" style={{ gap: '0.6rem', alignItems: 'center' }}>
              <span className="tnum" style={{ fontWeight: 600, fontSize: '1.05rem' }}>{rupiah(due.amount)}</span>
              <Button
                variant="primary"
                onClick={() => window.open(payLink(tenantSlug, due.id), '_blank', 'noopener')}
              >
                Bayar ↗
              </Button>
            </div>
          </div>
        )}
      </section>

      <section className="card stack" style={{ gap: '0.7rem' }}>
        <div className="spread" style={{ alignItems: 'center', gap: '0.5rem' }}>
          <strong style={{ fontSize: '0.95rem' }}>Sambungan</strong>
          <Button variant="subtle" onClick={() => onGo('koneksi')} style={{ fontSize: '0.8rem' }}>
            Detail koneksi
          </Button>
        </div>
        {!session ? (
          <EmptyState
            title="Belum ada sesi internet"
            hint="Sesi muncul setelah perangkatmu tersambung ke jaringan."
            icon={<IconWifi size={32} />}
          />
        ) : (
          <dl className="essentials wide">
            <Ess label="Menyala selama">{fmtUptime(session.uptimeSeconds)}</Ess>
            <Ess label="Alamat IP">
              <span className="tnum">{session.framedIp ?? '—'}</span>
            </Ess>
            <Ess label="Perangkat">
              {connection?.devices.length ? `${connection.devices.length} terpantau` : 'belum terpantau'}
            </Ess>
          </dl>
        )}
      </section>
    </div>
  )
}

/** Kartu metrik portal — memakai kelas `.stat` konsol supaya angkanya tampil seragam. */
function Stat({
  label,
  value,
  note,
  tone,
  valueColor,
}: {
  label: string
  value: ReactNode
  note?: string
  tone?: 'good' | 'warn' | 'crit'
  valueColor?: string
}) {
  const bar = tone === 'good' ? ' accent-bar' : tone === 'warn' ? ' warn-bar' : tone === 'crit' ? ' crit-bar' : ''
  return (
    <div className={`stat${bar}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value" style={valueColor ? { color: valueColor } : undefined}>{value}</div>
      {note && <div className="stat-note">{note}</div>}
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
  ready,
  tenantSlug,
  onReload,
}: {
  billing: PortalBilling | null
  ready: boolean
  tenantSlug: string
  onReload: () => Promise<unknown>
}) {
  if (!billing) return ready ? <Unavailable what="Tagihan" /> : <Loading />
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

function KoneksiTab({ connection, ready }: { connection: PortalConnection | null; ready: boolean }) {
  if (!connection) return ready ? <Unavailable what="Data koneksi" /> : <Loading />
  const s = connection.session
  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Sesi internet</strong>
        {!s ? (
          <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>Belum ada sesi PPPoE.</p>
        ) : (
          <dl className="essentials wide">
            <Ess label="Status">
              <StatusBadge status={s.online ? 'ONLINE' : 'OFFLINE'} />
            </Ess>
            <Ess label="Username">
              <span className="tnum">{s.username}</span>
            </Ess>
            <Ess label="Menyala selama">{fmtUptime(s.uptimeSeconds)}</Ess>
            <Ess label="Alamat IP">
              <span className="tnum">{s.framedIp ?? '—'}</span>
            </Ess>
            <Ess label="Paket">{s.planName ?? '—'}</Ess>
          </dl>
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
              <StatusBadge status={d.online ? 'ONLINE' : 'OFFLINE'} />
            </div>
          ))
        )}
      </div>
    </div>
  )
}

function ProfilTab({ profile, ready }: { profile: PortalAccount | null; ready: boolean }) {
  if (!profile) return ready ? <Unavailable what="Profil" /> : <Loading />
  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Profil</strong>
        <dl className="essentials wide">
          <Ess label="Nama">{profile.name}</Ess>
          <Ess label="Kode pelanggan">
            <span className="tnum">{profile.code}</span>
          </Ess>
          <Ess label="Telepon">{profile.phone ?? '—'}</Ess>
          <Ess label="Status">
            <StatusBadge status={profile.status} />
          </Ess>
        </dl>
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
                <StatusBadge status={sub.status} />
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

function Loading() {
  return (
    <div className="card" style={{ display: 'grid', placeItems: 'center', minHeight: 160 }}>
      <div className="stack" style={{ alignItems: 'center', gap: '0.6rem' }}>
        <Spinner />
        <span className="muted" style={{ fontSize: '0.85rem' }}>Memuat…</span>
      </div>
    </div>
  )
}

/** Pemuatan sudah selesai tapi datanya tak sampai — dikatakan apa adanya, bukan berputar terus. */
function Unavailable({ what }: { what: string }) {
  return (
    <div className="card">
      <EmptyState title={`${what} belum bisa ditampilkan`} hint="Coba muat ulang halaman sebentar lagi." />
    </div>
  )
}
