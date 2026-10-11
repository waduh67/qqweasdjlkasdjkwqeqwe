import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { expect, it, vi } from 'vitest'
import { PasswordPanel } from './PasswordPanel'

function setup(own = false, onSave = vi.fn().mockResolvedValue(undefined)) {
  const onSaved = vi.fn(), onClose = vi.fn()
  render(<MemoryRouter><PasswordPanel title={own ? 'Ganti password' : 'Reset password'} email="rina@example.test" own={own}
    onSave={onSave} onSaved={onSaved} onClose={onClose} /></MemoryRouter>)
  return { user: userEvent.setup(), onSave, onSaved, onClose }
}
it('validates confirmation and keeps passwords inside masked fields', async () => {
  const { user, onSave, onSaved } = setup()
  await user.type(screen.getByLabelText(/^Password baru/), 'new-password-123')
  await user.type(screen.getByLabelText(/^Konfirmasi password/), 'different-password')
  await user.click(screen.getByRole('button', { name: 'Simpan' }))
  expect(screen.getByRole('alert').textContent).toContain('Konfirmasi password belum sesuai.')
  expect(onSave).not.toHaveBeenCalled()
  expect(screen.getByLabelText(/^Password baru/).getAttribute('type')).toBe('password')
  expect(screen.queryByText('new-password-123')).toBeNull()
  await user.clear(screen.getByLabelText(/^Konfirmasi password/))
  await user.type(screen.getByLabelText(/^Konfirmasi password/), 'new-password-123')
  await user.click(screen.getByRole('button', { name: 'Simpan' }))
  await waitFor(() => expect(onSaved).toHaveBeenCalledOnce())
  expect(onSave).toHaveBeenCalledWith('new-password-123', '')
})
it('retains a rejected password draft and guards closing it', async () => {
  const { user, onSave, onSaved, onClose } = setup(false, vi.fn().mockRejectedValue(new Error('Simpan belum berhasil.')))
  await user.type(screen.getByLabelText(/^Password baru/), 'new-password-123')
  await user.type(screen.getByLabelText(/^Konfirmasi password/), 'new-password-123')
  await user.click(screen.getByRole('button', { name: 'Simpan' }))
  await screen.findByText('Simpan belum berhasil.')
  expect(onSaved).not.toHaveBeenCalled()
  expect(onSave).toHaveBeenCalledOnce()
  expect(screen.getByLabelText(/^Password baru/)).toHaveProperty('value', 'new-password-123')
  await user.click(screen.getByRole('button', { name: 'Batal' }))
  await screen.findByRole('dialog', { name: 'Buang perubahan?' })
  expect(onClose).not.toHaveBeenCalled()
})
it('requires the current password for self service', async () => {
  const { user, onSave } = setup(true)
  await user.type(screen.getByLabelText(/^Password baru/), 'new-password-123')
  await user.type(screen.getByLabelText(/^Konfirmasi password/), 'new-password-123')
  await user.click(screen.getByRole('button', { name: 'Simpan' }))
  expect(onSave).not.toHaveBeenCalled()
  await user.type(screen.getByLabelText(/^Password saat ini/), 'old-password-123')
  await user.click(screen.getByRole('button', { name: 'Simpan' }))
  await waitFor(() => expect(onSave).toHaveBeenCalledWith('new-password-123', 'old-password-123'))
})
