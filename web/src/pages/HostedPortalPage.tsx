import { useEffect, useState, type SyntheticEvent } from 'react'
import { Text } from '@fluentui/react-components'
import { useSearchParams } from 'react-router-dom'
import { resolvePublicPortalContext, type PublicPortalContext } from '@/api/publicPortal'
import { BrandMark, Button, Spinner, TextField } from '@/components/atoms'

type ViewState =
  | { kind: 'loading' }
  | { kind: 'ready'; context: PublicPortalContext }
  | { kind: 'invalid' }

const INVALID_CONTEXT_MESSAGE = 'Tautan tidak valid atau kedaluwarsa. Hubungkan kembali ke Wi-Fi.'

function Brand({ context }: { context: PublicPortalContext }) {
  return (
    <div className="row hosted-portal-brand">
      {context.logoUrl ? (
        <img className="hosted-portal-logo" src={context.logoUrl} alt="" />
      ) : (
        <span className="logo" aria-hidden><BrandMark size={26} /></span>
      )}
      <div>
        <Text as="h1" size={400} weight="semibold" className="hosted-portal-title">{context.displayName}</Text>
        <Text as="p" size={200} className="muted hosted-portal-subtitle">Masuk ke jaringan Wi-Fi</Text>
      </div>
    </div>
  )
}

export function HostedPortalPage() {
  const [params] = useSearchParams()
  const state = params.get('state')?.trim() ?? ''
  const [view, setView] = useState<ViewState>({ kind: 'loading' })
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')

  useEffect(() => {
    let active = true
    if (!state) {
      setView({ kind: 'invalid' })
      return () => { active = false }
    }

    resolvePublicPortalContext({ state })
      .then((context) => {
        if (active) setView({ kind: 'ready', context })
      })
      .catch(() => {
        if (active) setView({ kind: 'invalid' })
      })

    return () => { active = false }
  }, [state])

  function submit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault()
  }

  if (view.kind === 'loading') {
    return <main className="login-shell" aria-busy="true"><Spinner /></main>
  }

  if (view.kind === 'invalid') {
    return (
      <main className="login-shell">
        <section className="card login-card stack hosted-portal-card" aria-labelledby="portal-error-title">
          <span className="logo" aria-hidden><BrandMark size={26} /></span>
          <div className="stack" style={{ gap: '0.35rem' }}>
            <Text as="h1" id="portal-error-title" size={400} weight="semibold">Portal tidak tersedia</Text>
            <Text as="p" className="muted" style={{ margin: 0 }}>{INVALID_CONTEXT_MESSAGE}</Text>
          </div>
        </section>
      </main>
    )
  }

  return (
    <main className="login-shell">
      <form className="card login-card stack hosted-portal-card" onSubmit={submit}>
        <Brand context={view.context} />
        <div className="hr" />
        <div className="stack" style={{ gap: '0.75rem' }}>
          <TextField
            label="Username"
            name="username"
            autoComplete="username"
            value={username}
            onChange={(_, data) => setUsername(data.value)}
            required
          />
          <TextField
            label="Kata sandi"
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(_, data) => setPassword(data.value)}
            required
          />
        </div>
        <div className="hosted-portal-notice" role="status">
          Layanan masuk belum tersedia.
        </div>
        <Button variant="primary" type="submit" disabled>
          Masuk ke internet
        </Button>
      </form>
    </main>
  )
}
