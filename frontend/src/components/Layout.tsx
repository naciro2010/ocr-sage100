import { NavLink, Outlet } from 'react-router-dom'
import { BarChart3, Upload, FileText, Files, Download, Settings, Zap } from 'lucide-react'

export default function Layout() {
  return (
    <div className="app">
      <nav className="sidebar">
        <div className="sidebar-header">
          <div className="sidebar-brand">
            <div className="sidebar-logo"><Zap size={18} /></div>
            <div className="sidebar-title">
              <h2>OCR Sage 100</h2>
              <span>Traitement factures</span>
            </div>
          </div>
        </div>

        <div className="nav-section">Navigation</div>
        <ul className="nav-links">
          <li>
            <NavLink to="/" end>
              <BarChart3 size={18} /> <span>Dashboard</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/upload">
              <Upload size={18} /> <span>Upload</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/batch-upload">
              <Files size={18} /> <span>Batch Upload</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/invoices">
              <FileText size={18} /> <span>Factures</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/export">
              <Download size={18} /> <span>Export</span>
            </NavLink>
          </li>
          <li>
            <NavLink to="/settings">
              <Settings size={18} /> <span>Configuration</span>
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
