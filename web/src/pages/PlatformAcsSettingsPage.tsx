import { useCallback, useEffect, useRef, useState } from 'react'
import { Text } from '@fluentui/react-components'
import { ApiError } from '@/api/client'
import {
  getPlatformAcsSettings, updatePlatformAcsSettings, testPlatformAcsConnection,
  type PlatformAcsSettings, type UpdatePlatformAcsSettings, type AcsConnectionResult,
} from '@/api/platformAcs'
import { useCan } from '@/auth/useCan'
import { Badge, Button, Spinner, TextField } from '@/components/atoms'
import { FormSection, PageHeader } from '@/components/molecules'
import { useConfirm, useToast } from '@/system'

function toForm(settings: PlatformAcsSettings): UpdatePlatformAcsSettings {
  return { nbiUrl: settings.nbiUrl, username: settings.username, password: '', cwmpUrl: settings.cwmpUrl ?? '' }
}

function validUrl(value: string): boolean {
  try {
    const url = new URL(value.trim())
    return ['http:', 'https:'].includes(url.protocol) && !!url.hostname &&
      !url.username && !url.password && !url.search && !url.hash && value.trim().length <= 2048
  } catch { return false }
}

export function PlatformAcsSettingsPage() {
  const { can } = useCan()
  const manage = can('platform.acs.manage')
  const toast = useToast()
  const confirm = useConfirm()
  const [saved, setSaved] = useState<PlatformAcsSettings | null>(null)
  const [form, setForm] = useState<UpdatePlatformAcsSettings | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<AcsConnectionResult | null>(null)
  const active = useRef(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const settings = await getPlatformAcsSettings()
      setSaved(settings)
      setForm(toForm(settings))
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal memuat setelan ACS')
    } finally { setLoading(false) }
  }, [toast])
  useEffect(() => { void load() }, [load])

  const dirty = !!saved && !!form && JSON.stringify(toForm(saved)) !== JSON.stringify(form)
  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])

  const patch = (value: Partial<UpdatePlatformAcsSettings>) => setForm(current => current ? { ...current, ...value } : current)
  const valid = !!form && validUrl(form.nbiUrl) && validUrl(form.cwmpUrl) &&
    !form.username.includes(':') && (!form.username.trim() || !!form.password.trim() || !!saved?.passwordSet)

  const save = async () => {
    if (!manage || !form || !valid || active.current) return
    active.current = true
    setBusy(true)
    try {
      const settings = await updatePlatformAcsSettings(form)
      setSaved(settings)
      setForm(toForm(settings))
      setResult(null)
      toast.success('Setelan ACS disimpan. Operasi baru langsung memakai konfigurasi ini.')
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'Gagal menyimpan setelan ACS')
    } finally { active.current = false; setBusy(false) }
  }
  const test = async () => {
    if (!manage || dirty || active.current) return
    active.current = true
    setBusy(true)
    setResult(null)
    try { setResult(await testPlatformAcsConnection()) }
    catch (error) { toast.error(error instanceof ApiError ? error.message : 'Uji koneksi ACS gagal') }
    finally { active.current = false; setBusy(false) }
  }
  const discard = async () => {
    if (!saved || busy) return
    if (await confirm({ title: 'Buang perubahan ACS?', message: 'Perubahan yang belum disimpan akan dibuang.', confirmLabel: 'Buang perubahan' })) {
      setForm(toForm(saved))
    }
  }

  if (loading) return <Spinner />
  if (!saved || !form) return <div className="stack"><p>Setelan ACS gagal dimuat.</p><Button onClick={() => void load()}>Coba lagi</Button></div>

  return (
    <div className="stack settings-page horizontal-form">
      <PageHeader title="Server ACS" subtitle="Satu server GenieACS untuk semua tenant. Aplikasi dan ACS dapat dipasang di VPS berbeda." />
      <Badge tone="neutral">{saved.persisted ? 'Konfigurasi tersimpan' : 'Konfigurasi dari environment'}</Badge>
      <form className="stack" onSubmit={event => { event.preventDefault(); void save() }}>
        <FormSection title="API aplikasi ke ACS" description="URL NBI di VPS ACS. Endpoint ini diakses aplikasi, bukan ONT pelanggan.">
          <TextField label="NBI URL" required value={form.nbiUrl} maxLength={2048} disabled={!manage || busy}
            placeholder="https://api-acs.contoh.net" onChange={(_, data) => patch({ nbiUrl: data.value })}
            hint="Gunakan HTTPS dengan autentikasi atau jaringan privat/VPN antar VPS." />
          <TextField label="Username API" value={form.username} maxLength={255} disabled={!manage || busy}
            autoComplete="off" onChange={(_, data) => patch({ username: data.value })}
            hint="Kosongkan untuk menonaktifkan autentikasi API; hanya untuk NBI dalam jaringan privat." />
          <TextField label="Password API" type="password" value={form.password} maxLength={512} disabled={!manage || busy}
            autoComplete="new-password" onChange={(_, data) => patch({ password: data.value })}
            hint={saved.passwordSet ? 'Password sudah tersimpan. Kosongkan untuk mempertahankannya.' : 'Belum ada password tersimpan. Isi jika username API digunakan.'} />
        </FormSection>
        <FormSection title="ONT ke ACS" description="ONT memulai koneksi Inform ke URL ini melalui jaringan pelanggan.">
          <TextField label="CWMP URL lengkap" required value={form.cwmpUrl} maxLength={2048} disabled={!manage || busy}
            placeholder="http://acs.contoh.net:7547/" onChange={(_, data) => patch({ cwmpUrl: data.value })}
            hint="URL ini muncul pada kartu Setelan ONT. Menggantinya tidak otomatis mengubah ONT yang sudah terpasang." />
          <Text as="p" size={200} className="muted">Kredensial ONT dan connection request mengikuti konfigurasi deployment. Samakan kredensial ONT pada aplikasi dan VPS ACS.</Text>
        </FormSection>
        {manage && <div className="row" style={{ flexWrap: 'wrap' }}>
          <Button type="submit" variant="primary" disabled={!dirty || !valid || busy}>{busy ? 'Memproses…' : 'Simpan setelan'}</Button>
          <Button type="button" disabled={!dirty || busy} onClick={() => void discard()}>Buang perubahan</Button>
          <Button type="button" disabled={dirty || busy} onClick={() => void test()}>Uji koneksi tersimpan</Button>
        </div>}
      </form>
      {dirty && <Text as="p" size={200} className="muted">Simpan perubahan sebelum menguji koneksi.</Text>}
      <Text as="p" size={200} className="muted">Uji koneksi hanya memeriksa akses aplikasi ke API ACS. Jalur ONT ke CWMP dan akses balik ACS ke ONT perlu diuji dari jaringan masing-masing.</Text>
      {result && <div role="status"><Badge tone={result.reachable ? 'good' : 'critical'}>{result.reachable ? 'API ACS terjangkau' : 'API ACS gagal dihubungi'}</Badge>
        <p>{result.reachable ? 'Latensi: ' + result.latencyMs + ' ms' : result.error}</p></div>}
    </div>
  )
}
