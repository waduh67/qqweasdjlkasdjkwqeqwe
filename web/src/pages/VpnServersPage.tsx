import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import {
  createServer,
  deleteServer,
  listServers,
  regenerateToken,
  updateServer,
  type CreateVpnServerRequest,
  type UpdateVpnServerRequest,
  type VpnProtocol,
  type VpnServerView,
} from '../api/vpn'
import { useCan } from '../auth/useCan'
import { EmptyState, useToast } from '../components/ui'
import { IconAlert, IconPlus } from '../components/icons'

/**
 * Server VPN (PLATFORM). Halaman admin platform untuk mengelola hub OpenVPN yang jalan di VPS
 * kita (IP publik kita). Alur "matang": buat hub (aplikasi jadi CA-nya sendiri) → jalankan
 * perintah pasang SEKALI di VPS → hub siap dipakai. Tenant lalu tinggal generate akun (halaman
 * "Akun VPN") yang di-auto-assign ke hub yang tersedia. Rahasia sekali-tampil: token node &
 * perintah pasang hanya muncul saat buat/rotasi.
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

type ServerDraft = {
  id: string | null
  name: string
  host: string
  port: string
  protocol: VpnProtocol
  tunnelCidr: string
}

const EMPTY_SERVER: ServerDraft = { id: null, name: '', host: '', port: '1194', protocol: 'UDP', tunnelCidr: '10.8.0.0/24' }

export function VpnServersPage() {
  const { can } = useCan()
  const canManage = can('vpn.server.manage')
  const { items: servers, loading, run } = useResource(listServers)

  const [draft, setDraft] = useState<ServerDraft | null>(null)
  // Token node + perintah pasang hanya tampil sekali (setelah buat/rotasi).
  const [secret, setSecret] = useState<VpnServerView | null>(null)

  const edit = (server: VpnServerView) =>
    setDraft({
      id: server.id,
      name: server.name,
      host: server.host,
      port: String(server.port),
      protocol: server.protocol,
      tunnelCidr: server.tunnelCidr,
    })

  const save = () => {
    if (!draft) return
    void run(async () => {
      if (draft.id) {
        const body: UpdateVpnServerRequest = {
          name: draft.name,
          host: draft.host,
          port: Number(draft.port) || 1194,
          protocol: draft.protocol,
        }
        await updateServer(draft.id, body)
      } else {
        const body: CreateVpnServerRequest = {
          name: draft.name,
          host: draft.host,
          port: draft.port ? Number(draft.port) : null,
          protocol: draft.protocol,
          tunnelCidr: draft.tunnelCidr || null,
        }
        setSecret(await createServer(body))
      }
      setDraft(null)
    }, draft.id ? 'Hub diperbarui' : 'Hub dibuat — jalankan perintah pasang di VPS')
  }

  const regenerate = (server: VpnServerView) => {
    if (
      !window.confirm(
        `Rotasi token pasang untuk “${server.name}”? Perintah/token lama langsung tak berlaku dan VPS perlu dipasang ulang dengan yang baru.`,
      )
    )
      return
    void run(async () => {
      setSecret(await regenerateToken(server.id))
    }, 'Token pasang dirotasi')
  }

  const remove = (server: VpnServerView) => {
    if (!window.confirm(`Hapus hub “${server.name}”? Ditolak bila masih menampung akun.`)) return
    void run(() => deleteServer(server.id), 'Hub dihapus')
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">Server VPN</h1>
        <p className="page-sub">
          Hub OpenVPN platform — jalan di VPS kita dengan IP publik kita. Buat hub, jalankan perintah pasang sekali di
          VPS, lalu hub siap dipakai. Tenant tinggal generate akun VPN yang di-auto-assign ke hub yang tersedia.
        </p>
      </div>

      <div className="spread">
        <span className="muted">{servers.length} hub</span>
        {canManage && (
          <button className="primary" onClick={() => setDraft({ ...EMPTY_SERVER })}>
            <IconPlus size={15} /> Tambah hub
          </button>
        )}
      </div>

      {secret && <InstallSecretCard server={secret} onDismiss={() => setSecret(null)} />}

      {draft && <ServerForm draft={draft} setDraft={setDraft} onSave={save} onCancel={() => setDraft(null)} />}

      {loading ? (
        <div className="card">
          <p className="muted" style={{ margin: 0 }}>
            Memuat…
          </p>
        </div>
      ) : servers.length === 0 ? (
        <div className="card">
          <EmptyState title="Belum ada hub" hint="Buat hub, lalu jalankan perintah pasang sekali di VPS." />
        </div>
      ) : (
        <div className="card">
          <table>
            <thead>
              <tr>
                <th>Hub</th>
                <th>Titik dial</th>
                <th>Subnet overlay</th>
                <th>Akun</th>
                <th>PKI</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {servers.map((server) => (
                <tr key={server.id}>
                  <td>
                    {server.name}
                    <br />
                    <span
                      className="badge"
                      style={{ color: server.status === 'ACTIVE' ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
                    >
                      {server.status === 'ACTIVE' ? 'aktif' : 'nonaktif'}
                    </span>
                  </td>
                  <td className="muted">
                    {server.host}:{server.port}
                    <br />
                    <span style={{ fontSize: '0.8rem' }}>{server.protocol}</span>
                  </td>
                  <td className="muted">
                    {server.tunnelCidr}
                    <br />
                    <span style={{ fontSize: '0.8rem' }}>server {server.serverAddress}</span>
                  </td>
                  <td className="tnum">{server.peerCount}</td>
                  <td>
                    <span
                      className="badge"
                      style={{ color: server.pkiReady ? 'var(--good-ink)' : 'var(--warning-ink)', fontWeight: 600 }}
                    >
                      {server.pkiReady ? 'siap' : 'belum'}
                    </span>
                  </td>
                  <td>
                    {canManage && (
                      <div className="row">
                        <button onClick={() => regenerate(server)}>Perintah pasang</button>
                        <button onClick={() => edit(server)}>Ubah</button>
                        <button onClick={() => remove(server)}>Hapus</button>
                      </div>
                    )}
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

/* ---------- Form hub ---------- */

