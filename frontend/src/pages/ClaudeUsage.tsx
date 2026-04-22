import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  Activity, ArrowRight, BarChart3, Banknote, ChevronsRight, Cpu,
  Loader2, PhoneCall, Zap, AlertTriangle
} from 'lucide-react'
import {
  getClaudeSummary, getClaudeDaily, getClaudeTopDossiers, getClaudeByModel,
  estimateCostUsd, loadPricing, CLAUDE_PRICING,
  type ClaudeUsageSummary, type ClaudeUsageDay,
  type ClaudeUsageTopDossier, type ClaudeUsageByModel,
} from '../api/adminApi'

const WINDOWS = [
  { days: 7, label: '7 j' },
  { days: 30, label: '30 j' },
  { days: 90, label: '90 j' },
] as const

function fmtInt(n: number): string {
  return n.toLocaleString('fr-FR')
}

function fmtUsd(n: number): string {
  if (n >= 100) return `$${n.toFixed(0)}`
  if (n >= 1) return `$${n.toFixed(2)}`
  return `$${n.toFixed(3)}`
}

function fmtTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)} M`
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)} k`
  return String(n)
}

function SkeletonGrid() {
  return (
    <div className="skeleton">
      <div className="skeleton-bar h-lg w-40" />
      <div className="skeleton-grid">
        <div className="skeleton-grid-item" />
        <div className="skeleton-grid-item" />
        <div className="skeleton-grid-item" />
        <div className="skeleton-grid-item" />
      </div>
      <div className="skeleton-card" style={{ height: 220 }} />
    </div>
  )
}

