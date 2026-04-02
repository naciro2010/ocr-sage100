import { NavLink, Outlet } from 'react-router-dom'
import { BarChart3, Upload, FileText, Files, Download, Settings } from 'lucide-react'

export default function Layout() {
  return (
    <div className="app">
      <nav className="sidebar">
        <div className="sidebar-header">
          <h2>OCR Sage 100</h2>
        </div>
        <ul className="nav-links">
          <li>
            <NavLink to="/" end>
              <BarChart3 size={18} /> Dashboard
            </NavLink>
          </li>
          <li>
            <NavLink to="/upload">
              <Upload size={18} /> Upload
            </NavLink>
          </li>
          <li>
            <NavLink to="/batch-upload">
              <Files size={18} /> Batch Upload
            </NavLink>
          </li>
          <li>
            <NavLink to="/invoices">
              <FileText size={18} /> Factures
            </NavLink>
          </li>
          <li>
            <NavLink to="/export">
              <Download size={18} /> Export
            </NavLink>
          </li>
          <li>
            <NavLink to="/settings">
              <Settings size={18} /> Configuration
            </NavLink>
          </li>
        </ul>
      </nav>
      <main className="main-content">
        <Outlet />
      </main>
    </div>
  )
}
