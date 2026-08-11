import { JOINT_BOX_COLOR, ODF_COLOR, OLT_COLOR } from '@/map/mapStyle'

/**
 * Kartu legenda kiri-bawah. `hidden` = kelompok lapisan yang sedang dimatikan dari
 * laci setelan; barisnya ikut hilang, sebab menjelaskan warna yang tak ada di layar
 * cuma menambah yang harus dibaca tanpa menambah yang bisa dilihat.
 */
export function Legend({ hidden }: { hidden: Set<string> }) {
  const items = (
    [
      ['site', '#b47cff', 'Site/POP'],
      ['olt', OLT_COLOR, 'OLT'],
      ['odf', ODF_COLOR, 'ODF'],
      ['odc', '#22d3ee', 'ODC'],
      ['odp', '#fbbf24', 'ODP'],
      ['joint_box', JOINT_BOX_COLOR, 'Joint box'],
      ['customer', '#34d399', 'Pelanggan online'],
      ['customer', '#ff5470', 'ONU mati'],
      ['customer', '#8b95a7', 'Belum terpantau'],
    ] as Array<[string, string, string]>
  )
    .filter(([group]) => !hidden.has(group))
    .map(([, color, label]) => [color, label] as [string, string])
  return (
    <div className="row" style={{ flexWrap: 'wrap', gap: '0.75rem' }}>
      {items.map(([color, label]) => (
        <span key={label} className="row" style={{ gap: '0.35rem', alignItems: 'center' }}>
          <span style={{ width: 10, height: 10, borderRadius: '50%', background: color, display: 'inline-block' }} />
          <span className="muted" style={{ fontSize: '0.85rem' }}>
            {label}
          </span>
        </span>
      ))}
    </div>
  )
}

/** Skala warna heatmap utilisasi port: gradasi hijau (lengang) → merah (penuh). */
export function HeatmapLegend() {
  return (
    <span className="row" style={{ gap: '0.4rem', alignItems: 'center' }}>
      <span className="muted" style={{ fontSize: '0.85rem' }}>
        Utilisasi port
      </span>
      <span className="muted" style={{ fontSize: '0.75rem' }}>
        0%
      </span>
      <span
        style={{
          width: 96,
          height: 10,
          borderRadius: 6,
          display: 'inline-block',
          background: 'linear-gradient(90deg,#22c55e,#eab308,#f97316,#ef4444)',
        }}
      />
      <span className="muted" style={{ fontSize: '0.75rem' }}>
        100%
      </span>
    </span>
  )
}
