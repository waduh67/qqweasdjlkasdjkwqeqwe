import { useCallback, useEffect, useMemo, useState } from 'react'
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
import { DataTable, type Column } from '../components/DataTable'
import { Badge, EmptyState, SearchInput, StatusBadge, Toolbar, useConfirm, useToast } from '../components/ui'
import { PageHeader } from '../components/PageHeader'
import { IconAlert, IconPlus, IconRoute } from '../components/icons'

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

/** Label status hub dalam bahasa Indonesia; nilai tak dikenal ditampilkan apa adanya. */
const STATUS_LABELS: Record<string, string> = { ACTIVE: 'Aktif', INACTIVE: 'Nonaktif' }
const statusLabel = (status: string) => STATUS_LABELS[status] ?? status

export function VpnServersPage() {
  const { can } = useCan()
  const canManage = can('vpn.server.manage')
  const confirm = useConfirm()
  const { items: servers, loading, run } = useResource(listServers)

  const [draft, setDraft] = useState<ServerDraft | null>(null)
  // Token node + perintah pasang hanya tampil sekali (setelah buat/rotasi).
  const [secret, setSecret] = useState<VpnServerView | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('')

  // Status hub yang tersedia untuk dropdown filter (diturunkan dari data).
  const statuses = useMemo(
    () => Array.from(new Set(servers.map((s) => s.status))).sort(),
    [servers],
  )

  // Saring di sisi klien: cari nama/titik dial/subnet, plus filter status.
  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return servers.filter((s) => {
      if (statusFilter && s.status !== statusFilter) return false
      if (!q) return true
      return (
        s.name.toLowerCase().includes(q) ||
        s.host.toLowerCase().includes(q) ||
        `${s.host}:${s.port}`.includes(q) ||
        s.protocol.toLowerCase().includes(q) ||
        s.tunnelCidr.toLowerCase().includes(q) ||
        s.serverAddress.toLowerCase().includes(q)
      )
    })
  }, [servers, query, statusFilter])

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
    void (async () => {
      if (
        !(await confirm({
          title: 'Rotasi token pasang',
          message: `Rotasi token pasang untuk “${server.name}”? Perintah/token lama langsung tak berlaku dan VPS perlu dipasang ulang dengan yang baru.`,
          confirmLabel: 'Rotasi',
        }))
      )
        return
      void run(async () => {
        setSecret(await regenerateToken(server.id))
      }, 'Token pasang dirotasi')
    })()
  }

  const remove = (server: VpnServerView) => {
    void (async () => {
      if (
        !(await confirm({
          title: 'Hapus hub',
          message: `Hapus hub “${server.name}”? Ditolak bila masih menampung akun.`,
          confirmLabel: 'Hapus',
          danger: true,
        }))
      )
        return
      void run(() => deleteServer(server.id), 'Hub dihapus')
    })()
  }

  const columns: Column<VpnServerView>[] = [
    { key: 'name', header: 'Hub', sortValue: (s) => s.name, cell: (s) => <strong>{s.name}</strong> },
    {
      key: 'status',
      header: 'Status',
      sortValue: (s) => s.status,
      cell: (s) => <StatusBadge status={s.status} label={statusLabel(s.status)} />,
    },
    {
      key: 'endpoint',
      header: 'Titik dial',
      sortValue: (s) => s.host,
      cell: (s) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <span>
            {s.host}:{s.port}
          </span>
          <span className="muted" style={{ fontSize: '0.8rem' }}>{s.protocol}</span>
        </div>
      ),
    },
    {
      key: 'overlay',
      header: 'Subnet overlay',
      sortValue: (s) => s.tunnelCidr,
      cell: (s) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <span>{s.tunnelCidr}</span>
          <span className="muted" style={{ fontSize: '0.8rem' }}>server {s.serverAddress}</span>
        </div>
      ),
    },
    { key: 'peers', header: 'Akun', align: 'right', sortValue: (s) => s.peerCount, cell: (s) => s.peerCount },
    {
      key: 'pki',
      header: 'PKI',
      sortValue: (s) => (s.pkiReady ? 1 : 0),
      cell: (s) => <Badge tone={s.pkiReady ? 'good' : 'warning'}>{s.pkiReady ? 'siap' : 'belum'}</Badge>,
    },
  ]
  if (canManage) {
    columns.push({
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (s) => (
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <button onClick={() => regenerate(s)}>Perintah pasang</button>
          <button onClick={() => edit(s)}>Ubah</button>
          <button onClick={() => remove(s)}>Hapus</button>
        </div>
      ),
    })
  }

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Server VPN"
        subtitle="Hub OpenVPN platform — jalan di VPS kita dengan IP publik kita. Buat hub, jalankan perintah pasang sekali di VPS, lalu hub siap dipakai. Tenant tinggal generate akun VPN yang di-auto-assign ke hub yang tersedia."
      />

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

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama, titik dial, atau subnet…" />
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
          <option value="">Semua status</option>
          {statuses.map((s) => (
            <option key={s} value={s}>{statusLabel(s)}</option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(s) => s.id}
        loading={loading}
        initialSort={{ key: 'name', dir: 'asc' }}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada hub yang cocok' : 'Belum ada hub'}
            hint={
              query || statusFilter
                ? 'Coba ubah kata kunci atau filter.'
                : 'Buat hub, lalu jalankan perintah pasang sekali di VPS.'
            }
            icon={<IconRoute size={32} />}
          />
        }
      />
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
