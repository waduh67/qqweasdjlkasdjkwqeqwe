import { useEffect, useState, type FormEvent } from 'react'
import { Text } from '@fluentui/react-components'
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
        <Text as="h1" className="page-title" size={700} weight="semibold" style={{ margin: 0 }}>Profil</Text>
        <Text as="p" className="page-sub" size={400} style={{ margin: 0 }}>Data akun dan paket yang kamu langgan.</Text>
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <Text as="h2" size={400} weight="semibold">Data pelanggan</Text>
        <dl className="essentials wide">
          <Ess label="Nama">{profile.name}</Ess>
          <Ess label="Kode pelanggan">
            <Text as="span" className="tnum">{profile.code}</Text>
          </Ess>
          <Ess label="Telepon">{profile.phone ?? '—'}</Ess>
          <Ess label="Status">
            <StatusBadge status={profile.status} />
          </Ess>
        </dl>
      </div>

      <div className="card stack" style={{ gap: '0.6rem' }}>
        <Text as="h2" size={400} weight="semibold">Paket berlangganan</Text>
        {profile.subscription == null ? (
          <Text as="p" className="muted" size={300} style={{ margin: 0 }}>Belum ada langganan.</Text>
        ) : (
          <div className="spread" style={{ alignItems: 'center', gap: '0.5rem', flexWrap: 'wrap' }}>
            <div className="stack" style={{ gap: 2 }}>
              <Text as="span" weight="semibold">{profile.subscription.packageName}</Text>
              <Text as="span" className="muted" size={200}>{profile.subscription.downMbps && profile.subscription.upMbps
                ? `${profile.subscription.downMbps}/${profile.subscription.upMbps} Mbps`
                : `${profile.subscription.bandwidthMbps} Mbps`}
              {profile.subscription.fupEnabled ? ' · FUP' : ''}</Text>
            </div>
            <div className="row" style={{ gap: '0.6rem', alignItems: 'center' }}>
              {profile.subscription.monthlyFee && (
                <Text as="span" className="tnum" weight="semibold">{rupiah(profile.subscription.monthlyFee)}/bln</Text>
              )}
              <StatusBadge status={profile.subscription.status} />
            </div>
          </div>
        )}
      </div>

      {profile.subscription && <RequestPlanChange subscription={profile.subscription} />}

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
function RequestPlanChange({ subscription }: { subscription: PortalSubscription }) {
  const [options, setOptions] = useState<PortalPlanOption[] | null>(null)
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
      setReceipt(await requestPortalPlanChange(subscription.subscriptionId, planId, note))
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
        <Text as="h2" size={400} weight="semibold">Ajuan ganti paket terkirim</Text>
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Nomor ajuan <Text as="strong" weight="semibold" className="tnum">{receipt.ticketCode}</Text>. Tim kami akan menghubungimu untuk
          memastikan jadwal dan biayanya — perkembangannya bisa kamu ikuti di menu <Text as="strong" weight="semibold" >Bantuan</Text>.
        </Text>
      </div>
    )
  }

  return (
    <form className="card stack" style={{ gap: '0.6rem' }} onSubmit={onSubmit}>
      <Text as="h2" size={400} weight="semibold">Ajukan ganti paket</Text>
      <Text as="p" className="muted" size={200} style={{ margin: 0 }}>
        Paket tidak langsung berubah: ajuanmu ditinjau dulu oleh tim, termasuk biaya dan jadwalnya.
      </Text>
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
      {error && <Text as="p" className="error" size={300} style={{ margin: 0 }}>{error}</Text>}
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
      <Text as="h2" size={400} weight="semibold">Ganti password</Text>
      {done ? (
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Password diganti — kamu akan diminta masuk ulang.
        </Text>
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
          {error && <Text as="p" className="error" size={300} style={{ margin: 0 }}>{error}</Text>}
          <Button variant="primary" type="submit" disabled={busy} style={{ alignSelf: 'flex-start' }}>
            {busy ? 'Menyimpan…' : 'Simpan'}
          </Button>
        </>
      )}
    </form>
  )
}
