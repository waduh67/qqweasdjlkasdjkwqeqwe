import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { Pencil, RefreshCw, Trash2 } from 'lucide-react'
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
import { DataTable, type Column, type RowAction } from '@/components/organisms'
import { Badge, Button, EmptyState, SelectField, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { SearchInput } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconAlert, IconPlus, IconRoute } from '@/components/atoms/icons'

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

// Bawaan TCP, bukan UDP: klien OpenVPN RouterOS v6 tak mengenal UDP, dan perangkat v6
// (hAP lite/RB941 smips) tak akan pernah bisa di-upgrade — hub UDP menutup pintu buat mereka.
const EMPTY_SERVER: ServerDraft = { id: null, name: '', host: '', port: '1194', protocol: 'TCP', tunnelCidr: '10.8.0.0/24' }

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
          {/* Protokol saja tak berarti apa-apa bagi operator; yang dia perlu tahu adalah
              perangkat mana yang bisa masuk lewat hub ini. */}
          <Text as="span" className="muted" size={200}>{s.protocol === 'TCP' ? 'TCP · RouterOS v6 & v7' : 'UDP · RouterOS v7 saja'}</Text>
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
          <Text as="span" className="muted" size={200}>server {s.serverAddress}</Text>
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

  // Aksi per-baris di menu `…` ala Azure DataGrid (seragam dengan Pelanggan), bukan tombol inline.
  const rowActions = (s: VpnServerView): RowAction[] => [
    { key: 'regenerate', label: 'Perintah pasang', icon: <RefreshCw size={16} />, onClick: () => regenerate(s) },
    { key: 'edit', label: 'Ubah', icon: <Pencil size={16} />, onClick: () => edit(s) },
    { key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => remove(s) },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Server VPN"
        subtitle="Hub OpenVPN platform — jalan di VPS kita dengan IP publik kita. Buat hub, jalankan perintah pasang sekali di VPS, lalu hub siap dipakai. Tenant tinggal generate akun VPN yang di-auto-assign ke hub yang tersedia."
      />

      <div className="spread">
        <span className="muted">{servers.length} hub</span>
        {canManage && (
          <Button variant="primary" onClick={() => setDraft({ ...EMPTY_SERVER })}>
            <IconPlus size={15} /> Tambah hub
          </Button>
        )}
      </div>

      {secret && <InstallSecretCard server={secret} onDismiss={() => setSecret(null)} />}

      {draft && <ServerForm draft={draft} setDraft={setDraft} onSave={save} onCancel={() => setDraft(null)} />}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama, titik dial, atau subnet…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value)}>
          <option value="">Semua status</option>
          {statuses.map((s) => (
            <option key={s} value={s}>{statusLabel(s)}</option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(s) => s.id}
        loading={loading}
        initialSort={{ key: 'name', dir: 'asc' }}
        rowActions={canManage ? rowActions : undefined}
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
        <TextField
          label="Nama hub"
          value={draft.name}
          onChange={(_, data) => setDraft({ ...draft, name: data.value })}
          placeholder="Hub Utama"
          style={{ flex: 2 }}
        />
        <TextField
          label="Host / IP publik VPS"
          value={draft.host}
          onChange={(_, data) => setDraft({ ...draft, host: data.value })}
          placeholder="vpn.isp-anda.com"
          style={{ flex: 2 }}
        />
        <TextField
          label="Port"
          value={draft.port}
          onChange={(_, data) => setDraft({ ...draft, port: data.value })}
          placeholder="1194"
          style={{ flex: 1 }}
        />
        <SelectField
          label="Protokol"
          value={draft.protocol}
          onChange={(_, data) => setDraft({ ...draft, protocol: data.value as VpnProtocol })}
          style={{ flex: 1 }}
        >
          <option value="TCP">TCP — semua RouterOS</option>
          <option value="UDP">UDP — v7 saja</option>
        </SelectField>
      </div>

      {/* Pilihan protokol tak bisa dibalik tanpa mengganggu perangkat: yang sudah men-dial harus
          menempel ulang confignya. Sebutkan konsekuensinya di tempat pilihannya diambil. */}
      <Text as="p" className="muted" size={300} style={{ margin: 0 }}>{draft.protocol === 'TCP' ? (
        <>
          Perangkat <strong>RouterOS v6 maupun v7</strong> bisa masuk. Buka port{' '}
          <code>{draft.port || '1194'}/tcp</code> di firewall/NSG VPS.
        </>
      ) : (
        <>
          Hanya <strong>RouterOS v7</strong> yang bisa masuk — klien OpenVPN v6 tak mengenal UDP,
          dan perangkat lama (hAP lite, RB941) tak bisa di-upgrade ke v7. Pilih UDP hanya bila
          seluruh armada dipastikan v7.
        </>
      )}</Text>

      {draft.id === null ? (
        <TextField
          label="Subnet overlay (CIDR)"
          value={draft.tunnelCidr}
          onChange={(_, data) => setDraft({ ...draft, tunnelCidr: data.value })}
          placeholder="10.8.0.0/24"
        />
      ) : (
        <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
          Subnet overlay <code>{draft.tunnelCidr}</code> tetap setelah hub dibuat (IP akun sudah teralokasi darinya).
        </Text>
      )}

      <Text as="p" className="muted" size={300} style={{ margin: 0 }}>
        Aplikasi menerbitkan CA + sertifikat server otomatis saat hub dibuat — tak perlu easy-rsa manual. Perintah pasang
        satu-baris muncul sekali setelah simpan.
      </Text>
      <div className="row">
        <Button variant="primary" onClick={onSave}>
          Simpan
        </Button>
        <Button onClick={onCancel}>Batal</Button>
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
      <p className="muted" style={{ margin: '0 0 0.5rem',  }}>
        Jalankan sekali di VPS sebagai root. Installer memasang OpenVPN + PKI aplikasi dan menyambungkan callback
        verifikasi — tak ada langkah teknis manual setelahnya. Perintah &amp; token ini hanya ditampilkan sekali.
      </p>
      <code style={{ display: 'block', wordBreak: 'break-all', padding: '0.5rem', marginBottom: '0.5rem' }}>{command}</code>
      {needsBaseUrl && (
        <p className="muted" style={{ margin: '0 0 0.5rem',  }}>
          Ganti <code>&lt;URL-APLIKASI-ANDA&gt;</code> dengan URL publik aplikasi ini, atau set{' '}
          <code>FTTH_VPN_PUBLIC_BASE_URL</code> di server agar terisi otomatis.
        </p>
      )}
      <p className="muted" style={{ margin: '0 0 0.6rem',  }}>
        Token node: <code>{token}</code>
      </p>
      <div className="row">
        <Button
          size="small"
          onClick={() => void navigator.clipboard?.writeText(command).then(() => toast.success('Perintah pasang disalin'))}
        >
          Salin perintah
        </Button>
        <Button
          variant="subtle"
          size="small"
          onClick={() => void navigator.clipboard?.writeText(token).then(() => toast.success('Token node disalin'))}
        >
          Salin token
        </Button>
        <Button variant="subtle" size="small" onClick={onDismiss}>
          Selesai
        </Button>
      </div>
    </div>
  )
}
