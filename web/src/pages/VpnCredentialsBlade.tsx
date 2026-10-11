import { useId, useState } from 'react'
import { MessageBar, MessageBarBody } from '@fluentui/react-components'
import { Copy, Download, Eye, EyeOff } from 'lucide-react'
import type { VpnAccountView } from '@/api/vpn'
import { Button, SelectField, TextField } from '@/components/atoms'
import { Tabs } from '@/components/molecules'
import { Blade } from '@/components/organisms/Blade'
import { useToast } from '@/system'
import { copyText } from '@/utils/clipboard'
import './vpn-credentials.css'

type ClientVersion = 'V7' | 'V6'
type CredentialTab = 'connection' | 'configuration'

export function VpnCredentialsBlade({ account, canDownload, onClose, onDownload }: {
  account: VpnAccountView
  canDownload: boolean
  onClose: () => void
  onDownload: (format: 'routeros' | 'ovpn', version: ClientVersion) => void
}) {
  const toast = useToast()
  const id = useId()
  const [tab, setTab] = useState<CredentialTab>('connection')
  const [version, setVersion] = useState<ClientVersion>('V7')
  const [showPassword, setShowPassword] = useState(false)
  const command = version === 'V7' ? account.routerOsCommand : account.routerOsCommandV6
  const copy = async (value: string, label: string) => {
    try {
      if (await copyText(value)) toast.success(`${label} disalin`)
      else toast.error(`Gagal menyalin ${label.toLowerCase()}`)
    }
    catch { toast.error(`Gagal menyalin ${label.toLowerCase()}`) }
  }
  const copyButton = (value: string, label: string) => <Button variant="subtle" icon={<Copy size={16} />}
    aria-label={`Salin ${label.toLowerCase()}`} title={`Salin ${label.toLowerCase()}`} onClick={() => void copy(value, label)} />
  const properties: { label: string; value: string; copyValue?: string }[] = [
    { label: 'Server VPN', value: account.serverName },
    { label: 'Alamat server', value: account.host, copyValue: account.host },
    { label: 'Port / protokol', value: `${account.port} / ${account.protocol}`, copyValue: String(account.port) },
    { label: 'IP VPN perangkat', value: account.overlayIp },
    { label: 'Enkripsi', value: account.cipher },
    { label: 'Alamat Winbox', value: account.winboxAddress ?? '—', copyValue: account.winboxAddress ?? undefined },
  ]

  return <Blade open title="Sambungkan perangkat" subtitle={account.label} size="lg" className="vpn-credentials-blade" onClose={onClose}>
    <Tabs active={tab} onChange={setTab} idPrefix={id} tabs={[
      { key: 'connection', label: 'Koneksi' }, { key: 'configuration', label: 'Konfigurasi' },
    ]} />
    <div role="tabpanel" id={`${id}-panel-${tab}`} aria-labelledby={`${id}-tab-${tab}`} className="vpn-credentials-panel">
      {tab === 'connection' ? <>
        <section aria-labelledby={`${id}-details`}>
          <h3 id={`${id}-details`}>Detail koneksi</h3>
          <dl className="vpn-connection-properties">
            {properties.map(({ label, value, copyValue }) => <div key={label}><dt>{label}</dt><dd>
              <span title={value}>{value}</span>{copyValue && copyButton(copyValue, label === 'Port / protokol' ? 'Port' : label)}
            </dd></div>)}
          </dl>
        </section>
        <section aria-labelledby={`${id}-authentication`}>
          <h3 id={`${id}-authentication`}>Autentikasi</h3>
          <MessageBar intent="info"><MessageBarBody>Simpan kredensial sebelum menutup panel ini.</MessageBarBody></MessageBar>
          <div className="vpn-credential-fields">
            <TextField label="Username" readOnly value={account.username} contentAfter={copyButton(account.username, 'Username')} />
            <TextField label="Password" readOnly type={showPassword ? 'text' : 'password'} value={account.password ?? ''}
              contentAfter={<>
                <Button variant="subtle" icon={showPassword ? <EyeOff size={16} /> : <Eye size={16} />} disabled={!account.password}
                  aria-label={showPassword ? 'Sembunyikan password' : 'Tampilkan password'} title={showPassword ? 'Sembunyikan password' : 'Tampilkan password'}
                  onClick={() => setShowPassword(value => !value)} />
                {account.password && copyButton(account.password, 'Password')}
              </>} />
          </div>
        </section>
        <Button variant="primary" onClick={() => setTab('configuration')}>Lihat konfigurasi</Button>
      </> : <>
        <SelectField label="Versi RouterOS" fieldClassName="vpn-client-version" value={version} onChange={(_, data) => setVersion(data.value as ClientVersion)}>
          <option value="V7">RouterOS v7</option>
          {account.supportsV6 && <option value="V6">RouterOS v6</option>}
        </SelectField>
        {command && <section aria-labelledby={`${id}-command`}>
          <div className="vpn-command-heading"><h3 id={`${id}-command`}>Perintah koneksi</h3>
            <Button variant="subtle" icon={<Copy size={16} />} onClick={() => void copy(command, 'Perintah')}>Salin perintah</Button>
          </div>
          <p className="vpn-credentials-help">Tempel perintah ini di terminal RouterOS.</p>
          <pre className="vpn-connection-command"><code>{command}</code></pre>
        </section>}
        {canDownload && <section aria-labelledby={`${id}-download`}>
          <h3 id={`${id}-download`}>Berkas konfigurasi</h3>
          <div className="vpn-configuration-downloads">
            <Button icon={<Download size={16} />} onClick={() => onDownload('routeros', version)}>Unduh .rsc</Button>
            <Button icon={<Download size={16} />} onClick={() => onDownload('ovpn', version)}>Unduh .ovpn</Button>
          </div>
        </section>}
      </>}
    </div>
  </Blade>
}
