import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import Modal from './Modal'

describe('Modal', () => {
  it("ne rend rien quand open=false", () => {
    const { container } = render(
      <Modal open={false} title="T" message="M" onConfirm={() => {}} onCancel={() => {}} />
    )
    expect(container.firstChild).toBeNull()
  })

  it('Escape declenche onCancel', async () => {
    const onCancel = vi.fn()
    render(<Modal open={true} title="T" message="M" onConfirm={() => {}} onCancel={onCancel} />)
    fireEvent.keyDown(window, { key: 'Escape' })
    expect(onCancel).toHaveBeenCalled()
  })

  it('focus trap : Tab cycle sur les boutons', async () => {
    const user = userEvent.setup()
    render(
      <Modal open={true} title="T" message="M" confirmLabel="OK"
        onConfirm={() => {}} onCancel={() => {}} />
    )
    // Le 1er focusable est "Annuler"
    const cancel = screen.getByRole('button', { name: /Annuler/i })
    const ok = screen.getByRole('button', { name: 'OK' })
    await waitFor(() => expect(cancel).toHaveFocus())

    // Tab depuis Annuler -> OK
    await user.tab()
    expect(ok).toHaveFocus()

    // Tab depuis OK (dernier) -> trap -> retour Annuler
    await user.tab()
    expect(cancel).toHaveFocus()

    // Shift+Tab depuis Annuler (premier) -> trap -> OK (dernier)
    await user.tab({ shift: true })
    expect(ok).toHaveFocus()
  })

  it('aria-modal + aria-describedby presents (WCAG)', () => {
    render(<Modal open={true} title="T" message="Detail" onConfirm={() => {}} onCancel={() => {}} />)
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(dialog).toHaveAttribute('aria-labelledby', 'modal-title')
    expect(dialog).toHaveAttribute('aria-describedby', 'modal-message')
  })
})
