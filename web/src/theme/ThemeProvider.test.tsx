import { useState } from 'react'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, useNavigate } from 'react-router-dom'
import { expect, it } from 'vitest'
import { Modal } from '@/components/molecules/Modal'
import { ThemeProvider, useTheme } from './ThemeProvider'
import { azureLight } from './azureTheme'
import { gudangDark, gudangLight } from './gudangTheme'

function Surface() {
  const navigate = useNavigate(), { toggle } = useTheme()
  const [open, setOpen] = useState(false)
  return <main>
    <button onClick={() => navigate('/portal/login')}>Portal pelanggan</button>
    <button onClick={() => navigate('/platform/tenants')}>Platform</button>
    <button onClick={toggle}>Ganti tema</button>
    <button onClick={() => setOpen(true)}>Buka formulir</button>
    {open && <Modal title="Formulir" onClose={() => setOpen(false)}>Isian formulir</Modal>}
  </main>
}

it.each(['/warehouse/counts', '/customers', '/kpi', '/platform/tenants', '/portal-tools'])(
  'uses the Gudang theme on internal route %s', path => {
    localStorage.setItem('ftth.theme', 'light')
    const { container } = render(<MemoryRouter initialEntries={[path]}><ThemeProvider><Surface /></ThemeProvider></MemoryRouter>)
    expect(document.documentElement.dataset.console).toBe('internal')
    expect(getComputedStyle(container.querySelector('.internal-theme') ?? document.body).getPropertyValue('--colorBrandBackground')).toBe(gudangLight.colorBrandBackground)
  },
)

it.each(['/login', '/signup', '/portal', '/portal/login', '/hotspot-portal/login', '/bayar/invoice', '/paid', '/failed', '/expired'])(
  'preserves the public theme on %s', path => {
    localStorage.setItem('ftth.theme', 'light')
    const { container } = render(<MemoryRouter initialEntries={[path]}><ThemeProvider><Surface /></ThemeProvider></MemoryRouter>)
    expect(document.documentElement.dataset.console).toBe('public')
    expect(container.querySelector('.internal-theme')).toBeNull()
    expect(getComputedStyle(container.querySelector('.fui-FluentProvider') ?? document.body).getPropertyValue('--colorBrandBackground')).toBe(azureLight.colorBrandBackground)
  },
)

it('changes route and portal styling together while keeping the saved theme choice', () => {
  localStorage.setItem('ftth.theme', 'light')
  const { container, unmount } = render(<MemoryRouter initialEntries={['/customers']}><ThemeProvider><Surface /></ThemeProvider></MemoryRouter>)
  fireEvent.click(screen.getByRole('button', { name: 'Ganti tema' }))
  expect(localStorage.getItem('ftth.theme')).toBe('dark')
  expect(getComputedStyle(container.querySelector('.internal-theme') ?? document.body).getPropertyValue('--colorBrandBackground')).toBe(gudangDark.colorBrandBackground)
  fireEvent.click(screen.getByRole('button', { name: 'Buka formulir' }))
  expect(screen.getByRole('dialog', { name: 'Formulir' }).classList.contains('operational-dialog')).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Tutup' }))
  fireEvent.click(screen.getByRole('button', { name: 'Portal pelanggan' }))
  expect(document.documentElement.dataset.console).toBe('public')
  fireEvent.click(screen.getByRole('button', { name: 'Buka formulir' }))
  expect(screen.getByRole('dialog', { name: 'Formulir' }).classList.contains('operational-dialog')).toBe(false)
  fireEvent.click(screen.getByRole('button', { name: 'Tutup' }))
  fireEvent.click(screen.getByRole('button', { name: 'Platform' }))
  expect(document.documentElement.dataset.console).toBe('internal')
  expect(document.documentElement.dataset.theme).toBe('dark')
  unmount()
  expect(document.documentElement.hasAttribute('data-console')).toBe(false)
})
