import { createApp } from 'vue'
import { createPinia } from 'pinia'
// Element Plus 按需引入：组件、API 及其样式由 unplugin-vue-components / unplugin-auto-import 的
// ElementPlusResolver 自动注册，无需 app.use(ElementPlus) 与全量 CSS 导入
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.mount('#app')
