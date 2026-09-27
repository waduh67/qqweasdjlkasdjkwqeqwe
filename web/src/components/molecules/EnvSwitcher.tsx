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
import { Apps16Filled, Building16Filled } from '@fluentui/react-icons'

/**
 * Switcher konteks Platform ↔ Tenant di header global. Menu radio Fluent memberi
 * penanda konteks aktif dan navigasi keyboard; hanya tampil untuk platform admin.
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
            <span className="env-switch-info">
              <Text as="span" className="env-switch-name" size={300}>{active.name}</Text>
            </span>
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
                icon={o.key === 'platform' ? <Apps16Filled /> : <Building16Filled />}
              >
                <span className="env-option-text">
                  <Text as="span" className="env-switch-name" size={300}>{o.name}</Text>
                  <Text as="span" className="env-switch-cap" size={100}>{o.desc}</Text>
                </span>
              </MenuItemRadio>
            ))}
          </MenuList>
        </MenuPopover>
      </Menu>
    </div>
  )
}
