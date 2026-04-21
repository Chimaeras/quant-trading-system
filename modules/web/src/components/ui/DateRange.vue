<template>
  <div class="date-range">
    <input type="date" :value="modelValue?.[0] || ''" @change="onStartChange" />
    <span>至</span>
    <input type="date" :value="modelValue?.[1] || ''" @change="onEndChange" />
  </div>
</template>

<script setup lang="ts">
import { computed } from "vue"

const props = defineProps<{ modelValue: [string, string] | null }>()
const emit = defineEmits<{ (e: "update:modelValue", value: [string, string]): void }>()

const start = computed(() => props.modelValue?.[0] || "")
const end = computed(() => props.modelValue?.[1] || "")

function onStartChange(event: Event) {
  emit("update:modelValue", [(event.target as HTMLInputElement).value, end.value])
}
function onEndChange(event: Event) {
  emit("update:modelValue", [start.value, (event.target as HTMLInputElement).value])
}
</script>

<style scoped>
.date-range {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
input {
  height: 32px;
  border: 1px solid rgba(145, 196, 255, 0.24);
  border-radius: var(--r-xs);
  background: rgba(9, 18, 34, 0.72);
  color: var(--text-primary);
  padding: 0 8px;
}
</style>
