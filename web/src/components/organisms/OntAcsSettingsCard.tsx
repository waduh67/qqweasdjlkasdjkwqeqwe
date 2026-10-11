import { useEffect, useState } from 'react'
import { Copy } from 'lucide-react'
import { getAcsServerInfo, type AcsServerInfoView } from '@/api/acs'
import { useCan } from '@/auth/useCan'
import { Button, Spinner } from '@/components/atoms'
import { useToast } from '@/system'
import { copyText } from '@/utils/clipboard'

interface SettingsSource { info: AcsServerInfoView | null; failed: boolean }
export function OntAcsSettingsCard({ server, layout = 'card' }: { server?: SettingsSource; layout?: 'card' | 'section' }) {
  const { can } = useCan()
  const canView = can('cpe.acs.view')
  const [source, setSource] = useState<SettingsSource>({ info: null, failed: false })
  useEffect(() => {
    if (!canView || server !== undefined) return
    let alive = true, pending = false
    async function load() {
      if (pending) return
      pending = true
      try { const info = await getAcsServerInfo(); if (alive) setSource({ info, failed: false }) }
      catch { if (alive) setSource({ info: null, failed: true }) }
      finally { pending = false }
    }
    void load()
    const timer = window.setInterval(() => void load(), 30_000)
    window.addEventListener('focus', load)
    return () => { alive = false; window.clearInterval(timer); window.removeEventListener('focus', load) }
  }, [canView, server])
  if (!canView) return null
  const current = server ?? source
  return <section className={layout === 'card' ? 'card network-section' : 'network-section'} aria-label="Setelan ONT">
    <h2>Setelan ONT (TR-069)</h2>
    {current.failed ? <p role="alert">Setelan ONT gagal dimuat.</p> : current.info ? <Settings info={current.info} /> : <Spinner />}
  </section>
}
interface Setting { label: string; value: string | null; secret?: boolean; note?: string }
function Settings({ info }: { info: AcsServerInfoView }) {
  const toast = useToast()
  const [visible, setVisible] = useState(false)
  const groups: { title: string; rows: Setting[] }[] = [
    { title: 'Koneksi ACS', rows: [
      { label: 'ACS URL', value: info.cwmpUrl }, { label: 'ACS Username', value: info.acsUsername }, { label: 'ACS Password', value: info.acsPassword, secret: true },
    ] },
    { title: 'Connection Request', rows: [
      { label: 'Connection Request Username', value: info.connectionRequestUsername }, { label: 'Connection Request Password', value: info.connectionRequestPassword, secret: true },
    ] },
    { title: 'Laporan berkala', rows: [
      { label: 'Periodic Inform', value: info.periodicInformEnabled ? 'Aktif' : 'Nonaktif' },
      { label: 'Periodic Inform Interval', value: String(info.periodicInformIntervalSeconds), note: 'detik' },
    ] },
  ]
  async function copy(value: string, label: string) {
    if (await copyText(value)) toast.success(`${label} disalin.`)
    else toast.error('Gagal menyalin. Salin nilai secara manual.')
  }
  return <>
    <p className="network-note">Masukkan nilai berikut pada pengaturan TR-069 perangkat.</p>
    {!info.configured && <p role="status">Alamat ACS belum diatur. Hubungi administrator untuk melengkapi konfigurasi.</p>}
    <div className="row wrap">
      <Button disabled={!info.configured} onClick={() => void copy(['Setelan ACS (TR-069)', ...groups.flatMap(group => group.rows.map(row => `${row.label}: ${row.value ?? '(kosongkan)'}`))].join('\n'), 'Setelan ONT')}>Salin semua</Button>
      <Button onClick={() => setVisible(value => !value)}>{visible ? 'Sembunyikan kata sandi' : 'Tampilkan kata sandi'}</Button>
    </div>
    {groups.map(group => <section key={group.title} className="network-section" aria-label={group.title}>
      <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>{group.title}</h3>
      <dl className="network-properties">{group.rows.map(row => <div key={row.label}>
        <dt>{row.label}</dt><dd className="network-property-value"><span>{row.value ? row.secret && !visible ? '••••••••' : row.value : 'Kosongkan'}{row.note && ` ${row.note}`}</span>
          {row.value && <Button variant="subtle" icon={<Copy size={14} />} aria-label={`Salin ${row.label}`} onClick={() => void copy(row.value!, row.label)} />}
        </dd>
      </div>)}</dl>
    </section>)}
  </>
}
