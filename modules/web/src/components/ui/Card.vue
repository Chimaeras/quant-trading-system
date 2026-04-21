<template>
  <article class="ui-card">
    <p class="label">{{ label }}</p>
    <p class="value text-number">{{ value }}</p>
    <p v-if="hint" class="hint" :class="hintType">{{ hint }}</p>
  </article>
</template>

<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  hint?: string
  hintType?: "success" | "danger" | "warning" | "default"
}>()
</script>

<style scoped>
.ui-card {
  background: rgba(19, 33, 55, 0.68);
  border: 1px solid rgba(145, 196, 255, 0.2);
  border-radius: var(--r-sm);
  padding: var(--sp-3) var(--sp-4);
  transition: transform var(--dur-fast), border-color var(--dur-mid), box-shadow var(--dur-mid);
}

.ui-card:hover {
  transform: translateY(-2px);
  border-color: var(--glass-border-strong);
  box-shadow: var(--shadow-glow);
}

.label {
  margin: 0;
  font-size: var(--fz-xs);
  color: var(--text-secondary);
}

.value {
  margin: 8px 0 0;
  font-size: 24px;
}

.hint {
  margin: 8px 0 0;
  font-size: var(--fz-xs);
}

.hint.default {
  color: var(--text-secondary);
}
.hint.success {
  color: var(--success);
}
.hint.danger {
  color: var(--danger);
}
.hint.warning {
  color: var(--warning);
}
</style>
