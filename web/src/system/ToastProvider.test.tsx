import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { ToastProvider, useToast } from './ToastProvider'

function ToastControls() {
  const toast = useToast()
  return (
    <>
      <button type="button" onClick={() => toast.success('Polling selesai')}>
        Success
      </button>
      <button type="button" onClick={() => toast.error('Polling gagal')}>
        Error
      </button>
    </>
  )
}

describe('ToastProvider accessibility', () => {
  it('announces success once through an atomic status region', async () => {
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <ToastControls />
      </ToastProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'Success' }))

    const announcement = screen.getByRole('status')
    expect(announcement.textContent).toBe('Polling selesai')
    expect(announcement.getAttribute('aria-atomic')).toBe('true')
    expect(screen.getAllByRole('status')).toHaveLength(1)
  })

  it('announces errors once through an atomic alert region', async () => {
    const user = userEvent.setup()
    render(
      <ToastProvider>
        <ToastControls />
      </ToastProvider>,
    )

    await user.click(screen.getByRole('button', { name: 'Error' }))

    const announcement = screen.getByRole('alert')
    expect(announcement.textContent).toBe('Polling gagal')
    expect(announcement.getAttribute('aria-atomic')).toBe('true')
    expect(screen.getAllByRole('alert')).toHaveLength(1)
  })
})
