import { useState } from 'react'
import { Shield, Loader2 } from 'lucide-react'

const API_URL = (import.meta.env.VITE_API_URL || '').replace(/\/+$/, '')

interface Props {
  onLogin: (user: { id: number; email: string; nom: string; role: string }) => void
}

export default function Login({ onLogin }: Props) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setLoading(true); setError('')
    try {
      const res = await fetch(`${API_URL}/api/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Basic ' + btoa(`${email}:${password}`) },
        body: JSON.stringify({ email, password }),
      })
      if (!res.ok) {
        const body = await res.json().catch(() => ({ error: 'Erreur de connexion' }))
        throw new Error(body.error || `HTTP ${res.status}`)
      }
      const user = await res.json()
      localStorage.setItem('recondoc_user', JSON.stringify(user))
      localStorage.setItem('recondoc_auth', btoa(`${email}:${password}`))
      onLogin(user)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Erreur')
    } finally { setLoading(false) }
  }

  return (
    <div style={{ minHeight: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--ink-02)' }}>
      <div style={{ width: 380, padding: 32, background: '#fff', borderRadius: 12, border: '1px solid var(--ink-05)', boxShadow: '0 8px 32px rgba(0,0,0,0.06)' }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <div style={{ width: 48, height: 48, background: 'linear-gradient(135deg, var(--accent-deep), var(--accent))', borderRadius: 12, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', marginBottom: 12 }}>
            <Shield size={24} color="white" />
          </div>
          <h1 style={{ fontSize: 20, fontWeight: 700, color: 'var(--ink)', marginBottom: 4 }}>ReconDoc MADAEF</h1>
          <p style={{ fontSize: 12, color: 'var(--ink-40)' }}>Reconciliation documentaire des dossiers de paiement</p>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label">Email</label>
            <input className="form-input" type="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="admin@madaef.ma" required autoFocus />
          </div>
          <div className="form-group">
            <label className="form-label">Mot de passe</label>
            <input className="form-input" type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="***" required />
          </div>
          {error && <div className="alert alert-error" style={{ marginBottom: 12 }}>{error}</div>}
          <button className="btn btn-primary full-width" type="submit" disabled={loading} style={{ padding: '10px 16px', justifyContent: 'center' }}>
            {loading ? <Loader2 size={15} className="spin" /> : 'Se connecter'}
          </button>
        </form>

        <div style={{ textAlign: 'center', marginTop: 16, fontSize: 10, color: 'var(--ink-30)' }}>
          MADAEF — Groupe CDG
        </div>
      </div>
    </div>
  )
}
