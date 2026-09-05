<script setup lang="ts">
import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import JsonEditor from '../../../components/JsonEditor.vue'
import {
  createSdkConfigEnvelope,
  getSdkConfigEnvelopes,
  publishSdkConfigEnvelope,
  rollbackSdkConfigEnvelope,
  submitSdkConfigApproval,
  validateSdkConfigEnvelope,
} from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type { JsonValue, SdkConfigEnvelope, SdkConfigMutation } from '../../../types/admin'
import { LatestRequestGate, SubmissionCoordinator } from '../../../utils/requestControl'

const errors = useErrorStore()
const session = useSessionStore()
const canPublish = computed(() => session.hasRole('MOCK_ADMIN'))
const loading = ref(false)
const creating = ref(false)
const actionId = ref<number | null>(null)
const envelopes = shallowRef<SdkConfigEnvelope[]>([])
const appCode = ref('sample-jdk17')
const environment = computed({
  get: () => session.environment,
  set: (value: 'TEST' | 'UAT') => { session.environment = value },
})
const createVisible = ref(false)
const publishVisible = ref(false)
const publishTarget = shallowRef<SdkConfigEnvelope | null>(null)
const policyIds = ref('')
const draft = reactive({
  appCode: 'sample-jdk17',
  environment: 'TEST',
  effectiveAt: new Date().toISOString(),
  expireAt: '',
  sourceAuditRef: 'local-m2-acceptance',
  routing: '{\n  "runtimeBaseUri": "http://localhost:19091",\n  "allowRequestOverride": false,\n  "defaultRoute": {\n    "mode": "REAL",\n    "unavailablePolicy": "FAST_FAIL",\n    "allowedBusinessHeaders": [],\n    "additionalSensitiveHeaders": [],\n    "allowedRealHosts": []\n  },\n  "providerRoutes": {},\n  "apiRoutes": {}\n}',
})
const publishDraft = reactive({
  expectedConfigVersion: 0,
  targetType: 'NACOS' as 'APOLLO' | 'NACOS',
  targetNamespace: 'mock-platform.test',
})
const loadGate = new LatestRequestGate()
const submissions = new SubmissionCoordinator()

function parseJson(value: string): JsonValue {
  try {
    return JSON.parse(value) as JsonValue
  } catch {
    throw new Error('Routing 不是合法 JSON')
  }
}

function parseIds(value: string): number[] {
  if (!value.trim()) return []
  const result = value.split(/[\s,]+/).filter(Boolean).map(Number)
  if (result.some((id) => !Number.isSafeInteger(id) || id <= 0)) {
    throw new Error('安全策略 Version ID 必须是正整数')
  }
  return [...new Set(result)]
}

async function load() {
  const request = loadGate.next()
  const app = appCode.value.trim()
  const targetEnvironment = environment.value
  envelopes.value = []
  publishTarget.value = null
  loading.value = true
  errors.clear()
  try {
    const result = await getSdkConfigEnvelopes(app, targetEnvironment)
    if (loadGate.isLatest(request)) envelopes.value = result
  } catch {
    if (loadGate.isLatest(request) && !errors.latest) errors.capture({ code: 'SDK_CONFIG_LOAD_FAILED', message: 'SDK Config Envelope 加载失败' })
  } finally {
    if (loadGate.isLatest(request)) loading.value = false
  }
}

async function submitCreate() {
  if (creating.value) return
  let payload: SdkConfigMutation
  try {
    payload = {
      appCode: draft.appCode.trim(),
      environment: draft.environment,
      routing: parseJson(draft.routing),
      securityPolicyVersionIds: parseIds(policyIds.value),
      effectiveAt: draft.effectiveAt,
      expireAt: draft.expireAt || undefined,
      sourceAuditRef: draft.sourceAuditRef || undefined,
    }
  } catch (failure) {
    ElMessage.warning(failure instanceof Error ? failure.message : 'Envelope 输入错误')
    return
  }
  if (!payload.appCode || !payload.effectiveAt) {
    ElMessage.warning('App Code 和生效时间不能为空')
    return
  }
  const attempt = submissions.begin('sdk:create', JSON.stringify(payload))
  if (!attempt) return
  creating.value = true
  let succeeded = false
  try {
    await createSdkConfigEnvelope(payload, attempt.key)
    succeeded = true
    ElMessage.success('SDK Config Envelope 草稿已创建')
    createVisible.value = false
    appCode.value = payload.appCode
    session.environment = payload.environment as 'TEST' | 'UAT'
    await load()
  } catch {
    if (!errors.latest) errors.capture({ code: 'SDK_CONFIG_CREATE_FAILED', message: 'SDK Config Envelope 创建失败' })
  } finally {
    creating.value = false
    attempt.finish(succeeded)
  }
}

