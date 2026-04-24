import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
    warmup: {
      // Pre-transforme les fichiers les plus visites au boot du dev server.
      // L'utilisateur arrive sur Dashboard / DossierList -> ces routes sont
      // pretes des le premier clic au lieu d'attendre la transform a la
      // demande.
      clientFiles: [
        './src/App.tsx',
        './src/pages/Dashboard.tsx',
        './src/pages/DossierList.tsx',
        './src/pages/DossierDetail.tsx',
      ],
    },
  },
  optimizeDeps: {
    // Pre-bundle les deps critiques pour eviter les pauses du dev server
    // sur les premieres navigations.
    include: ['react', 'react-dom', 'react-router-dom', 'lucide-react'],
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    cssMinify: 'lightningcss',
    // Coupe la mesure de la taille gzippee du bundle pour gagner ~20-30%
    // de temps de build CI : la metrique reste disponible via `vite-bundle-analyzer`
    // si on la veut un jour.
    reportCompressedSize: false,
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('react-router') || id.includes('react-dom') || id.includes('node_modules/react/')) {
              return 'vendor-react'
            }
            if (id.includes('lucide-react')) return 'icons'
            if (id.includes('@tailwindcss')) return 'vendor-tailwind'
            return 'vendor'
          }
          // Composants partages charges dans plusieurs pages -> on les
          // isole pour qu'ils ne soient pas dupliques entre les chunks de
          // page lazy.
          if (id.includes('/src/components/dossier/') || id.includes('/src/components/Toast') || id.includes('/src/components/Modal')) {
            return 'shared-ui'
          }
        },
      },
    },
  },
})
