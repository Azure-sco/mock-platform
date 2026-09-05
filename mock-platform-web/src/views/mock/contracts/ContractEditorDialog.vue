<script setup lang="ts">
import { reactive, watch } from 'vue'
import { ElMessage } from 'element-plus'
import JsonEditor from '../../../components/JsonEditor.vue'
import type { ContractMutation, JsonValue } from '../../../types/admin'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ submitting: boolean; disabled: boolean }>()
const emit = defineEmits<{ submit: [payload: ContractMutation] }>()

const emptyDraft = () => ({
  requestSchema: '{\n  "type": "object",\n  "properties": {}\n}',
  responseSchema: '{\n  "type": "object",\n  "properties": {}\n}',
  examples: '[]',
  errorCodes: '[]',
  businessKeyExtractor: '{}',
  signatureMetadata: '{}',
})
const draft = reactive(emptyDraft())

watch(visible, (open) => {
  if (open) Object.assign(draft, emptyDraft())
})

function parse(label: string, value: string): JsonValue {
  try {
    return JSON.parse(value) as JsonValue
  } catch {
    throw new Error(`${label} 不是合法 JSON`)
  }
}

function submit() {
  if (props.submitting) return
  try {
    emit('submit', {
      requestSchema: parse('Request Schema', draft.requestSchema),
      responseSchema: parse('Response Schema', draft.responseSchema),
      examples: parse('Examples', draft.examples),
      errorCodes: parse('Error Codes', draft.errorCodes),
      businessKeyExtractor: parse('Business Key Extractor', draft.businessKeyExtractor),
      signatureMetadata: parse('Signature Metadata', draft.signatureMetadata),
      sourceType: 'MANUAL',
    })
  } catch (failure) {
    ElMessage.warning(failure instanceof Error ? failure.message : '契约 JSON 解析失败')
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="新建契约版本" width="min(920px, 92vw)" top="4vh">
    <el-alert type="info" :closable="false" show-icon title="创建后先执行校验，通过后才能发布。" />
    <el-form class="contract-form" label-position="top">
      <div class="schema-grid">
        <el-form-item label="Request Schema" required><JsonEditor v-model="draft.requestSchema" :rows="11" /></el-form-item>
        <el-form-item label="Response Schema" required><JsonEditor v-model="draft.responseSchema" :rows="11" /></el-form-item>
      </div>
      <div class="schema-grid compact-json-grid">
        <el-form-item label="Examples"><JsonEditor v-model="draft.examples" /></el-form-item>
        <el-form-item label="Error Codes"><JsonEditor v-model="draft.errorCodes" /></el-form-item>
        <el-form-item label="Business Key Extractor"><JsonEditor v-model="draft.businessKeyExtractor" /></el-form-item>
        <el-form-item label="Signature Metadata"><JsonEditor v-model="draft.signatureMetadata" /></el-form-item>
      </div>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="disabled" @click="submit">创建草稿</el-button>
    </template>
  </el-dialog>
</template>
