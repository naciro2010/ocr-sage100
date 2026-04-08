import { NavLink, Outlet } from 'react-router-dom'
import { BarChart3, FolderOpen, Settings, ShieldCheck } from 'lucide-react'

export default function Layout() {
  return (
    <div className="app">
      <nav className="sidebar">
        <div className="sidebar-header">
          <div className="sidebar-brand">
            <div className="sidebar-logo"><ShieldCheck size={18} /></div>
            <div className="sidebar-title">
              <h2>MADAEF</h2>
              <span>Reconciliation paiements</span>
            </div>
          </div>
        </div>

        <div className="nav-section">Navigation</div>
        <ul className="nav-links">
          <li>
            <NavLink to="/" end>
              <BarChart3 size={17} /> <span>Dashboard</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/dossiers">
              <FolderOpen size={17} /> <span>Dossiers</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/settings">
              <Settings size={17} /> <span>Configuration</span>
            </NavLink>
          </li>
        </ul>

        <div className="sidebar-footer">
          <div className="dot" />
          <span>Systeme operationnel</span>
        </div>
      </nav>
      <main className="main-content">
        <Outlet />
      </main>
    </div>
  )
}
