<script setup lang="ts">
import { reactive, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'

export interface ContractImportPayload {
  file: File
  options: { path?: string; method?: string; target: 'REQUEST' | 'RESPONSE' }
}

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ submitting: boolean; disabled: boolean }>()
const emit = defineEmits<{ submit: [payload: ContractImportPayload] }>()
const file = shallowRef<File | null>(null)
const options = reactive({ path: '', method: '', target: 'REQUEST' as 'REQUEST' | 'RESPONSE' })

watch(visible, (open) => {
  if (!open) return
  file.value = null
  Object.assign(options, { path: '', method: '', target: 'REQUEST' })
})

function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
}

function submit() {
  if (props.submitting || !file.value) return
  if (file.value.size > 5 * 1024 * 1024) {
    ElMessage.warning('文件不能超过 5 MB')
    return
  }
  const path = options.path.trim()
  const method = options.method.trim()
  if ((path && !method) || (!path && method)) {
    ElMessage.warning('OpenAPI Path 和 Method 必须同时填写')
    return
  }
  emit('submit', {
    file: file.value,
    options: { path: path || undefined, method: method || undefined, target: options.target },
  })
}
</script>

<template>
  <el-dialog v-model="visible" title="导入契约文件" width="min(620px, 92vw)">
    <el-alert type="info" :closable="false" show-icon title="支持不超过 5 MB 的 OpenAPI 3.0 JSON/YAML 或 JSON Schema。" />
    <el-form class="contract-form" label-position="top">
      <el-form-item label="本地文件" required>
        <input type="file" accept=".json,.yaml,.yml" @change="selectFile">
        <span v-if="file" class="field-hint">{{ file.name }}</span>
      </el-form-item>
      <div class="schema-grid">
        <el-form-item label="OpenAPI Path"><el-input v-model="options.path" placeholder="/api/orders" /></el-form-item>
        <el-form-item label="OpenAPI Method">
          <el-select v-model="options.method" clearable placeholder="自动选择单一操作" style="width: 100%">
            <el-option v-for="method in ['GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS']" :key="method" :label="method" :value="method" />
          </el-select>
        </el-form-item>
      </div>
      <el-form-item label="JSON Schema 导入目标">
        <el-radio-group v-model="options.target">
          <el-radio-button value="REQUEST">Request Schema</el-radio-button>
          <el-radio-button value="RESPONSE">Response Schema</el-radio-button>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="disabled || !file" @click="submit">导入草稿</el-button>
    </template>
  </el-dialog>
</template>
