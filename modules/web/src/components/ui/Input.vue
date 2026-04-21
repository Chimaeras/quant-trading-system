<template>
  <input
    class="ui-input"
    :value="modelValue"
    :type="type"
    :placeholder="placeholder"
    @input="onInput"
    @keyup.enter="$emit('enter')"
  />
</template>

<script setup lang="ts">
defineProps<{
  modelValue: string | number
  placeholder?: string
  type?: string
}>()

const emit = defineEmits<{
  "update:modelValue": [value: string]
  enter: []
}>()

function onInput(event: Event) {
  emit("update:modelValue", (event.target as HTMLInputElement).value)
}
</script>

<style scoped>
.ui-input {
  width: 100%;
  height: 34px;
  border-radius: var(--r-sm);
  border: 1px solid rgba(145, 196, 255, 0.2);
  background: rgba(10, 19, 34, 0.72);
  color: var(--text-primary);
  padding: 0 10px;
}

.ui-input::placeholder {
  color: var(--text-dim);
}

.ui-input:focus {
  border-color: rgba(88, 185, 255, 0.7);
  box-shadow: var(--focus-ring);
  outline: none;
}
</style>
