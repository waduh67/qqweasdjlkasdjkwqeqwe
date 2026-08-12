import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { Download, DoorOpen, KeyRound, Power, PowerOff, Trash2 } from 'lucide-react'
import { ApiError } from '../api/client'
import {
  addAccountForward,
  deleteAccount,
  disableAccount,
  downloadAccountOvpn,
  downloadAccountRouterOs,
  enableAccount,
  generateAccount,
  listAccounts,
  removeAccountForward,
  retargetAccountForward,
  rotateAccountPassword,
  type VpnAccountView,
  type VpnForwardProtocol,
  type VpnPortForwardView,
} from '../api/vpn'
import { useCan } from '../auth/useCan'
import { DataTable, type Column, type RowAction } from '@/components/organisms'
import { Button, EmptyState, SelectField, StatusBadge, TextField, Toolbar } from '@/components/atoms'
import { Modal, SearchInput } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'
import { PageHeader } from '@/components/molecules'
import { IconAlert, IconPlus } from '@/components/atoms/icons'

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

/**
 * Layanan yang lazim di-remote pada perangkat pelanggan. Dipakai untuk MENGISI-OTOMATIS port +
 * protokol (dan menebak label) — kolomnya tetap boleh diketik manual, karena banyak teknisi
 * memindah port bawaan demi keamanan. Cermin daftar `WELL_KNOWN` di sisi server.
 */
const SERVICE_PRESETS: { label: string; devicePort: number; protocol: VpnForwardProtocol }[] = [
  { label: 'Winbox', devicePort: 8291, protocol: 'TCP' },
  { label: 'API', devicePort: 8728, protocol: 'TCP' },
  { label: 'API-SSL', devicePort: 8729, protocol: 'TCP' },
  { label: 'SSH', devicePort: 22, protocol: 'TCP' },
  { label: 'Telnet', devicePort: 23, protocol: 'TCP' },
  { label: 'WebFig', devicePort: 80, protocol: 'TCP' },
  { label: 'WebFig HTTPS', devicePort: 443, protocol: 'TCP' },
  { label: 'SNMP', devicePort: 161, protocol: 'UDP' },
  { label: 'Bandwidth test', devicePort: 2000, protocol: 'TCP' },
]

/** Batas pintu per akun — sama dengan `VpnPortForward.MAX_PER_PEER` di server. */
const MAX_FORWARDS = 10

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
  const { items: accounts, loading, reload, run } = useResource(listAccounts)

  const [label, setLabel] = useState('')
  const [busy, setBusy] = useState(false)
  // Kredensial (dengan password) hanya tampil sekali — setelah generate/rotasi.
  const [fresh, setFresh] = useState<VpnAccountView | null>(null)
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  // Akun yang sedang dibuka panel port remote-nya.
  const [forwardsOf, setForwardsOf] = useState<VpnAccountView | null>(null)

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
      header: 'Port remote',
      sortValue: (a) => a.winboxAddress ?? '',
      cell: (a) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <span className="tnum">{a.winboxAddress ?? '—'}</span>
          <span className="muted" style={{ fontSize: '0.76rem' }}>
            {a.forwards.length === 0
              ? 'tanpa pintu — hanya dari dalam tunnel'
              : a.forwards.length === 1
                ? a.forwards[0].label
                : `${a.forwards[0].label} +${a.forwards.length - 1} pintu lain`}
          </span>
        </div>
      ),
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
  ]

  // Aksi per-baris di menu `…` ala Azure DataGrid (seragam dengan Pelanggan), bukan deretan tombol inline.
  const rowActions = (a: VpnAccountView): RowAction[] => {
    const list: RowAction[] = []
    if (canConfig) {
      list.push({
        key: 'routeros',
        label: 'Unduh RouterOS',
        icon: <Download size={16} />,
        onClick: () => void saveBlob(() => downloadAccountRouterOs(a.id), `${a.username}.rsc`, toast.error),
      })
      list.push({
        key: 'ovpn',
        label: 'Unduh .ovpn',
        icon: <Download size={16} />,
        onClick: () => void saveBlob(() => downloadAccountOvpn(a.id), `${a.username}.ovpn`, toast.error),
      })
    }
    if (canManage) {
      list.push({
        key: 'forwards',
        label: 'Port remote',
        icon: <DoorOpen size={16} />,
        onClick: () => setForwardsOf(a),
      })
      list.push({
        key: 'toggle',
        label: a.status === 'ENABLED' ? 'Nonaktifkan' : 'Aktifkan',
        icon: a.status === 'ENABLED' ? <PowerOff size={16} /> : <Power size={16} />,
        onClick: () => toggle(a),
      })
      list.push({ key: 'rotate', label: 'Rotasi password', icon: <KeyRound size={16} />, onClick: () => rotate(a) })
      list.push({ key: 'delete', label: 'Hapus', icon: <Trash2 size={16} />, onClick: () => remove(a) })
    }
    return list
  }

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
            <TextField
              label="Label (opsional)"
              value={label}
              onChange={(_, data) => setLabel(data.value)}
              onKeyDown={(e) => e.key === 'Enter' && !busy && generate()}
              placeholder="mis. Mikrotik Bekasi"
              style={{ flex: 1 }}
            />
            <Button variant="primary" onClick={generate} disabled={busy}>
              <IconPlus size={15} /> {busy ? 'Membuat…' : 'Generate akun'}
            </Button>
          </div>
          <p className="muted" style={{ margin: 0, fontSize: '0.82rem' }}>
            Server dipilih otomatis. Password hanya tampil sekali setelah dibuat — salin atau unduh config-nya.
          </p>
        </div>
      )}

      {fresh && <CredentialCard account={fresh} onDismiss={() => setFresh(null)} />}

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari label, server, username, atau IP…" />
        <SelectField value={statusFilter} onChange={(_, data) => setStatusFilter(data.value)}>
          {STATUS_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>
              {o.label}
            </option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(a) => a.id}
        loading={loading}
        initialSort={{ key: 'label', dir: 'asc' }}
        rowActions={canConfig || canManage ? rowActions : undefined}
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

      {forwardsOf && (
        <PortForwardModal
          account={forwardsOf}
          onClose={() => {
            setForwardsOf(null)
            void reload()
          }}
        />
      )}
    </div>
  )
}

