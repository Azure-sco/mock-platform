import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '')
  const controlTarget = env.VITE_CONTROL_TARGET || 'http://localhost:19090'
  const runtimeTarget = env.VITE_RUNTIME_TARGET || 'http://localhost:19091'

  return {
    plugins: [vue()],
    server: {
      port: 5173,
      proxy: {
        '/api': controlTarget,
        '/runtime-actuator': {
          target: runtimeTarget,
          rewrite: (path) => path.replace(/^\/runtime-actuator/, '/actuator'),
        },
      },
    },
  }
})
