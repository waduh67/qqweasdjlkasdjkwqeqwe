import { useMemo, useState } from 'react'
import { Button, TextField } from '@/components/atoms'
import { ResourceForm } from '@/components/organisms/ResourceForm'
import type { RadiusEndpointView } from '@/api/bng'
import { useToast } from '@/system'
import { copyText } from '@/utils/clipboard'
import { WALLED_GARDEN_PORT, defaultIsolirUrl, isolirScript, parseIsolirTarget } from '@/utils/isolirScript'

function mikrotikScript(endpoint: RadiusEndpointView, secret: string, radiusHost: string | null): string {
  const host = radiusHost ?? '<IP-RADIUS>'
  const sec = secret || endpoint.sharedSecret || '<SECRET-BRAS>'
  return [
    `/ip pool add name=pool-pppoe ranges=10.20.0.2-10.20.255.254`,
    `/ppp profile set [find name=default] local-address=10.20.0.1 remote-address=pool-pppoe`,
    `/radius add service=ppp address=${host} secret=${sec} \\`,
    `    authentication-port=${endpoint.authPort} accounting-port=${endpoint.acctPort}`,
    `/radius incoming set accept=yes port=${endpoint.coaPort}`,
    `/ppp aaa set use-radius=yes accounting=yes interim-update=5m`,
  ].join('\n')
}

/** Blok skrip RouterOS siap-salin (monospace multi-baris + tombol Salin). */
function MikrotikSnippet({
  script,
  label = 'Salin konfigurasi RouterOS',
  copied = 'Konfigurasi RouterOS disalin',
}: {
  script: string
  label?: string
  copied?: string
}) {
  const toast = useToast()
  return (
    <div className="stack" style={{ gap: '0.4rem' }}>
      <code
        style={{
          display: 'block',
          whiteSpace: 'pre',
          overflowX: 'auto',
          padding: '0.6rem 0.75rem',
          
        }}
      >
        {script}
      </code>
      <div>
        <Button
          type="button"
          size="small"
          onClick={() => void copyText(script).then(ok => ok ? toast.success(copied) : toast.error('Gagal menyalin konfigurasi.'))}
        >
          {label}
        </Button>
      </div>
    </div>
  )
}


export function BngConfiguration({ endpoint, secret, host, onClose }: {
  endpoint: RadiusEndpointView | null; secret: string; host: string | null; onClose: () => void
}) {
  const [url, setUrl] = useState(() => defaultIsolirUrl(window.location.origin))
  const target = useMemo(() => parseIsolirTarget(url), [url])
  return <ResourceForm title="Konfigurasi RouterOS" readOnly onBack={() => {}} onClose={onClose}>
    <div className="network-form-step">
      {!endpoint ? <p>Informasi server RADIUS belum tersedia.</p> : <>
        <h2>RADIUS</h2>
        <p className="network-note">Sesuaikan pool alamat dan profil PPPoE dengan jaringan Anda.</p>
        {!host && <p role="status">Alamat server RADIUS belum diatur. Lengkapi alamat sebelum menerapkan konfigurasi.</p>}
        {!secret && !endpoint.sharedSecret && <p role="status">Ganti &lt;SECRET-BRAS&gt; dengan shared secret router Anda.</p>}
        <MikrotikSnippet script={mikrotikScript(endpoint, secret, host)} />
        <h2>Halaman isolir</h2>
        <TextField label="Alamat halaman tagihan" value={url} onChange={(_, data) => setUrl(data.value)} placeholder="https://portal.contoh.id/portal" />
        <p className="network-note">Izinkan pelanggan yang diisolir mengakses halaman tagihan melalui port {WALLED_GARDEN_PORT}.</p>
        {target && !target.ip && <p>Ganti &lt;IP-HALAMAN-TAGIHAN&gt; pada konfigurasi dengan IP server {target.host}.</p>}
        <MikrotikSnippet script={isolirScript(endpoint.isolirAddressList, target)} label="Salin aturan isolir" copied="Aturan isolir disalin" />
      </>}
    </div>
  </ResourceForm>
}
