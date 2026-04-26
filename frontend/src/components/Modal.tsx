import { useEffect, useRef } from 'react'

interface ModalProps {
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  confirmColor?: string
  onConfirm: () => void
  onCancel: () => void
  children?: React.ReactNode
}

const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]),' +
  ' textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

export default function Modal({ open, title, message, confirmLabel = 'Confirmer', confirmColor, onConfirm, onCancel, children }: ModalProps) {
  const modalRef = useRef<HTMLDivElement>(null)
  const previousFocus = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (open) {
      previousFocus.current = document.activeElement as HTMLElement
      // On donne le focus au 1er element focusable de la modal (au lieu du
      // conteneur lui-meme), pour qu'un screen reader annonce "bouton Annuler"
      // plutot que silence.
      setTimeout(() => {
        const focusables = modalRef.current?.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)
        const first = focusables && focusables[0]
        if (first) first.focus()
        else modalRef.current?.focus()
      }, 50)
    } else if (previousFocus.current) {
      previousFocus.current.focus()
      previousFocus.current = null
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onCancel()
        return
      }
      // Focus trap : Tab reste dans la modal. Sans ce trap, Tab fait
      // sortir vers la page derriere — anti-pattern WCAG 2.1.2.
      if (e.key !== 'Tab') return
      const root = modalRef.current
      if (!root) return
      // Pas de filtre sur `offsetParent` : il est null en jsdom (pas de layout)
      // et casserait le trap dans les tests. Le `disabled` est deja exclu par
      // le selecteur CSS.
      const focusables = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
      if (focusables.length === 0) return
      const first = focusables[0]
      const last = focusables[focusables.length - 1]
      const active = document.activeElement as HTMLElement | null
      if (e.shiftKey && active === first) {
        e.preventDefault()
        last.focus()
      } else if (!e.shiftKey && active === last) {
        e.preventDefault()
        first.focus()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open, onCancel])

  if (!open) return null
  return (
    <div className="modal-overlay" onClick={onCancel} role="presentation">
      <div
        ref={modalRef}
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-labelledby="modal-title"
        aria-describedby="modal-message"
        tabIndex={-1}
        onClick={e => e.stopPropagation()}
      >
        <h3 id="modal-title">{title}</h3>
        <p id="modal-message">{message}</p>
        {children}
        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={onCancel}>Annuler</button>
          <button
            className="btn"
            onClick={onConfirm}
            style={{
              background: confirmColor || 'var(--accent)',
              color: '#fff',
              border: 'none',
              boxShadow: `0 2px 8px ${confirmColor ? 'rgba(239,68,68,0.25)' : 'rgba(16,185,129,0.25)'}`,
            }}
          >
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
