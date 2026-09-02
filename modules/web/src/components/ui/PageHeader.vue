<template>
  <div class="page-header">
    <div class="ph-title">
      <h1>{{ title }}</h1>
      <p v-if="subtitle" class="ph-subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="ph-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  subtitle?: string
}>()
</script>
