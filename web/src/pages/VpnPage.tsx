import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
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
import { DataTable, type Column } from '../components/DataTable'
import { EmptyState, SearchInput, StatusBadge, Toolbar, useConfirm, useToast } from '../components/ui'
import { PageHeader } from '../components/PageHeader'
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

const STATUS_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: 'Semua status' },
  { value: 'ENABLED', label: 'Aktif' },
  { value: 'DISABLED', label: 'Nonaktif' },
]

export function VpnPage() {
  const { can } = useCan()
  const canManage = can('vpn.peer.manage')
  const canConfig = can('vpn.config.view')
  const toast = useToast()
  const confirm = useConfirm()
  const { items: accounts, loading, run } = useResource(listAccounts)

  const [label, setLabel] = useState('')
  const [busy, setBusy] = useState(false)
  // Kredensial (dengan password) hanya tampil sekali — setelah generate/rotasi.
  const [fresh, setFresh] = useState<VpnAccountView | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('')

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
    void (async () => {
      if (
        !(await confirm({
          title: 'Rotasi password',
          message: `Rotasi password “${a.label}”? Password lama langsung tak berlaku — perbarui di Mikrotik.`,
          confirmLabel: 'Rotasi',
        }))
      )
        return
      void run(async () => {
        setFresh(await rotateAccountPassword(a.id))
      }, 'Password dirotasi — salin yang baru di bawah')
    })()
  }

  const remove = (a: VpnAccountView) => {
    void (async () => {
      if (
        !(await confirm({
          title: 'Hapus akun',
          message: `Hapus akun “${a.label}”? Koneksi Mikrotik dengan akun ini akan putus.`,
          confirmLabel: 'Hapus',
          danger: true,
        }))
      )
        return
      void run(() => deleteAccount(a.id), 'Akun dihapus')
    })()
  }

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return accounts.filter((a) => {
      if (statusFilter && a.status !== statusFilter) return false
      if (!q) return true
      return [a.label, a.serverName, a.host, a.username, a.overlayIp, a.winboxAddress].some((v) =>
        v?.toLowerCase().includes(q),
      )
    })
  }, [accounts, query, statusFilter])

  const columns: Column<VpnAccountView>[] = [
    { key: 'label', header: 'Label', sortValue: (a) => a.label, cell: (a) => <strong>{a.label}</strong> },
    {
      key: 'server',
      header: 'Server',
      sortValue: (a) => a.serverName,
      cell: (a) => <span className="muted">{a.serverName}</span>,
    },
    {
      key: 'dial',
      header: 'Titik dial',
      sortValue: (a) => a.host,
      cell: (a) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <span className="tnum">
            {a.host}:{a.port}
          </span>
          <span className="muted" style={{ fontSize: '0.8rem' }}>
            {a.protocol}
          </span>
        </div>
      ),
    },
    {
      key: 'username',
      header: 'Username',
      sortValue: (a) => a.username,
      cell: (a) => <span className="muted">{a.username}</span>,
    },
    {
      key: 'overlayIp',
      header: 'IP overlay',
      sortValue: (a) => a.overlayIp,
      cell: (a) => <span className="tnum">{a.overlayIp}</span>,
    },
    {
      key: 'winbox',
      header: 'Winbox (remote)',
      sortValue: (a) => a.winboxAddress,
      cell: (a) => <span className="tnum">{a.winboxAddress}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      sortValue: (a) => a.status,
      cell: (a) => (
        <StatusBadge
          status={a.status === 'ENABLED' ? 'ACTIVE' : 'DISABLED'}
          label={a.status === 'ENABLED' ? 'aktif' : 'nonaktif'}
        />
      ),
    },
    {
      key: 'connection',
      header: 'Koneksi',
      sortValue: (a) => (a.online ? 1 : 0),
      cell: (a) => <LiveIndicator online={a.online} lastHandshakeAt={a.lastHandshakeAt} />,
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      width: '1%',
      cell: (a) => (
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          {canConfig && (
            <>
              <button
                onClick={() => void saveBlob(() => downloadAccountRouterOs(a.id), `${a.username}.rsc`, toast.error)}
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
      ),
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Akun VPN"
        subtitle={
          <>
            Butuh remote Mikrotik tanpa IP publik? Cukup <strong>Generate akun</strong> — sistem memilih server VPN
            otomatis dan memberi Anda host, port, tipe keamanan, username &amp; password siap tempel di Mikrotik.
          </>
        }
      />

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

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari label, server, username, atau IP…" />
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}>
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </select>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(a) => a.id}
        loading={loading}
        initialSort={{ key: 'label', dir: 'asc' }}
        empty={
          <EmptyState
            title={query || statusFilter ? 'Tidak ada akun yang cocok' : 'Belum ada akun VPN'}
            hint={
              query || statusFilter
                ? 'Coba ubah kata kunci atau filter.'
                : canManage
                  ? 'Tekan “Generate akun” untuk membuat yang pertama.'
                  : 'Belum ada akun untuk ditampilkan.'
            }
          />
        }
      />
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
        <CommandBlock
          title={
            <>
              Atau tinggal tempel di terminal RouterOS <strong>v7</strong> (langsung jadi):
            </>
          }
          command={account.routerOsCommand}
          copyLabel="Salin perintah"
          onCopy={() => copy(account.routerOsCommand!, 'Perintah RouterOS v7')}
          hint={
            account.supportsV6
              ? 'RouterOS v7 (UDP/TCP + AES-256-GCM). Perangkat v6? Pakai perintah di bawah.'
              : 'RouterOS v7 (AES-256-GCM). Perangkat v6 butuh hub TCP — hub akun ini bukan TCP.'
          }
        />
      )}
      {account.supportsV6 && account.routerOsCommandV6 && (
        <CommandBlock
          title={
            <>
              Perangkat lama? Tempel di terminal RouterOS <strong>v6</strong> (TCP + AES-256-CBC):
            </>
          }
          command={account.routerOsCommandV6}
          copyLabel="Salin perintah v6"
          onCopy={() => copy(account.routerOsCommandV6!, 'Perintah RouterOS v6')}
          hint="Best-effort untuk v6 — bila ada properti yang ditolak, sesuaikan dengan rilis RouterOS Anda."
        />
      )}
      <div className="row" style={{ flexWrap: 'wrap' }}>
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
        {account.supportsV6 && (
          <>
            <button
              className="small"
              onClick={() =>
                void saveBlob(
                  () => downloadAccountRouterOs(account.id, 'V6'),
                  `${account.username}-v6.rsc`,
                  toast.error,
                )
              }
            >
              Unduh RouterOS v6
            </button>
            <button
              className="small ghost"
              onClick={() =>
                void saveBlob(() => downloadAccountOvpn(account.id, 'V6'), `${account.username}-v6.ovpn`, toast.error)
              }
            >
              Unduh .ovpn v6
            </button>
          </>
        )}
        <button className="ghost small" onClick={onDismiss}>
          Selesai
        </button>
      </div>
    </div>
  )
}

