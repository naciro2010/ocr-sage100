import { useEffect, useMemo, useState } from 'react'
import { ShieldAlert, AlertTriangle, CheckCircle2 } from 'lucide-react'
import { apiFetch, handleResponse, API_URL } from '../api/http'

const WINDOWS = [
  { days: 30, label: '30 j' },
  { days: 90, label: '90 j' },
  { days: 365, label: '1 an' },
] as const

interface RuleCorrection {
  regle: string
  total: number
  corrections: number
  falsePositives: number
  falseNegatives: number
  correctionRate: number
}

async function getRulesCorrections(days: number): Promise<RuleCorrection[]> {
  const res = await apiFetch(`${API_URL}/api/admin/rules/corrections?days=${days}`)
  return handleResponse(res)
}

export default function RulesHealthPage() {
  const [days, setDays] = useState<number>(90)
  const [rows, setRows] = useState<RuleCorrection[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setError(null)
    setRows(null)
    getRulesCorrections(days)
      .then(r => { if (!cancelled) setRows(r) })
      .catch(e => { if (!cancelled) setError(e?.message || 'Erreur') })
    return () => { cancelled = true }
  }, [days])

  const totals = useMemo(() => {
    if (!rows) return { corrections: 0, fp: 0, fn: 0 }
    return rows.reduce((acc, r) => ({
      corrections: acc.corrections + r.corrections,
      fp: acc.fp + r.falsePositives,
      fn: acc.fn + r.falseNegatives,
    }), { corrections: 0, fp: 0, fn: 0 })
  }, [rows])

  if (error) {
    return (
      <div>
        <div className="page-header"><h1><ShieldAlert size={18} /> Sante des regles</h1></div>
        <div className="alert alert-error">{error}</div>
      </div>
    )
  }

  return (
    <div>
      <div className="page-header">
        <h1><ShieldAlert size={18} /> Sante des regles</h1>
        <div className="header-actions">
          <div className="dossier-chips" role="tablist" aria-label="Periode">
            {WINDOWS.map(w => (
              <button key={w.days}
                role="tab"
                aria-selected={days === w.days}
                className={`dossier-chip ${days === w.days ? 'active' : ''}`}
                onClick={() => setDays(w.days)}>
                {w.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-icon blue"><CheckCircle2 size={16} /></div>
          <div className="stat-value">{totals.corrections.toLocaleString('fr-FR')}</div>
          <div className="stat-label">Corrections manuelles</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon amber"><AlertTriangle size={16} /></div>
          <div className="stat-value">{totals.fp.toLocaleString('fr-FR')}</div>
          <div className="stat-label">Faux positifs (NOK corrige en OK)</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon red"><AlertTriangle size={16} /></div>
          <div className="stat-value">{totals.fn.toLocaleString('fr-FR')}</div>
          <div className="stat-label">Faux negatifs (OK corrige en NOK)</div>
        </div>
      </div>

      <div className="card">
        <h2>Corrections par regle</h2>
        {rows === null ? (
          <div style={{ color: 'var(--ink-40)', fontSize: 13 }}>Chargement...</div>
        ) : rows.length === 0 ? (
          <div style={{ color: 'var(--ink-40)', fontSize: 13 }}>
            Aucune correction manuelle sur la periode. Les verdicts automatiques sont acceptes tels quels.
          </div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Regle</th>
                <th>Evaluations</th>
                <th>Corrections</th>
                <th>Faux positifs</th>
                <th>Faux negatifs</th>
                <th>Taux correction</th>
              </tr>
            </thead>
            <tbody>
              {rows.map(r => (
                <tr key={r.regle}>
                  <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{r.regle}</td>
                  <td className="cell-mono">{r.total.toLocaleString('fr-FR')}</td>
                  <td className="cell-mono">{r.corrections.toLocaleString('fr-FR')}</td>
                  <td className="cell-mono" style={{ color: r.falsePositives > 0 ? 'var(--warning, #f59e0b)' : undefined }}>
                    {r.falsePositives.toLocaleString('fr-FR')}
                  </td>
                  <td className="cell-mono" style={{ color: r.falseNegatives > 0 ? 'var(--danger, #ef4444)' : undefined, fontWeight: r.falseNegatives > 0 ? 600 : undefined }}>
                    {r.falseNegatives.toLocaleString('fr-FR')}
                  </td>
                  <td className="cell-mono" style={{ fontWeight: 600 }}>
                    {(r.correctionRate * 100).toFixed(1)} %
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>

      <div className="card" style={{ marginTop: 12, padding: '10px 14px', fontSize: 11, color: 'var(--ink-40)' }}>
        Les regles avec beaucoup de <strong>faux positifs</strong> (NON_CONFORME corrige en CONFORME)
        crient a tort : cible de retuning ou de regles CUSTOM plus fines. Les <strong>faux negatifs</strong>
        sont critiques : la regle laisse passer un probleme. Priorite maximale.
      </div>
    </div>
  )
}
