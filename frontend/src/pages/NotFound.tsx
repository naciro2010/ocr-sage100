import { Link } from 'react-router-dom'
import { FileQuestion } from 'lucide-react'

export default function NotFound() {
  return (
    <div className="not-found">
      <FileQuestion size={48} style={{ color: 'var(--ink-20)', marginBottom: 16 }} aria-hidden="true" />
      <h1>Page introuvable</h1>
      <p>Cette page n'existe pas ou a ete deplacee.</p>
      <Link to="/" className="btn btn-primary" style={{ textDecoration: 'none' }}>Retour au tableau de bord</Link>
    </div>
  )
}
