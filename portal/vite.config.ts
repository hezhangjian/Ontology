import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  build: {
    chunkSizeWarningLimit: 1200,
    rollupOptions: {
      output: {
        manualChunks: {
          antd: ['antd'],
          icons: ['@ant-design/icons'],
        },
      },
    },
  },
  plugins: [react()],
  server: {
    port: 3000,
  },
  preview: {
    port: 3000,
  },
});
