import { useState, useEffect } from 'react'
import { saveErpSettings, testErpConnection } from '../api/client'
import { Settings as SettingsIcon, Plug, CheckCircle, XCircle, Loader2 } from 'lucide-react'

const ERP_OPTIONS = [
  { value: 'SAGE_1000', label: 'Sage 1000', desc: 'Objets Metiers REST API' },
  { value: 'SAGE_X3', label: 'Sage X3', desc: 'Syracuse OData Web Services' },
  { value: 'SAGE_50', label: 'Sage 50', desc: 'REST Bridge / SDK' },
]

export default function Settings() {
  const [erpType, setErpType] = useState('SAGE_1000')
  const [saving, setSaving] = useState(false)
  const [testing, setTesting] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const [testResult, setTestResult] = useState<{ success: boolean; message: string } | null>(null)
  const [configured, setConfigured] = useState(false)

  useEffect(() => {
    fetch('/api/settings/erp')
      .then(res => res.ok ? res.json() : null)
      .then((data: { activeType?: string; availableTypes?: string[]; erpType?: string; configured?: boolean } | null) => {
        if (data) {
          const type = data.activeType || data.erpType
          if (type) {
            // activeType from backend is class name like "Sage1000Service", map to ERP_OPTIONS value
            const mapped = ERP_OPTIONS.find(o =>
              type.toUpperCase().includes(o.value.replace('_', '')) ||
              type.toUpperCase().replace('_', '') === o.value.replace('_', '')
            )
            setErpType(mapped?.value || type)
          }
          setConfigured(data.configured ?? (data.activeType != null))
        }
      })
      .catch(() => {})
  }, [])

  const handleSave = async () => {
    setSaving(true); setSaveResult(null)
    try {
      await saveErpSettings({ erpType, configured: true })
      setConfigured(true)
      setSaveResult({ success: true, message: 'Configuration sauvegardee avec succes.' })
    } catch (e: unknown) {
      setSaveResult({ success: false, message: e instanceof Error ? e.message : 'Erreur inconnue' })
    } finally { setSaving(false) }
  }

  const handleTest = async () => {
    setTesting(true); setTestResult(null)
    try { setTestResult(await testErpConnection(erpType)) }
    catch (e: unknown) { setTestResult({ success: false, message: e instanceof Error ? e.message : 'Erreur de connexion' }) }
    finally { setTesting(false) }
  }

  return (
    <div>
      <div className="page-header">
        <h1><SettingsIcon size={24} /> Configuration ERP</h1>
      </div>

      <div className="card">
        <h2>Statut actuel</h2>
        <div className="status-item">
          {configured ? (
            <>
              <CheckCircle size={18} color="#059669" />
              <span>ERP configure : <strong>{ERP_OPTIONS.find(o => o.value === erpType)?.label || erpType}</strong></span>
            </>
          ) : (
            <>
              <XCircle size={18} color="#ef4444" />
              <span>Aucun ERP configure</span>
            </>
          )}
        </div>
      </div>

      <div className="card">
        <h2>Selectionner l'ERP</h2>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '10px', margin: '8px 0 24px' }}>
          {ERP_OPTIONS.map(option => (
            <label
              key={option.value}
              className={`radio-card ${erpType === option.value ? 'selected' : ''}`}
            >
              <input
                type="radio"
                name="erpType"
                value={option.value}
                checked={erpType === option.value}
                onChange={e => setErpType(e.target.value)}
              />
              <div>
                <div className="fw-700">{option.label}</div>
                <div className="text-sm text-muted">{option.desc}</div>
              </div>
            </label>
          ))}
        </div>

        <div className="flex-gap">
          <button className="btn btn-primary" disabled={saving} onClick={handleSave}>
            {saving ? <><Loader2 size={16} className="spin" /> Sauvegarde...</> : <><SettingsIcon size={16} /> Sauvegarder</>}
          </button>
          <button className="btn btn-secondary" disabled={testing} onClick={handleTest}>
            {testing ? <><Loader2 size={16} className="spin" /> Test en cours...</> : <><Plug size={16} /> Tester la connexion</>}
          </button>
        </div>

        {saveResult && (
          <div className={`result-banner ${saveResult.success ? 'success' : 'error'} mt-2`}>
            {saveResult.success ? <CheckCircle size={18} /> : <XCircle size={18} />}
            <span>{saveResult.message}</span>
          </div>
        )}
        {testResult && (
          <div className={`result-banner ${testResult.success ? 'success' : 'error'} mt-2`}>
            {testResult.success ? <CheckCircle size={18} /> : <XCircle size={18} />}
            <span>{testResult.message}</span>
          </div>
        )}
      </div>
    </div>
  )
}