async function runAction(row: SdkConfigEnvelope, action: 'validate' | 'submit' | 'rollback') {
  if (actionId.value) return
  const operation = `sdk:${action}:${row.id}`
  const attempt = submissions.begin(operation)
  if (!attempt) return
  actionId.value = row.id
  let succeeded = false
  errors.clear()
  try {
    if (action === 'validate') {
      await validateSdkConfigEnvelope(row.id, attempt.key)
      ElMessage.success(`Config v${row.configVersion} 校验通过`)
    } else if (action === 'submit') {
      await submitSdkConfigApproval(row.id, 'SDK_CONFIG_DUAL_CONTROL', 2, attempt.key)
      ElMessage.success(`Config v${row.configVersion} 已提交审批`)
    } else {
      await ElMessageBox.confirm('回滚会复制历史内容并创建更大的 Config Version 草稿，确认继续？', 'SDK Config 回滚', { type: 'warning' })
      const copy = await rollbackSdkConfigEnvelope(row.id, attempt.key)
      ElMessage.success(`已创建回滚草稿 Config v${copy.configVersion}`)
    }
    succeeded = true
    await load()
  } catch (failure) {
    if (failure !== 'cancel' && !errors.latest) {
      errors.capture({ code: 'SDK_CONFIG_ACTION_FAILED', message: 'SDK Config 操作失败' })
    }
  } finally {
    actionId.value = null
    attempt.finish(succeeded)
  }
}

function openPublish(row: SdkConfigEnvelope) {
  publishTarget.value = row
  publishDraft.expectedConfigVersion = Math.max(
    0,
    ...envelopes.value.filter((item) => item.status === 'PUBLISHED').map((item) => item.configVersion),
  )
  publishVisible.value = true
}

async function submitPublish() {
  if (actionId.value || !publishTarget.value || !publishDraft.targetNamespace.trim()) return
  const operation = `sdk:publish:${publishTarget.value.id}`
  const attempt = submissions.begin(operation)
  if (!attempt) return
  actionId.value = publishTarget.value.id
  let succeeded = false
  errors.clear()
  try {
    await publishSdkConfigEnvelope(publishTarget.value.id, { ...publishDraft }, attempt.key)
    succeeded = true
    ElMessage.success('签名 Config Activation Wrapper 已持久化并进入投影')
    publishVisible.value = false
    await load()
  } catch {
    if (!errors.latest) errors.capture({ code: 'SDK_CONFIG_PUBLISH_FAILED', message: 'SDK Config 发布失败，请核对权威 Config Version' })
  } finally {
    actionId.value = null
    attempt.finish(succeeded)
  }
}

function statusType(status: SdkConfigEnvelope['status']) {
  if (status === 'PUBLISHED' || status === 'APPROVED') return 'success'
  if (status === 'PENDING_APPROVAL' || status === 'VALIDATED' || status === 'PUBLISHING') return 'warning'
  if (status === 'DEPRECATED') return 'info'
  return 'primary'
}

function policyVersionLabels(envelope: SdkConfigEnvelope) {
  return envelope.securityPolicyRefs.map((reference) => reference.policyVersionId).join(', ') || '—'
}

function openCreate() {
  draft.environment = session.environment
  draft.appCode = appCode.value.trim()
  createVisible.value = true
}

