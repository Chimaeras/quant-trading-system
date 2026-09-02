<template>
  <div class="page-shell" :class="{ flush }">
    <slot />
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  /** 取消最大宽度约束（全宽布局） */
  flush?: boolean
}>(), {
  flush: false
})
</script>
