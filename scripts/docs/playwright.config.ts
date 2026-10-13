import { defineConfig } from '@playwright/test'
import net from 'node:net'

// Reserve a free loopback port; never attach to another task's development server.
const reservation = net.createServer()
await new Promise<void>(resolve => reservation.listen(0, '127.0.0.1', resolve))
const port = (reservation.address() as net.AddressInfo).port
await new Promise<void>((resolve, reject) => reservation.close(error => error ? reject(error) : resolve()))

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  workers: 1,
  timeout: 90_000,
  use: { baseURL: `http://127.0.0.1:${port}/crypto-census/`, headless: true, trace: 'retain-on-failure' },
  reporter: 'list',
  webServer: {
    command: `npm run docs:preview -- --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}/crypto-census/`,
    reuseExistingServer: false,
    timeout: 30_000,
  },
})
