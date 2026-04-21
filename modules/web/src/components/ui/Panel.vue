<template>
  <section class="ui-panel glass" :class="{ elevated }">
    <header v-if="$slots.header || title" class="ui-panel__header">
      <div>
        <h3 class="ui-panel__title">{{ title }}</h3>
        <p v-if="subtitle" class="ui-panel__subtitle">{{ subtitle }}</p>
      </div>
      <slot name="header" />
    </header>
    <div class="ui-panel__content">
      <slot />
    </div>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  title?: string
  subtitle?: string
  elevated?: boolean
}>()
</script>

<style scoped>
.ui-panel {
  padding: var(--sp-4);
}

.ui-panel.elevated {
  border-color: var(--glass-border-strong);
  box-shadow: var(--shadow-soft), var(--shadow-glow);
}

.ui-panel__header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: var(--sp-3);
  margin-bottom: var(--sp-4);
}

.ui-panel__title {
  margin: 0;
  font-size: var(--fz-lg);
}

.ui-panel__subtitle {
  margin: 4px 0 0;
  color: var(--text-secondary);
  font-size: var(--fz-sm);
}

.ui-panel__content {
  min-height: 16px;
}
</style>
