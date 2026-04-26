import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import AuditLog from './AuditLog'
import type { AuditEntry } from '../../api/dossierTypes'

function entry(action: string, detail = 'detail', daysAgo = 0): AuditEntry {
  const d = new Date()
  d.setDate(d.getDate() - daysAgo)
  return { action, detail, utilisateur: 'alice', dateAction: d.toISOString() }
}

describe('AuditLog', () => {
  it('rend rien si historique vide', () => {
    const { container } = render(<AuditLog audit={[]} />)
    expect(container.firstChild).toBeNull()
  })

  it('affiche systematiquement les actions critiques (VALIDATION/REJET/CORRECTION)', () => {
    // Cas reel : 6 entrees routine + 1 critique. Sans le filtre, la critique
    // serait masquee derriere "Voir les X restantes" — anti-pattern audit.
    const audit: AuditEntry[] = [
      entry('UPLOAD_DOCUMENT', 'facture.pdf', 0),
      entry('UPLOAD_DOCUMENT', 'bc.pdf', 1),
      entry('RE_PROCESS', 'auto', 2),
      entry('UPLOAD_DOCUMENT', 'op.pdf', 3),
      entry('UPLOAD_DOCUMENT', 'pv.pdf', 4),
      entry('RE_PROCESS', 'auto', 5),
      entry('REJET_DOSSIER', 'Pieces manquantes', 6),
    ]
    render(<AuditLog audit={audit} />)
    expect(screen.getByText('REJET_DOSSIER')).toBeInTheDocument()
    expect(screen.getByText('Pieces manquantes')).toBeInTheDocument()
    // Les routinieres au-dela de 5 sont collapsable :
    expect(screen.getByRole('button', { name: /Voir les/i })).toBeInTheDocument()
  })

  it('classe correctement les actions critiques (regex)', () => {
    const audit: AuditEntry[] = [
      entry('CORRECTION_VALEUR_R09', 'ICE corrige'),
      entry('OVERRIDE_R18', 'Force CONFORME'),
      entry('VALIDATION_DOSSIER', 'Validee par alice'),
      entry('UPLOAD_DOCUMENT', 'routine'),
    ]
    render(<AuditLog audit={audit} />)
    expect(screen.getByText('CORRECTION_VALEUR_R09')).toBeInTheDocument()
    expect(screen.getByText('OVERRIDE_R18')).toBeInTheDocument()
    expect(screen.getByText('VALIDATION_DOSSIER')).toBeInTheDocument()
    expect(screen.getByText('UPLOAD_DOCUMENT')).toBeInTheDocument()
  })

  it('replie uniquement les routinieres au-dela de 5', () => {
    const audit: AuditEntry[] = Array.from({ length: 10 }, (_, i) =>
      entry('UPLOAD_DOCUMENT', `doc-${i}.pdf`, i)
    )
    render(<AuditLog audit={audit} />)
    // 5 routinieres visibles
    expect(screen.getAllByText('UPLOAD_DOCUMENT')).toHaveLength(5)
    // bouton "Voir les 5 entrees..."
    expect(screen.getByText(/5 entree/i)).toBeInTheDocument()
  })

  it("affiche l'utilisateur quand connu", () => {
    render(<AuditLog audit={[entry('VALIDATION_DOSSIER', 'OK')]} />)
    expect(screen.getByText(/par alice/i)).toBeInTheDocument()
  })
})
