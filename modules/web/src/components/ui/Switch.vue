<template>
  <button class="ui-switch" :class="{ on: modelValue }" type="button" @click="emit('update:modelValue', !modelValue)">
    <span class="thumb" />
  </button>
</template>

<script setup lang="ts">
defineProps<{
  modelValue: boolean
}>()

const emit = defineEmits<{
  "update:modelValue": [value: boolean]
}>()
</script>

<style scoped>
.ui-switch {
  width: 42px;
  height: 24px;
  border-radius: 999px;
  border: 1px solid rgba(145, 196, 255, 0.35);
  background: rgba(145, 196, 255, 0.2);
  padding: 2px;
  position: relative;
  cursor: pointer;
  transition: background var(--dur-mid);
}

.thumb {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #fff;
  display: block;
  transition: transform var(--dur-mid);
}

.ui-switch.on {
  background: rgba(28, 214, 166, 0.45);
}

.ui-switch.on .thumb {
  transform: translateX(18px);
}
</style>
