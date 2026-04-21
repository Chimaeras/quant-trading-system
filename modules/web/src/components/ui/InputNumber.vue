<template>
  <div class="num-wrap">
    <button @click="stepDown">-</button>
    <input :value="modelValue" type="number" @input="onInput" />
    <button @click="stepUp">+</button>
  </div>
</template>

<script setup lang="ts">
const props = withDefaults(defineProps<{ modelValue: number; min?: number; max?: number; step?: number }>(), {
  min: Number.NEGATIVE_INFINITY,
  max: Number.POSITIVE_INFINITY,
  step: 1
})
const emit = defineEmits<{ (e: "update:modelValue", value: number): void }>()

function clamp(v: number) {
  return Math.min(props.max, Math.max(props.min, v))
}
function stepUp() {
  emit("update:modelValue", clamp(props.modelValue + props.step))
}
function stepDown() {
  emit("update:modelValue", clamp(props.modelValue - props.step))
}
function onInput(event: Event) {
  const next = Number((event.target as HTMLInputElement).value)
  if (Number.isFinite(next)) emit("update:modelValue", clamp(next))
}
</script>

<style scoped>
.num-wrap {
  display: inline-flex;
  align-items: center;
  border: 1px solid rgba(145, 196, 255, 0.24);
  border-radius: var(--r-xs);
  background: rgba(9, 18, 34, 0.72);
}
input {
  width: 78px;
  background: transparent;
  border: 0;
  color: var(--text-primary);
  text-align: center;
  outline: none;
}
button {
  width: 28px;
  height: 30px;
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}
</style>
