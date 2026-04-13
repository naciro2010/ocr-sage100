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

export default function Modal({ open, title, message, confirmLabel = 'Confirmer', confirmColor, onConfirm, onCancel, children }: ModalProps) {
  const modalRef = useRef<HTMLDivElement>(null)
  const previousFocus = useRef<HTMLElement | null>(null)

  useEffect(() => {
    if (open) {
      previousFocus.current = document.activeElement as HTMLElement
      setTimeout(() => modalRef.current?.focus(), 50)
    } else if (previousFocus.current) {
      previousFocus.current.focus()
      previousFocus.current = null
    }
  }, [open])

  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onCancel()
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
        tabIndex={-1}
        onClick={e => e.stopPropagation()}
      >
        <h3 id="modal-title">{title}</h3>
        <p>{message}</p>
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
