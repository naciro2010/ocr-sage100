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
        headers: { 'Content-Type': 'application/json' },
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
    <div className="login-page">
      <div className="login-card">
        <div className="login-header">
          <div className="login-logo">
            <Shield size={24} color="white" />
          </div>
          <h1 className="login-title">ReconDoc MADAEF</h1>
          <p className="login-subtitle">Reconciliation documentaire des dossiers de paiement</p>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="login-email">Email</label>
            <input id="login-email" className="form-input" type="email" value={email} onChange={e => setEmail(e.target.value)} placeholder="admin@madaef.ma" required autoFocus />
          </div>
          <div className="form-group">
            <label className="form-label" htmlFor="login-password">Mot de passe</label>
            <input id="login-password" className="form-input" type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="***" required />
          </div>
          {error && <div className="alert alert-error" style={{ marginBottom: 12 }}>{error}</div>}
          <button className="btn btn-primary full-width" type="submit" disabled={loading} style={{ padding: '10px 16px', justifyContent: 'center' }}>
            {loading ? <Loader2 size={15} className="spin" /> : 'Se connecter'}
          </button>
        </form>

        <div className="login-footer">
          MADAEF — Groupe CDG
        </div>
      </div>
    </div>
  )
}
