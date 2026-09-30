import { StrictMode, useState } from 'react'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
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
    await waitFor(() => expect(launcher.closest('main')?.closest('[aria-hidden="true"]')).not.toBeNull())
    await user.click(await screen.findByRole('button', { name: 'Selesai' }))
    await waitFor(() => expect(document.activeElement).toBe(launcher))
    expect(screen.getByRole('main', { name: 'Pengaturan' })).toBeTruthy()
  })
  it('returns focus to the page when saving disables the still-connected launcher', async () => {
    render(<Example disableOnClose />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Tinjau perubahan' }))
    await user.click(await screen.findByRole('button', { name: 'Selesai' }))
    await waitFor(() => expect(document.activeElement).toBe(screen.getByRole('main', { name: 'Pengaturan' })))
    expect(screen.getByRole('main').hasAttribute('tabindex')).toBe(false)
  })
  it('keeps the active dialog accessible through StrictMode effect replay', async () => {
    render(<StrictMode><Example /></StrictMode>)
    const user = userEvent.setup()
    const launcher = screen.getByRole('button', { name: 'Tinjau perubahan' })
    await user.click(launcher)
    await waitFor(() => expect(launcher.closest('main')?.closest('[aria-hidden="true"]')).not.toBeNull())
    expect(screen.getByRole('dialog', { name: 'Tinjau perubahan' })).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Selesai' }))
    await waitFor(() => expect(screen.getByRole('main', { name: 'Pengaturan' })).toBeTruthy())
  })

  it('restores an unlabelled page when the dialog was opened without a focused launcher', async () => {
    function Page() {
      const [open, setOpen] = useState(false)
      return <div><button onClick={() => setOpen(true)}>Buka</button>{open && <Modal title="Dokumen" onClose={() => setOpen(false)}><button onClick={() => setOpen(false)}>Simpan</button></Modal>}</div>
    }
    const { container } = render(<Page />)
    fireEvent.click(screen.getByRole('button', { name: 'Buka' }))
    await waitFor(() => expect(container.closest('[aria-hidden="true"]')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: 'Simpan' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Buka' })).toBeTruthy())
    expect(document.activeElement?.hasAttribute('data-tabster-dummy')).toBe(false)
  })

})
