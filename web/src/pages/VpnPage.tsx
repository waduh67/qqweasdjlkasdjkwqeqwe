import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import {
  deleteAccount,
  disableAccount,
  downloadAccountOvpn,
  downloadAccountRouterOs,
  enableAccount,
  generateAccount,
  listAccounts,
  rotateAccountPassword,
  type VpnAccountView,
} from '../api/vpn'
import { useCan } from '../auth/useCan'
import { EmptyState, useToast } from '../components/ui'
import { IconAlert, IconPlus } from '../components/icons'

/**
 * Akun VPN (tenant). Alur unggulan satu klik: tekan Generate → sistem meng-AUTO-ASSIGN akun
 * ke server VPN platform yang tersedia dan menampilkan kredensial siap tempel ke Mikrotik
 * (host:port, protokol, tipe keamanan, username, password). Tenant tak pernah memilih/melihat
 * server — itu urusan admin platform. Password hanya tampil sekali (saat generate/rotasi) atau
 * lewat unduh .ovpn/RouterOS.
 */

/** Hook pemuat daftar bersama untuk endpoint yang mengembalikan array polos. */
function useResource<T>(fetcher: () => Promise<T[]>) {
  const toast = useToast()
  const [items, setItems] = useState<T[]>([])
  const [loading, setLoading] = useState(true)

  const reload = useCallback(async () => {
    try {
      setItems(await fetcher())
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal memuat data')
    } finally {
      setLoading(false)
    }
  }, [fetcher, toast])

  useEffect(() => {
    void reload()
  }, [reload])

  const run = async (action: () => Promise<unknown>, okMessage?: string) => {
    try {
      await action()
      await reload()
      if (okMessage) toast.success(okMessage)
      return true
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Operasi gagal')
      return false
    }
  }

  return { items, loading, reload, run }
}

/** Unduh Blob teks sebagai berkas di browser. Konten butuh header Bearer → diambil dulu, lalu object URL. */
async function saveBlob(fetchBlob: () => Promise<Blob>, filename: string, onError: (msg: string) => void) {
  try {
    const blob = await fetchBlob()
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    document.body.appendChild(a)
    a.click()
    a.remove()
    URL.revokeObjectURL(url)
  } catch (err) {
    onError(err instanceof ApiError ? err.message : 'Gagal mengunduh berkas')
  }
}

function fmtWhen(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('id-ID', { dateStyle: 'short', timeStyle: 'short' })
}

