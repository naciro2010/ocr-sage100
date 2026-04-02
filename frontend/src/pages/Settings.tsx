import { useState, useEffect } from 'react'
import { saveErpSettings, testErpConnection } from '../api/client'
import type { ErpSettings } from '../api/types'
import { Settings as SettingsIcon, Plug, CheckCircle, XCircle, Loader2 } from 'lucide-react'

const ERP_OPTIONS = [
  { value: 'SAGE_1000', label: 'Sage 1000' },
  { value: 'SAGE_X3', label: 'Sage X3' },
  { value: 'SAGE_50', label: 'Sage 50' },
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
      .then((data: ErpSettings | null) => {
        if (data) {
          setErpType(data.erpType)
          setConfigured(data.configured)
        }
      })
      .catch(() => {})
  }, [])

  const handleSave = async () => {
    setSaving(true)
    setSaveResult(null)
    try {
      await saveErpSettings({ erpType, configured: true })
      setConfigured(true)
      setSaveResult({ success: true, message: 'Configuration sauvegardee avec succes.' })
    } catch (e: unknown) {
      setSaveResult({
        success: false,
        message: e instanceof Error ? e.message : 'Erreur inconnue',
      })
    } finally {
      setSaving(false)
    }
  }

  const handleTest = async () => {
    setTesting(true)
    setTestResult(null)
    try {
      const result = await testErpConnection(erpType)
      setTestResult(result)
    } catch (e: unknown) {
      setTestResult({
        success: false,
        message: e instanceof Error ? e.message : 'Erreur de connexion',
      })
    } finally {
      setTesting(false)
    }
  }

  return (
    <div>
      <div className="page-header">
        <h1><SettingsIcon size={24} /> Configuration ERP</h1>
      </div>

      <div className="card">
        <h2>Statut actuel</h2>
        <div className="status-item" style={{ marginBottom: '1rem' }}>
          {configured ? (
            <>
              <CheckCircle size={18} color="#059669" />
              <span>ERP configure : {ERP_OPTIONS.find(o => o.value === erpType)?.label || erpType}</span>
            </>
          ) : (
            <>
              <XCircle size={18} color="#ef4444" />
              <span>Aucun ERP configure</span>
            </>
          )}
        </div>
      </div>

      <div className="card" style={{ marginTop: '1rem' }}>
        <h2>Selectionner l'ERP</h2>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem', margin: '1rem 0' }}>
          {ERP_OPTIONS.map(option => (
            <label
              key={option.value}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: '0.5rem',
                padding: '0.75rem 1rem',
                border: erpType === option.value ? '2px solid #3b82f6' : '2px solid #e5e7eb',
                borderRadius: '8px',
                cursor: 'pointer',
                backgroundColor: erpType === option.value ? '#eff6ff' : 'transparent',
              }}
            >
              <input
                type="radio"
                name="erpType"
                value={option.value}
                checked={erpType === option.value}
                onChange={e => setErpType(e.target.value)}
              />
              {option.label}
            </label>
          ))}
        </div>

        <div style={{ display: 'flex', gap: '0.75rem', marginTop: '1.5rem' }}>
          <button
            className="btn btn-primary"
            disabled={saving}
            onClick={handleSave}
          >
            {saving ? (
              <><Loader2 size={16} className="spin" /> Sauvegarde...</>
            ) : (
              <><SettingsIcon size={16} /> Sauvegarder</>
            )}
          </button>

          <button
            className="btn btn-secondary"
            disabled={testing}
            onClick={handleTest}
          >
            {testing ? (
              <><Loader2 size={16} className="spin" /> Test en cours...</>
            ) : (
              <><Plug size={16} /> Tester la connexion</>
            )}
          </button>
        </div>

        {saveResult && (
          <div className={`result-banner ${saveResult.success ? 'success' : 'error'}`} style={{ marginTop: '1rem' }}>
            {saveResult.success ? <CheckCircle size={18} /> : <XCircle size={18} />}
            <span>{saveResult.message}</span>
          </div>
        )}

        {testResult && (
          <div className={`result-banner ${testResult.success ? 'success' : 'error'}`} style={{ marginTop: '1rem' }}>
            {testResult.success ? <CheckCircle size={18} /> : <XCircle size={18} />}
            <span>{testResult.message}</span>
          </div>
        )}
      </div>
    </div>
  )
}
