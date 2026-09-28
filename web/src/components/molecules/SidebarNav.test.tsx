import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { IconWifi } from '@/components/atoms/icons'
import { HOTSPOT_VIEW_PERMISSIONS } from '@/api/hotspot'
import { SidebarNav } from './SidebarNav'

const groups = [
  {
    label: 'Layanan Pelanggan',
    items: [
      {
        to: '/hotspot',
        label: 'Hotspot & Voucher',
        permission: HOTSPOT_VIEW_PERMISSIONS,
        icon: IconWifi,
      },
    ],
  },
]

describe('SidebarNav hotspot', () => {
  it('menampilkan menu untuk pengguna yang punya kebijakan view hotspot', () => {
    localStorage.removeItem('hotspot-authorized.v3.closed')

    render(
      <MemoryRouter>
        <SidebarNav
          groups={groups}
          can={(permission) => permission === 'hotspot.voucher.view'}
          storageKey="hotspot-authorized"
        />
      </MemoryRouter>,
    )

    expect(screen.getByRole('link', { name: 'Hotspot & Voucher' }).getAttribute('href')).toBe('/hotspot')
  })

  it('menyembunyikan menu tanpa kebijakan view hotspot', () => {
    render(
      <MemoryRouter>
        <SidebarNav groups={groups} can={() => false} storageKey="hotspot-denied" />
      </MemoryRouter>,
    )

    expect(screen.queryByRole('link', { name: 'Hotspot & Voucher' })).toBeNull()
  })
})

 describe('SidebarNav discovery', () => {
  it('restores an explicitly collapsed active section after refresh', () => {
    localStorage.setItem('nav-active.v3.closed', JSON.stringify(['Layanan Pelanggan']))
    render(<MemoryRouter initialEntries={['/hotspot/vouchers']}><SidebarNav groups={groups} can={() => true} storageKey="nav-active" /></MemoryRouter>)
    expect(screen.getByRole('button', { name: 'Layanan Pelanggan' }).getAttribute('aria-expanded')).toBe('false')
    expect(screen.getByRole('link', { name: 'Hotspot & Voucher' }).getAttribute('aria-current')).toBe('page')
  })
})

it('keeps multiple groups open after navigation and restores their saved state on remount', () => {
  const key = 'nav-independent'
  localStorage.removeItem(`${key}.v3.closed`)
  const items = ['Pelanggan', 'Gudang', 'Jaringan'].map((label, index) => ({ label, items: [{ to: `/group-${index}`, label: `Halaman ${index}`, permission: null, icon: IconWifi }] }))
  const show = () => render(<MemoryRouter initialEntries={['/group-0']}><SidebarNav compact groups={items} can={() => true} storageKey={key} /></MemoryRouter>)
  let view = show()
  fireEvent.click(screen.getByRole('button', { name: 'Gudang' }))
  fireEvent.click(screen.getByRole('button', { name: 'Jaringan' }))
  fireEvent.click(screen.getByRole('link', { name: 'Halaman 2' }))
  for (const name of ['Pelanggan', 'Gudang', 'Jaringan']) expect(screen.getByRole('button', { name }).getAttribute('aria-expanded')).toBe('true')
  expect(JSON.parse(localStorage.getItem(`${key}.v3.closed`)!)).toEqual([])
  view.unmount(); view = show()
  for (const name of ['Pelanggan', 'Gudang', 'Jaringan']) expect(screen.getByRole('button', { name }).getAttribute('aria-expanded')).toBe('true')
  fireEvent.click(screen.getByRole('button', { name: 'Gudang' }))
  view.unmount(); show()
  expect(screen.getByRole('button', { name: 'Gudang' }).getAttribute('aria-expanded')).toBe('false')
  expect(screen.getByRole('button', { name: 'Jaringan' }).getAttribute('aria-expanded')).toBe('true')
})
