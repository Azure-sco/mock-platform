<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import type { ContractVersion, JsonValue, MockApi, Scenario, ScenarioVersion } from '../../../types/admin'
import { saveResponse } from '../../../api/admin/interfaceWorkflow'
import { jsonObject } from '../../../utils/interfaceWorkflow'
import JsonEditor from '../../../components/JsonEditor.vue'

const visible = defineModel<boolean>({ required: true })
const props = defineProps<{ api: MockApi; contracts: ContractVersion[]; app: string; environment: string;
  scenario?: Scenario; version?: ScenarioVersion }>()
const emit = defineEmits<{ saved: [] }>()
const busy = ref(false)
const error = ref('')
const advanced = ref<string[]>([])
let openedScope = { app: '', environment: '', apiId: 0 }
const draft = reactive({ code: '', name: '', contractId: 0, priority: 100, status: 200,
  body: '{"code":"0","message":"成功","data":{}}', headers: '{"Content-Type":"application/json"}',
  rules: [] as { type: string; key: string; value: string; caseSensitive: boolean }[] })
watch(visible, open => {
  if (!open) return
  error.value = ''; advanced.value = []
  openedScope = { app: props.app, environment: props.environment, apiId: props.api.id }
  const version = props.version
  const response = version?.response as Record<string, JsonValue> | undefined
  Object.assign(draft, { code: props.scenario?.scenarioCode ?? `response-${crypto.randomUUID()}`,
    name: props.scenario?.scenarioName ?? '', contractId: version?.contractVersionId ?? props.contracts.find(c => c.status === 'PUBLISHED')?.id ?? 0,
    priority: version?.priority ?? 100, status: response?.httpStatus ?? 200,
    body: typeof response?.bodyTemplate === 'string' ? response.bodyTemplate : '{"code":"0","message":"成功","data":{}}',
    headers: JSON.stringify(response?.headers ?? { 'Content-Type': 'application/json' }, null, 2),
    rules: version ? (version.matchRules as Record<string, JsonValue>[]).map(rule => ({ type: String(rule.type), key: String(rule.key),
      value: String(rule.value), caseSensitive: Boolean(rule.caseSensitive) })) : [] })
})
async function save(submit: boolean) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try {
    if (props.app !== openedScope.app || props.environment !== openedScope.environment || props.api.id !== openedScope.apiId) {
      throw new Error('应用或环境已切换，请取消并在目标环境重新打开响应，避免保存到错误范围')
    }
    if (!draft.name.trim() || !draft.contractId) throw new Error('请填写响应名称并选择已发布契约')
    const body = JSON.parse(draft.body) as JsonValue
    if (draft.body.includes('${')) throw new Error('动态模板请通过高级场景管理编辑')
    const headers = jsonObject(draft.headers, '响应头')
    if (Object.values(headers).some(value => typeof value !== 'string')) throw new Error('响应头的值必须是字符串')
    if (draft.rules.some(rule => !rule.key.trim())) throw new Error('请填写每条条件的字段名')
    await saveResponse({ scenarioCode: draft.code, scenarioName: draft.name, providerId: props.api.providerId, apiId: props.api.id }, {
      contractVersionId: draft.contractId, priority: draft.priority,
      scope: { environments: [props.environment], apps: [props.app], tenants: [], testAccounts: [] },
      matchRules: draft.rules.map(rule => ({ ...rule, operator: 'EQ' })),
      response: { httpStatus: draft.status, headers, bodyTemplate: JSON.stringify(body) }, callbacks: [],
    }, submit)
    emit('saved'); visible.value = false
  } catch (failure) { error.value = failure instanceof Error ? failure.message : '保存失败，请重试' }
  finally { busy.value = false }
}
</script>

<template>
  <el-dialog v-model="visible" :title="version ? '编辑响应（保存为新版本）' : '添加响应'" width="min(850px, 95vw)"
    :close-on-click-modal="!busy" :show-close="!busy" :close-on-press-escape="!busy">
    <p>{{ api.apiName }} · {{ app }} · {{ environment }}</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-form label-position="top" :disabled="busy">
      <el-form-item label="响应名称" required><el-input v-model="draft.name" :disabled="Boolean(scenario)" placeholder="例如：创建成功、余额不足" /></el-form-item>
      <el-form-item label="匹配条件（全部满足时返回；无条件表示默认响应）">
        <div class="rule-list">
          <div v-for="(rule, index) in draft.rules" :key="index" class="rule">
            <el-select v-model="rule.type" aria-label="条件来源"><el-option label="请求体字段" value="JSON_PATH" /><el-option label="查询参数" value="QUERY" /><el-option label="请求头" value="HEADER" /></el-select>
            <el-input v-model="rule.key" :placeholder="rule.type === 'JSON_PATH' ? '$.result' : '字段名'" aria-label="条件字段" />
            <span>等于</span><el-input v-model="rule.value" placeholder="SUCCESS" aria-label="条件值" />
            <el-checkbox v-model="rule.caseSensitive">区分大小写</el-checkbox>
            <el-button :disabled="busy" @click="draft.rules.splice(index, 1)">删除</el-button>
          </div>
          <el-button @click="draft.rules.push({ type: 'JSON_PATH', key: '', value: '', caseSensitive: true })">添加条件</el-button>
        </div>
      </el-form-item>
      <el-form-item label="HTTP 状态码"><el-input-number v-model="draft.status" :min="100" :max="599" /></el-form-item>
      <el-form-item label="响应内容（直接填写 JSON，无需转义）" required><JsonEditor v-model="draft.body" :rows="12" :disabled="busy" /></el-form-item>
      <el-collapse v-model="advanced"><el-collapse-item title="高级设置" name="advanced">
        <el-form-item label="契约版本"><el-select v-model="draft.contractId"><el-option v-for="c in contracts.filter(c => c.status === 'PUBLISHED')" :key="c.id" :label="`v${c.versionNo}`" :value="c.id" /></el-select></el-form-item>
        <el-form-item label="优先级（同优先级规则重叠时校验会拒绝）"><el-input-number v-model="draft.priority" :min="0" :max="100000" /></el-form-item>
        <el-form-item label="响应头"><JsonEditor v-model="draft.headers" :disabled="busy" /></el-form-item>
      </el-collapse-item></el-collapse>
    </el-form>
    <template #footer><el-button :disabled="busy" @click="visible = false">取消</el-button>
      <el-button :loading="busy" @click="save(false)">保存草稿</el-button>
      <el-button type="primary" :loading="busy" @click="save(true)">提交发布</el-button></template>
  </el-dialog>
</template>

<style scoped>
.rule-list { width: 100%; }
.rule { display: grid; grid-template-columns: 125px 1fr 28px 1fr auto auto; gap: 8px; align-items: center; margin-bottom: 12px; }
@media (max-width: 700px) { .rule { grid-template-columns: 1fr 1fr; } }
</style>
