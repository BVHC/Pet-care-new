import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

// Proxy bam theo VITE_API_URL de khong lech voi axios.ts khi BE chay port khac
// (8080 hay bi service khac chiem). Khong co .env thi ve mac dinh 8080.
export default defineConfig(({ mode }) => ({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: loadEnv(mode, process.cwd(), '').VITE_API_URL || 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  publicDir: 'public',
}))
