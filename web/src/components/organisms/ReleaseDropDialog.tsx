import { useState } from 'react'
import { Checkbox, MessageBar, MessageBarBody, typographyStyles } from '@fluentui/react-components'
import { api, ApiError } from '@/api/client'
import type { CableView, DropReleaseView } from '@/api/network'
import { Button, TextField } from '@/components/atoms'
import { Modal } from '@/components/molecules'
import { useToast } from '@/system'

/**
 * "Pelanggannya cabut" — satu tombol, bukan tujuh langkah.
 *
 * Yang terjadi tanpa layar ini: teknisi menggulung drop-nya, tiket ditutup, dan
 * TAK ADA yang kembali ke meja sambung untuk melepas kaki splitter di ODP. Enam
 * bulan kemudian kotak itu terlihat penuh — padahal seperempat kakinya milik
 * orang yang sudah pindah kota. Kotak "penuh" palsu itu mahal: ia memicu
 * pemasangan kotak baru di tiang yang sebetulnya masih lapang.
 *
 * Karena itu pembebasan core dilakukan di sini, di tempat kabelnya dibuka, dan
 * bukan sebagai efek samping diam-diam dari status ONU. ONU diganti dengan yang
 * baru di rumah yang sama adalah kejadian sehari-hari — kalau pencabutan ikut
 * berjalan otomatis di situ, sambungan yang masih hidup ikut terhapus.
 *
 * Satu pilihan yang disodorkan: **ditandai ditinggal**. Sengaja tak dicentang
 * dari sananya, sebab rumah yang sama kerap langsung berlangganan lagi atas nama
 * penghuni baru dan drop-nya masih berguna. Yang benar-benar digulung dan tak
 * akan dipakai lagi baru pantas disebut ditinggal — supaya tak terhitung sebagai
 * kabel siap pakai saat orang merencanakan pelanggan berikutnya.
 */
export function ReleaseDropDialog({
  cable,
  onClose,
  onDone,
}: {
  cable: CableView
  onClose: () => void
  /** Dipanggil setelah server menjawab — pemanggil menyegarkan panel & peta. */
  onDone: (result: DropReleaseView) => void
}) {
  const toast = useToast()
  const [abandon, setAbandon] = useState(false)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)

  const submit = async () => {
    setBusy(true)
    try {
      const result = await api.post<DropReleaseView>(`/api/cables/${cable.id}/release-drop`, {
        abandon,
        note: note.trim() || undefined,
      })
      toast.success(result.message)
      onDone(result)
      onClose()
    } catch (err) {
      toast.error(err instanceof ApiError ? err.message : 'Gagal mencabut pelanggan')
      setBusy(false)
    }
  }

  return (
    <Modal
      title={`Cabut pelanggan dari ${cable.name}`}
      onClose={onClose}
      footer={
        <>
          <Button variant="subtle" onClick={onClose} disabled={busy}>
            Batal
          </Button>
          <Button variant="primary" onClick={() => void submit()} disabled={busy}>
            {busy ? 'Melepas…' : 'Cabut pelanggan'}
          </Button>
        </>
      }
    >
      <div className="stack" style={{ gap: '0.8rem' }}>
        <MessageBar intent="warning">
          <MessageBarBody>
            Semua sambungan kabel ini dilepas dan core-nya kembali bebas — termasuk kaki splitter di kotak
            hulunya, supaya kotak itu bisa dijual lagi. Kabelnya sendiri tidak dihapus: seratnya masih
            tergantung di tiang.
          </MessageBarBody>
        </MessageBar>

        <Checkbox
          label="Tandai kabelnya ditinggal (digulung, tak akan dipakai lagi)"
          checked={abandon}
          onChange={(_, data) => setAbandon(!!data.checked)}
          disabled={busy}
        />
        <p className="muted" style={{ margin: 0, ...typographyStyles.caption1 }}>
          {abandon
            ? 'Kabel ditinggal tak lagi terhitung sebagai kabel siap pakai saat merencanakan pelanggan baru.'
            : 'Biarkan tak tercentang bila rumah ini kemungkinan berlangganan lagi — drop-nya siap dipakai penghuni berikutnya.'}
        </p>

        <TextField
          label="Catatan"
          hint="Alasan pencabutan — tersimpan di jejak audit. Boleh dikosongkan."
          value={note}
          maxLength={200}
          onChange={(_, data) => setNote(data.value)}
          disabled={busy}
        />
      </div>
    </Modal>
  )
}
