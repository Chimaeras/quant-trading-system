<template>
  <div class="notfound-shell">
    <div class="notfound-card">
      <p class="code">404</p>
      <h1 class="title">页面不存在</h1>
      <p class="desc">
        没有匹配的页面：<span class="path">{{ attempted }}</span>
      </p>
      <div class="actions">
        <el-button type="primary" @click="goHome">返回首页</el-button>
        <el-button @click="goBack">返回上一页</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const attempted = computed(() => route.fullPath || '/')

function goHome() {
  router.push('/')
}

function goBack() {
  // 直接落地本页时没有历史记录，回退无效果，此时退回首页。
  if (window.history.length > 1) {
    router.back()
    return
  }
  router.push('/')
}
</script>

<style scoped>
.notfound-shell {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  background: var(--bg-0);
  color: var(--text-primary);
}

.notfound-card {
  width: min(520px, 100%);
  padding: 32px;
  text-align: center;
  border: 1px solid var(--glass-border);
  border-radius: 12px;
  background: var(--bg-1);
}

.code {
  margin: 0;
  font-size: 56px;
  font-weight: 700;
  line-height: 1;
  color: var(--text-dim);
}

.title {
  margin: 12px 0 0;
  font-size: 20px;
  font-weight: 600;
}

.desc {
  margin: 12px 0 24px;
  font-size: 13px;
  color: var(--text-secondary);
  word-break: break-all;
}

.path {
  color: var(--text-primary);
}

.actions {
  display: flex;
  gap: 12px;
  justify-content: center;
}
</style>
