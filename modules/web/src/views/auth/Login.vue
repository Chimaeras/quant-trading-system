<template>
  <div class="login-page">
    <el-card class="login-card" shadow="hover">
      <template #header><span>登录量化系统</span></template>
      <el-form @submit.prevent="submit">
        <el-form-item label="用户名"><el-input v-model="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="password" type="password" show-password /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="submit">登录</el-button>
          <el-button @click="register">注册</el-button>
        </el-form-item>
      </el-form>
      <p class="tip">默认账户 admin / admin123（首次启动自动创建）。鉴权默认关闭，可在 application.yml 开启 quant.auth.enabled。</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import { useToast } from '@/composables/useToast'

const router = useRouter()
const toast = useToast()
const username = ref('admin')
const password = ref('admin123')
const loading = ref(false)

async function submit() {
  loading.value = true
  try {
    await authApi.login(username.value, password.value)
    toast.success('登录成功')
    router.push('/account/overview')
  } catch (e: any) {
    toast.error(e?.message || '登录失败')
  } finally {
    loading.value = false
  }
}

async function register() {
  loading.value = true
  try {
    await authApi.register(username.value, password.value)
    toast.success('注册并登录成功')
    router.push('/account/overview')
  } catch (e: any) {
    toast.error(e?.message || '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 24px; }
.login-card { width: 420px; max-width: 100%; }
.tip { font-size: 12px; color: var(--text-secondary); margin: 0; }
</style>
