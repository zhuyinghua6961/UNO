import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: process.env.GATEWAY_URL ?? 'http://127.0.0.1:8080' },
      '/ws': { target: process.env.GATEWAY_URL ?? 'http://127.0.0.1:8080', ws: true },
    },
  },
  test: { environment: 'jsdom', include: ['src/**/*.test.ts'] },
})
