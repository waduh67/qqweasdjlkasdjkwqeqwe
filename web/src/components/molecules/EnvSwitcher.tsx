import {
  Menu,
  MenuButton,
  MenuItemRadio,
  MenuList,
  MenuPopover,
  MenuTrigger,
  Text,
} from '@fluentui/react-components'
import { useNavigate } from 'react-router-dom'
import { IconChevronsUpDown, IconCheck } from '@/components/atoms/icons'

/**
 * Switcher konteks Platform ↔ Tenant di puncak sidebar — bergaya pemilih direktori/
 * langganan Azure Portal (kotak berbingkai: titik status + nama konteks + chevron
 * naik-turun, membuka dropdown pilihan). Hanya dipakai platform admin, menggantikan
 * menu "Tampilan Tenant/Platform" lama.
 *
 * Perpindahan lewat `useNavigate` eksplisit (bukan `<NavLink>`) supaya andal: memilih
 * opsi selalu menavigasi ke shell terkait lalu menutup menu — tak bergantung pada
 * pencocokan rute aktif. Server tetap otoritatif atas izin.
 */
const OPTIONS: { key: 'platform' | 'tenant'; to: string; name: string; desc: string }[] = [
  { key: 'platform', to: '/platform', name: 'Platform', desc: 'Konsol super-admin SaaS' },
  { key: 'tenant', to: '/', name: 'Tenant', desc: 'Operasi ISP sehari-hari' },
]

export function EnvSwitcher({ current }: { current: 'platform' | 'tenant' }) {
  const navigate = useNavigate()
  const active = OPTIONS.find((o) => o.key === current) ?? OPTIONS[0]

  const choose = (to: string, key: 'platform' | 'tenant') => {
    // Sudah di konteks ini → tak perlu navigasi (hindari reload rute yang sama).
    if (key !== current) navigate(to)
  }

  return (
    <div className="env-switch">
      <Menu
        checkedValues={{ 'env-switcher': [current] }}
        onCheckedValueChange={(_, data) => {
          const selected = OPTIONS.find((option) => option.key === data.checkedItems[0])
          if (selected) choose(selected.to, selected.key)
        }}
      >
        <MenuTrigger disableButtonEnhancement>
          <MenuButton
            className="env-switch-btn"
            title={`Konteks: ${active.name}`}
            icon={null}
          >
            <span className={`env-dot ${current}`} aria-hidden />
            <span className="env-switch-info">
              <Text as="span" className="env-switch-name" size={300}>{active.name}</Text>
              <Text as="span" className="env-switch-cap" size={100}>Ganti konteks</Text>
            </span>
            <IconChevronsUpDown size={15} />
          </MenuButton>
        </MenuTrigger>
        <MenuPopover className="env-menu">
          <MenuList>
            {OPTIONS.map((o) => (
              <MenuItemRadio
                key={o.key}
                className={o.key === current ? 'current' : undefined}
                name="env-switcher"
                value={o.key}
              >
                <span className={`env-dot ${o.key}`} aria-hidden />
                <span className="env-switch-info">
                  <Text as="span" className="env-switch-name" size={300}>{o.name}</Text>
                  <Text as="span" className="env-switch-cap" size={100}>{o.desc}</Text>
                </span>
                {o.key === current && <IconCheck size={15} className="env-check" />}
              </MenuItemRadio>
            ))}
          </MenuList>
        </MenuPopover>
      </Menu>
    </div>
  )
}
