<template>
  <div class="login-page">
    <div class="login-box glass">
      <h1>QUANT TERMINAL</h1>
      <p class="tip">深色专业交易终端</p>
      <label class="field">
        <span>用户名</span>
        <UiInput v-model="form.username" placeholder="请输入用户名" />
      </label>
      <label class="field">
        <span>密码</span>
        <UiInput v-model="form.password" type="password" placeholder="请输入密码" @enter="handleLogin" />
      </label>
      <UiButton variant="primary" :loading="loading" block @click="handleLogin">登录</UiButton>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import UiInput from '@/components/ui/Input.vue'
import UiButton from '@/components/ui/Button.vue'

const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const form = reactive({
  username: 'admin',
  password: '123456'
})

const handleLogin = async () => {
  if (!form.username || !form.password) {
    return
  }
  loading.value = true
  try {
    userStore.login(form)
    router.push('/data/overview')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  background:
    radial-gradient(circle at 80% 10%, rgba(88, 185, 255, 0.3), transparent 35%),
    linear-gradient(140deg, var(--bg-0), var(--bg-1) 46%, var(--bg-2));
  display: flex;
  align-items: center;
  justify-content: center;
}
.login-box {
  width: min(420px, 92vw);
  padding: 36px;
  border-radius: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.login-box h1 {
  color: var(--text-primary);
  text-align: center;
  margin: 0;
  font-size: 22px;
  letter-spacing: 1px;
}
.tip {
  color: var(--text-secondary);
  font-size: var(--fz-sm);
  text-align: center;
  margin: 0 0 6px;
}

.field {
  display: grid;
  gap: 6px;
}

.field span {
  color: var(--text-secondary);
  font-size: var(--fz-xs);
}
</style>
