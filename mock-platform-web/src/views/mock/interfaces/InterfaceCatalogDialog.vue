<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { createApi, createProvider, getProviderApis, getProviders } from '../../../api/admin'
import { ensureContract } from '../../../api/admin/interfaceWorkflow'
import { jsonObject, schemaFromExample } from '../../../utils/interfaceWorkflow'
import type { MockApi, Provider } from '../../../types/admin'
import JsonEditor from '../../../components/JsonEditor.vue'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ providers: Provider[]; operator: string; api?: MockApi }>()
const emit = defineEmits<{ saved: [api: MockApi] }>()
const busy = ref(false)
const error = ref('')
const stage = ref('')
const schemaReady = ref(false)
const savedApi = ref<MockApi>()
const draft = reactive({ providerId: 0, providerName: '', providerCode: '', apiName: '', apiCode: '',
  method: 'POST', path: '', contentType: 'application/json', request: '{}', response: '{"code":"0","data":{}}',
  requestSchema: '{}', responseSchema: '{}' })
watch(visible, open => {
  if (!open) return
  error.value = ''; schemaReady.value = false; savedApi.value = props.api
  Object.assign(draft, { providerId: props.api?.providerId ?? props.providers[0]?.id ?? 0, providerName: '',
    providerCode: `provider-${crypto.randomUUID().slice(0, 8)}`, apiName: props.api?.apiName ?? '',
    apiCode: props.api?.apiCode ?? `api-${crypto.randomUUID().slice(0, 8)}`, method: props.api?.httpMethod ?? 'POST',
    path: props.api?.path ?? '', contentType: props.api?.contentType ?? 'application/json',
    request: '{}', response: '{"code":"0","data":{}}', requestSchema: '{}', responseSchema: '{}' })
})
function suggest() {
  try {
    draft.requestSchema = JSON.stringify(draft.contentType.includes('application/json')
      ? schemaFromExample(jsonObject(draft.request, '请求示例')) : {}, null, 2)
    draft.responseSchema = JSON.stringify(schemaFromExample(jsonObject(draft.response, '响应示例')), null, 2)
    schemaReady.value = true; error.value = ''
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '示例格式错误' }
}
async function save() {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    const requestSchema = jsonObject(draft.requestSchema, '请求 Schema')
    const responseSchema = jsonObject(draft.responseSchema, '响应 Schema')
    if (!savedApi.value) {
      if (!draft.apiName.trim() || !draft.path.startsWith('/')) throw new Error('请填写接口名称，以及以 / 开头的路径')
      if (!draft.providerId) {
        if (!draft.providerName.trim()) throw new Error('请填写服务商名称')
        stage.value = '保存服务商'
        let provider = (await getProviders({ page: 1, size: 10000 })).records.find(p => p.providerCode === draft.providerCode)
        if (!provider) provider = await createProvider({ providerCode: draft.providerCode, providerName: draft.providerName,
          owner: props.operator, status: 'ENABLED' })
        draft.providerId = provider.id
      }
      stage.value = '保存接口'
      const existing = (await getProviderApis(draft.providerId, { page: 1, size: 10000 })).records.find(a => a.apiCode === draft.apiCode)
      if (existing && (existing.path !== draft.path || existing.httpMethod !== draft.method || existing.contentType !== draft.contentType)) {
        throw new Error('接口编码已存在且请求配置不同，请修改编码')
      }
      savedApi.value = existing ?? await createApi({ providerId: draft.providerId, apiCode: draft.apiCode,
        apiName: draft.apiName, httpMethod: draft.method, path: draft.path, contentType: draft.contentType,
        owner: props.operator, status: 'ENABLED' })
    }
    stage.value = '校验并发布契约'
    await ensureContract(savedApi.value.id, { requestSchema, responseSchema, sourceType: 'MANUAL',
      examples: [], errorCodes: [], businessKeyExtractor: {}, signatureMetadata: {} })
    emit('saved', savedApi.value); visible.value = false
  } catch (failure) {
    error.value = `${stage.value}：${failure instanceof Error ? failure.message : '操作失败'}。已保存的步骤可在重试时继续。`
  } finally { busy.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" :title="api ? '配置接口契约' : '新建接口'" width="min(850px, 95vw)"
    :close-on-click-modal="!busy" :show-close="!busy" :close-on-press-escape="!busy">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" :disabled="busy">
      <template v-if="!api && !savedApi">
        <el-form-item label="服务商"><el-select v-model="draft.providerId"><el-option label="新增服务商" :value="0" />
          <el-option v-for="p in providers" :key="p.id" :label="p.providerName" :value="p.id" /></el-select></el-form-item>
        <el-form-item v-if="!draft.providerId" label="服务商名称" required><el-input v-model="draft.providerName" /></el-form-item>
        <el-form-item label="接口名称" required><el-input v-model="draft.apiName" placeholder="例如：创建签署流程" /></el-form-item>
        <div class="request-line">
          <el-form-item label="方法"><el-select v-model="draft.method"><el-option v-for="method in ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']" :key="method" :value="method" /></el-select></el-form-item>
          <el-form-item label="请求路径" required><el-input v-model="draft.path" placeholder="/sign/create-and-start" /></el-form-item>
        </div>
        <el-form-item label="Content-Type"><el-input v-model="draft.contentType" /></el-form-item>
        <details><summary>接入编码（已有 SDK 时填写对应编码）</summary>
          <el-form-item v-if="!draft.providerId" label="Provider Code"><el-input v-model="draft.providerCode" /></el-form-item>
          <el-form-item label="API Code"><el-input v-model="draft.apiCode" /></el-form-item>
        </details>
      </template>
      <el-alert v-if="savedApi && !api" type="info" :closable="false" :title="`接口 ${savedApi.apiName} 已保存，继续确认格式即可`" />
      <p>填写示例生成格式草稿，再确认请求和响应允许的类型。示例值不会成为固定值或必填约束。</p>
      <el-form-item v-if="draft.contentType.includes('application/json')" label="请求 JSON 示例"><JsonEditor v-model="draft.request" :disabled="busy" /></el-form-item>
      <el-form-item label="响应 JSON 示例"><JsonEditor v-model="draft.response" :disabled="busy" /></el-form-item>
      <el-button @click="suggest">从示例生成格式</el-button>
      <template v-if="schemaReady">
        <el-form-item label="请求格式（确认后可调整）"><JsonEditor v-model="draft.requestSchema" :disabled="busy" /></el-form-item>
        <el-form-item label="响应格式（须同时允许成功与失败的结构）"><JsonEditor v-model="draft.responseSchema" :disabled="busy" /></el-form-item>
      </template>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!schemaReady" :loading="busy" @click="save">{{ busy ? stage : '确认格式并保存接口' }}</el-button></template>
  </el-dialog>
</template>

<style scoped>
.request-line { display: grid; grid-template-columns: 130px 1fr; gap: 16px; }
summary { cursor: pointer; margin-bottom: 14px; }
</style>
