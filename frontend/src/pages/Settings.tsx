import { useState, useEffect } from 'react'
import {
  getAiSettings, saveAiSettings as saveAiSettingsApi,
  getErpSettings, saveErpSettings as saveErpSettingsApi,
  testErpConnection
} from '../api/client'
import type { AiSettingsResponse, ErpSettingsResponse } from '../api/types'
import {
  Settings as SettingsIcon, Brain, Server, ScanLine, Cpu,
  CheckCircle, XCircle, Loader2, Plug, Eye, EyeOff,
  Shield, Globe, Key, Database, Building2
} from 'lucide-react'

const ERP_TYPES = [
  { value: 'SAGE_1000', label: 'Sage 1000', desc: 'Objets Metiers REST API' },
  { value: 'SAGE_X3', label: 'Sage X3', desc: 'Syracuse OData Web Services' },
  { value: 'SAGE_50', label: 'Sage 50', desc: 'REST Bridge / SDK' },
]

const AI_MODELS = [
  { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
  { value: 'claude-opus-4-6', label: 'Claude Opus 4.6' },
  { value: 'claude-haiku-4-5-20251001', label: 'Claude Haiku 4.5' },
]

export default function Settings() {
  // AI state
  const [aiSettings, setAiSettings] = useState<AiSettingsResponse | null>(null)
  const [aiEnabled, setAiEnabled] = useState(false)
  const [aiApiKey, setAiApiKey] = useState('')
  const [aiModel, setAiModel] = useState('claude-sonnet-4-6')
  const [aiBaseUrl, setAiBaseUrl] = useState('https://api.anthropic.com')
  const [showApiKey, setShowApiKey] = useState(false)
  const [aiSaving, setAiSaving] = useState(false)
  const [aiMsg, setAiMsg] = useState<{ ok: boolean; text: string } | null>(null)

  // ERP state
  const [, setErpSettings] = useState<ErpSettingsResponse | null>(null)
  const [activeErp, setActiveErp] = useState('SAGE_1000')
  const [erpSaving, setErpSaving] = useState(false)
  const [erpMsg, setErpMsg] = useState<{ ok: boolean; text: string } | null>(null)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)

  // Sage 1000 fields
  const [s1Url, setS1Url] = useState('')
  const [s1ApiKey, setS1ApiKey] = useState('')
  const [s1Company, setS1Company] = useState('')
  const [s1Timeout, setS1Timeout] = useState('30')
  const [showS1Key, setShowS1Key] = useState(false)

  // Sage X3 fields
  const [x3Url, setX3Url] = useState('')
  const [x3ClientId, setX3ClientId] = useState('')
  const [x3ClientSecret, setX3ClientSecret] = useState('')
  const [x3Folder, setX3Folder] = useState('MAROC')
  const [x3PoolAlias, setX3PoolAlias] = useState('x3')
  const [showX3Secret, setShowX3Secret] = useState(false)

  // Sage 50 fields
  const [s50Url, setS50Url] = useState('')
  const [s50User, setS50User] = useState('')
  const [s50Pass, setS50Pass] = useState('')
  const [s50File, setS50File] = useState('')
  const [s50Journal, setS50Journal] = useState('ACH')
  const [s50Year, setS50Year] = useState('2024')
  const [showS50Pass, setShowS50Pass] = useState(false)

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

    getErpSettings()
      .then(data => {
        setErpSettings(data)
        setActiveErp(data.activeType)
        // Sage 1000
        setS1Url(data.sage1000.baseUrl)
        setS1ApiKey(data.sage1000.apiKeyConfigured ? data.sage1000.apiKey : '')
        setS1Company(data.sage1000.companyCode)
        setS1Timeout(data.sage1000.timeout)
        // Sage X3
        setX3Url(data.sageX3.baseUrl)
        setX3ClientId(data.sageX3.clientId)
        setX3ClientSecret(data.sageX3.clientSecretConfigured ? data.sageX3.clientSecret : '')
        setX3Folder(data.sageX3.folder)
        setX3PoolAlias(data.sageX3.poolAlias)
        // Sage 50
        setS50Url(data.sage50.baseUrl)
        setS50User(data.sage50.username)
        setS50Pass(data.sage50.passwordConfigured ? data.sage50.password : '')
        setS50File(data.sage50.companyFile)
        setS50Journal(data.sage50.journalCode)
        setS50Year(data.sage50.fiscalYear)
      })
      .catch(() => {})
  }, [])

  const handleSaveAi = async () => {
    setAiSaving(true); setAiMsg(null)
    try {
      const result = await saveAiSettingsApi({
        enabled: aiEnabled,
        apiKey: aiApiKey || undefined,
        model: aiModel,
        baseUrl: aiBaseUrl,
      })
      setAiSettings(result)
      setAiMsg({ ok: true, text: 'Configuration IA sauvegardee.' })
    } catch (e: unknown) {
      setAiMsg({ ok: false, text: e instanceof Error ? e.message : 'Erreur' })
    } finally { setAiSaving(false) }
  }

  const handleSaveErp = async () => {
    setErpSaving(true); setErpMsg(null)
    try {
      const payload: Record<string, unknown> = { activeType: activeErp }

      if (activeErp === 'SAGE_1000') {
        payload.sage1000 = {
          baseUrl: s1Url, apiKey: s1ApiKey, companyCode: s1Company, timeout: s1Timeout
        }
      } else if (activeErp === 'SAGE_X3') {
        payload.sageX3 = {
          baseUrl: x3Url, clientId: x3ClientId, clientSecret: x3ClientSecret,
          folder: x3Folder, poolAlias: x3PoolAlias
        }
      } else if (activeErp === 'SAGE_50') {
        payload.sage50 = {
          baseUrl: s50Url, username: s50User, password: s50Pass,
          companyFile: s50File, journalCode: s50Journal, fiscalYear: s50Year
        }
      }

      const result = await saveErpSettingsApi(payload)
      setErpSettings(result)
      setErpMsg({ ok: true, text: 'Configuration ERP sauvegardee.' })
    } catch (e: unknown) {
      setErpMsg({ ok: false, text: e instanceof Error ? e.message : 'Erreur' })
    } finally { setErpSaving(false) }
  }

  const handleTestConnection = async () => {
    setTesting(true); setTestResult(null)
    try { setTestResult(await testErpConnection(activeErp)) }
    catch (e: unknown) { setTestResult({ success: false, message: e instanceof Error ? e.message : 'Erreur' }) }
    finally { setTesting(false) }
  }

  const PasswordField = ({ value, onChange, show, onToggle, placeholder }: {
    value: string; onChange: (v: string) => void; show: boolean; onToggle: () => void; placeholder?: string
  }) => (
    <div style={{ position: 'relative' }}>
      <input
        type={show ? 'text' : 'password'}
        className="form-input mono-input"
        value={value}
        onChange={e => onChange(e.target.value)}
        placeholder={placeholder || '***'}
        style={{ paddingRight: 36 }}
      />
      <button
        type="button"
        onClick={onToggle}
        style={{
          position: 'absolute', right: 8, top: '50%', transform: 'translateY(-50%)',
          background: 'none', border: 'none', cursor: 'pointer', color: '#999', padding: 2
        }}
      >
        {show ? <EyeOff size={14} /> : <Eye size={14} />}
      </button>
    </div>
  )

  return (
    <div>
      <div className="page-header">
        <h1><SettingsIcon size={22} /> Configuration</h1>
      </div>

      {/* ===== AI Configuration ===== */}
      <div className="card">
        <div className="settings-section-header">
          <div className="settings-section-title">
            <Brain size={18} /> Extraction IA (Claude)
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            {aiSettings?.apiKeyConfigured ? (
              <span className="config-status configured"><CheckCircle size={12} /> Cle configuree</span>
            ) : (
              <span className="config-status not-configured"><XCircle size={12} /> Non configure</span>
            )}
            <label className="toggle-switch">
              <input type="checkbox" checked={aiEnabled} onChange={e => setAiEnabled(e.target.checked)} />
              <span className="toggle-slider" />
            </label>
          </div>
        </div>

        <div style={{ opacity: aiEnabled ? 1 : 0.5, pointerEvents: aiEnabled ? 'auto' : 'none', transition: 'opacity 0.2s' }}>
          <div className="settings-grid">
            <div>
              <label className="form-label"><Key size={11} /> Cle API</label>
              <PasswordField
                value={aiApiKey}
                onChange={setAiApiKey}
                show={showApiKey}
                onToggle={() => setShowApiKey(!showApiKey)}
                placeholder="sk-ant-..."
              />
              <p className="form-hint">Cle API Anthropic. Stockee de maniere securisee en base.</p>
            </div>
            <div>
              <label className="form-label"><Cpu size={11} /> Modele</label>
              <select className="form-select full-width" value={aiModel} onChange={e => setAiModel(e.target.value)}>
                {AI_MODELS.map(m => (
                  <option key={m.value} value={m.value}>{m.label}</option>
                ))}
              </select>
              <p className="form-hint">Modele utilise pour l'extraction structuree.</p>
            </div>
            <div className="full-span">
              <label className="form-label"><Globe size={11} /> URL de base</label>
              <input
                type="text"
                className="form-input mono-input"
                value={aiBaseUrl}
                onChange={e => setAiBaseUrl(e.target.value)}
                placeholder="https://api.anthropic.com"
              />
              <p className="form-hint">Point d'acces API. Modifiable pour proxy ou API compatible.</p>
            </div>
          </div>
        </div>

        <div style={{ marginTop: 16, display: 'flex', alignItems: 'center', gap: 10 }}>
          <button className="btn btn-primary" disabled={aiSaving} onClick={handleSaveAi}>
            {aiSaving ? <><Loader2 size={14} className="spin" /> Sauvegarde...</> : <><Shield size={14} /> Sauvegarder IA</>}
          </button>
          {aiMsg && (
            <span style={{ fontSize: 13, fontWeight: 600, color: aiMsg.ok ? '#0d7a5f' : '#9b2c2c' }}>
              {aiMsg.ok ? <CheckCircle size={14} style={{ verticalAlign: -2 }} /> : <XCircle size={14} style={{ verticalAlign: -2 }} />}
              {' '}{aiMsg.text}
            </span>
          )}
        </div>

        {!aiEnabled && (
          <div style={{ marginTop: 12, padding: '10px 14px', background: 'var(--bg)', borderRadius: 'var(--radius-sm)', fontSize: 12, color: 'var(--text-secondary)' }}>
            <strong>Mode hors-ligne actif</strong> — L'extraction utilise uniquement les patterns regex deterministes.
            Activez l'IA pour enrichir automatiquement les donnees manquantes via Claude.
          </div>
        )}
      </div>

      {/* ===== ERP Configuration ===== */}
      <div className="card">
        <div className="settings-section-header">
          <div className="settings-section-title">
            <Database size={18} /> Configuration ERP Sage
          </div>
        </div>

        <div className="erp-tabs">
          {ERP_TYPES.map(erp => (
            <button
              key={erp.value}
              className={`erp-tab ${activeErp === erp.value ? 'active' : ''}`}
              onClick={() => setActiveErp(erp.value)}
            >
              <Building2 size={14} />
              {erp.label}
            </button>
          ))}
        </div>

        <p style={{ fontSize: 12, color: 'var(--text-secondary)', marginBottom: 16 }}>
          {ERP_TYPES.find(e => e.value === activeErp)?.desc}
        </p>

        {/* Sage 1000 */}
        {activeErp === 'SAGE_1000' && (
          <div className="settings-grid">
            <div>
              <label className="form-label"><Server size={11} /> URL de base</label>
              <input
                type="text"
                className="form-input mono-input"
                value={s1Url}
                onChange={e => setS1Url(e.target.value)}
                placeholder="http://sage1000:8443"
              />
            </div>
            <div>
              <label className="form-label"><Key size={11} /> Cle API</label>
              <PasswordField
                value={s1ApiKey}
                onChange={setS1ApiKey}
                show={showS1Key}
                onToggle={() => setShowS1Key(!showS1Key)}
                placeholder="Cle API Sage 1000"
              />
            </div>
            <div>
              <label className="form-label"><Building2 size={11} /> Code societe</label>
              <input
                type="text"
                className="form-input"
                value={s1Company}
                onChange={e => setS1Company(e.target.value)}
                placeholder="DEFAULT"
              />
            </div>
            <div>
              <label className="form-label">Timeout (secondes)</label>
              <input
                type="text"
                className="form-input"
                value={s1Timeout}
                onChange={e => setS1Timeout(e.target.value)}
                placeholder="30"
              />
            </div>
          </div>
        )}

        {/* Sage X3 */}
        {activeErp === 'SAGE_X3' && (
          <div className="settings-grid">
            <div className="full-span">
              <label className="form-label"><Server size={11} /> URL de base Syracuse</label>
              <input
                type="text"
                className="form-input mono-input"
                value={x3Url}
                onChange={e => setX3Url(e.target.value)}
                placeholder="https://sagex3:8124"
              />
            </div>
            <div>
              <label className="form-label">Client ID (OAuth2)</label>
              <input
                type="text"
                className="form-input mono-input"
                value={x3ClientId}
                onChange={e => setX3ClientId(e.target.value)}
                placeholder="Client ID"
              />
            </div>
            <div>
              <label className="form-label"><Key size={11} /> Client Secret</label>
              <PasswordField
                value={x3ClientSecret}
                onChange={setX3ClientSecret}
                show={showX3Secret}
                onToggle={() => setShowX3Secret(!showX3Secret)}
                placeholder="Client Secret"
              />
            </div>
            <div>
              <label className="form-label">Dossier (Folder)</label>
              <input
                type="text"
                className="form-input"
                value={x3Folder}
                onChange={e => setX3Folder(e.target.value)}
                placeholder="MAROC"
              />
            </div>
            <div>
              <label className="form-label">Pool Alias</label>
              <input
                type="text"
                className="form-input"
                value={x3PoolAlias}
                onChange={e => setX3PoolAlias(e.target.value)}
                placeholder="x3"
              />
            </div>
          </div>
        )}

        {/* Sage 50 */}
        {activeErp === 'SAGE_50' && (
          <div className="settings-grid">
            <div className="full-span">
              <label className="form-label"><Server size={11} /> URL REST Bridge</label>
              <input
                type="text"
                className="form-input mono-input"
                value={s50Url}
                onChange={e => setS50Url(e.target.value)}
                placeholder="http://sage50:9090"
              />
            </div>
            <div>
              <label className="form-label">Utilisateur</label>
              <input
                type="text"
                className="form-input"
                value={s50User}
                onChange={e => setS50User(e.target.value)}
                placeholder="admin"
              />
            </div>
            <div>
              <label className="form-label"><Key size={11} /> Mot de passe</label>
              <PasswordField
                value={s50Pass}
                onChange={setS50Pass}
                show={showS50Pass}
                onToggle={() => setShowS50Pass(!showS50Pass)}
                placeholder="Mot de passe"
              />
            </div>
            <div>
              <label className="form-label">Fichier societe</label>
              <input
                type="text"
                className="form-input mono-input"
                value={s50File}
                onChange={e => setS50File(e.target.value)}
                placeholder="MAROC_2024.SAI"
              />
            </div>
            <div>
              <label className="form-label">Code journal</label>
              <input
                type="text"
                className="form-input"
                value={s50Journal}
                onChange={e => setS50Journal(e.target.value)}
                placeholder="ACH"
              />
            </div>
            <div>
              <label className="form-label">Exercice fiscal</label>
              <input
                type="text"
                className="form-input"
                value={s50Year}
                onChange={e => setS50Year(e.target.value)}
                placeholder="2024"
              />
            </div>
          </div>
        )}

        <div style={{ marginTop: 18, display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
          <button className="btn btn-primary" disabled={erpSaving} onClick={handleSaveErp}>
            {erpSaving ? <><Loader2 size={14} className="spin" /> Sauvegarde...</> : <><Database size={14} /> Sauvegarder ERP</>}
          </button>
          <button className="btn btn-secondary" disabled={testing} onClick={handleTestConnection}>
            {testing ? <><Loader2 size={14} className="spin" /> Test en cours...</> : <><Plug size={14} /> Tester la connexion</>}
          </button>
          {erpMsg && (
            <span style={{ fontSize: 13, fontWeight: 600, color: erpMsg.ok ? '#0d7a5f' : '#9b2c2c' }}>
              {erpMsg.ok ? <CheckCircle size={14} style={{ verticalAlign: -2 }} /> : <XCircle size={14} style={{ verticalAlign: -2 }} />}
              {' '}{erpMsg.text}
            </span>
          )}
        </div>

        {testResult && (
          <div className={`result-banner ${testResult.success ? 'success' : 'error'} mt-2`}>
            {testResult.success ? <CheckCircle size={16} /> : <XCircle size={16} />}
            <span>{testResult.message}</span>
          </div>
        )}
      </div>

      {/* ===== OCR Pipeline ===== */}
      <div className="card">
        <div className="settings-section-header">
          <div className="settings-section-title">
            <ScanLine size={18} /> Pipeline OCR
          </div>
          <span className="config-status configured"><CheckCircle size={12} /> Operationnel</span>
        </div>

        <div className="status-list">
          <div className="status-item">
            <Cpu size={16} color="var(--primary)" />
            <span><strong>Moteur principal :</strong> Apache Tika 3.0 (PDF natifs)</span>
          </div>
          <div className="status-item">
            <Cpu size={16} color="var(--accent)" />
            <span><strong>Moteur secondaire :</strong> Tesseract OCR via Tess4J (scans/images)</span>
          </div>
          <div className="status-item">
            <CheckCircle size={16} color="var(--primary)" />
            <span><strong>Langues :</strong> Francais (fra) + Arabe (ara)</span>
          </div>
          <div className="status-item">
            <CheckCircle size={16} color="var(--primary)" />
            <span><strong>Preprocessing :</strong> Deskew, Binarisation Sauvola, Debruitage Gaussien, Auto-scale</span>
          </div>
          <div className="status-item">
            <CheckCircle size={16} color="var(--primary)" />
            <span><strong>Configuration :</strong> OEM 1 (LSTM), PSM 6, 300 DPI</span>
          </div>
        </div>

        <div style={{ marginTop: 14, padding: '10px 14px', background: 'var(--bg)', borderRadius: 'var(--radius-sm)', fontSize: 12, color: 'var(--text-secondary)', lineHeight: 1.6 }}>
          <strong>Cascade intelligente :</strong> Tika extrait le texte natif. Si insuffisant (&lt;20 mots),
          Tesseract prend le relai avec preprocessing d'image.
          {aiEnabled && ' L\'IA enrichit ensuite les donnees si l\'extraction regex est incomplete.'}
        </div>
      </div>
    </div>
  )
}
