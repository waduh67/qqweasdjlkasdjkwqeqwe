import { useEffect, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { useParams, useSearchParams } from 'react-router-dom'
import { getPublicHotspotPortalContext, type PublicHotspotPortalContext } from '@/api/hotspot'
import { Button, Spinner, TextField } from '@/components/atoms'

/**
 * Portal captive publik. Browser hanya membawa `state` yang ditandatangani server;
 * identitas site, NAS, dan tujuan redirect tidak pernah dibaca dari parameter bebas.
 * Handoff kredensial ke NAS/RADIUS menunggu kontrak T8, maka formulir sengaja belum
 * mengirim kredensial atau menampilkan keberhasilan palsu.
 */
export function HotspotPortalPage() {
  const { portalId = '' } = useParams()
  const [params] = useSearchParams()
  const state = params.get('state')?.trim() ?? ''
  const [context, setContext] = useState<PublicHotspotPortalContext | null>(null)
  const [loading, setLoading] = useState(true)
  const [invalid, setInvalid] = useState(false)

  useEffect(() => {
    let active = true
    if (!portalId || !state) {
      setLoading(false)
      setInvalid(true)
      return () => {
        active = false
      }
    }

    getPublicHotspotPortalContext(state)
      .then((resolved) => {
        if (!active) return
        setContext(resolved)
      })
      .catch(() => {
        if (!active) return
        setInvalid(true)
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    return () => {
      active = false
    }
  }, [portalId, state])

  if (loading) {
    return (
      <main className="login-shell" aria-busy="true">
        <div className="card login-card stack">
          <Spinner />
          <Text className="muted" size={200}>Memuat portal…</Text>
        </div>
      </main>
    )
  }

  if (invalid || !context) {
    return <InvalidPortalState />
  }

  return (
    <main className="login-shell">
      <section className="card login-card stack" aria-labelledby="hotspot-portal-title">
        <div className="stack">
          {context.logoUrl ? (
            <img src={context.logoUrl} alt={`Logo ${context.displayName}`} />
          ) : null}
          <div>
            <Text as="h1" id="hotspot-portal-title" size={600} weight="semibold" style={{ margin: 0 }}>
              {context.displayName}
            </Text>
            <Text as="p" className="muted" size={200} style={{ margin: '0.3rem 0 0' }}>
              Masuk untuk menggunakan jaringan Wi-Fi ini.
            </Text>
          </div>
        </div>

        <form className="stack" onSubmit={(event) => event.preventDefault()}>
          <TextField
            label="Username atau kode voucher"
            aria-label="Username atau kode voucher"
            name="username"
            autoComplete="username"
            required
          />
          <TextField
            label="Kata sandi"
            aria-label="Kata sandi"
            name="password"
            type="password"
            autoComplete="current-password"
            required
          />
          <Text className="muted" size={200}>
            Masuk belum tersedia. Penghubung aman ke NAS/RADIUS sedang disiapkan; jangan masukkan kredensial Anda.
          </Text>
          <Button type="submit" variant="primary" disabled aria-describedby="hotspot-login-unavailable">
            Masuk ke Wi-Fi
          </Button>
          <Text id="hotspot-login-unavailable" className="dim" size={100}>
            Formulir ini belum dapat mengautentikasi atau mengaktifkan voucher.
          </Text>
        </form>
      </section>
    </main>
  )
}

function InvalidPortalState() {
  return (
    <main className="login-shell">
      <section className="card login-card stack" aria-labelledby="hotspot-invalid-title">
        <div>
          <Text as="h1" id="hotspot-invalid-title" size={600} weight="semibold" style={{ margin: 0 }}>
            Tautan portal tidak dapat digunakan
          </Text>
          <Text as="p" className="muted" size={200} style={{ margin: '0.3rem 0 0' }}>
            Tautan ini tidak valid atau sudah kedaluwarsa. Hubungkan kembali ke jaringan Wi-Fi untuk mendapatkan tautan baru.
          </Text>
        </div>
      </section>
    </main>
  )
}
