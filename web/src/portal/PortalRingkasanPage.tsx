import { useNavigate } from 'react-router-dom'
import { usePortalData } from './PortalLayout'
import { INVOICE_STATUS_LABEL, INVOICE_TONE, Loading, Stat, fmtDate, fmtUptime, rupiah } from './portalFormat'
import { payLink } from '@/api/publicPayment'
import { Badge, Button, EmptyState, IconReceipt, IconWifi, StatusBadge } from '@/components/atoms'
import { Ess } from '@/components/molecules'

/**
 * Ringkasan = jawaban atas tiga pertanyaan yang membawa pelanggan ke portal: "berapa yang harus
 * saya bayar?", "kenapa internet saya begini?", dan "saya berlangganan apa?". Karena itu isinya
 * kartu sambutan + metrik + tagihan terdekat + keadaan sambungan — bukan sekadar dua angka.
 */
export function PortalRingkasanPage() {
  const { profile, billing, connection, ready, tenantSlug, customerName } = usePortalData()
  const navigate = useNavigate()

  if (!ready) return <Loading />

  // `outstandingAmount` server = yang MENUNGGAK saja (lewat jatuh tempo); tagihan bulan
  // berjalan yang belum jatuh tempo tak masuk hitungan itu — karenanya dijumlah sendiri,
  // supaya portal tak pernah menulis "lunas" sementara ada tagihan terbuka di bawahnya.
  const arrears = billing ? Number(billing.outstandingAmount) : 0
  const unpaidCount = billing?.unpaidCount ?? 0
  const session = connection?.session ?? null
  const online = session?.online ?? false
  // Satu pelanggan satu langganan — tak ada yang perlu dipilih atau diwakilkan.
  const sub = profile?.subscription ?? null
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
          <Button variant="primary" onClick={() => void navigate('/portal/tagihan')}>
            {due ? 'Bayar tagihan' : 'Lihat tagihan'}
          </Button>
          <Button variant="subtle" onClick={() => void navigate('/portal/bantuan')}>Lapor gangguan</Button>
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
        <Stat label="Kecepatan paket" value={speed} note={sub ? sub.packageName : 'Belum ada langganan'} />
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
          <Button variant="subtle" onClick={() => void navigate('/portal/tagihan')} style={{ fontSize: '0.8rem' }}>
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
              <Badge tone={INVOICE_TONE[due.status] ?? 'neutral'}>
                {INVOICE_STATUS_LABEL[due.status] ?? due.status}
              </Badge>
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
          <Button variant="subtle" onClick={() => void navigate('/portal/koneksi')} style={{ fontSize: '0.8rem' }}>
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
