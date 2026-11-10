import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

const backend = process.env.APM_BACKEND ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': backend, '/v1': backend } },
  test: { environment: 'node' },
});
