<template>
  <textarea class="ui-textarea" :value="modelValue" @input="onInput" />
</template>

<script setup lang="ts">
defineProps<{ modelValue: string }>()
const emit = defineEmits<{ (e: "update:modelValue", value: string): void }>()
function onInput(event: Event) {
  emit("update:modelValue", (event.target as HTMLTextAreaElement).value)
}
</script>

<style scoped>
.ui-textarea {
  width: 100%;
  min-height: 86px;
  border: 1px solid rgba(145, 196, 255, 0.24);
  border-radius: var(--r-sm);
  padding: 10px;
  background: rgba(9, 18, 34, 0.72);
  color: var(--text-primary);
}

.ui-textarea:focus {
  outline: none;
  box-shadow: var(--focus-ring);
}
</style>
