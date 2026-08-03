import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { registerEChartsTheme } from './utils/echartsTheme'
import './style.css'

registerEChartsTheme()

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(ElementPlus)
app.use(router)
app.mount('#app')
