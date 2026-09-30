import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import { FluentProvider, webLightTheme } from '@fluentui/react-components'
import { ToastProvider, useToast } from './ToastProvider'
import { Modal } from '@/components/molecules/Modal'

function Editor() {
  const toast = useToast()
  return <Modal title="Form terbuka" onClose={() => {}}><button onClick={() => toast.error('Gagal menyimpan perubahan')}>Simpan</button></Modal>
}

it('announces a toast outside the page hidden by an active modal', async () => {
  const user = userEvent.setup()
  render(<FluentProvider theme={webLightTheme}><ToastProvider><Editor /></ToastProvider></FluentProvider>)
  await user.click(screen.getByRole('button', { name: 'Simpan' }))
  await waitFor(() => {
    const live = document.body.querySelector<HTMLElement>(':scope > [aria-live="assertive"][data-tabster-never-hide]')
    expect(live).toBeTruthy()
    expect(live?.querySelector('span')?.innerText).toContain('Gagal menyimpan perubahan')
    expect(live?.closest('[aria-hidden="true"], [inert], [hidden]')).toBeNull()
  })
  expect(screen.getByRole('dialog', { name: 'Form terbuka' })).toBeTruthy()
})

function Feedback({ kind }: { kind: 'success' | 'error' }) {
  const toast = useToast()
  return <button onClick={() => toast[kind]('Hasil pemeriksaan')}>Periksa</button>
}

it.each(['success', 'error'] as const)('announces %s feedback once through the Fluent DOM fallback', async (kind) => {
  const user = userEvent.setup()
  render(<FluentProvider theme={webLightTheme}><ToastProvider><Feedback kind={kind} /></ToastProvider></FluentProvider>)
  await user.click(screen.getByRole('button', { name: 'Periksa' }))
  await screen.findByText('Hasil pemeriksaan', { exact: true })
  await waitFor(() => {
    // Fluent's fallback uses one assertive region when ariaNotify is unavailable.
    const live = document.body.querySelector<HTMLElement>(':scope > [aria-live="assertive"][data-tabster-never-hide]')
    expect(live?.querySelector('span')?.innerText).toContain('Hasil pemeriksaan')
    expect(live?.querySelectorAll('span')).toHaveLength(1)
  })
})
