<template>
  <span class="status-chip" :class="status">
    <span class="dot">
      <span v-if="status === 'running'" class="pulse" />
    </span>
    <span>{{ label || statusText }}</span>
  </span>
</template>

<script setup lang="ts">
import { computed } from "vue"

const props = defineProps<{
  status: "success" | "running" | "failed" | "warning" | "idle"
  label?: string
}>()

const statusText = computed(() => {
  const map = {
    success: "成功",
    running: "运行中",
    failed: "失败",
    warning: "警告",
    idle: "空闲"
  }
  return map[props.status]
})
</script>

<style scoped>
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 4px 8px;
  border-radius: var(--r-xs);
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  font-size: var(--fz-xs);
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  position: relative;
}

.pulse {
  position: absolute;
  inset: -4px;
  border-radius: 50%;
  background: var(--running);
  animation: pulse 1.4s ease-out infinite;
}

.success .dot {
  background: var(--success);
}
.running .dot {
  background: var(--running);
}
.failed .dot {
  background: var(--danger);
}
.warning .dot {
  background: var(--warning);
}
.idle .dot {
  background: var(--text-dim);
}
</style>
