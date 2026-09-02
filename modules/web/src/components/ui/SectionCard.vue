<template>
  <section class="section-card" :class="{ 'sc-pad-sm': pad === 'sm' }">
    <header v-if="title || $slots.extra" class="sc-head">
      <h3 v-if="title" class="sc-title">{{ title }}</h3>
      <div v-else class="sc-title"><slot name="title" /></div>
      <div v-if="$slots.extra" class="sc-extra">
        <slot name="extra" />
      </div>
    </header>
    <div class="sc-body">
      <slot />
    </div>
  </section>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  title?: string
  pad?: 'md' | 'sm'
}>(), {
  pad: 'md'
})
</script>

<style scoped>
.sc-body {
  min-width: 0;
}
</style>
