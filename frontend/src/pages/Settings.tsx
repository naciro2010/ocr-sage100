import { useState, useEffect } from 'react'
import {
  getAiSettings, saveAiSettings as saveAiSettingsApi,
} from '../api/client'
import type { AiSettingsResponse } from '../api/types'
import { useToast } from '../components/Toast'
import {
  Settings as SettingsIcon, Brain, ScanLine, Cpu,
  CheckCircle, XCircle, Loader2, Eye, EyeOff,
  Shield, Globe, Key, Info
} from 'lucide-react'

const AI_MODELS = [
  { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6', desc: 'Rapide, ideal pour extraction' },
  { value: 'claude-opus-4-6', label: 'Claude Opus 4.6', desc: 'Plus precis, documents complexes' },
  { value: 'claude-haiku-4-5-20251001', label: 'Claude Haiku 4.5', desc: 'Leger, economique' },
]

export default function Settings() {
  const { toast } = useToast()
  const [aiSettings, setAiSettings] = useState<AiSettingsResponse | null>(null)
  const [aiEnabled, setAiEnabled] = useState(false)
  const [aiApiKey, setAiApiKey] = useState('')
  const [aiModel, setAiModel] = useState('claude-sonnet-4-6')
  const [aiBaseUrl, setAiBaseUrl] = useState('https://api.anthropic.com')
  const [showApiKey, setShowApiKey] = useState(false)
  const [aiSaving, setAiSaving] = useState(false)

  useEffect(() => {
    getAiSettings()
      .then(data => {
        setAiSettings(data)
        setAiEnabled(data.enabled)
        setAiApiKey(data.apiKeyConfigured ? data.apiKey : '')
        setAiModel(data.model)
        setAiBaseUrl(data.baseUrl)
      })
      .catch(() => {})
  }, [])

  const handleSaveAi = async () => {
    setAiSaving(true)
    try {
      const result = await saveAiSettingsApi({
        enabled: aiEnabled,
        apiKey: aiApiKey || undefined,
        model: aiModel,
        baseUrl: aiBaseUrl,
      })
      setAiSettings(result)
      toast('success', 'Configuration IA sauvegardee')
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setAiSaving(false) }
  }

  const PasswordField = ({ value, onChange, show, onToggle, placeholder }: {
    value: string; onChange: (v: string) => void; show: boolean; onToggle: () => void; placeholder?: string
  }) => (
    <div style={{ position: 'relative' }}>
      <input
        type={show ? 'text' : 'password'}
        className="form-input"
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder || '***'}
        style={{ paddingRight: 36, fontFamily: 'var(--mono)' }}
      />
      <button
        type="button"
        onClick={onToggle}
        style={{
          position: 'absolute', right: 8, top: '50%', transform: 'translateY(-50%)',
          background: 'none', border: 'none', cursor: 'pointer', color: 'var(--ink-faint)', padding: 2
        }}
      >
        {show ? <EyeOff size={14} /> : <Eye size={14} />}
      </button>
    </div>
  )

  return (
    <div>
      <div className="page-header">
        <h1><SettingsIcon size={22} /> Parametres</h1>
      </div>

      {/* ===== Extraction IA ===== */}
      <div className="card">
        <h2><Brain size={14} /> Extraction IA</h2>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
          <p style={{ fontSize: 13, color: 'var(--ink-muted)', margin: 0, maxWidth: 500 }}>
            L'IA Claude analyse les documents PDF et extrait les donnees structurees (montants, references, fournisseurs).
            Sans IA, seuls les patterns regex deterministes sont utilises.
          </p>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            {aiSettings?.apiKeyConfigured ? (
              <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--accent)', display: 'flex', alignItems: 'center', gap: 4 }}>
                <CheckCircle size={12} /> Configure
              </span>
            ) : (
              <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--danger)', display: 'flex', alignItems: 'center', gap: 4 }}>
                <XCircle size={12} /> Non configure
              </span>
            )}
            <label style={{ position: 'relative', display: 'inline-block', width: 40, height: 22, cursor: 'pointer' }}>
              <input type="checkbox" checked={aiEnabled} onChange={e => setAiEnabled(e.target.checked)}
                style={{ opacity: 0, width: 0, height: 0 }} />
              <span style={{
                position: 'absolute', inset: 0, borderRadius: 11,
                background: aiEnabled ? 'var(--accent)' : 'var(--border)',
                transition: 'background 0.2s',
              }}>
                <span style={{
                  position: 'absolute', top: 3, left: aiEnabled ? 21 : 3,
                  width: 16, height: 16, borderRadius: '50%', background: '#fff',
                  transition: 'left 0.2s', boxShadow: '0 1px 3px rgba(0,0,0,0.2)',
                }} />
              </span>
            </label>
          </div>
        </div>

        <div style={{ opacity: aiEnabled ? 1 : 0.4, pointerEvents: aiEnabled ? 'auto' : 'none', transition: 'opacity 0.2s' }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 16 }}>
            <div>
              <label className="form-label"><Key size={11} /> Cle API Anthropic</label>
              <PasswordField
                value={aiApiKey} onChange={setAiApiKey}
                show={showApiKey} onToggle={() => setShowApiKey(!showApiKey)}
                placeholder="sk-ant-..."
              />
            </div>
            <div>
              <label className="form-label"><Cpu size={11} /> Modele</label>
              <select className="form-select full-width" value={aiModel} onChange={e => setAiModel(e.target.value)}>
                {AI_MODELS.map(m => (
                  <option key={m.value} value={m.value}>{m.label} — {m.desc}</option>
                ))}
              </select>
            </div>
            <div style={{ gridColumn: '1 / -1' }}>
              <label className="form-label"><Globe size={11} /> URL de base</label>
              <input type="text" className="form-input" value={aiBaseUrl}
                onChange={e => setAiBaseUrl(e.target.value)}
                placeholder="https://api.anthropic.com"
                style={{ fontFamily: 'var(--mono)' }}
              />
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 8 }}>
          <button className="btn btn-primary" disabled={aiSaving} onClick={handleSaveAi}>
            {aiSaving ? <><Loader2 size={14} className="spin" /> Sauvegarde...</> : <><Shield size={14} /> Sauvegarder</>}
          </button>
        </div>
      </div>

      {/* ===== Pipeline OCR ===== */}
      <div className="card">
        <h2><ScanLine size={14} /> Pipeline OCR</h2>
        <p style={{ fontSize: 13, color: 'var(--ink-muted)', marginBottom: 20 }}>
          Les documents PDF sont traites par une cascade de 3 moteurs OCR. Le systeme choisit automatiquement le meilleur moteur selon le type de document.
        </p>

        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
          {[
            { name: 'Apache Tika 3.0', tag: 'TIKA', priority: 1, color: 'var(--accent)', desc: 'Extraction texte natif pour PDF numeriques. Instantane.' },
            { name: 'PaddleOCR 3.4', tag: 'PADDLE', priority: 2, color: '#3b7dd8', desc: 'OCR deep learning. Francais + Arabe, 300 DPI.' },
            { name: 'Tesseract 5', tag: 'TESS', priority: 3, color: 'var(--warning)', desc: 'Fallback local avec preprocessing d\'image.' },
          ].map(engine => (
            <div key={engine.tag} style={{
              border: '1px solid var(--border)', borderRadius: 2, padding: 20,
              borderLeft: `3px solid ${engine.color}`,
            }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
                <span style={{
                  fontSize: 10, fontWeight: 700, letterSpacing: 0.8, textTransform: 'uppercase' as const,
                  color: engine.color, background: `${engine.color}12`, padding: '2px 8px', borderRadius: 2,
                }}>
                  {engine.tag}
                </span>
                <span style={{ fontSize: 10, color: 'var(--ink-faint)', fontWeight: 600 }}>Priorite {engine.priority}</span>
              </div>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--ink)', marginBottom: 6 }}>{engine.name}</div>
              <div style={{ fontSize: 12, color: 'var(--ink-muted)', lineHeight: 1.5 }}>{engine.desc}</div>
            </div>
          ))}
        </div>

        <div style={{
          marginTop: 16, padding: '12px 16px', background: 'var(--surface)', borderRadius: 2,
          fontSize: 12, color: 'var(--ink-muted)', display: 'flex', gap: 8, alignItems: 'flex-start', lineHeight: 1.6,
        }}>
          <Info size={14} style={{ flexShrink: 0, marginTop: 2 }} />
          <span>
            <strong>Cascade intelligente :</strong> Tika extrait le texte natif. Si insuffisant (&lt;20 mots),
            PaddleOCR prend le relai. Si PaddleOCR est indisponible, Tesseract sert de fallback.
            {aiEnabled && ' L\'IA Claude enrichit ensuite les donnees si l\'extraction regex est incomplete.'}
          </span>
        </div>
      </div>

      {/* ===== About ===== */}
      <div className="card">
        <h2><Info size={14} /> A propos</h2>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 24, fontSize: 13 }}>
          <div>
            <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-faint)', textTransform: 'uppercase' as const, letterSpacing: 0.8, marginBottom: 6 }}>Plateforme</div>
            <div style={{ fontWeight: 700, color: 'var(--ink)' }}>ReconDoc MADAEF</div>
            <div style={{ color: 'var(--ink-muted)', fontSize: 12, marginTop: 2 }}>Reconciliation documentaire des dossiers de paiement</div>
          </div>
          <div>
            <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-faint)', textTransform: 'uppercase' as const, letterSpacing: 0.8, marginBottom: 6 }}>Organisation</div>
            <div style={{ fontWeight: 700, color: 'var(--ink)' }}>MADAEF — Groupe CDG</div>
            <div style={{ color: 'var(--ink-muted)', fontSize: 12, marginTop: 2 }}>Gestion des dossiers fournisseurs</div>
          </div>
          <div>
            <div style={{ fontSize: 10, fontWeight: 700, color: 'var(--ink-faint)', textTransform: 'uppercase' as const, letterSpacing: 0.8, marginBottom: 6 }}>Version</div>
            <div style={{ fontWeight: 700, color: 'var(--ink)', fontFamily: 'var(--mono)' }}>1.1.0</div>
            <div style={{ color: 'var(--ink-muted)', fontSize: 12, marginTop: 2 }}>Kotlin + React + Claude IA</div>
          </div>
        </div>
      </div>
    </div>
  )
}
