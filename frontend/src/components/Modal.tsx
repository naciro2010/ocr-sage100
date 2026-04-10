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
  if (!open) return null
  return (
    <div className="modal-overlay" onClick={onCancel}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <h3>{title}</h3>
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
