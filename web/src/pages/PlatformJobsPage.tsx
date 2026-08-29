import { useCallback, useEffect, useMemo, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { listJobHealth, type JobHealthView } from '../api/ops'
import { Badge, EmptyState, SelectField, Toolbar } from '@/components/atoms'
import { IconMonitor } from '@/components/atoms/icons'
import { PageHeader, SearchInput } from '@/components/molecules'
import { DataTable, type Column } from '@/components/organisms'

/**
 * Pekerjaan latar server (PLATFORM). Belasan `@Scheduled` yang menagih, memoll,
 * menyinkron, dan menjatuhkan alarm — semuanya bekerja tanpa dilihat siapa pun, dan
 * kalau salah satunya berhenti tak ada layar yang berubah merah. Halaman ini satu-satunya
 * tempat kesunyian itu bisa dibedakan dari "tidak ada masalah".
 *
 * Sengaja tanpa aksi apa pun: tak ada tombol "jalankan sekarang". Menjalankan penagihan
 * atau provisioning di luar jadwal dari sebuah layar admin adalah cara yang terlalu mudah
 * untuk menerbitkan tagihan ganda. Halaman ini membaca, manusianya yang memutuskan.
 */
const REFRESH_MS = 15_000

export function PlatformJobsPage() {
  const [jobs, setJobs] = useState<JobHealthView[] | null>(null)
  const [query, setQuery] = useState('')
  const [moduleFilter, setModuleFilter] = useState('')

  const load = useCallback(async () => {
    try {
      setJobs(await listJobHealth())
    } catch {
      setJobs([])
    }
  }, [])

  useEffect(() => {
    void load()
    // Muat ulang berkala: nilai di sini semuanya "umur sejak…", jadi halaman yang
    // dibiarkan terbuka tanpa refresh justru menampilkan keadaan yang makin salah.
    const timer = setInterval(() => void load(), REFRESH_MS)
    return () => clearInterval(timer)
  }, [load])

  // Di-memo supaya fallback `[]` tak jadi array baru tiap render dan membatalkan memo di bawahnya.
  const all = useMemo(() => jobs ?? [], [jobs])
  const modules = useMemo(() => Array.from(new Set(all.map((j) => j.module))).sort(), [all])
  const stalled = all.filter((j) => j.stalled).length
  const failing = all.filter((j) => !j.stalled && j.lastError != null).length

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase()
    return all.filter((job) => {
      if (moduleFilter && job.module !== moduleFilter) return false
      if (!q) return true
      return job.name.toLowerCase().includes(q) || job.module.toLowerCase().includes(q)
    })
  }, [all, query, moduleFilter])

  const columns: Column<JobHealthView>[] = [
    {
      key: 'name',
      header: 'Pekerjaan',
      sortValue: (j) => j.name,
      cell: (j) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <Text as="strong" weight="semibold" >{j.name}</Text>
          <Text as="span" className="muted" size={300}>modul {j.module}</Text>
        </div>
      ),
    },
    {
      key: 'status',
      header: 'Status',
      // Urut menaruh yang paling gawat di atas: macet → pernah gagal → sehat.
      sortValue: (j) => (j.stalled ? 0 : j.lastError != null ? 1 : 2),
      cell: (j) =>
        j.stalled ? (
          <Badge tone="critical">Macet</Badge>
        ) : j.running ? (
          <Badge tone="accent">Berjalan</Badge>
        ) : j.lastError != null ? (
          <Badge tone="warning">Ronde terakhir gagal</Badge>
        ) : (
          <Badge tone="good">Sehat</Badge>
        ),
    },
    {
      key: 'interval',
      header: 'Jadwal',
      sortValue: (j) => j.intervalSeconds ?? Number.MAX_SAFE_INTEGER,
      cell: (j) => (j.intervalSeconds == null ? <Text as="span" className="muted">tak tetap</Text> : `tiap ${humanize(j.intervalSeconds)}`),
    },
    {
      key: 'lastSuccess',
      header: 'Sukses terakhir',
      sortValue: (j) => j.sinceSuccessSeconds,
      cell: (j) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <Text as="span" >{j.lastSuccessAt == null ? 'belum pernah' : `${humanize(j.sinceSuccessSeconds)} lalu`}</Text>
          {j.stallAfterSeconds != null && (
            <Text as="span" className="muted" size={300}>
              ambang macet {humanize(j.stallAfterSeconds)}</Text>
          )}
        </div>
      ),
    },
    {
      key: 'runs',
      header: 'Ronde',
      align: 'right',
      sortValue: (j) => j.runs,
      cell: (j) => (
        <div className="stack" style={{ gap: '0.15rem' }}>
          <Text as="span" >{j.runs.toLocaleString('id-ID')}</Text>
          {j.failures > 0 && (
            <Text as="span" className="muted" size={300}>{j.failures.toLocaleString('id-ID')} gagal</Text>
          )}
        </div>
      ),
    },
    {
      key: 'duration',
      header: 'Durasi terakhir',
      align: 'right',
      sortValue: (j) => j.lastDurationSeconds ?? -1,
      cell: (j) => (j.lastDurationSeconds == null ? <Text as="span" className="muted">—</Text> : formatDuration(j.lastDurationSeconds)),
    },
    {
      key: 'error',
      header: 'Galat terakhir',
      sortValue: (j) => j.lastError ?? '',
      cell: (j) =>
        j.lastError == null ? (
          <Text as="span" className="muted">—</Text>
        ) : (
          <Text as="span" title={j.lastError} size={300}>{j.lastError.length > 70 ? `${j.lastError.slice(0, 70)}…` : j.lastError}</Text>
        ),
    },
  ]

  return (
    <div className="stack" style={{ gap: '1.25rem' }}>
      <PageHeader
        title="Pekerjaan Latar"
        subtitle="Denyut nadi penjadwal server: penagihan, polling OLT, provisioning RADIUS, sinkronisasi CPE. Job yang berhenti tak menimbulkan galat apa pun — hanya kesunyian yang tampak seperti baik-baik saja."
      />

      <div className="stat-grid">
        <Stat label="Pekerjaan terpantau" value={all.length} />
        <Stat label="Macet" value={stalled} accent={stalled > 0 ? 'crit' : undefined} />
        <Stat label="Ronde terakhir gagal" value={failing} accent={failing > 0 ? 'warn' : undefined} />
      </div>

      <Toolbar>
        <SearchInput value={query} onChange={setQuery} placeholder="Cari nama pekerjaan atau modul…" />
        <SelectField value={moduleFilter} onChange={(_, data) => setModuleFilter(data.value)}>
          <option value="">Semua modul</option>
          {modules.map((m) => (
            <option key={m} value={m}>{m}</option>
          ))}
        </SelectField>
      </Toolbar>

      <DataTable
        columns={columns}
        rows={rows}
        rowKey={(j) => j.name}
        loading={jobs == null}
        initialSort={{ key: 'status', dir: 'asc' }}
        empty={
          <EmptyState
            title={query || moduleFilter ? 'Tidak ada pekerjaan yang cocok' : 'Belum ada pekerjaan terpantau'}
            hint={
              query || moduleFilter
                ? 'Coba ubah kata kunci atau filter modul.'
                : 'Daftar terisi otomatis saat server selesai menyalakan penjadwalnya.'
            }
            icon={<IconMonitor size={32} />}
          />
        }
      />
    </div>
  )
}

