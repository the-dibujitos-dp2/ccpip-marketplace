import { defineConfig, devices } from '@playwright/test'

/**
 * Pruebas de punta a punta contra el stack de e2e/docker-compose.yml (imagenes de produccion).
 * En local usa el Chrome instalado; en CI, el Chromium de Playwright.
 */
export default defineConfig({
  testDir: './tests',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:4173',
    locale: 'es-PE',
    timezoneId: 'America/Lima',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'web',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 }, channel: process.env.CI ? undefined : 'chrome' },
    },
    {
      name: 'movil',
      use: { ...devices['Pixel 7'], channel: process.env.CI ? undefined : 'chrome' },
      testMatch: /movil\.spec\.js/,
    },
  ],
})
