import { useEffect, useRef, useState } from 'react'
import { Button, TextField } from '@/components/atoms'

type Detector = { detect(source: HTMLVideoElement): Promise<{ rawValue: string }[]> }
type DetectorConstructor = new () => Detector

/** Only identifies a candidate. The enclosing form owns review and acknowledgement. */
export function MaterialScanner({ onScan, disabled = false }: { onScan: (serial: string) => void; disabled?: boolean }) {
  const [value, setValue] = useState(''), [error, setError] = useState<string | null>(null)
  const [camera, setCamera] = useState(false), [busy, setBusy] = useState(false)
  const stream = useRef<MediaStream | null>(null), video = useRef<HTMLVideoElement | null>(null), mounted = useRef(true), detector = useRef<Detector | null>(null)
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; stream.current?.getTracks().forEach(track => track.stop()) } }, [])
  function stop() { stream.current?.getTracks().forEach(track => track.stop()); stream.current = null; detector.current = null; setCamera(false) }
  function select(raw: string) { const serial = raw.trim(); if (disabled || !serial) return; setValue(serial); setError(null); onScan(serial) }
  async function start() {
    if (disabled || busy) return
    const BarcodeDetector = (window as unknown as { BarcodeDetector?: DetectorConstructor }).BarcodeDetector
    if (!BarcodeDetector || !navigator.mediaDevices?.getUserMedia) { setError('Pemindaian kamera belum tersedia di browser ini. Ketik serial atau gunakan pemindai keyboard.'); return }
    setBusy(true); setError(null)
    try {
      detector.current = new BarcodeDetector()
      const acquired = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: { ideal: 'environment' } } })
      if (!mounted.current) { acquired.getTracks().forEach(track => track.stop()); return }
      stream.current = acquired; setCamera(true)
    } catch { stop(); setError('Kamera tidak dapat dibuka. Periksa izin kamera atau masukkan serial secara manual.') }
    finally { if (mounted.current) setBusy(false) }
  }
  async function read() {
    if (disabled || busy || !video.current || !detector.current) return
    setBusy(true); setError(null)
    try {
      const found = await detector.current.detect(video.current)
      if (!mounted.current) return
      if (found.length !== 1 || !found[0].rawValue.trim()) { setError('Arahkan kamera ke satu kode yang jelas, lalu coba lagi.'); return }
      select(found[0].rawValue); stop()
    } catch { if (mounted.current) { stop(); setError('Kode belum terbaca. Gunakan serial manual atau pemindai keyboard.') } }
    finally { if (mounted.current) setBusy(false) }
  }
  return <div className="stack">
    <TextField label="Serial perangkat" hint="Ketik atau pindai, lalu Enter untuk memilih. Penerimaan tetap perlu dikonfirmasi." value={value} maxLength={128} disabled={disabled || busy}
      onChange={(_, data) => setValue(data.value)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); event.stopPropagation(); select(value) } }} />
    <div className="row wrap"><Button disabled={disabled || busy || !value.trim()} onClick={() => select(value)}>Pilih serial</Button>
      {!camera && <Button disabled={disabled || busy} onClick={() => void start()}>{busy ? 'Membuka kamera…' : 'Gunakan kamera'}</Button>}</div>
    {camera && <><video aria-label="Pratinjau pemindai serial" autoPlay muted playsInline style={{ width: '100%', maxHeight: '16rem', objectFit: 'contain' }} ref={element => { video.current = element; if (element) element.srcObject = stream.current }} />
      <div className="row wrap"><Button disabled={disabled || busy} onClick={() => void read()}>Baca kode di kamera</Button><Button disabled={busy} onClick={stop}>Tutup kamera</Button></div></>}
    {error && <p role="alert">{error}</p>}
  </div>
}
