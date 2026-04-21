<template>
  <div v-if="modelValue" class="dialog-mask" @click.self="emit('update:modelValue', false)">
    <section class="dialog-panel glass">
      <header class="dialog-header">
        <strong>{{ title }}</strong>
        <button class="close-btn" @click="emit('update:modelValue', false)">×</button>
      </header>
      <div class="dialog-body">
        <slot />
      </div>
      <footer v-if="$slots.footer" class="dialog-footer">
        <slot name="footer" />
      </footer>
    </section>
  </div>
</template>

<script setup lang="ts">
defineProps<{ modelValue: boolean; title?: string }>()
const emit = defineEmits<{ (e: "update:modelValue", value: boolean): void }>()
</script>

<style scoped>
.dialog-mask {
  position: fixed;
  inset: 0;
  background: rgba(4, 8, 16, 0.64);
  display: grid;
  place-items: center;
  z-index: 1200;
}

.dialog-panel {
  width: min(840px, calc(100vw - 24px));
  max-height: calc(100vh - 24px);
  overflow: auto;
  border-radius: var(--r-md);
}

.dialog-header,
.dialog-footer {
  padding: 12px 16px;
  border-bottom: 1px solid rgba(145, 196, 255, 0.16);
}

.dialog-footer {
  border-bottom: 0;
  border-top: 1px solid rgba(145, 196, 255, 0.16);
}

.dialog-body {
  padding: 14px 16px;
}

.close-btn {
  margin-left: auto;
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  font-size: 20px;
  cursor: pointer;
}

.dialog-header {
  display: flex;
  align-items: center;
}
</style>
