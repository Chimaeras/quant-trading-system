import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  base: '/',  // 构建后资源路径，部署到 8080 根路径
  plugins: [
    vue(),
    AutoImport({
      resolvers: [ElementPlusResolver()],
      // 自动导入 Vue 与 Element Plus 的 API（ref/reactive/ElMessage 等）
      imports: ['vue'],
      dts: 'src/types/auto-imports.d.ts',
      eslintrc: { enabled: false }
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      // 自动注册 Element Plus 组件（<el-table /> 等无需 import）
      dts: 'src/types/components.d.ts'
    })
  ],
  resolve: {
    alias: [
      { find: '@', replacement: resolve(__dirname, 'src') }
    ]
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/pyapi': {
        target: 'http://127.0.0.1:8790',
        changeOrigin: true,
        rewrite: (path: string) => path.replace(/^\/pyapi/, '')
      }
    }
  },
  build: {
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules')) {
            if (id.includes('echarts') || id.includes('zrender')) {
              return 'echarts'
            }
            if (id.includes('element-plus') || id.includes('@element-plus')) {
              return 'element-plus'
            }
            if (id.includes('vue') || id.includes('pinia')) {
              return 'vue-vendor'
            }
          }
        }
      }
    }
  }
})
