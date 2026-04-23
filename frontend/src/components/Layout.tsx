import { useState, useEffect } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { Activity, BarChart3, Briefcase, FolderOpen, Settings, Shield, ShieldAlert, Search, LogOut, Moon, Sun, CheckCircle, Users } from 'lucide-react'
import SearchPanel from './SearchPanel'
import * as Pages from '../routes/lazyPages'

const preloadDashboard = () => { Pages.Dashboard.preload() }
const preloadEngagements = () => { Pages.EngagementList.preload() }
const preloadDossiers = () => { Pages.DossierList.preload() }
const preloadFournisseurs = () => { Pages.FournisseurList.preload() }
const preloadClaudeUsage = () => { Pages.ClaudeUsage.preload() }
const preloadRulesHealth = () => { Pages.RulesHealth.preload() }
const preloadSettings = () => { Pages.Settings.preload() }

interface Props {
  user?: { nom: string; role: string; email: string }
  onLogout?: () => void
}

export default function Layout({ user, onLogout }: Props) {
  const location = useLocation()
  const [searchOpen, setSearchOpen] = useState(false)
  const [dark, setDark] = useState(() => localStorage.getItem('recondoc_theme') === 'dark')

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light')
    localStorage.setItem('recondoc_theme', dark ? 'dark' : 'light')
  }, [dark])

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        setSearchOpen(true)
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [])

  return (
    <div className="app">
      <nav className="sidebar" aria-label="Navigation principale">
        <div className="sidebar-header">
          <div className="sidebar-brand">
            <div className="sidebar-logo"><Shield size={17} /></div>
            <div className="sidebar-title">
              <h2>ReconDoc</h2>
              <span>MADAEF / Groupe CDG</span>
            </div>
          </div>
        </div>

        <div className="nav-section">Navigation</div>
        <ul className="nav-links">
          <li>
            <NavLink to="/" end onMouseEnter={preloadDashboard} onFocus={preloadDashboard} onTouchStart={preloadDashboard}>
              <BarChart3 size={16} /> <span>Tableau de bord</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/engagements" onMouseEnter={preloadEngagements} onFocus={preloadEngagements} onTouchStart={preloadEngagements} className={() => {
              return location.pathname.startsWith('/engagements') ? 'active' : ''
            }}>
              <Briefcase size={16} /> <span>Engagements</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/dossiers" onMouseEnter={preloadDossiers} onFocus={preloadDossiers} onTouchStart={preloadDossiers} className={() => {
              const loc = location
              return loc.pathname === '/dossiers' && !loc.search.includes('statut=VALIDE') ? 'active' : ''
            }}>
              <FolderOpen size={16} /> <span>Dossiers</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/dossiers?statut=VALIDE" onMouseEnter={preloadDossiers} onFocus={preloadDossiers} onTouchStart={preloadDossiers} className={() => {
              const loc = location
              return loc.pathname === '/dossiers' && loc.search.includes('statut=VALIDE') ? 'active' : ''
            }}>
              <CheckCircle size={16} /> <span>Finalises</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/fournisseurs" onMouseEnter={preloadFournisseurs} onFocus={preloadFournisseurs} onTouchStart={preloadFournisseurs}>
              <Users size={16} /> <span>Fournisseurs</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/admin/claude-usage" onMouseEnter={preloadClaudeUsage} onFocus={preloadClaudeUsage} onTouchStart={preloadClaudeUsage}>
              <Activity size={16} /> <span>Consommation IA</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/admin/rules-health" onMouseEnter={preloadRulesHealth} onFocus={preloadRulesHealth} onTouchStart={preloadRulesHealth}>
              <ShieldAlert size={16} /> <span>Sante des regles</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/settings" onMouseEnter={preloadSettings} onFocus={preloadSettings} onTouchStart={preloadSettings}>
              <Settings size={16} /> <span>Parametres</span>
            </NavLink>
          </li>
        </ul>

        <div className="sidebar-actions">
          <button className="sidebar-search-btn" onClick={() => setSearchOpen(true)} aria-label="Rechercher (Ctrl+K)">
            <Search size={14} />
            <span style={{ flex: 1, textAlign: 'left' }}>Rechercher...</span>
            <kbd>Ctrl+K</kbd>
          </button>
        </div>

        <div className="sidebar-theme-wrap">
          <button className="sidebar-theme-btn" onClick={() => setDark(!dark)} aria-label={dark ? 'Activer le mode clair' : 'Activer le mode sombre'}>
            {dark ? <Sun size={12} /> : <Moon size={12} />} <span>{dark ? 'Mode clair' : 'Mode sombre'}</span>
          </button>
        </div>

        {user && (
          <div className="sidebar-user">
            <div className="sidebar-user-name">{user.nom}</div>
            <div className="sidebar-user-role">{user.role}</div>
            {onLogout && (
              <button className="sidebar-logout" onClick={onLogout} aria-label="Se deconnecter">
                <LogOut size={10} /> <span>Deconnexion</span>
              </button>
            )}
          </div>
        )}
        <div className="sidebar-footer">
          <div className="dot" aria-hidden="true" />
          <span>Systeme operationnel</span>
        </div>
      </nav>
      <main className="main-content">
        <Outlet />
      </main>
      <SearchPanel open={searchOpen} onClose={() => setSearchOpen(false)} />
    </div>
  )
}
