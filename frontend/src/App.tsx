import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import Layout from './components/Layout'
import DossierList from './pages/DossierList'
import DossierDetail from './pages/DossierDetail'
import Settings from './pages/Settings'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/" element={<Navigate to="/dossiers" replace />} />
          <Route path="/dossiers" element={<DossierList />} />
          <Route path="/dossiers/:id" element={<DossierDetail />} />
          <Route path="/settings" element={<Settings />} />
        </Route>
      </Routes>
    </BrowserRouter>
  )
}
