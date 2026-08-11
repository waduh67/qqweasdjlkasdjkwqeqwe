import { useEffect, useState } from 'react'
import { MessageBar, MessageBarBody } from '@fluentui/react-components'
import { api } from '@/api/client'
import type { CableChainView } from '@/api/network'
import { useCan } from '@/auth/useCan'
import { Badge } from '@/components/atoms'

/**
 * "Ruas kotak-ke-kotak ini beneran atau cuma penyamar?"
 *
 * Dua hal yang tergambar persis sama di peta: (a) splitter bertingkat — kaki
 * splitter kotak pertama menyuapi splitter kotak berikutnya, sah dan memang
 * dipasang orang; (b) satu selubung menerus yang dipecah jadi beberapa ruas cuma
 * supaya hubungannya bisa dicatat, peninggalan zaman sebelum sambungan dicatat
 * per core. Yang kedua membuat panjang material dihitung berlebih dan simulasi
 * putus meleset — tapi tak ada satu pun tanda di layar yang membedakannya.
 *
 * Panel ini menaruh jawabannya persis di tempat orang membuka ruasnya. Tiga
 * pilihan sikap, sengaja tak seragam:
 *
 * - **Sah** cukup satu baris tenang. Kalau tiap kabel benar berbunyi hijau
 *   sekeras kabel bermasalah berbunyi kuning, keduanya sama-sama tak dibaca.
 * - **Diduga** memakai bilah kuning lengkap dengan buktinya, karena yang diminta
 *   dari operator bukan percaya, melainkan memeriksa.
 * - **Belum bisa dipastikan** ditulis apa adanya — menuduh atas kotak yang
 *   datanya memang belum pernah diisi adalah cara tercepat membuat peringatan
 *   ini diabaikan selamanya.
 *
 * Tak ada tombol "perbaiki otomatis": menyatukan dua ruas berarti membuang core
 * beserta sambungan yang menempel padanya, dan itu keputusan orang yang tahu
 * keadaan di lapangan.
 */
export function CableChainNotice({
  cableId,
  fromKind,
  toKind,
}: {
  cableId: string
  fromKind: string
  toKind: string
}) {
  const { can } = useCan()
  // Hanya ruas ODP → ODP yang punya pertanyaan ini; sisanya tak usah dibebani
  // satu panggilan pun. Izinnya izin meja sambung — jawabannya memang isi kotak.
  const relevant = fromKind === 'ODP' && toKind === 'ODP' && can('network.splice.view')
  const [chain, setChain] = useState<CableChainView | null>(null)

  useEffect(() => {
    if (!relevant) {
      setChain(null)
      return
    }
    let alive = true
    api
      .get<CableChainView>(`/api/cables/${cableId}/chain-check`)
      .then((d) => {
        if (alive) setChain(d)
      })
      .catch(() => {
        /* pemeriksaan ini tambahan; panel kabelnya tetap berguna tanpanya */
      })
    return () => {
      alive = false
    }
  }, [cableId, relevant])

  if (!chain || chain.verdict === 'NOT_CHAINED') return null

  if (chain.verdict === 'CASCADE') {
    return (
      <div className="row wrap" style={{ gap: '0.4rem', fontSize: '0.82rem' }}>
        <Badge tone="good">Splitter bertingkat</Badge>
        <span className="muted">{chain.evidence[0]}</span>
      </div>
    )
  }

  return (
    <MessageBar intent={chain.verdict === 'SUSPECT' ? 'warning' : 'info'}>
      <MessageBarBody>
        <div className="stack" style={{ gap: '0.35rem' }}>
          <strong>{chain.headline}</strong>
          {chain.evidence.map((e) => (
            <span key={e} style={{ fontSize: '0.82rem' }}>
              {e}
            </span>
          ))}
          {chain.suggestion && (
            <span className="muted" style={{ fontSize: '0.82rem' }}>
              {chain.suggestion}
            </span>
          )}
        </div>
      </MessageBarBody>
    </MessageBar>
  )
}
