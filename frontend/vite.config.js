import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // En desarrollo, /api se redirige al backend Spring Boot (evita configurar CORS)
    // API_PROXY permite apuntar a otro backend, p. ej. el stack de e2e (http://localhost:4173)
    proxy: {
      '/api': process.env.API_PROXY ?? 'http://localhost:8080',
    },
  },
  // CCPIP-27: pruebas con Vitest + Testing Library. La cobertura (lcov) la lee SonarQube.
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
    coverage: {
      provider: 'v8',
      // Rutas relativas a la raiz del repo, que es desde donde analiza SonarQube
      reporter: ['text-summary', ['lcov', { projectRoot: '..' }]],
      include: ['src/**/*.{js,jsx}'],
      exclude: ['src/main.jsx', 'src/test/**'],
    },
  },
})
