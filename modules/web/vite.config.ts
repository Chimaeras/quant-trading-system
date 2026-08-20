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
        // 观察池包含写操作，开发环境也统一经过 Java 鉴权代理，
        // 不允许浏览器绕过后端直接访问本地 watch-serve。
        target: 'http://localhost:8080',
        changeOrigin: true,
        configure(proxy) {
          // 浏览器面对的是 5173 同源请求；转发到 8080 后移除原始 Origin，
          // 避免 Spring 把这次服务端代理误判为未配置的跨域访问。
          proxy.on('proxyReq', (proxyReq) => proxyReq.removeHeader('origin'))
        }
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
