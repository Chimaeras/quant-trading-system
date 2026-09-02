<template>
  <header class="topbar">
    <div class="brandLine">
      <span class="brand-mark">{{ brandMark }}</span>
      <div class="brand-name">
        <strong>{{ brandTitle }}</strong>
        <span>{{ brandSubtitle }}</span>
      </div>
    </div>

    <SegmentedNav :items="navItems" :active-key="activeKey" @select="$emit('select', $event)" />

    <div class="topActions">
      <slot />
    </div>
  </header>
</template>

<script setup lang="ts">
import SegmentedNav, { type SegmentedNavItem } from "./SegmentedNav.vue"

withDefaults(
  defineProps<{
    navItems: SegmentedNavItem[]
    activeKey: string
    brandTitle?: string
    brandSubtitle?: string
    brandMark?: string
  }>(),
  {
    brandTitle: "QUANT TERMINAL",
    brandSubtitle: "量化选股系统",
    brandMark: "Q"
  }
)

defineEmits<{
  (e: 'select', key: string): void
}>()
</script>
