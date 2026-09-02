<template>
  <nav class="topSegmentedNav" role="tablist" aria-label="一级导航">
    <button
      v-for="item in items"
      :key="item.key"
      class="segmented-item"
      :class="{ active: item.key === activeKey }"
      type="button"
      role="tab"
      :aria-selected="item.key === activeKey"
      @click="$emit('select', item.key)"
    >
      {{ item.label }}
    </button>
  </nav>
</template>

<script setup lang="ts">
export interface SegmentedNavItem {
  key: string
  label: string
}

defineProps<{
  items: SegmentedNavItem[]
  activeKey: string
}>()

defineEmits<{
  (e: 'select', key: string): void
}>()
</script>
