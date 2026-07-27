import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../api/client'
import {
  createPeer,
  createServer,
  deletePeer,
  deleteServer,
  disablePeer,
  downloadPeerOvpn,
  downloadPeerRouterOs,
  enablePeer,
  listPeers,
  listServers,
  regenerateToken,
  rotatePeerPassword,
  updateServer,
  type CreateVpnServerRequest,
  type UpdateVpnServerRequest,
  type VpnPeerView,
  type VpnProtocol,
  type VpnServerView,
} from '../api/vpn'
import { useCan } from '../auth/useCan'
import { EmptyState, useToast } from '../components/ui'
import { IconAlert, IconPlus } from '../components/icons'

/**
 * VPN back-haul: remote Mikrotik & perangkat tanpa IP publik lewat hub OpenVPN.
 *
 * Alur "matang": buat hub (aplikasi jadi CA-nya sendiri) → jalankan perintah pasang
 * SEKALI di VPS → tambah perangkat. Verifikasi user/pass & IP overlay berjalan otomatis
 * lewat callback aplikasi, jadi tak ada langkah teknis manual di VPS setelah pasang.
 * Rahasia tak pernah dibaca balik: token node & perintah pasang hanya tampil sekali
 * (saat buat/rotasi), password perangkat hanya keluar lewat unduh .ovpn/RouterOS.
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

type ServerDraft = {
  id: string | null
  name: string
  host: string
  port: string
  protocol: VpnProtocol
  tunnelCidr: string
}

const EMPTY_SERVER: ServerDraft = { id: null, name: '', host: '', port: '1194', protocol: 'UDP', tunnelCidr: '10.8.0.0/24' }

export function VpnPage() {
  const { can } = useCan()
  const canManage = can('vpn.server.manage')
  const { items: servers, loading, run, reload } = useResource(listServers)

  const [draft, setDraft] = useState<ServerDraft | null>(null)
  // Token node + perintah pasang hanya tampil sekali (setelah buat/rotasi).
  const [secret, setSecret] = useState<VpnServerView | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const selected = servers.find((s) => s.id === selectedId) ?? null

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
    if (!window.confirm(`Hapus hub “${server.name}”? Ditolak bila masih punya perangkat.`)) return
    void run(async () => {
      await deleteServer(server.id)
      if (selectedId === server.id) setSelectedId(null)
    }, 'Hub dihapus')
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <div>
        <h1 className="page-title">VPN Back-haul</h1>
        <p className="page-sub">
          Remote Mikrotik &amp; perangkat tanpa IP publik lewat hub OpenVPN. Buat hub, jalankan perintah
          pasang sekali di VPS, lalu tambah perangkat — verifikasi user &amp; IP overlay berjalan otomatis
          lewat callback aplikasi.
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
                <th>Perangkat</th>
                <th>PKI</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {servers.map((server) => (
                <tr
                  key={server.id}
                  style={server.id === selectedId ? { background: 'color-mix(in srgb, var(--accent) 8%, transparent)' } : undefined}
                >
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
                    <div className="row">
                      <button onClick={() => setSelectedId(server.id === selectedId ? null : server.id)}>
                        {server.id === selectedId ? 'Tutup' : 'Kelola perangkat'}
                      </button>
                      {canManage && (
                        <>
                          <button onClick={() => regenerate(server)}>Perintah pasang</button>
                          <button onClick={() => edit(server)}>Ubah</button>
                          <button onClick={() => remove(server)}>Hapus</button>
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

      {selected && can('vpn.peer.view') && (
        <PeersPanel key={selected.id} server={selected} onPeersChanged={reload} />
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
          <input
            value={draft.name}
            onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            placeholder="Hub Utama"
          />
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
          <select
            value={draft.protocol}
            onChange={(e) => setDraft({ ...draft, protocol: e.target.value as VpnProtocol })}
          >
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
          Subnet overlay <code>{draft.tunnelCidr}</code> tetap setelah hub dibuat (IP perangkat sudah teralokasi darinya).
        </p>
      )}

      <p className="muted" style={{ margin: 0, fontSize: '0.85rem' }}>
        Aplikasi menerbitkan CA + sertifikat server otomatis saat hub dibuat — tak perlu easy-rsa manual.
        Perintah pasang satu-baris muncul sekali setelah simpan.
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
      <code style={{ display: 'block', wordBreak: 'break-all', padding: '0.5rem', marginBottom: '0.5rem' }}>
        {command}
      </code>
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

/* ---------- Panel perangkat sebuah hub ---------- */

