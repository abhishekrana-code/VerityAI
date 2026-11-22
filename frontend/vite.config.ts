import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'path';

const apiProxy = {
  '/api': {
    // The E2E suite runs an isolated backend on 8081 and points the proxy
    // there through VITE_API_PROXY_TARGET; normal development keeps 8080.
    target: process.env.VITE_API_PROXY_TARGET || 'http://localhost:8080',
    changeOrigin: true,
  },
};

// Vite rejects requests whose Host header it does not recognise (DNS-rebinding
// protection). Tailscale Funnel serves the app from
// https://<host>.<tailnet>.ts.net[:port], so that suffix must be allowlisted.
const tunnelHosts = ['.ts.net'];

// Loopback only: Tailscale proxies to 127.0.0.1, so binding every interface
// would just widen the attack surface.
const loopback = '127.0.0.1';

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // Playwright specs live in e2e/ and must not be collected by Vitest.
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
  },
  server: {
    port: 5173,
    host: loopback,
    allowedHosts: tunnelHosts,
    proxy: apiProxy,
  },
  // `vite preview` serves the production build (no /@fs/ source access, no
  // HMR socket), which is what gets exposed through the tunnel.
  preview: {
    port: 5173,
    host: loopback,
    allowedHosts: tunnelHosts,
    proxy: apiProxy,
  },
});