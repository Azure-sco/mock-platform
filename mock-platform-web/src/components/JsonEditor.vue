<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  modelValue: string
  rows?: number
  disabled?: boolean
}>(), {
  rows: 6,
  disabled: false,
})

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const error = computed(() => {
  try {
    JSON.parse(props.modelValue)
    return ''
  } catch (failure) {
    if (!(failure instanceof SyntaxError)) return 'JSON 语法错误'
    const position = /position\s+(\d+)/i.exec(failure.message)?.[1]
    if (!position) return failure.message
    const offset = Number(position)
    const prefix = props.modelValue.slice(0, offset)
    const line = prefix.split('\n').length
    const column = offset - prefix.lastIndexOf('\n')
    return `第 ${line} 行，第 ${column} 列：${failure.message}`
  }
})

function format() {
  if (error.value) return
  emit('update:modelValue', JSON.stringify(JSON.parse(props.modelValue), null, 2))
}
</script>

<template>
  <div class="json-editor" :class="{ 'has-error': error }">
    <el-input
      :model-value="modelValue"
      type="textarea"
      :rows="rows"
      :disabled="disabled"
      spellcheck="false"
      @update:model-value="emit('update:modelValue', $event)"
    />
    <div class="json-editor-footer">
      <span :class="error ? 'json-error' : 'json-valid'">{{ error || 'JSON 语法正确' }}</span>
      <el-button link type="primary" :disabled="Boolean(error) || disabled" @click="format">格式化</el-button>
    </div>
  </div>
</template>