/** Kartu ringkasan seragam dengan dashboard platform (bar warna kiri sebagai nada). */
function Stat({ label, value, accent }: { label: string; value: number; accent?: 'crit' | 'warn' }) {
  return (
    <div className={`stat ${accent === 'crit' ? 'crit-bar' : accent === 'warn' ? 'warn-bar' : 'accent-bar'}`}>
      <div className="stat-label">{label}</div>
      <div className="stat-value">{value.toLocaleString('id-ID')}</div>
    </div>
  )
}

/** Durasi kasar dalam bahasa manusia, dua satuan terbesar: "2 jam 15 menit". */
function humanize(seconds: number): string {
  const total = Math.max(0, Math.round(seconds))
  if (total < 60) return `${total} detik`
  const parts: string[] = []
  let rest = total
  for (const [unit, label] of [[86400, 'hari'], [3600, 'jam'], [60, 'menit']] as const) {
    const count = Math.floor(rest / unit)
    if (count > 0) {
      parts.push(`${count} ${label}`)
      rest -= count * unit
    }
    if (parts.length === 2) break
  }
  return parts.join(' ')
}

/** Durasi eksekusi: presisi kecil justru yang menarik di sini (banyak ronde < 1 detik). */
function formatDuration(seconds: number): string {
  if (seconds < 1) return `${Math.round(seconds * 1000)} ms`
  if (seconds < 60) return `${seconds.toFixed(1)} detik`
  return humanize(seconds)
}
