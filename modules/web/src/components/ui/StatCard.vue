<template>
  <div class="stat-card" :class="{ compact }">
    <p class="label">{{ label }}</p>
    <p class="value"><slot name="value">{{ value }}</slot></p>
    <p v-if="hint" class="hint">{{ hint }}</p>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  label: string
  value?: string | number
  hint?: string
  compact?: boolean
}>(), {
  compact: false
})
</script>