export default function ClaudeUsagePage() {
  const [days, setDays] = useState<number>(30)
  const [summary, setSummary] = useState<ClaudeUsageSummary | null>(null)
  const [daily, setDaily] = useState<ClaudeUsageDay[] | null>(null)
  const [top, setTop] = useState<ClaudeUsageTopDossier[] | null>(null)
  const [byModel, setByModel] = useState<ClaudeUsageByModel[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setError(null)
    setSummary(null); setDaily(null); setTop(null); setByModel(null)
    Promise.all([
      getClaudeSummary(days),
      getClaudeDaily(days),
      getClaudeTopDossiers(days, 10),
      getClaudeByModel(days),
      loadPricing(),
    ]).then(([s, d, t, m]) => {
      if (cancelled) return
      setSummary(s); setDaily(d); setTop(t); setByModel(m)
    }).catch(e => { if (!cancelled) setError(e?.message || 'Erreur') })
    return () => { cancelled = true }
  }, [days])

  // Estimated cost = sum over models (respects per-model pricing)
  const estCost = useMemo(() => {
    if (!byModel) return 0
    return byModel.reduce((acc, m) => acc + estimateCostUsd(m.model, m.inputTokens, m.outputTokens), 0)
  }, [byModel])

  const avgCostPerCall = summary && summary.calls > 0 ? estCost / summary.calls : 0
  const errorRate = summary && summary.calls > 0 ? (summary.errors / summary.calls) * 100 : 0

  // Chart: pad missing days so the eye can see gaps
  const chart = useMemo(() => {
    if (!daily) return [] as Array<{ day: string; short: string; input: number; output: number; total: number; calls: number; errors: number }>
    const map = new Map(daily.map(d => [d.day, d]))
    const out: Array<{ day: string; short: string; input: number; output: number; total: number; calls: number; errors: number }> = []
    for (let i = days - 1; i >= 0; i--) {
      const d = new Date()
      d.setDate(d.getDate() - i)
      const key = d.toISOString().slice(0, 10)
      const row = map.get(key)
      out.push({
        day: key,
        short: d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit' }),
        input: row?.inputTokens ?? 0,
        output: row?.outputTokens ?? 0,
        total: (row?.inputTokens ?? 0) + (row?.outputTokens ?? 0),
        calls: row?.calls ?? 0,
        errors: row?.errors ?? 0,
      })
    }
    return out
  }, [daily, days])

  const maxTotal = Math.max(1, ...chart.map(c => c.total))

  if (error) {
    return (
      <div>
        <div className="page-header"><h1><Activity size={18} /> Consommation IA</h1></div>
        <div className="alert alert-error">{error}</div>
      </div>
    )
  }
  if (!summary || !byModel) return <SkeletonGrid />

  return (
    <div>
      <div className="page-header">
        <h1><Activity size={18} /> Consommation IA</h1>
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

      {/* KPI cards */}
      <div className="stats-grid">
        <div className="stat-card">
          <div className="stat-icon teal"><PhoneCall size={16} /></div>
          <div className="stat-value">{fmtInt(summary.calls)}</div>
          <div className="stat-label">Appels API</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon blue"><Zap size={16} /></div>
          <div className="stat-value">{fmtTokens(summary.inputTokens + summary.outputTokens)}</div>
          <div className="stat-label">Tokens total</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon green"><Banknote size={16} /></div>
          <div className="stat-value">{fmtUsd(estCost)}</div>
          <div className="stat-label">Cout estime</div>
        </div>
        <div className="stat-card">
          <div className="stat-icon amber"><AlertTriangle size={16} /></div>
          <div className="stat-value">{errorRate.toFixed(1)}%</div>
          <div className="stat-label">{fmtInt(summary.errors)} erreur(s)</div>
        </div>
      </div>

      {/* Daily chart + indicators */}
      <div className="cards-row" style={{ marginBottom: 12 }}>
        <div className="card" style={{ minWidth: 0 }}>
          <div className="card-flex" style={{ marginBottom: 8 }}>
            <h2 style={{ marginBottom: 0 }}><BarChart3 size={12} /> Consommation par jour</h2>
            <span style={{ fontSize: 11, color: 'var(--ink-40)' }}>
              Depuis {new Date(summary.since).toLocaleDateString('fr-FR')}
            </span>
          </div>
          {summary.calls === 0 ? (
            <div style={{ color: 'var(--ink-40)', fontSize: 13, padding: '12px 4px' }}>
              Aucune consommation sur la periode.
            </div>
          ) : (
            <div style={{ display: 'flex', alignItems: 'flex-end', gap: 3, height: 140, paddingBottom: 18, overflowX: 'auto' }}>
              {chart.map(c => {
                const h = c.total > 0 ? Math.max(4, (c.total / maxTotal) * 120) : 1
                const hasCalls = c.calls > 0
                return (
                  <div key={c.day} title={`${c.short} : ${fmtInt(c.calls)} appels, ${fmtTokens(c.total)} tokens${c.errors ? `, ${c.errors} erreurs` : ''}`}
                    style={{ flex: '1 0 auto', minWidth: 10, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
                    <div style={{
                      width: '100%',
                      height: `${h}px`,
                      background: c.errors > 0
                        ? 'linear-gradient(180deg, var(--danger) 0%, var(--warning) 100%)'
                        : hasCalls
                          ? 'linear-gradient(180deg, var(--accent) 0%, var(--accent-deep) 100%)'
                          : 'var(--ink-10)',
                      borderRadius: '3px 3px 0 0',
                      transition: 'height 0.25s ease'
                    }} />
                    <div style={{ fontSize: 9, color: 'var(--ink-30)', marginTop: 4 }}>{c.short.split('/')[0]}</div>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        <div className="card" style={{ minWidth: 0 }}>
          <h2><Activity size={12} /> Indicateurs</h2>
          <div className="indicator-group">
            <div>
              <div className="indicator-row">
                <span className="indicator-label">Tokens entree</span>
                <span className="indicator-value">{fmtTokens(summary.inputTokens)}</span>
              </div>
              <div className="indicator-track">
                <div className="indicator-fill" style={{
                  width: `${summary.inputTokens + summary.outputTokens > 0
                    ? (summary.inputTokens / (summary.inputTokens + summary.outputTokens)) * 100 : 0}%`,
                  background: 'var(--accent)'
                }} />
              </div>
            </div>
            <div>
              <div className="indicator-row">
                <span className="indicator-label">Tokens sortie</span>
                <span className="indicator-value">{fmtTokens(summary.outputTokens)}</span>
              </div>
              <div className="indicator-track">
                <div className="indicator-fill" style={{
                  width: `${summary.inputTokens + summary.outputTokens > 0
                    ? (summary.outputTokens / (summary.inputTokens + summary.outputTokens)) * 100 : 0}%`,
                  background: 'var(--accent-deep)'
                }} />
              </div>
            </div>
            {(() => {
              const totalInput = summary.inputTokens + summary.cacheReadInputTokens
              const hitPct = totalInput > 0 ? (summary.cacheReadInputTokens / totalInput) * 100 : 0
              return (
                <div>
                  <div className="indicator-row">
                    <span className="indicator-label">Cache prompt (hit)</span>
                    <span className="indicator-value">
                      {totalInput > 0 ? `${hitPct.toFixed(0)} %` : '—'}
                    </span>
                  </div>
                  <div className="indicator-track">
                    <div className="indicator-fill" style={{
                      width: `${hitPct}%`,
                      background: 'var(--success, #22c55e)'
                    }} />
                  </div>
                  <div className="stat-label" style={{ fontSize: 10, marginTop: 2 }}>
                    {fmtTokens(summary.cacheReadInputTokens)} lus / {fmtTokens(summary.cacheCreationInputTokens)} crees
                  </div>
                </div>
              )
            })()}
            <div className="indicator-divider">
              <div className="stat-label" style={{ marginBottom: 4 }}>Cout moyen / appel</div>
              <div className="indicator-amount">{fmtUsd(avgCostPerCall)}</div>
              <div className="stat-label">USD</div>
            </div>
          </div>
        </div>
      </div>

      {/* By model */}
      <div className="card" style={{ marginBottom: 12 }}>
        <h2><Cpu size={12} /> Par modele</h2>
        {byModel.length === 0 ? (
          <div style={{ color: 'var(--ink-40)', fontSize: 13 }}>Aucun appel enregistre.</div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Modele</th>
                <th>Appels</th>
                <th>Tokens in</th>
                <th>Tokens out</th>
                <th>Tarif ($/M in)</th>
                <th>Cout estime</th>
              </tr>
            </thead>
            <tbody>
              {byModel.map(m => {
                const pricing = CLAUDE_PRICING[m.model] ?? CLAUDE_PRICING.default
                const cost = estimateCostUsd(m.model, m.inputTokens, m.outputTokens)
                return (
                  <tr key={m.model}>
                    <td style={{ fontFamily: 'monospace', fontSize: 12 }}>{m.model}</td>
                    <td className="cell-mono">{fmtInt(m.calls)}</td>
                    <td className="cell-mono">{fmtTokens(m.inputTokens)}</td>
                    <td className="cell-mono">{fmtTokens(m.outputTokens)}</td>
                    <td className="cell-mono" style={{ color: 'var(--ink-40)' }}>
                      {pricing.input} / {pricing.output}
                    </td>
                    <td className="cell-mono" style={{ fontWeight: 600 }}>{fmtUsd(cost)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      {/* Top dossiers */}
      <div className="card">
        <div className="card-flex" style={{ marginBottom: 10 }}>
          <h2 style={{ marginBottom: 0 }}><ChevronsRight size={12} /> Top 10 dossiers</h2>
          <Link to="/dossiers" style={{ fontSize: 11, fontWeight: 600, color: 'var(--accent-deep)', textDecoration: 'none', display: 'flex', alignItems: 'center', gap: 4 }}>
            Voir tout <ArrowRight size={12} />
          </Link>
        </div>
        {!top || top.length === 0 ? (
          <div style={{ color: 'var(--ink-40)', fontSize: 13 }}>Aucun dossier avec consommation IA.</div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Reference</th>
                <th>Fournisseur</th>
                <th>Appels</th>
                <th>Tokens</th>
                <th>Cout (estim. Sonnet)</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {top.map(d => {
                const cost = estimateCostUsd('claude-sonnet-4-6', d.inputTokens, d.outputTokens)
                return (
                  <tr key={d.dossierId}>
                    <td><Link to={`/dossiers/${d.dossierId}`}>{d.reference || d.dossierId.slice(0, 8)}</Link></td>
                    <td>{d.fournisseur || '\u2014'}</td>
                    <td className="cell-mono">{fmtInt(d.calls)}</td>
                    <td className="cell-mono">{fmtTokens(d.inputTokens + d.outputTokens)}</td>
                    <td className="cell-mono" style={{ fontWeight: 600 }}>{fmtUsd(cost)}</td>
                    <td>
                      <Link to={`/dossiers/${d.dossierId}`} className="btn btn-secondary btn-sm" aria-label="Ouvrir le dossier">
                        <ArrowRight size={12} />
                      </Link>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>

      <div className="card" style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '8px 14px', marginTop: 12 }}>
        <Loader2 size={12} style={{ color: 'var(--ink-30)' }} aria-hidden="true" />
        <span style={{ fontSize: 11, color: 'var(--ink-40)' }}>
          Tarifs indicatifs (Anthropic public pricing, $ / 1 M tokens). Les couts reels sont factures directement par Anthropic.
        </span>
      </div>
    </div>
  )
}
