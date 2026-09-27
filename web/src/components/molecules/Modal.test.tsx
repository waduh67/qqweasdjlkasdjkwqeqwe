import { useState } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { Modal } from './Modal'

function Example({ disableOnClose = false }: { disableOnClose?: boolean }) {
  const [open, setOpen] = useState(false)
  const [disabled, setDisabled] = useState(false)
  return <main aria-label="Pengaturan">
    <button disabled={disabled} onClick={() => setOpen(true)}>Tinjau perubahan</button>
    {open && <Modal title="Tinjau perubahan" onClose={() => setOpen(false)} footer={<button onClick={() => { setOpen(false); setDisabled(disableOnClose) }}>Selesai</button>}>Perubahan siap disimpan.</Modal>}
  </main>
}

describe('imperative Fluent dialog focus', () => {
  it('returns focus to the launcher when dismissed', async () => {
    render(<Example />)
    const user = userEvent.setup()
    const launcher = screen.getByRole('button', { name: 'Tinjau perubahan' })
    await user.click(launcher)
    await user.click(await screen.findByRole('button', { name: 'Selesai' }))
    await waitFor(() => expect(document.activeElement).toBe(launcher))
  })
  it('returns focus to the page when saving disables the still-connected launcher', async () => {
    render(<Example disableOnClose />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Tinjau perubahan' }))
    await user.click(await screen.findByRole('button', { name: 'Selesai' }))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('main', { name: 'Pengaturan' })))
    expect(screen.getByRole('main').hasAttribute('tabindex')).toBe(false)
  })
})
