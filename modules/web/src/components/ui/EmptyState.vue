<template>
  <div class="empty-state glass">
    <p class="title">{{ title }}</p>
    <p class="desc">{{ description }}</p>
    <slot />
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  description?: string
}>()
</script>

<style scoped>
.empty-state {
  min-height: 200px;
  border-radius: var(--r-md);
  display: grid;
  place-items: center;
  text-align: center;
  padding: var(--sp-6);
}

.title {
  margin: 0;
  font-size: var(--fz-lg);
}

.desc {
  margin: 8px 0 0;
  font-size: var(--fz-sm);
  color: var(--text-secondary);
}
</style>
