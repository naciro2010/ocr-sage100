import { useState, useEffect } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { BarChart3, FolderOpen, Settings, Shield, Search } from 'lucide-react'
import SearchPanel from './SearchPanel'

export default function Layout() {
  const [searchOpen, setSearchOpen] = useState(false)

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
      <nav className="sidebar">
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
            <NavLink to="/" end>
              <BarChart3 size={16} /> <span>Tableau de bord</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/dossiers">
              <FolderOpen size={16} /> <span>Dossiers</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/settings">
              <Settings size={16} /> <span>Parametres</span>
            </NavLink>
          </li>
        </ul>

        <div style={{ padding: '0 12px', marginBottom: 12 }}>
          <button
            onClick={() => setSearchOpen(true)}
            style={{
              width: '100%', display: 'flex', alignItems: 'center', gap: 10,
              padding: '9px 14px', background: 'rgba(255,255,255,0.04)',
              border: '1px solid rgba(255,255,255,0.08)', borderRadius: 6,
              color: 'rgba(255,255,255,0.35)', cursor: 'pointer', fontSize: 12,
              transition: 'all 0.15s',
            }}
          >
            <Search size={14} />
            <span style={{ flex: 1, textAlign: 'left' }}>Rechercher...</span>
            <kbd style={{ fontSize: 9, opacity: 0.5, background: 'rgba(255,255,255,0.08)', padding: '1px 5px', borderRadius: 3 }}>Ctrl+K</kbd>
          </button>
        </div>

        <div className="sidebar-footer">
          <div className="dot" />
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
