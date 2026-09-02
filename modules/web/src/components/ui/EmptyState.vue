<template>
  <div class="empty-state">
    <div v-if="$slots.icon" class="es-icon">
      <slot name="icon" />
    </div>
    <p class="es-message">{{ message }}</p>
    <div v-if="$slots.actions" class="es-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  message: string
}>(), {})
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--sp-3);
  padding: var(--sp-7) var(--sp-5);
  text-align: center;
  color: var(--muted);
}
.es-icon {
  font-size: 40px;
  line-height: 1;
  opacity: 0.6;
}
.es-message {
  margin: 0;
  font-size: var(--fz-md);
  color: var(--text-secondary);
}
.es-actions {
  display: flex;
  gap: var(--sp-2);
}
</style>
