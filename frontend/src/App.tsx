import { lazy, Suspense, useState } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { ToastProvider } from './components/Toast'
import ErrorBoundary from './components/ErrorBoundary'
import Layout from './components/Layout'
import Login from './pages/Login'

const Dashboard = lazy(() => import('./pages/Dashboard'))
const DossierList = lazy(() => import('./pages/DossierList'))
const DossierDetail = lazy(() => import('./pages/DossierDetail'))
const EngagementList = lazy(() => import('./pages/EngagementList'))
const EngagementDetail = lazy(() => import('./pages/EngagementDetail'))
const EngagementUpload = lazy(() => import('./pages/EngagementUpload'))
const EngagementNew = lazy(() => import('./pages/EngagementNew'))
const FournisseurList = lazy(() => import('./pages/FournisseurList'))
const FournisseurDetail = lazy(() => import('./pages/FournisseurDetail'))
const Settings = lazy(() => import('./pages/Settings'))
const Finalize = lazy(() => import('./pages/Finalize'))
const ClaudeUsage = lazy(() => import('./pages/ClaudeUsage'))
const RulesHealth = lazy(() => import('./pages/RulesHealth'))
const NotFound = lazy(() => import('./pages/NotFound'))

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
                <Route path="/" element={<Dashboard />} />
                <Route path="/dossiers" element={<DossierList />} />
                <Route path="/dossiers/:id" element={<DossierDetail />} />
                <Route path="/dossiers/:id/finalize" element={<Finalize />} />
                <Route path="/engagements" element={<EngagementList />} />
                <Route path="/engagements/nouveau" element={<EngagementUpload />} />
                <Route path="/engagements/manuel" element={<EngagementNew />} />
                <Route path="/engagements/:id" element={<EngagementDetail />} />
                <Route path="/fournisseurs" element={<FournisseurList />} />
                <Route path="/fournisseurs/:nom" element={<FournisseurDetail />} />
                <Route path="/admin/claude-usage" element={<ClaudeUsage />} />
                <Route path="/admin/rules-health" element={<RulesHealth />} />
                <Route path="/settings" element={<Settings />} />
                <Route path="*" element={<NotFound />} />
              </Route>
            </Routes>
          </Suspense>
        </BrowserRouter>
      </ToastProvider>
    </ErrorBoundary>
  )
}
