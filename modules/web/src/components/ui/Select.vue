<template>
  <select class="ui-select" :value="modelValue" @change="onChange">
    <option
      v-for="item in options"
      :key="item.value"
      :value="item.value"
    >
      {{ item.label }}
    </option>
  </select>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: string
  options: Array<{ label: string; value: string }>
}>()

const emit = defineEmits<{
  "update:modelValue": [value: string]
}>()

function onChange(event: Event) {
  emit("update:modelValue", (event.target as HTMLSelectElement).value)
}
</script>

<style scoped>
.ui-select {
  width: 100%;
  height: 34px;
  border-radius: var(--r-sm);
  border: 1px solid rgba(145, 196, 255, 0.2);
  background: rgba(10, 19, 34, 0.72);
  color: var(--text-primary);
  padding: 0 10px;
}

.ui-select:focus {
  border-color: rgba(88, 185, 255, 0.7);
  box-shadow: var(--focus-ring);
  outline: none;
}
</style>