function PeersPanel({ server, onPeersChanged }: { server: VpnServerView; onPeersChanged: () => void }) {
  const { can } = useCan()
  const toast = useToast()
  const canManage = can('vpn.peer.manage')
  const canConfig = can('vpn.config.view')
  const fetcher = useCallback(() => listPeers(server.id), [server.id])
  const { items: peers, loading, run } = useResource(fetcher)
  const [name, setName] = useState('')

  const addPeer = () => {
    const trimmed = name.trim()
    if (!trimmed) return
    void run(async () => {
      await createPeer(server.id, { name: trimmed })
      setName('')
      onPeersChanged()
    }, 'Perangkat ditambahkan — unduh config untuk memasangnya')
  }

  const toggle = (peer: VpnPeerView) =>
    void run(
      () => (peer.status === 'ENABLED' ? disablePeer(peer.id) : enablePeer(peer.id)),
      peer.status === 'ENABLED' ? 'Perangkat dinonaktifkan' : 'Perangkat diaktifkan',
    )

  const rotate = (peer: VpnPeerView) => {
    if (!window.confirm(`Rotasi password “${peer.name}”? Unduh ulang config setelah ini agar perangkat tetap tersambung.`))
      return
    void run(() => rotatePeerPassword(peer.id), 'Password dirotasi — unduh ulang config')
  }

  const removePeer = (peer: VpnPeerView) => {
    if (!window.confirm(`Hapus perangkat “${peer.name}”?`)) return
    void run(async () => {
      await deletePeer(peer.id)
      onPeersChanged()
    }, 'Perangkat dihapus')
  }

  return (
    <div className="card stack">
      <div className="spread">
        <div>
          <strong>Perangkat — {server.name}</strong>
          <div className="muted" style={{ fontSize: '0.82rem' }}>
            {server.host}:{server.port} · {server.protocol} · subnet {server.tunnelCidr}
          </div>
        </div>
      </div>

      {canManage && (
        <div className="row">
          <input
            style={{ flex: 1 }}
            value={name}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && addPeer()}
            placeholder="Nama perangkat (mis. Mikrotik Bekasi)"
          />
          <button className="primary" onClick={addPeer}>
            <IconPlus size={15} /> Tambah perangkat
          </button>
        </div>
      )}

      {loading ? (
        <p className="muted" style={{ margin: 0 }}>
          Memuat…
        </p>
      ) : peers.length === 0 ? (
        <EmptyState title="Belum ada perangkat" hint="Tambah perangkat, lalu unduh .ovpn / skrip RouterOS-nya." />
      ) : (
        <table>
          <thead>
            <tr>
              <th>Nama</th>
              <th>Username</th>
              <th>IP overlay</th>
              <th>Status</th>
              <th>Handshake terakhir</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {peers.map((peer) => (
              <tr key={peer.id}>
                <td>{peer.name}</td>
                <td className="muted">{peer.username}</td>
                <td className="tnum">{peer.overlayIp}</td>
                <td>
                  <span
                    className="badge"
                    style={{ color: peer.status === 'ENABLED' ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
                  >
                    {peer.status === 'ENABLED' ? 'aktif' : 'nonaktif'}
                  </span>
                </td>
                <td className="muted">{fmtWhen(peer.lastHandshakeAt)}</td>
                <td>
                  <div className="row">
                    {canConfig && (
                      <>
                        <button
                          onClick={() =>
                            void saveBlob(() => downloadPeerRouterOs(peer.id), `${peer.username}.rsc`, toast.error)
                          }
                        >
                          RouterOS
                        </button>
                        <button
                          onClick={() =>
                            void saveBlob(() => downloadPeerOvpn(peer.id), `${peer.username}.ovpn`, toast.error)
                          }
                        >
                          .ovpn
                        </button>
                      </>
                    )}
                    {canManage && (
                      <>
                        <button onClick={() => toggle(peer)}>
                          {peer.status === 'ENABLED' ? 'Nonaktifkan' : 'Aktifkan'}
                        </button>
                        <button onClick={() => rotate(peer)}>Rotasi password</button>
                        <button onClick={() => removePeer(peer)}>Hapus</button>
                      </>
                    )}
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}
