import { lazy, Suspense } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { ToastProvider } from './components/Toast'
import Layout from './components/Layout'

const Dashboard = lazy(() => import('./pages/Dashboard'))
const DossierList = lazy(() => import('./pages/DossierList'))
const DossierDetail = lazy(() => import('./pages/DossierDetail'))
const Settings = lazy(() => import('./pages/Settings'))

export default function App() {
  return (
    <ToastProvider>
      <BrowserRouter>
        <Suspense fallback={<div className="loading">Chargement...</div>}>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<Dashboard />} />
              <Route path="/dossiers" element={<DossierList />} />
              <Route path="/dossiers/:id" element={<DossierDetail />} />
              <Route path="/settings" element={<Settings />} />
            </Route>
          </Routes>
        </Suspense>
      </BrowserRouter>
    </ToastProvider>
  )
}