onMounted(load)
watch(() => session.environment, () => {
  envelopes.value = []
  draft.environment = session.environment
  void load()
})
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">ATOMIC SDK CONFIG</p>
        <h2>SDK Config</h2>
        <p>路由、Header Filter 与 Fallback Policy 通过一个签名 Envelope 整包切换。</p>
      </div>
      <el-button type="primary" :disabled="!canPublish" @click="openCreate">新建 Envelope</el-button>
    </div>
    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form inline>
        <el-form-item label="App"><el-input v-model="appCode" style="width: 220px" /></el-form-item>
        <el-form-item label="Environment"><el-select v-model="environment" style="width: 110px"><el-option label="TEST" value="TEST" /><el-option label="UAT" value="UAT" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" plain @click="load">查询</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="envelopes" row-key="id">
        <el-table-column label="Config" width="90"><template #default="{ row }">v{{ row.configVersion }}</template></el-table-column>
        <el-table-column prop="appCode" label="App" min-width="140" />
        <el-table-column prop="environment" label="环境" width="90" />
        <el-table-column label="状态" width="150"><template #default="{ row }"><el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="checksum" label="Envelope Checksum" min-width="250" show-overflow-tooltip><template #default="{ row }"><code>{{ row.checksum }}</code></template></el-table-column>
        <el-table-column label="策略版本" min-width="140"><template #default="{ row }">{{ policyVersionLabels(row) }}</template></el-table-column>
        <el-table-column prop="effectiveAt" label="计划生效" min-width="170" />
        <el-table-column label="操作" width="245" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="!canPublish || row.status !== 'DRAFT'" @click="runAction(row, 'validate')">校验</el-button>
            <el-button link type="primary" :disabled="!canPublish || row.status !== 'VALIDATED'" @click="runAction(row, 'submit')">审批</el-button>
            <el-button link type="primary" :disabled="!canPublish || row.status !== 'APPROVED'" @click="openPublish(row)">发布</el-button>
            <el-button link type="warning" :disabled="!canPublish || row.status !== 'PUBLISHED'" @click="runAction(row, 'rollback')">回滚草稿</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无 SDK Config Envelope" /></template>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="新建 SDK Config Envelope" width="760px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="App Code" required><el-input v-model="draft.appCode" /></el-form-item>
          <el-form-item label="Environment" required><el-select v-model="draft.environment" disabled><el-option label="TEST" value="TEST" /><el-option label="UAT" value="UAT" /></el-select></el-form-item>
          <el-form-item label="Effective At" required><el-date-picker v-model="draft.effectiveAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" /></el-form-item>
          <el-form-item label="Expire At（可选）"><el-date-picker v-model="draft.expireAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" clearable /></el-form-item>
        </div>
        <el-form-item label="Routing JSON" required><JsonEditor v-model="draft.routing" :rows="12" /></el-form-item>
        <el-form-item label="Security Policy Version IDs"><el-input v-model="policyIds" placeholder="例如 21, 22；启用 MOCK/CANARY 时必须引用 Header Filter" /></el-form-item>
        <el-form-item label="外部审计引用"><el-input v-model="draft.sourceAuditRef" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="creating" :disabled="!canPublish" @click="submitCreate">创建草稿</el-button></template>
    </el-dialog>

    <el-dialog v-model="publishVisible" title="发布 SDK Config" width="560px">
      <el-alert type="warning" :closable="false" title="Target 集合不进入 Envelope checksum；提交后 Wrapper 精确字节由 Outbox 幂等重放。" />
      <el-form label-position="top">
        <el-form-item label="Expected Config Version"><el-input-number v-model="publishDraft.expectedConfigVersion" :min="0" /></el-form-item>
        <el-form-item label="配置中心"><el-radio-group v-model="publishDraft.targetType"><el-radio-button value="APOLLO">Apollo</el-radio-button><el-radio-button value="NACOS">Nacos</el-radio-button></el-radio-group></el-form-item>
        <el-form-item label="Target Namespace"><el-input v-model="publishDraft.targetNamespace" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="publishVisible = false">取消</el-button><el-button type="primary" :loading="actionId === publishTarget?.id" :disabled="!canPublish || Boolean(actionId)" @click="submitPublish">确认发布</el-button></template>
    </el-dialog>
  </section>
</template>
