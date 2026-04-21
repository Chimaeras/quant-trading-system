<template>
  <button
    class="ui-btn"
    :class="[variant, { loading, block }]"
    :disabled="disabled || loading"
    type="button"
  >
    <span v-if="loading" class="spinner" />
    <slot />
  </button>
</template>

<script setup lang="ts">
defineProps<{
  variant?: "primary" | "ghost" | "danger"
  loading?: boolean
  disabled?: boolean
  block?: boolean
}>()
</script>

<style scoped>
.ui-btn {
  border: 1px solid transparent;
  border-radius: var(--r-sm);
  height: 34px;
  padding: 0 14px;
  cursor: pointer;
  color: var(--text-primary);
  background: var(--glass-bg-strong);
  transition: transform var(--dur-fast), border-color var(--dur-mid), opacity var(--dur-mid);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.ui-btn:hover {
  transform: translateY(-1px);
  border-color: var(--glass-border-strong);
}

.ui-btn.primary {
  background: var(--accent-grad);
  color: #032237;
  font-weight: 600;
}

.ui-btn.ghost {
  background: rgba(88, 185, 255, 0.08);
  border-color: rgba(88, 185, 255, 0.2);
}

.ui-btn.danger {
  background: rgba(255, 107, 122, 0.18);
  border-color: rgba(255, 107, 122, 0.36);
  color: #ffd6db;
}

.ui-btn.block {
  width: 100%;
}

.ui-btn:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.spinner {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: rgba(255, 255, 255, 0.95);
  animation: spin 0.9s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
