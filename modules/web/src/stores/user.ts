import { defineStore } from 'pinia'
import { ref } from 'vue'
import { login as apiLogin } from '@/api/auth'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const username = ref(localStorage.getItem('username') || '')

  async function login(form: { username: string; password: string }) {
    const res = await apiLogin(form)
    token.value = res.data.token
    username.value = form.username
    localStorage.setItem('token', res.data.token)
    localStorage.setItem('username', form.username)
    return res
  }

  function logout() {
    token.value = ''
    username.value = ''
    localStorage.removeItem('token')
    localStorage.removeItem('username')
  }

  return { token, username, login, logout }
})
