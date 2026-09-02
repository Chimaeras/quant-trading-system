<template>
  <nav class="subNav" role="tablist" aria-label="二级导航">
    <button
      v-for="item in visibleItems"
      :key="item.key || item.path"
      class="subnav-item"
      :class="{ active: item.active }"
      type="button"
      role="tab"
      :aria-selected="item.active"
      @click="$emit('select', item)"
    >
      {{ item.label }}
    </button>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'

export interface SubNavItem {
  key?: string
  path?: string
  label: string
  action?: 'logout'
  active?: boolean
  visible?: () => boolean
}

const props = defineProps<{
  items: SubNavItem[]
}>()

defineEmits<{
  (e: 'select', item: SubNavItem): void
}>()

const visibleItems = computed(() => props.items.filter((i) => !i.visible || i.visible()))
</script>
