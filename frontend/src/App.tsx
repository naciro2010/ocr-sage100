import { Suspense, useState } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { ToastProvider } from './components/Toast'
import ErrorBoundary from './components/ErrorBoundary'
import Layout from './components/Layout'
import Login from './pages/Login'
import * as Pages from './routes/lazyPages'

interface User { id: number; email: string; nom: string; role: string }

export default function App() {
  const [user, setUser] = useState<User | null>(() => {
    try { return JSON.parse(localStorage.getItem('recondoc_user') || 'null') } catch { return null }
  })

  const handleLogin = (u: User) => setUser(u)
  const handleLogout = () => {
    localStorage.removeItem('recondoc_user')
    localStorage.removeItem('recondoc_auth')
    setUser(null)
  }

  if (!user) {
    return (
      <ErrorBoundary>
        <Login onLogin={handleLogin} />
      </ErrorBoundary>
    )
  }

  return (
    <ErrorBoundary>
      <ToastProvider>
        <BrowserRouter>
          <Suspense fallback={<div className="loading">Chargement...</div>}>
            <Routes>
              <Route element={<Layout user={user} onLogout={handleLogout} />}>
                <Route path="/" element={<Pages.Dashboard.Component />} />
                <Route path="/dossiers" element={<Pages.DossierList.Component />} />
                <Route path="/dossiers/:id" element={<Pages.DossierDetail.Component />} />
                <Route path="/dossiers/:id/finalize" element={<Pages.Finalize.Component />} />
                <Route path="/engagements" element={<Pages.EngagementList.Component />} />
                <Route path="/engagements/nouveau" element={<Pages.EngagementUpload.Component />} />
                <Route path="/engagements/manuel" element={<Pages.EngagementNew.Component />} />
                <Route path="/engagements/:id" element={<Pages.EngagementDetail.Component />} />
                <Route path="/fournisseurs" element={<Pages.FournisseurList.Component />} />
                <Route path="/fournisseurs/:nom" element={<Pages.FournisseurDetail.Component />} />
                <Route path="/admin/claude-usage" element={<Pages.ClaudeUsage.Component />} />
                <Route path="/admin/rules-health" element={<Pages.RulesHealth.Component />} />
                <Route path="/settings" element={<Pages.Settings.Component />} />
                <Route path="*" element={<Pages.NotFound.Component />} />
              </Route>
            </Routes>
          </Suspense>
        </BrowserRouter>
      </ToastProvider>
    </ErrorBoundary>
  )
}
