import { useState, useEffect } from 'react'
import { getAiSettings, saveAiSettings as saveAiSettingsApi } from '../api/client'
import type { AiSettingsResponse } from '../api/types'
import { useToast } from '../components/Toast'
import {
  Settings as SettingsIcon, Brain, ScanLine, Cpu,
  CheckCircle, XCircle, Loader2, Eye, EyeOff,
  Shield, Globe, Key, Info, Keyboard,
} from 'lucide-react'

const AI_MODELS = [
  { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6', desc: 'Rapide, ideal pour extraction' },
  { value: 'claude-opus-4-6', label: 'Claude Opus 4.6', desc: 'Plus precis, documents complexes' },
  { value: 'claude-haiku-4-5-20251001', label: 'Claude Haiku 4.5', desc: 'Leger, economique' },
]

const OCR_ENGINES = [
  { name: 'Apache Tika 3.0', tag: 'TIKA', priority: 1, color: 'var(--teal-700)', desc: 'Extraction texte natif pour PDF numeriques. Instantane.' },
  { name: 'PaddleOCR 3.4', tag: 'PADDLE', priority: 2, color: 'var(--blue-600)', desc: 'OCR deep learning. Francais + Arabe, 300 DPI.' },
  { name: 'Tesseract 5', tag: 'TESS', priority: 3, color: 'var(--amber-600)', desc: 'Fallback local avec preprocessing d\'image.' },
]

const SHORTCUTS = [
  { keys: 'Ctrl+K', desc: 'Recherche globale' },
  { keys: 'Esc', desc: 'Fermer modale/recherche' },
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
        enabled: aiEnabled, apiKey: aiApiKey || undefined,
        model: aiModel, baseUrl: aiBaseUrl,
      })
      setAiSettings(result)
      toast('success', 'Configuration IA sauvegardee')
    } catch (e: unknown) {
      toast('error', e instanceof Error ? e.message : 'Erreur')
    } finally { setAiSaving(false) }
  }

  return (
    <div>
      <div className="page-header"><h1><SettingsIcon size={22} /> Parametres</h1></div>

      {/* AI Extraction */}
      <div className="card">
        <h2><Brain size={14} /> Extraction IA</h2>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
          <p style={{ fontSize: 13, color: 'var(--slate-500)', margin: 0, maxWidth: 520 }}>
            L'IA Claude analyse les documents PDF et extrait les donnees structurees.
            Sans IA, seuls les patterns regex sont utilises.
          </p>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            {aiSettings?.apiKeyConfigured ? (
              <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--emerald-600)', display: 'flex', alignItems: 'center', gap: 4 }}>
                <CheckCircle size={12} /> Configure
              </span>
            ) : (
              <span style={{ fontSize: 11, fontWeight: 700, color: 'var(--danger)', display: 'flex', alignItems: 'center', gap: 4 }}>
                <XCircle size={12} /> Non configure
              </span>
            )}
            <label className="toggle">
              <input type="checkbox" checked={aiEnabled} onChange={e => setAiEnabled(e.target.checked)} />
              <span className="toggle-track" />
              <span className="toggle-thumb" />
            </label>
          </div>
        </div>
        <div style={{ opacity: aiEnabled ? 1 : 0.4, pointerEvents: aiEnabled ? 'auto' : 'none', transition: 'opacity 0.2s' }}>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14, marginBottom: 14 }}>
            <div>
              <label className="form-label"><Key size={11} /> Cle API Anthropic</label>
              <div style={{ position: 'relative' }}>
                <input
                  type={showApiKey ? 'text' : 'password'}
                  className="form-input"
                  value={aiApiKey}
                  onChange={e => setAiApiKey(e.target.value)}
                  placeholder="sk-ant-..."
                  style={{ paddingRight: 36, fontFamily: 'var(--font-mono)', fontSize: 12 }}
                />
                <button
                  type="button"
                  onClick={() => setShowApiKey(!showApiKey)}
                  style={{
                    position: 'absolute', right: 8, top: '50%', transform: 'translateY(-50%)',
                    background: 'none', border: 'none', cursor: 'pointer', color: 'var(--slate-400)', padding: 2
                  }}
                >
                  {showApiKey ? <EyeOff size={14} /> : <Eye size={14} />}
                </button>
              </div>
            </div>
            <div>
              <label className="form-label"><Cpu size={11} /> Modele</label>
              <select className="form-select full-width" value={aiModel} onChange={e => setAiModel(e.target.value)}>
                {AI_MODELS.map(m => <option key={m.value} value={m.value}>{m.label} — {m.desc}</option>)}
              </select>
            </div>
            <div style={{ gridColumn: '1 / -1' }}>
              <label className="form-label"><Globe size={11} /> URL de base</label>
              <input
                type="text" className="form-input" value={aiBaseUrl}
                onChange={e => setAiBaseUrl(e.target.value)}
                placeholder="https://api.anthropic.com"
                style={{ fontFamily: 'var(--font-mono)', fontSize: 12 }}
              />
            </div>
          </div>
        </div>
        <button className="btn btn-primary" disabled={aiSaving} onClick={handleSaveAi}>
          {aiSaving ? <><Loader2 size={14} className="spin" /> Sauvegarde...</> : <><Shield size={14} /> Sauvegarder</>}
        </button>
      </div>

      {/* OCR Pipeline */}
      <div className="card">
        <h2><ScanLine size={14} /> Pipeline OCR</h2>
        <p style={{ fontSize: 13, color: 'var(--slate-500)', marginBottom: 16 }}>
          Les documents PDF sont traites par une cascade de 3 moteurs OCR.
        </p>
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 12 }}>
          {OCR_ENGINES.map(engine => (
            <div key={engine.tag} className="engine-card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
                <span className="tag" style={{ color: engine.color }}>{engine.tag}</span>
                <span style={{ fontSize: 10, color: 'var(--slate-400)', fontWeight: 600 }}>Priorite {engine.priority}</span>
              </div>
              <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--slate-900)', marginBottom: 4 }}>{engine.name}</div>
              <div style={{ fontSize: 12, color: 'var(--slate-500)', lineHeight: 1.5 }}>{engine.desc}</div>
            </div>
          ))}
        </div>

        <div className="alert alert-info" style={{ marginTop: 14 }}>
          <Info size={14} style={{ flexShrink: 0 }} />
          <span>
            <strong>Cascade intelligente :</strong> Tika extrait le texte natif. Si insuffisant (&lt;20 mots),
            PaddleOCR prend le relai. Si PaddleOCR est indisponible, Tesseract sert de fallback.
            {aiEnabled && ' L\'IA Claude enrichit ensuite les donnees si l\'extraction regex est incomplete.'}
          </span>
        </div>
      </div>

      {/* Raccourcis clavier */}
      <div className="card">
        <h2><Keyboard size={14} /> Raccourcis clavier</h2>
        <table className="data-table">
          <thead><tr><th>Raccourci</th><th>Action</th></tr></thead>
          <tbody>
            {SHORTCUTS.map(s => (
              <tr key={s.keys}>
                <td><kbd style={{ fontSize: 11, background: 'var(--slate-100)', padding: '2px 8px', borderRadius: 4, border: '1px solid var(--slate-200)', fontFamily: 'var(--font-mono)' }}>{s.keys}</kbd></td>
                <td>{s.desc}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* About */}
      <div className="card">
        <h2><Info size={14} /> A propos</h2>
        <div className="info-grid">
          <div>
            <div className="info-block-label">Plateforme</div>
            <div className="info-block-value">ReconDoc MADAEF</div>
            <div className="info-block-desc">Reconciliation documentaire des dossiers de paiement</div>
          </div>
          <div>
            <div className="info-block-label">Organisation</div>
            <div className="info-block-value">MADAEF — Groupe CDG</div>
            <div className="info-block-desc">Gestion des dossiers fournisseurs</div>
          </div>
          <div>
            <div className="info-block-label">Version</div>
            <div className="info-block-value mono">1.2.0</div>
            <div className="info-block-desc">Kotlin + React + Claude IA</div>
          </div>
        </div>
      </div>
    </div>
  )
}
