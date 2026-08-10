import { useEffect, useState, type FormEvent } from 'react'
import { usePortalAuth } from './PortalAuthContext'
import { usePortalData } from './PortalLayout'
import { PortalApiError } from './portalClient'
import {
  changePortalPassword,
  getPortalPlanOptions,
  requestPortalPlanChange,
  type PortalPlanChangeReceipt,
  type PortalPlanOption,
  type PortalSubscription,
} from './portalApi'
import { Loading, Unavailable, rupiah } from './portalFormat'
import { Button, SelectField, StatusBadge, TextField } from '@/components/atoms'
import { Ess } from '@/components/molecules'

/** Identitas pelanggan, paket yang dipegang, dan dua tindakan mandiri: ganti paket & ganti password. */
export function PortalProfilPage() {
  const { profile, ready } = usePortalData()
  if (!profile) return ready ? <Unavailable what="Profil" /> : <Loading />

  return (
    <div className="stack" style={{ gap: '1rem' }}>
      <div className="stack" style={{ gap: '0.15rem' }}>
        <h1 className="page-title" style={{ margin: 0 }}>Profil</h1>
        <p className="page-sub" style={{ margin: 0 }}>Data akun dan paket yang kamu langgan.</p>
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <strong style={{ fontSize: '0.95rem' }}>Data pelanggan</strong>
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
