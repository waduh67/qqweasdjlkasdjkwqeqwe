import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { CommandBar } from './CommandBar'

describe('CommandBar', () => {
  it('menempatkan aksi utama sebelum aksi lain dan membuat pemisah sebagai separator', () => {
    render(
      <CommandBar
        primary={{ key: 'create', label: 'Tambah OLT', onClick: vi.fn() }}
        actions={[
          { key: 'delete', label: 'Hapus', onClick: vi.fn(), disabled: true },
          { key: 'refresh', label: 'Segarkan', onClick: vi.fn(), dividerBefore: true },
        ]}
      />,
    )

    const toolbar = screen.getByRole('toolbar', { name: 'Aksi' })
    expect(Array.from(toolbar.querySelectorAll('button')).map((button) => button.textContent)).toEqual([
      'Tambah OLT',
      'Hapus',
      'Segarkan',
    ])
    expect(screen.getByRole('button', { name: 'Hapus' }).hasAttribute('disabled')).toBe(true)

    const dividers = toolbar.querySelectorAll('.cmd-divider')
    expect(dividers).toHaveLength(2)
    dividers.forEach((divider) => {
      expect(divider.getAttribute('role')).toBe('separator')
      expect(divider.getAttribute('aria-orientation')).toBe('vertical')
    })
  })
})
