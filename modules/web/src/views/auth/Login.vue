<template>
  <div class="login-page">
    <div class="login-card glass">
      <div class="login-header">
        <span class="brand-mark">Q</span>
        <div>
          <h1 class="login-title">量化选股系统</h1>
          <p class="login-subtitle">Quant Trading Terminal</p>
        </div>
      </div>
      <el-form @submit.prevent="submit" label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password placeholder="••••••••" />
        </el-form-item>
        <div class="login-actions">
          <el-button type="primary" :loading="loading" @click="submit">登录</el-button>
          <el-button @click="register">注册</el-button>
        </div>
      </el-form>
      <p class="tip">默认账户 admin / admin123（首次启动自动创建）。鉴权默认关闭，可在 application.yml 开启 quant.auth.enabled。</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '@/api/auth'
import { errorText } from '@/api/request'
import { useToast } from '@/composables/useToast'

const router = useRouter()
const route = useRoute()
const toast = useToast()
const username = ref('')
const password = ref('')
const loading = ref(false)

function resolvePostLoginPath(): string {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('/login')) {
    return redirect
  }
  return '/account/overview'
}

async function submit() {
  loading.value = true
  try {
    await authApi.login(username.value, password.value)
    toast.success('登录成功')
    router.push(resolvePostLoginPath())
  } catch (e: unknown) {
    // 登录失败兜底清理残留 token，避免无效凭证驻留
    authApi.logout()
    toast.error(errorText(e))
  } finally {
    loading.value = false
  }
}

async function register() {
  loading.value = true
  try {
    await authApi.register(username.value, password.value)
    toast.success('注册并登录成功')
    router.push(resolvePostLoginPath())
  } catch (e: unknown) {
    toast.error(errorText(e))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--sp-6);
}

.login-card {
  width: 420px;
  max-width: 100%;
  padding: var(--sp-7);
  border-radius: var(--r-lg);
}

.login-header {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-6);
}

.brand-mark {
  width: 48px;
  height: 48px;
  border-radius: var(--r-md);
  background: var(--accent-grad);
  color: #032237;
  font-weight: 800;
  font-size: 26px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 6px 18px rgba(88, 185, 255, 0.35);
  flex-shrink: 0;
}

.login-title {
  margin: 0;
  font-size: var(--fz-xl);
  font-weight: 700;
  color: var(--text-primary);
}

.login-subtitle {
  margin: 2px 0 0;
  font-size: var(--fz-xs);
  color: var(--text-dim);
  letter-spacing: 1px;
}

.login-actions {
  display: flex;
  gap: var(--sp-3);
  margin-top: var(--sp-2);
}

.tip {
  font-size: var(--fz-xs);
  color: var(--text-dim);
  margin: var(--sp-5) 0 0;
  line-height: var(--lh-normal);
}
</style>
