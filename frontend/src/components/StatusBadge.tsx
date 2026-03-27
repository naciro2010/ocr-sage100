const STATUS_CONFIG: Record<string, { label: string; color: string }> = {
  UPLOADED: { label: 'Uploadée', color: '#6b7280' },
  OCR_IN_PROGRESS: { label: 'OCR en cours', color: '#f59e0b' },
  OCR_COMPLETED: { label: 'OCR terminé', color: '#3b82f6' },
  AI_EXTRACTION_IN_PROGRESS: { label: 'Extraction IA', color: '#f59e0b' },
  EXTRACTED: { label: 'Extraite', color: '#8b5cf6' },
  VALIDATION_FAILED: { label: 'Validation échouée', color: '#ef4444' },
  READY_FOR_SAGE: { label: 'Prête Sage', color: '#10b981' },
  SAGE_SYNCED: { label: 'Synchronisée', color: '#059669' },
  SAGE_SYNC_FAILED: { label: 'Sync échouée', color: '#ef4444' },
  ERROR: { label: 'Erreur', color: '#dc2626' },
}

export default function StatusBadge({ status }: { status: string }) {
  const config = STATUS_CONFIG[status] || { label: status, color: '#6b7280' }
  return (
    <span
      className="status-badge"
      style={{ backgroundColor: config.color + '20', color: config.color, borderColor: config.color }}
    >
      {config.label}
    </span>
  )
}