/* ---------- Panel port remote: pintu-pintu dari internet ke perangkat ---------- */

/**
 * Satu akun boleh punya beberapa pintu: `hub:portPublik` → `perangkat:portPerangkat`. Port
 * publik dipilih sistem dan PERMANEN (alamat yang sudah dipegang teknisi tak boleh bergeser);
 * yang bisa diubah cuma sasarannya di perangkat — inilah yang menyelamatkan perangkat dengan
 * Winbox/API yang portnya sudah dipindah dari bawaan.
 */
function PortForwardModal({ account, onClose }: { account: VpnAccountView; onClose: () => void }) {
  const toast = useToast()
  const confirm = useConfirm()
  const [acct, setAcct] = useState(account)
  const [busy, setBusy] = useState(false)
  const [addDraft, setAddDraft] = useState({ devicePort: '8291', protocol: 'TCP' as VpnForwardProtocol, label: '' })
  const [editing, setEditing] = useState<{ id: string; devicePort: string; protocol: VpnForwardProtocol; label: string } | null>(
    null,
  )

  const apply = (action: () => Promise<VpnAccountView>, ok: string, after?: () => void) => {
    setBusy(true)
    action()
      .then((updated) => {
        setAcct(updated)
        after?.()
        toast.success(ok)
      })
      .catch((err) => toast.error(err instanceof ApiError ? err.message : 'Operasi gagal'))
      .finally(() => setBusy(false))
  }

  const parsePort = (raw: string): number | null => {
    const port = Number(raw)
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      toast.error('Port perangkat harus angka 1–65535')
      return null
    }
    return port
  }

  const add = () => {
    const port = parsePort(addDraft.devicePort)
    if (port === null) return
    apply(
      () =>
        addAccountForward(acct.id, {
          devicePort: port,
          protocol: addDraft.protocol,
          label: addDraft.label.trim() || null,
        }),
      'Pintu ditambahkan',
      () => setAddDraft({ devicePort: '8291', protocol: 'TCP', label: '' }),
    )
  }

  const saveEdit = () => {
    if (!editing) return
    const port = parsePort(editing.devicePort)
    if (port === null) return
    apply(
      () =>
        retargetAccountForward(acct.id, editing.id, {
          devicePort: port,
          protocol: editing.protocol,
          label: editing.label.trim() || null,
        }),
      'Pintu diarahkan ulang',
      () => setEditing(null),
    )
  }

  const remove = (f: VpnPortForwardView) => {
    void (async () => {
      if (
        !(await confirm({
          title: 'Cabut pintu',
          message: `Cabut ${f.label} (${f.address})? Alamat itu langsung tak bisa dipakai lagi dan port publiknya bisa dipakai akun lain.`,
          confirmLabel: 'Cabut',
          danger: true,
        }))
      )
        return
      apply(() => removeAccountForward(acct.id, f.id), 'Pintu dicabut')
    })()
  }

  const copy = (value: string) => void navigator.clipboard?.writeText(value).then(() => toast.success('Alamat disalin'))

  /** Preset mengisi port + protokol; label dibiarkan kosong supaya server yang menebaknya. */
  const applyPreset = (value: string) => {
    const preset = SERVICE_PRESETS.find((p) => String(p.devicePort) === value)
    if (preset) setAddDraft({ devicePort: String(preset.devicePort), protocol: preset.protocol, label: '' })
  }

  const full = acct.forwards.length >= MAX_FORWARDS

  return (
    <Modal title={`Port remote “${acct.label}”`} onClose={onClose} wide>
      <p className="muted" style={{ margin: '0 0 0.75rem', fontSize: '0.83rem' }}>
        Tiap baris adalah satu pintu dari internet ke perangkat: <code>{acct.host}:portPublik</code> diteruskan ke{' '}
        <code>{acct.overlayIp}:portPerangkat</code>. Port publik dipilih sistem dan <strong>tak berubah</strong> —
        kalau port layanan di perangkat dipindah (mis. Winbox ke 9291), cukup ubah kolom “Port di perangkat”.
        Perubahan menyusul di hub paling lama ~1 menit.
      </p>

      <table>
        <thead>
          <tr>
            <th>Layanan</th>
            <th>Alamat publik</th>
            <th>Port di perangkat</th>
            <th style={{ width: '9rem' }} />
          </tr>
        </thead>
        <tbody>
          {acct.forwards.length === 0 && (
            <tr>
              <td colSpan={4} className="muted">
                Belum ada pintu — perangkat hanya terjangkau dari dalam tunnel.
              </td>
            </tr>
          )}
          {acct.forwards.map((f) =>
            editing?.id === f.id ? (
              <tr key={f.id}>
                <td>
                  <TextField
                    value={editing.label}
                    onChange={(_, data) => setEditing({ ...editing, label: data.value })}
                    placeholder="otomatis"
                  />
                </td>
                <td className="tnum muted">{f.address}</td>
                <td>
                  <div className="row" style={{ gap: '0.35rem' }}>
                    <TextField
                      value={editing.devicePort}
                      onChange={(_, data) => setEditing({ ...editing, devicePort: data.value })}
                      style={{ width: '6rem' }}
                    />
                    <SelectField
                      value={editing.protocol}
                      onChange={(_, data) => setEditing({ ...editing, protocol: data.value as VpnForwardProtocol })}
                    >
                      <option value="TCP">TCP</option>
                      <option value="UDP">UDP</option>
                    </SelectField>
                  </div>
                </td>
                <td>
                  <div className="row" style={{ gap: '0.35rem' }}>
                    <Button variant="primary" size="small" onClick={saveEdit} disabled={busy}>
                      Simpan
                    </Button>
                    <Button variant="subtle" size="small" onClick={() => setEditing(null)} disabled={busy}>
                      Batal
                    </Button>
                  </div>
                </td>
              </tr>
            ) : (
              <tr key={f.id}>
                <td>
                  <strong>{f.label}</strong>
                </td>
                <td>
                  <span className="tnum">{f.address}</span>{' '}
                  <Button variant="subtle" size="small" onClick={() => copy(f.address)}>
                    Salin
                  </Button>
                </td>
                <td className="tnum">
                  {f.devicePort} <span className="muted">{f.protocol}</span>
                </td>
                <td>
                  <div className="row" style={{ gap: '0.35rem' }}>
                    <Button
                      variant="subtle"
                      size="small"
                      disabled={busy}
                      onClick={() =>
                        setEditing({
                          id: f.id,
                          devicePort: String(f.devicePort),
                          protocol: f.protocol,
                          label: f.label,
                        })
                      }
                    >
                      Ubah
                    </Button>
                    <Button variant="subtle" size="small" disabled={busy} onClick={() => remove(f)}>
                      Cabut
                    </Button>
                  </div>
                </td>
              </tr>
            ),
          )}
        </tbody>
      </table>

      <div className="stack" style={{ gap: '0.4rem', marginTop: '1rem' }}>
        <strong style={{ fontSize: '0.86rem' }}>Tambah pintu</strong>
        <div className="row" style={{ alignItems: 'flex-end', flexWrap: 'wrap' }}>
          <SelectField label="Layanan" value={addDraft.devicePort} onChange={(_, data) => applyPreset(data.value)}>
            {!SERVICE_PRESETS.some((p) => String(p.devicePort) === addDraft.devicePort) && (
              <option value={addDraft.devicePort}>Lainnya</option>
            )}
            {SERVICE_PRESETS.map((p) => (
              <option key={p.devicePort} value={p.devicePort}>
                {p.label} ({p.devicePort})
              </option>
            ))}
          </SelectField>
          <TextField
            label="Port di perangkat"
            value={addDraft.devicePort}
            onChange={(_, data) => setAddDraft({ ...addDraft, devicePort: data.value })}
            style={{ width: '9rem' }}
          />
          <SelectField
            label="Protokol"
            value={addDraft.protocol}
            onChange={(_, data) => setAddDraft({ ...addDraft, protocol: data.value as VpnForwardProtocol })}
          >
            <option value="TCP">TCP</option>
            <option value="UDP">UDP</option>
          </SelectField>
          <TextField
            label="Nama (opsional)"
            value={addDraft.label}
            onChange={(_, data) => setAddDraft({ ...addDraft, label: data.value })}
            placeholder="otomatis dari port"
            style={{ flex: 1, minWidth: '10rem' }}
          />
          <Button variant="primary" onClick={add} disabled={busy || full}>
            <IconPlus size={15} /> Tambah
          </Button>
        </div>
        <span className="muted" style={{ fontSize: '0.78rem' }}>
          {full
            ? `Sudah ${MAX_FORWARDS} pintu — cabut salah satu dulu.`
            : 'Port publiknya dipilih sistem supaya tak bentrok dengan akun lain di hub yang sama.'}
        </span>
      </div>
    </Modal>
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
    { label: 'Winbox (remote)', value: account.winboxAddress ?? '—', copy: !!account.winboxAddress },
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
                  <Button variant="subtle" size="small" onClick={() => copy(r.value, r.label)}>
                    Salin
                  </Button>
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
              : `RouterOS v7 (AES-256-GCM). Perangkat v6 tak bisa memakai hub ini: klien OpenVPN v6 ` +
                `tak mengenal ${account.protocol}. Minta admin platform mengubah hub "${account.serverName}" ` +
                `ke TCP, menjalankan ulang perintah pasang di VPS, lalu membuka port ${account.port}/tcp.`
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
          hint={
            `Isinya persis kolom yang ada di menu ovpn-client v6. Kalau interfacenya berhenti di ` +
            `"connecting...", yang paling sering: port ${account.port}/tcp hub belum terbuka di ` +
            `firewall/NSG VPS.`
          }
        />
      )}
      <div className="row" style={{ flexWrap: 'wrap' }}>
        <Button
          size="small"
          onClick={() => void saveBlob(() => downloadAccountRouterOs(account.id), `${account.username}.rsc`, toast.error)}
        >
          Unduh RouterOS
        </Button>
        <Button
          variant="subtle"
          size="small"
          onClick={() => void saveBlob(() => downloadAccountOvpn(account.id), `${account.username}.ovpn`, toast.error)}
        >
          Unduh .ovpn
        </Button>
        {account.supportsV6 && (
          <>
            <Button
              size="small"
              onClick={() =>
                void saveBlob(
                  () => downloadAccountRouterOs(account.id, 'V6'),
                  `${account.username}-v6.rsc`,
                  toast.error,
                )
              }
            >
              Unduh RouterOS v6
            </Button>
            <Button
              variant="subtle"
              size="small"
              onClick={() =>
                void saveBlob(() => downloadAccountOvpn(account.id, 'V6'), `${account.username}-v6.ovpn`, toast.error)
              }
            >
              Unduh .ovpn v6
            </Button>
          </>
        )}
        <Button variant="subtle" size="small" onClick={onDismiss}>
          Selesai
        </Button>
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
        <Button variant="subtle" size="small" onClick={onCopy}>
          {copyLabel}
        </Button>
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