/* ---------- Blok perintah RouterOS satu-baris (v7/v6), dengan salin + catatan ---------- */

function CommandBlock({
  title,
  command,
  copyLabel,
  onCopy,
  hint,
}: {
  title: ReactNode
  command: string
  copyLabel: string
  onCopy: () => void
  hint: string
}) {
  return (
    <div className="stack" style={{ gap: '0.35rem', marginBottom: '0.7rem' }}>
      <div className="row" style={{ justifyContent: 'space-between', alignItems: 'center' }}>
        <span className="muted" style={{ fontSize: '0.82rem' }}>
          {title}
        </span>
        <button className="small ghost" onClick={onCopy}>
          {copyLabel}
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
        <code>{command}</code>
      </pre>
      <span className="muted" style={{ fontSize: '0.76rem' }}>
        {hint}
      </span>
    </div>
  )
}

/* ---------- Indikator liveness peer (online nyata dari hub, bukan status administratif) ---------- */

function LiveIndicator({ online, lastHandshakeAt }: { online: boolean; lastHandshakeAt: string | null }) {
  const sub = online
    ? `sejak ${fmtWhen(lastHandshakeAt)}`
    : lastHandshakeAt
      ? `terakhir ${fmtWhen(lastHandshakeAt)}`
      : 'belum pernah terhubung'
  return (
    <div className="stack" style={{ gap: '0.15rem' }}>
      <span
        className="badge"
        style={{ color: online ? 'var(--good-ink)' : 'var(--muted)', fontWeight: 600 }}
      >
        {online ? '● online' : '○ offline'}
      </span>
      <span className="muted" style={{ fontSize: '0.76rem' }}>
        {sub}
      </span>
    </div>
  )
}
