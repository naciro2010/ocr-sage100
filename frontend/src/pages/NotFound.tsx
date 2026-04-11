import { Link } from 'react-router-dom'
import { FileQuestion } from 'lucide-react'

export default function NotFound() {
  return (
    <div style={{ padding: 64, textAlign: 'center' }}>
      <FileQuestion size={48} style={{ color: 'var(--ink-20)', marginBottom: 16 }} />
      <h1 style={{ fontSize: 20, fontWeight: 700, marginBottom: 8 }}>Page introuvable</h1>
      <p style={{ fontSize: 13, color: 'var(--ink-40)', marginBottom: 20 }}>Cette page n'existe pas ou a ete deplacee.</p>
      <Link to="/" className="btn btn-primary" style={{ textDecoration: 'none' }}>Retour au tableau de bord</Link>
    </div>
  )
}
