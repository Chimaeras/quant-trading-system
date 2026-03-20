import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useSystemStore = defineStore('system', () => {
  const theme = ref<'light' | 'dark'>('dark')
  const commissionRate = ref(0.0003)
  const stampDuty = ref(0.001)

  function setTheme(t: 'light' | 'dark') {
    theme.value = t
    document.documentElement.classList.toggle('dark', t === 'dark')
  }

  return { theme, commissionRate, stampDuty, setTheme }
})
