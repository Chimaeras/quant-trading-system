<template>
  <div class="skeleton" :style="{ height }" />
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    height?: string
  }>(),
  {
    height: "18px"
  }
)
</script>

<style scoped>
.skeleton {
  border-radius: var(--r-xs);
  background: linear-gradient(
    90deg,
    rgba(130, 149, 181, 0.18) 0%,
    rgba(130, 149, 181, 0.34) 45%,
    rgba(130, 149, 181, 0.18) 100%
  );
  background-size: 200% 100%;
  animation: wave 1.2s linear infinite;
}

@keyframes wave {
  from {
    background-position: 200% 0;
  }
  to {
    background-position: -200% 0;
  }
}
</style>