export function VpnPage() {
  const { can } = useCan()
  const canManage = can('vpn.peer.manage')
  const canConfig = can('vpn.config.view')
  const toast = useToast()
  const { items: accounts, loading, run } = useResource(listAccounts)

  const [label, setLabel] = useState('')
  const [busy, setBusy] = useState(false)
  // Kredensial (dengan password) hanya tampil sekali — setelah generate/rotasi.
  const [fresh, setFresh] = useState<VpnAccountView | null>(null)

  const generate = () => {
    setBusy(true)
    void run(async () => {
      const account = await generateAccount({ label: label.trim() || null })
      setFresh(account)
      setLabel('')
    }, 'Akun VPN dibuat — salin kredensial di bawah').finally(() => setBusy(false))
  }

  const toggle = (a: VpnAccountView) =>
    void run(
      () => (a.status === 'ENABLED' ? disableAccount(a.id) : enableAccount(a.id)),
      a.status === 'ENABLED' ? 'Akun dinonaktifkan' : 'Akun diaktifkan',
    )

  const rotate = (a: VpnAccountView) => {
    if (!window.confirm(`Rotasi password “${a.label}”? Password lama langsung tak berlaku — perbarui di Mikrotik.`))
      return
    void run(async () => {
      setFresh(await rotateAccountPassword(a.id))
    }, 'Password dirotasi — salin yang baru di bawah')
  }

  const remove = (a: VpnAccountView) => {
    if (!window.confirm(`Hapus akun “${a.label}”? Koneksi Mikrotik dengan akun ini akan putus.`)) return
    void run(() => deleteAccount(a.id), 'Akun dihapus')
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">Akun VPN</h1>
        <p className="page-sub">
          Butuh remote Mikrotik tanpa IP publik? Cukup <strong>Generate akun</strong> — sistem memilih server VPN
          otomatis dan memberi Anda host, port, tipe keamanan, username &amp; password siap tempel di Mikrotik.
        </p>
      </div>

      {canManage && (
        <div className="card stack" style={{ gap: '0.75rem' }}>
          <div className="row" style={{ alignItems: 'flex-end' }}>
            <label style={{ flex: 1 }}>
              <span>Label (opsional)</span>
              <input
                value={label}
                onChange={(e) => setLabel(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && !busy && generate()}
                placeholder="mis. Mikrotik Bekasi"
              />
            </label>
            <button className="primary" onClick={generate} disabled={busy}>
              <IconPlus size={15} /> {busy ? 'Membuat…' : 'Generate akun'}
            </button>
          </div>
          <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
            Server dipilih otomatis. Password hanya tampil sekali setelah dibuat — salin atau unduh config-nya.
          </p>
        </div>
      )}

      {fresh && <CredentialCard account={fresh} onDismiss={() => setFresh(null)} />}

      {loading ? (
        <div className="card">
          <p className="muted" style={{ margin: 0 }}>
            Memuat…
          </p>
        </div>
      ) : accounts.length === 0 ? (
        <div className="card">
          <EmptyState
            title="Belum ada akun VPN"
            hint={canManage ? 'Tekan “Generate akun” untuk membuat yang pertama.' : 'Belum ada akun untuk ditampilkan.'}
          />
        </div>
      ) : (
        <div className="card">
          <table>
            <thead>
              <tr>
                <th>Label</th>
                <th>Server</th>
                <th>Titik dial</th>
                <th>Username</th>
                <th>IP overlay</th>
                <th>Winbox (remote)</th>
                <th>Status</th>
                <th>Handshake terakhir</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {accounts.map((a) => (
                <tr key={a.id}>
                  <td>{a.label}</td>
                  <td className="muted">{a.serverName}</td>
                  <td className="muted">
                    {a.host}:{a.port}
                    <br />
                    <span style={{ fontSize: '0.8rem' }}>{a.protocol}</span>
                  </td>
                  <td className="muted">{a.username}</td>
                  <td className="tnum">{a.overlayIp}</td>
                  <td className="tnum">{a.winboxAddress}</td>
                  <td>
                    <span
                      className="badge"
                      style={{ color: a.status === 'ENABLED' ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
                    >
                      {a.status === 'ENABLED' ? 'aktif' : 'nonaktif'}
                    </span>
                  </td>
                  <td className="muted">{fmtWhen(a.lastHandshakeAt)}</td>
                  <td>
                    <div className="row">
                      {canConfig && (
                        <>
                          <button
                            onClick={() =>
                              void saveBlob(() => downloadAccountRouterOs(a.id), `${a.username}.rsc`, toast.error)
                            }
                          >
                            RouterOS
                          </button>
                          <button
                            onClick={() => void saveBlob(() => downloadAccountOvpn(a.id), `${a.username}.ovpn`, toast.error)}
                          >
                            .ovpn
                          </button>
                        </>
                      )}
                      {canManage && (
                        <>
                          <button onClick={() => toggle(a)}>{a.status === 'ENABLED' ? 'Nonaktifkan' : 'Aktifkan'}</button>
                          <button onClick={() => rotate(a)}>Rotasi password</button>
                          <button onClick={() => remove(a)}>Hapus</button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}

/* ---------- Kartu kredensial sekali-tampil ---------- */

function CredentialCard({ account, onDismiss }: { account: VpnAccountView; onDismiss: () => void }) {
  const toast = useToast()
  const copy = (value: string, what: string) =>
    void navigator.clipboard?.writeText(value).then(() => toast.success(`${what} disalin`))

  const rows: Array<{ label: string; value: string; copy?: boolean }> = [
    { label: 'Server', value: account.serverName },
    { label: 'Host / IP', value: account.host, copy: true },
    { label: 'Port', value: String(account.port), copy: true },
    { label: 'Tipe keamanan', value: account.securityType },
    { label: 'Username', value: account.username, copy: true },
    { label: 'Password', value: account.password ?? '—', copy: !!account.password },
    { label: 'IP overlay', value: account.overlayIp },
    { label: 'Winbox (remote)', value: account.winboxAddress, copy: true },
  ]

  return (
    <div
      className="card"
      style={{ borderColor: 'var(--warning)', background: 'color-mix(in srgb, var(--warning) 8%, var(--surface))' }}
    >
      <div className="row" style={{ gap: '0.5rem', marginBottom: '0.5rem' }}>
        <IconAlert size={17} style={{ color: 'var(--warning-ink)' }} />
        <strong>Kredensial akun “{account.label}”</strong>
      </div>
      <p className="muted" style={{ margin: '0 0 0.6rem', fontSize: '0.83rem' }}>
        Tempel data ini ke OVPN client Mikrotik Anda. <strong>Password hanya ditampilkan sekali</strong> — bila
        terlewat, rotasi ulang atau unduh config.
      </p>
      <table style={{ marginBottom: '0.6rem' }}>
        <tbody>
          {rows.map((r) => (
            <tr key={r.label}>
              <td className="muted" style={{ width: '9rem' }}>
                {r.label}
              </td>
              <td>
                <code style={{ wordBreak: 'break-all' }}>{r.value}</code>
              </td>
              <td style={{ width: '4rem' }}>
                {r.copy && (
                  <button className="small ghost" onClick={() => copy(r.value, r.label)}>
                    Salin
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {account.routerOsCommand && (
        <div className="stack" style={{ gap: '0.35rem', marginBottom: '0.7rem' }}>
          <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              Atau tinggal tempel di terminal RouterOS <strong>v7</strong> (langsung jadi):
            </span>
            <button className="small ghost" onClick={() => copy(account.routerOsCommand!, 'Perintah RouterOS')}>
              Salin perintah
            </button>
          </div>
          <pre
            style={{
              margin: 0,
              padding: '0.6rem 0.7rem',
              background: 'var(--surface)',
              border: '1px solid var(--border)',
              borderRadius: '6px',
              overflowX: 'auto',
              fontSize: '0.76rem',
              lineHeight: 1.5,
            }}
          >
            <code>{account.routerOsCommand}</code>
          </pre>
          <span className="muted" style={{ fontSize: '0.76rem' }}>
            Perlu RouterOS v7 — v6 tak didukung (hub pakai UDP + AES-256-GCM).
          </span>
        </div>
      )}
      <div className="row">
        <button
          className="small"
          onClick={() => void saveBlob(() => downloadAccountRouterOs(account.id), `${account.username}.rsc`, toast.error)}
        >
          Unduh RouterOS
        </button>
        <button
          className="small ghost"
          onClick={() => void saveBlob(() => downloadAccountOvpn(account.id), `${account.username}.ovpn`, toast.error)}
        >
          Unduh .ovpn
        </button>
        <button className="ghost small" onClick={onDismiss}>
          Selesai
        </button>
      </div>
    </div>
  )
}
