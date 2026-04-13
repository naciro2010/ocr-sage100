import { useState, useEffect } from 'react'
import { getAiSettings, saveAiSettings as saveAiSettingsApi } from '../api/client'
import type { AiSettingsResponse } from '../api/types'
import { useToast } from '../components/Toast'
import { ALL_RULES, getDisabledRules, setDisabledRules } from '../config/validationRules'
import {
  Settings as SettingsIcon, Brain, ScanLine, Cpu,
  CheckCircle, XCircle, Loader2, Eye, EyeOff,
  Shield, Globe, Key, Info, Keyboard, ShieldCheck,
} from 'lucide-react'

const AI_MODELS = [
  { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6', desc: 'Rapide, ideal pour extraction' },
  { value: 'claude-opus-4-6', label: 'Claude Opus 4.6', desc: 'Plus precis, documents complexes' },
  { value: 'claude-haiku-4-5-20251001', label: 'Claude Haiku 4.5', desc: 'Leger, economique' },
]

const OCR_ENGINES = [
  { name: 'Apache Tika 3.0', tag: 'TIKA', priority: 1, color: 'var(--accent-deep)', desc: 'Extraction texte natif pour PDF numeriques. Instantane.' },
  { name: 'PaddleOCR 3.4', tag: 'PADDLE', priority: 2, color: 'var(--info)', desc: 'OCR deep learning. Francais + Arabe, 300 DPI.' },
  { name: 'Tesseract 5', tag: 'TESS', priority: 3, color: 'var(--warning)', desc: 'Fallback local avec preprocessing d\'image.' },
]

const SHORTCUTS = [
  { keys: 'Ctrl+K', desc: 'Recherche globale' },
  { keys: 'Esc', desc: 'Fermer modale/recherche' },
]

export default function Settings() {
  const { toast } = useToast()
  const [activeTab, setActiveTab] = useState<'ia' | 'ocr' | 'rules' | 'about'>('ia')
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

      <div className="settings-tabs" role="tablist" aria-label="Onglets parametres">
        {([['ia', 'Extraction IA'], ['ocr', 'Pipeline OCR'], ['rules', 'Regles'], ['about', 'A propos']] as const).map(([key, label]) => (
          <button key={key}
            role="tab"
            aria-selected={activeTab === key}
            aria-controls={`tab-panel-${key}`}
            className={`btn ${activeTab === key ? 'btn-primary' : 'btn-secondary'}`}
            onClick={() => setActiveTab(key)}>
            {label}
          </button>
        ))}
      </div>

      {activeTab === 'ia' && <div className="card" role="tabpanel" id="tab-panel-ia">
        <h2><Brain size={14} /> Extraction IA</h2>
        <div className="settings-toggle-row">
          <p className="settings-desc">
            L'IA Claude analyse les documents PDF et extrait les donnees structurees.
            Sans IA, seuls les patterns regex sont utilises.
          </p>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            {aiSettings?.apiKeyConfigured ? (
              <span className="settings-ai-status configured">
                <CheckCircle size={12} /> Configure
              </span>
            ) : (
              <span className="settings-ai-status not-configured">
                <XCircle size={12} /> Non configure
              </span>
            )}
            <label className="toggle">
              <input type="checkbox" checked={aiEnabled} onChange={e => setAiEnabled(e.target.checked)} aria-label="Activer l'extraction IA" />
              <span className="toggle-track" />
              <span className="toggle-thumb" />
            </label>
          </div>
        </div>
        <div className={`settings-disableable ${aiEnabled ? '' : 'disabled'}`}>
          <div className="form-grid">
            <div>
              <label className="form-label" htmlFor="ai-api-key"><Key size={11} /> Cle API Anthropic</label>
              <div className="form-input-wrap">
                <input
                  id="ai-api-key"
                  type={showApiKey ? 'text' : 'password'}
                  className="form-input"
                  value={aiApiKey}
                  onChange={e => setAiApiKey(e.target.value)}
                  placeholder="sk-ant-..."
                  style={{ paddingRight: 36, fontFamily: 'var(--font-mono)', fontSize: 12 }}
                />
                <button
                  type="button"
                  className="form-input-icon"
                  onClick={() => setShowApiKey(!showApiKey)}
                  aria-label={showApiKey ? 'Masquer la cle API' : 'Afficher la cle API'}
                >
                  {showApiKey ? <EyeOff size={14} /> : <Eye size={14} />}
                </button>
              </div>
            </div>
            <div>
              <label className="form-label" htmlFor="ai-model"><Cpu size={11} /> Modele</label>
              <select id="ai-model" className="form-select full-width" value={aiModel} onChange={e => setAiModel(e.target.value)}>
                {AI_MODELS.map(m => <option key={m.value} value={m.value}>{m.label} — {m.desc}</option>)}
              </select>
            </div>
            <div className="form-grid-full">
              <label className="form-label" htmlFor="ai-base-url"><Globe size={11} /> URL de base</label>
              <input
                id="ai-base-url"
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

      }

      {activeTab === 'ocr' && <><div className="card" role="tabpanel" id="tab-panel-ocr">
        <h2><ScanLine size={14} /> Pipeline OCR</h2>
        <p className="settings-desc" style={{ marginBottom: 16 }}>
          Les documents PDF sont traites par une cascade de 3 moteurs OCR.
        </p>
        <div className="engine-grid">
          {OCR_ENGINES.map(engine => (
            <div key={engine.tag} className="engine-card">
              <div className="engine-header">
                <span className="tag" style={{ color: engine.color }}>{engine.tag}</span>
                <span className="engine-priority">Priorite {engine.priority}</span>
              </div>
              <div className="engine-name">{engine.name}</div>
              <div className="engine-desc">{engine.desc}</div>
            </div>
          ))}
        </div>

        <div className="alert alert-info" style={{ marginTop: 14 }}>
          <Info size={14} style={{ flexShrink: 0 }} aria-hidden="true" />
          <span>
            <strong>Cascade intelligente :</strong> Tika extrait le texte natif. Si insuffisant (&lt;20 mots),
            PaddleOCR prend le relai. Si PaddleOCR est indisponible, Tesseract sert de fallback.
            {aiEnabled && ' L\'IA Claude enrichit ensuite les donnees si l\'extraction regex est incomplete.'}
          </span>
        </div>
      </div>

      <div className="card">
        <h2><Keyboard size={14} /> Raccourcis clavier</h2>
        <table className="data-table">
          <thead><tr><th>Raccourci</th><th>Action</th></tr></thead>
          <tbody>
            {SHORTCUTS.map(s => (
              <tr key={s.keys}>
                <td><kbd className="kbd-key">{s.keys}</kbd></td>
                <td>{s.desc}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      </>}

      {activeTab === 'rules' && <div role="tabpanel" id="tab-panel-rules"><ValidationRulesSection /></div>}

      {activeTab === 'about' && <div className="card" role="tabpanel" id="tab-panel-about">
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
      </div>}
    </div>
  )
}

function ValidationRulesSection() {
  const [disabled, setDisabled] = useState<Set<string>>(getDisabledRules)

  const toggle = (code: string) => {
    const next = new Set(disabled)
    if (next.has(code)) next.delete(code); else next.add(code)
    setDisabled(next)
    setDisabledRules(next)
  }

  const systemRules = ALL_RULES.filter(r => r.category === 'system')
  const checklistRules = ALL_RULES.filter(r => r.category === 'checklist')

  return (
    <div className="card">
      <h2><ShieldCheck size={14} /> Regles de validation</h2>
      <p className="rule-desc" style={{ marginBottom: 16 }}>
        Activez ou desactivez les regles de verification croisee. Les regles desactivees ne seront pas executees lors de la validation.
      </p>

      <div className="rules-section-label">
        Regles systeme ({systemRules.filter(r => !disabled.has(r.code)).length}/{systemRules.length} actives)
      </div>
      <table className="data-table" style={{ marginBottom: 20 }}>
        <thead>
          <tr>
            <th style={{ width: 50 }}>Actif</th>
            <th style={{ width: 70 }}>Code</th>
            <th>Regle</th>
            <th>Description</th>
            <th style={{ width: 80 }}>BC</th>
            <th style={{ width: 80 }}>Contrat</th>
          </tr>
        </thead>
        <tbody>
          {systemRules.map(r => (
            <tr key={r.code} style={{ opacity: disabled.has(r.code) ? 0.4 : 1 }}>
              <td>
                <label className="toggle">
                  <input type="checkbox" checked={!disabled.has(r.code)} onChange={() => toggle(r.code)} aria-label={`Activer la regle ${r.code}`} />
                  <span className="toggle-track" />
                  <span className="toggle-thumb" />
                </label>
              </td>
              <td className="rule-code">{r.code}</td>
              <td className="rule-label">{r.label}</td>
              <td className="rule-desc">{r.desc}</td>
              <td>{r.appliesToBC ? <CheckCircle size={12} style={{ color: 'var(--success)' }} aria-label="Applicable" /> : <span style={{ color: 'var(--ink-20)' }} aria-label="Non applicable">—</span>}</td>
              <td>{r.appliesToContractuel ? <CheckCircle size={12} style={{ color: 'var(--success)' }} aria-label="Applicable" /> : <span style={{ color: 'var(--ink-20)' }} aria-label="Non applicable">—</span>}</td>
            </tr>
          ))}
        </tbody>
      </table>

      <div className="rules-section-label">
        Points de controle TC ({checklistRules.filter(r => !disabled.has(r.code)).length}/{checklistRules.length} actifs)
      </div>
      <table className="data-table">
        <thead>
          <tr>
            <th style={{ width: 50 }}>Actif</th>
            <th style={{ width: 70 }}>Code</th>
            <th>Point de controle</th>
            <th>Description</th>
          </tr>
        </thead>
        <tbody>
          {checklistRules.map(r => (
            <tr key={r.code} style={{ opacity: disabled.has(r.code) ? 0.4 : 1 }}>
              <td>
                <label className="toggle">
                  <input type="checkbox" checked={!disabled.has(r.code)} onChange={() => toggle(r.code)} aria-label={`Activer le point ${r.code}`} />
                  <span className="toggle-track" />
                  <span className="toggle-thumb" />
                </label>
              </td>
              <td className="rule-code">{r.code}</td>
              <td className="rule-label">{r.label}</td>
              <td className="rule-desc">{r.desc}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
