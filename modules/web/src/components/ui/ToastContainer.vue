<template>
  <div class="toast-wrap">
    <div v-for="item in toasts" :key="item.id" class="toast-item" :data-type="item.type">
      <span class="dot" />
      <span class="msg">{{ item.message }}</span>
      <button class="close-btn" @click="remove(item.id)">×</button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useToast } from "@/composables/useToast"

const { toasts, remove } = useToast()
</script>

<style scoped>
.toast-wrap {
  position: fixed;
  top: 18px;
  right: 18px;
  z-index: 2100;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.toast-item {
  width: 340px;
  min-height: 42px;
  display: flex;
  align-items: center;
  gap: 8px;
  border-radius: var(--r-sm);
  border: 1px solid rgba(145, 196, 255, 0.32);
  background: rgba(9, 18, 34, 0.95);
  color: var(--text-primary);
  box-shadow: var(--shadow-soft);
  padding: 10px 12px;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #58b9ff;
}

.toast-item[data-type="success"] .dot {
  background: #1cd6a6;
}
.toast-item[data-type="error"] .dot {
  background: #ff6b7a;
}
.toast-item[data-type="warning"] .dot {
  background: #f4c15d;
}

.msg {
  flex: 1;
  font-size: var(--fz-sm);
}

.close-btn {
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  font-size: 18px;
  cursor: pointer;
}
</style>