function ServerForm({
  draft,
  setDraft,
  onSave,
  onCancel,
}: {
  draft: ServerDraft
  setDraft: (d: ServerDraft) => void
  onSave: () => void
  onCancel: () => void
}) {
  return (
    <div className="card stack">
      <div className="row">
        <label style={{ flex: 2 }}>
          <span>Nama hub</span>
          <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="Hub Utama" />
        </label>
        <label style={{ flex: 2 }}>
          <span>Host / IP publik VPS</span>
          <input
            value={draft.host}
            onChange={(e) => setDraft({ ...draft, host: e.target.value })}
            placeholder="vpn.isp-anda.com"
          />
        </label>
        <label style={{ flex: 1 }}>
          <span>Port</span>
          <input value={draft.port} onChange={(e) => setDraft({ ...draft, port: e.target.value })} placeholder="1194" />
        </label>
        <label style={{ flex: 1 }}>
          <span>Protokol</span>
          <select value={draft.protocol} onChange={(e) => setDraft({ ...draft, protocol: e.target.value as VpnProtocol })}>
            <option value="UDP">UDP</option>
            <option value="TCP">TCP</option>
          </select>
        </label>
      </div>

      {draft.id === null ? (
        <label>
          <span>Subnet overlay (CIDR)</span>
          <input
            value={draft.tunnelCidr}
            onChange={(e) => setDraft({ ...draft, tunnelCidr: e.target.value })}
            placeholder="10.8.0.0/24"
          />
        </label>
      ) : (
        <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
          Subnet overlay <code>{draft.tunnelCidr}</code> tetap setelah hub dibuat (IP akun sudah teralokasi darinya).
        </p>
      )}

      <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
        Aplikasi menerbitkan CA + sertifikat server otomatis saat hub dibuat — tak perlu easy-rsa manual. Perintah pasang
        satu-baris muncul sekali setelah simpan.
      </p>
      <div className="row">
        <button className="primary" onClick={onSave}>
          Simpan
        </button>
        <button onClick={onCancel}>Batal</button>
      </div>
    </div>
  )
}

/* ---------- Kartu rahasia sekali-tampil ---------- */

function InstallSecretCard({ server, onDismiss }: { server: VpnServerView; onDismiss: () => void }) {
  const toast = useToast()
  const command = server.installCommand ?? ''
  const token = server.nodeToken ?? ''
  const needsBaseUrl = command.includes('<URL-APLIKASI-ANDA>')

  return (
    <div
      className="card"
      style={{ borderColor: 'var(--warning)', background: 'color-mix(in srgb, var(--warning) 8%, var(--surface))' }}
    >
      <div className="row" style={{ gap: '0.5rem', marginBottom: '0.5rem' }}>
        <IconAlert size={17} style={{ color: 'var(--warning-ink)' }} />
        <strong>Perintah pasang untuk hub “{server.name}”</strong>
      </div>
      <p className="muted" style={{ margin: '0 0 0.5rem', fontSize: '0.83rem' }}>
        Jalankan sekali di VPS sebagai root. Installer memasang OpenVPN + PKI aplikasi dan menyambungkan callback
        verifikasi — tak ada langkah teknis manual setelahnya. Perintah &amp; token ini hanya ditampilkan sekali.
      </p>
      <code style={{ display: 'block', wordBreak: 'break-all', padding: '0.5rem', marginBottom: '0.5rem' }}>{command}</code>
      {needsBaseUrl && (
        <p className="muted" style={{ margin: '0 0 0.5rem', fontSize: '0.8rem' }}>
          Ganti <code>&lt;URL-APLIKASI-ANDA&gt;</code> dengan URL publik aplikasi ini, atau set{' '}
          <code>FTTH_VPN_PUBLIC_BASE_URL</code> di server agar terisi otomatis.
        </p>
      )}
      <p className="muted" style={{ margin: '0 0 0.6rem', fontSize: '0.8rem' }}>
        Token node: <code>{token}</code>
      </p>
      <div className="row">
        <button
          className="small"
          onClick={() => void navigator.clipboard?.writeText(command).then(() => toast.success('Perintah pasang disalin'))}
        >
          Salin perintah
        </button>
        <button
          className="small ghost"
          onClick={() => void navigator.clipboard?.writeText(token).then(() => toast.success('Token node disalin'))}
        >
          Salin token
        </button>
        <button className="ghost small" onClick={onDismiss}>
          Selesai
        </button>
      </div>
    </div>
  )
}
