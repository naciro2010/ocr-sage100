import { memo, useEffect, useState } from 'react'
import { compareDocuments, type CompareRow } from '../../api/dossierApi'
import { AlertTriangle, CheckCircle2 } from 'lucide-react'

interface Props {
  dossierId: string
}

const COLUMNS: Array<{ key: string; label: string }> = [
  { key: 'FACTURE', label: 'Facture' },
  { key: 'BON_COMMANDE', label: 'Bon de commande' },
  { key: 'CONTRAT_AVENANT', label: 'Contrat / Avenant' },
  { key: 'ORDRE_PAIEMENT', label: 'Ordre de paiement' },
]

/**
 * Reads /api/dossiers/{id}/compare and renders one row per logical field with
 * a value column per source. Conflict cells get the warning treatment so users
 * spot inconsistencies in <1s instead of scrolling each document.
 */
export default memo(function FieldDiffMatrix({ dossierId }: Props) {
  const [rows, setRows] = useState<CompareRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setLoading(true)
    compareDocuments(dossierId)
      .then(res => { if (!cancelled) setRows(res.rows) })
      .catch(e => { if (!cancelled) setError(e.message || 'Erreur') })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [dossierId])

  if (loading) return <div className="card"><p style={{ color: 'var(--ink-30)', fontSize: 13 }}>Chargement…</p></div>
  if (error) return <div className="card"><p style={{ color: 'var(--danger)', fontSize: 13 }}>{error}</p></div>
  if (!rows.length) return null

  const conflictsCount = rows.filter(r => r.conflict).length

  return (
    <div className="card">
      <h2 style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        Matrice de coherence
        {conflictsCount > 0 ? (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: 'var(--danger)', fontSize: 12 }}>
            <AlertTriangle size={14} /> {conflictsCount} conflit(s)
          </span>
        ) : (
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: 'var(--success)', fontSize: 12 }}>
            <CheckCircle2 size={14} /> coherent
          </span>
        )}
      </h2>
      <div style={{ overflowX: 'auto' }}>
        <table className="kv-table" style={{ width: '100%', minWidth: 720 }}>
          <thead>
            <tr>
              <th style={{ textAlign: 'left', fontSize: 12, color: 'var(--ink-30)', padding: '6px 8px' }}>Champ</th>
              {COLUMNS.map(c => (
                <th key={c.key} style={{ textAlign: 'left', fontSize: 12, color: 'var(--ink-30)', padding: '6px 8px' }}>{c.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map(r => (
              <tr key={r.label} style={r.conflict ? { background: 'var(--warning-bg, #fff5e6)' } : undefined}>
                <td style={{ fontWeight: 500, padding: '6px 8px' }}>{r.label}</td>
                {COLUMNS.map(c => (
                  <td key={c.key} style={{
                    padding: '6px 8px',
                    fontFamily: 'monospace',
                    fontSize: 12,
                    color: r.values[c.key] ? 'var(--ink-90)' : 'var(--ink-30)'
                  }}>
                    {r.values[c.key] ?? '—'}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
})
