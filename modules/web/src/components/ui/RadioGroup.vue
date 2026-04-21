<template>
  <div class="radio-group">
    <button
      v-for="option in options"
      :key="option.value"
      class="radio-item"
      :class="{ active: modelValue === option.value }"
      @click="emit('update:modelValue', option.value)"
    >
      {{ option.label }}
    </button>
  </div>
</template>

<script setup lang="ts">
defineProps<{ modelValue: string; options: Array<{ label: string; value: string }> }>()
const emit = defineEmits<{ (e: "update:modelValue", value: string): void }>()
</script>

<style scoped>
.radio-group {
  display: inline-flex;
  border: 1px solid rgba(145, 196, 255, 0.28);
  border-radius: var(--r-xs);
  overflow: hidden;
}
.radio-item {
  border: 0;
  background: rgba(9, 18, 34, 0.72);
  color: var(--text-secondary);
  height: 30px;
  padding: 0 10px;
  cursor: pointer;
}
.radio-item.active {
  color: var(--text-primary);
  background: rgba(88, 185, 255, 0.2);
}
</style>
