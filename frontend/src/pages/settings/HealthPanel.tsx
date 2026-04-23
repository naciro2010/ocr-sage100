import { useState, useEffect, useCallback } from 'react'
import {
  Activity, RefreshCw, AlertTriangle, Loader2, Clock,
  Database, ScanLine, Brain, HardDrive, Cpu, Server,
} from 'lucide-react'
import { getSystemHealth } from '../../api/client'
import type { SystemHealthResponse, HealthComponent, HealthTone } from '../../api/types'
import { useToast } from '../../components/Toast'
import SettingsHero from './SettingsHero'

export default function HealthPanel() {
  const { toast } = useToast()
  const [health, setHealth] = useState<SystemHealthResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [lastFetch, setLastFetch] = useState<Date | null>(null)

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoading(true)
    try {
      const data = await getSystemHealth()
      setHealth(data)
      setLastFetch(new Date())
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Impossible de recuperer l\'etat systeme')
    } finally {
      if (!silent) setLoading(false)
    }
  }, [toast])

  useEffect(() => { load() }, [load])

  useEffect(() => {
    const id = setInterval(() => load(true), 15000)
    return () => clearInterval(id)
  }, [load])

  const worstTone = deriveWorstTone(health?.components)
  const statusLabel =
    worstTone === 'error' ? 'Incident en cours'
    : worstTone === 'warn' ? 'Mode degrade'
    : 'Tout est operationnel'

  return (
    <div role="tabpanel" id="tab-panel-health">
      <SettingsHero
        eyebrow="Observabilite"
        title={<>Surveiller en un coup d'oeil <span style={{ color: 'var(--accent-deep)' }}>chaque brique</span> de la plateforme.</>}
        icon={<Activity size={24} aria-hidden="true" />}
        lead="Chaque composant est teste en direct : PostgreSQL (SELECT 1), stockage, pipeline OCR, integration Claude, memoire JVM. La vue s'auto-actualise toutes les 15 s. Utilisez cet ecran avant une validation en masse ou pour diagnostiquer un incident."
        status={worstTone === 'ok' ? 'active' : worstTone === 'warn' ? 'idle' : 'off'}
        statusLabel={statusLabel}
        kpi={health?.uptime || '—'}
        kpiLabel="Uptime"
      />

      <div className="card">
        <div className="section-title-rail" style={{ marginTop: 0 }}>
          Composants surveilles
          <span style={{
            marginLeft: 'auto',
            fontFamily: 'var(--font-mono)', fontSize: 10,
            fontWeight: 500, letterSpacing: 0.5, textTransform: 'none',
            color: 'var(--ink-40)', display: 'flex', alignItems: 'center', gap: 8,
          }}>
            {lastFetch && (
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                <Clock size={10} /> {formatRelative(lastFetch)}
              </span>
            )}
            <button
              className="btn btn-secondary"
              style={{ fontSize: 11, padding: '4px 10px' }}
              onClick={() => load()}
              disabled={loading}
              aria-label="Rafraichir l'etat systeme"
            >
              {loading
                ? <><Loader2 size={12} className="spin" /> Refresh</>
                : <><RefreshCw size={12} /> Refresh</>}
            </button>
          </span>
        </div>

        {!health && !loading && (
          <div className="alert alert-warning">
            <AlertTriangle size={14} />
            <span>Pas de donnees. Vérifiez que le backend est joignable.</span>
          </div>
        )}

        {health && (
          <div className="health-grid">
            {health.components.map(comp => (
              <HealthCard key={comp.id} comp={comp} />
            ))}
          </div>
        )}
      </div>

      {health && (
        <div className="card">
          <div className="section-title-rail" style={{ marginTop: 0 }}>Informations runtime</div>
          <div className="about-meta">
            <div className="about-meta-item">
              <div className="lbl">Application</div>
              <div className="val">{health.application.name}</div>
              <div className="sub">Demarree le {formatDateTime(health.application.startedAt)}</div>
            </div>
            <div className="about-meta-item">
              <div className="lbl">JVM</div>
              <div className="val">Java {health.application.javaVersion}</div>
              <div className="sub">Timezone {health.application.timezone}</div>
            </div>
            <div className="about-meta-item">
              <div className="lbl">Derniere verif</div>
              <div className="val">{formatDateTime(health.checkedAt)}</div>
              <div className="sub">Auto-refresh toutes les 15 s</div>
            </div>
          </div>
        </div>
      )}

      <div className="card">
        <div className="section-title-rail" style={{ marginTop: 0 }}>Comment lire cette page</div>
        <div className="howto-steps">
          <div className="howto-step">
            <div className="howto-step-num" style={{ background: 'rgba(16,185,129,0.1)', color: 'var(--accent-deep)' }}>●</div>
            <div>
              <div className="howto-step-title">Vert — Operationnel <span className="pill-meta accent">tone:ok</span></div>
              <div className="howto-step-desc">Le composant repond normalement, dans les temps attendus.</div>
            </div>
          </div>
          <div className="howto-step">
            <div className="howto-step-num" style={{ background: 'rgba(245,158,11,0.1)', color: '#b45309' }}>●</div>
            <div>
              <div className="howto-step-title">Ambre — Mode degrade <span className="pill-meta warn">tone:warn</span></div>
              <div className="howto-step-desc">
                Le composant fonctionne mais en mode de repli : par exemple OCR sans Mistral (Tesseract seul),
                ou circuit breaker Claude OPEN apres des erreurs.
              </div>
            </div>
          </div>
          <div className="howto-step">
            <div className="howto-step-num" style={{ background: 'rgba(239,68,68,0.1)', color: 'var(--danger)' }}>●</div>
            <div>
              <div className="howto-step-title">Rouge — Indisponible <span className="pill-meta" style={{ background: 'rgba(239,68,68,0.08)', color: 'var(--danger)' }}>tone:error</span></div>
              <div className="howto-step-desc">
                Incident reel — la DB ne repond plus, ou la memoire JVM est saturee.
                Rapprocher l'incident d'un deploiement recent ou d'un pic de charge.
              </div>
            </div>
          </div>
          <div className="howto-step">
            <div className="howto-step-num" style={{ background: 'var(--ink-05)', color: 'var(--ink-50)' }}>○</div>
            <div>
              <div className="howto-step-title">Gris — Desactive <span className="pill-meta">tone:muted</span></div>
              <div className="howto-step-desc">
                Composant optionnel non configure (ex : Claude sans cle API). Sans impact sur le healthcheck
                global — la plateforme sert toujours les requetes.
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function deriveWorstTone(components?: HealthComponent[]): HealthTone {
  if (!components?.length) return 'muted'
  if (components.some(c => c.tone === 'error')) return 'error'
  if (components.some(c => c.tone === 'warn')) return 'warn'
  if (components.every(c => c.tone === 'muted')) return 'muted'
  return 'ok'
}

function HealthCard({ comp }: { comp: HealthComponent }) {
  const details = comp.details as Record<string, unknown> | undefined
  return (
    <div className={`health-card health-tone-${comp.tone}`}>
      <div className="health-card-head">
        <div className="health-card-icon" aria-hidden="true">{renderComponentIcon(comp.id)}</div>
        <div className="health-card-title">
          <span className="health-card-label">{comp.label}</span>
          <span className="health-card-cat">{comp.category}</span>
        </div>
        <StatusDot tone={comp.tone} />
      </div>
      <div className="health-card-status">{prettyStatus(comp.status)}</div>
      {typeof comp.latencyMs === 'number' && comp.latencyMs >= 0 && (
        <div className="health-card-kv">
          <span className="health-card-key">Latence</span>
          <span className="health-card-val mono">{comp.latencyMs} ms</span>
        </div>
      )}
      {details && Object.entries(details).filter(([, v]) => v !== null && v !== undefined && v !== '').map(([k, v]) => (
        <div className="health-card-kv" key={k}>
          <span className="health-card-key">{humanizeKey(k)}</span>
          <span className="health-card-val mono">{formatValue(v)}</span>
        </div>
      ))}
    </div>
  )
}

function StatusDot({ tone }: { tone: HealthTone }) {
  return <span className={`health-dot health-dot-${tone}`} aria-hidden="true" />
}

function renderComponentIcon(id: string) {
  switch (id) {
    case 'db': return <Database size={14} />
    case 'ocr': return <ScanLine size={14} />
    case 'ai': return <Brain size={14} />
    case 'storage': return <HardDrive size={14} />
    case 'jvm': return <Cpu size={14} />
    default: return <Server size={14} />
  }
}

function prettyStatus(s: string) {
  switch (s) {
    case 'up': return 'En ligne'
    case 'degraded': return 'Degrade'
    case 'down': return 'Hors-ligne'
    case 'off': return 'Desactive'
    default: return s
  }
}

function humanizeKey(k: string): string {
  const map: Record<string, string> = {
    version: 'Version',
    error: 'Erreur',
    type: 'Type',
    presignSupported: 'URL signees',
    usableSpaceMb: 'Espace libre',
    totalSpaceMb: 'Espace total',
    engine: 'Moteur',
    fallback: 'Fallback',
    mistralConfigured: 'Cle Mistral',
    mistralEnabled: 'Mistral actif',
    model: 'Modele',
    mode: 'Mode',
    apiKeyConfigured: 'Cle API',
    enabled: 'Active',
    circuit: 'Circuit breaker',
    failureRate: 'Taux d\'erreur',
    usedMb: 'Utilise',
    totalMb: 'Total',
    maxMb: 'Max',
    usagePct: 'Utilisation',
    availableProcessors: 'CPUs',
  }
  return map[k] || k
}

function formatValue(v: unknown): string {
  if (v === true) return 'oui'
  if (v === false) return 'non'
  if (typeof v === 'number') {
    if (Number.isInteger(v)) return v.toLocaleString('fr-FR')
    return v.toFixed(2)
  }
  if (typeof v === 'string') {
    if (v.length > 60) return v.slice(0, 57) + '...'
    return v
  }
  return String(v)
}

function formatDateTime(iso: string): string {
  try {
    const d = new Date(iso)
    return d.toLocaleString('fr-FR', { dateStyle: 'medium', timeStyle: 'short' })
  } catch { return iso }
}

function formatRelative(d: Date): string {
  const diff = Math.round((Date.now() - d.getTime()) / 1000)
  if (diff < 5) return 'a l\'instant'
  if (diff < 60) return `il y a ${diff}s`
  const m = Math.floor(diff / 60)
  if (m < 60) return `il y a ${m} min`
  return d.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' })
}
