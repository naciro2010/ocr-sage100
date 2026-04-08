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
          <button className="btn btn-primary" onClick={onConfirm} style={confirmColor ? { background: confirmColor } : undefined}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}
