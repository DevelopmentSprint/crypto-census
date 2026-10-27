import { defineConfig } from '@playwright/test'
import net from 'node:net'

// Reserve a free loopback port; never attach to another task's development server.
let port = Number(process.env.DOCS_TEST_PORT)
if (!port) {
  const reservation = net.createServer()
  await new Promise<void>(resolve => reservation.listen(0, '127.0.0.1', resolve))
  port = (reservation.address() as net.AddressInfo).port
  await new Promise<void>((resolve, reject) => reservation.close(error => error ? reject(error) : resolve()))
  // Worker processes load this config too; inherit the one preview server's port.
  process.env.DOCS_TEST_PORT = String(port)
}

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts',
  workers: 1,
  timeout: 90_000,
  use: { baseURL: `http://127.0.0.1:${port}/`, headless: true, trace: 'retain-on-failure' },
  reporter: 'list',
  webServer: {
    command: `npm run docs:preview -- --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}/`,
    reuseExistingServer: false,
    timeout: 30_000,
  },
})
