import type { ReactNode } from 'react'
import { act, renderHook } from '@testing-library/react'
import { MemoryRouter, useNavigate } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { setViewportMatches } from '@/test/setup'
import { useAppShellNav } from './useAppShellNav'

/**
 * Satu tombol dengan dua arti (ciutkan sidebar / buka laci) gampang tertukar saat
 * kode disentuh lagi — dan tertukarnya baru ketahuan di ponsel, tempat paling jarang
 * dibuka saat mengembangkan.
 */
function wrapper({ children, path = '/' }: { children: ReactNode; path?: string }) {
  return <MemoryRouter initialEntries={[path]}>{children}</MemoryRouter>
}

describe('useAppShellNav di layar lebar', () => {
  it('menciutkan sidebar, bukan membuka laci', () => {
    const { result } = renderHook(() => useAppShellNav(), { wrapper })

    act(() => result.current.toggleNav())

    expect(result.current.collapsed).toBe(true)
    expect(result.current.navOpen).toBe(false)
    expect(result.current.shellClass).toBe('app sidebar-collapsed')
  })

  // Lebar sidebar adalah preferensi kerja yang harus bertahan; laci ponsel tidak.
  it('mengingat pilihan ciut antar sesi lewat localStorage', () => {
    const first = renderHook(() => useAppShellNav(), { wrapper })
    act(() => first.result.current.toggleNav())
    expect(localStorage.getItem('ftth.sidebarCollapsed')).toBe('1')

    const second = renderHook(() => useAppShellNav(), { wrapper })
    expect(second.result.current.collapsed).toBe(true)

    act(() => second.result.current.toggleNav())
    expect(localStorage.getItem('ftth.sidebarCollapsed')).toBe('0')
  })
})

describe('useAppShellNav di lebar ponsel', () => {
  it('membuka laci nav tanpa mengubah keadaan ciut', () => {
    setViewportMatches(true)
    const { result } = renderHook(() => useAppShellNav(), { wrapper })

    act(() => result.current.toggleNav())

    expect(result.current.navOpen).toBe(true)
    expect(result.current.collapsed).toBe(false)
    expect(result.current.shellClass).toBe('app nav-open')
    // Laci yang sudah menutupi layar saat aplikasi dibuka adalah gangguan, bukan preferensi.
    expect(localStorage.getItem('ftth.sidebarCollapsed')).toBeNull()
  })

  it('menutup laci lewat closeNav (latar gelap disentuh)', () => {
    setViewportMatches(true)
    const { result } = renderHook(() => useAppShellNav(), { wrapper })

    act(() => result.current.toggleNav())
    act(() => result.current.closeNav())

    expect(result.current.navOpen).toBe(false)
  })

  // Kalau laci tak ikut menutup, menu tetap menutupi halaman yang baru saja dipilih dan
  // pengguna mengira tapnya tak terjadi apa-apa.
  it('menutup laci saat pindah halaman', () => {
    setViewportMatches(true)
    const { result } = renderHook(() => ({ nav: useAppShellNav(), navigate: useNavigate() }), {
      wrapper,
    })

    act(() => result.current.nav.toggleNav())
    expect(result.current.nav.navOpen).toBe(true)

    act(() => result.current.navigate('/customers'))
    expect(result.current.nav.navOpen).toBe(false)
  })
})
