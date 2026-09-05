<script setup lang="ts">
import { computed, onMounted, reactive, ref, shallowRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import JsonEditor from '../../../components/JsonEditor.vue'
import {
  createScenario,
  createScenarioVersion,
  getContracts,
  getProviderApis,
  getProviders,
  getScenario,
  getScenarios,
  submitScenarioApproval,
  validateScenarioVersion,
} from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type {
  JsonValue,
  MockApi,
  Provider,
  Scenario,
  ScenarioMutation,
  ScenarioVersion,
  ScenarioVersionMutation,
  ContractVersion,
} from '../../../types/admin'
import { LatestRequestGate, SubmissionCoordinator } from '../../../utils/requestControl'

const errors = useErrorStore()
const session = useSessionStore()
const canEdit = computed(() => session.hasRole('MOCK_ADMIN'))
const loading = ref(false)
const actionId = ref<number | null>(null)
const savingScenario = ref(false)
const savingVersion = ref(false)
const scenarios = ref<Scenario[]>([])
const providers = ref<Provider[]>([])
const apis = ref<MockApi[]>([])
const contractVersions = shallowRef<ContractVersion[]>([])
const selected = shallowRef<Scenario | null>(null)
const versions = shallowRef<ScenarioVersion[]>([])
const createVisible = ref(false)
const versionVisible = ref(false)
const apiGate = new LatestRequestGate()
const contractGate = new LatestRequestGate()
const submissions = new SubmissionCoordinator()

const scenarioDraft = reactive<ScenarioMutation>({
  scenarioCode: '',
  scenarioName: '',
  providerId: 0,
  apiId: 0,
})

const versionDraft = reactive({
  contractVersionId: 0,
  flowDefinitionVersionId: '',
  priority: 100,
  effectiveFrom: '',
  effectiveTo: '',
  scope: '{\n  "environment": "TEST",\n  "apps": ["sample-jdk17"],\n  "tenants": ["m0-tenant"],\n  "testAccounts": ["m0-account"]\n}',
  matchRules: '[]',
  response: '{\n  "httpStatus": 200,\n  "headers": {"Content-Type": "application/json"},\n  "bodyTemplate": "{\\"code\\":\\"0\\",\\"requestId\\":\\"${mockRequestId}\\"}"\n}',
  callbacks: '[]',
})

function parseJson(label: string, value: string): JsonValue {
  try {
    return JSON.parse(value) as JsonValue
  } catch {
    throw new Error(`${label} 不是合法 JSON`)
  }
}

async function load() {
  loading.value = true
  errors.clear()
  try {
    const [scenarioResult, providerResult] = await Promise.all([
      getScenarios(),
      getProviders({ page: 1, size: 200 }),
    ])
    scenarios.value = scenarioResult
    providers.value = providerResult.records
  } catch {
    if (!errors.latest) errors.capture({ code: 'SCENARIO_LOAD_FAILED', message: '场景列表加载失败' })
  } finally {
    loading.value = false
  }
}

async function loadApis(providerId: number) {
  const request = apiGate.next()
  scenarioDraft.apiId = 0
  apis.value = []
  contractVersions.value = []
  contractGate.invalidate()
  if (!providerId) {
    return
  }
  const result = await getProviderApis(providerId, { page: 1, size: 200 })
  if (apiGate.isLatest(request)) apis.value = result.records
}

async function loadContractVersions(apiId: number) {
  const request = contractGate.next()
  contractVersions.value = []
  versionDraft.contractVersionId = 0
  if (!apiId) return
  const result = await getContracts(apiId)
  if (contractGate.isLatest(request)) {
    contractVersions.value = result.filter((version) => version.status === 'PUBLISHED')
  }
}

async function submitScenario() {
  if (savingScenario.value) return
  if (!scenarioDraft.scenarioCode || !scenarioDraft.scenarioName || !scenarioDraft.providerId || !scenarioDraft.apiId) {
    ElMessage.warning('请完整填写场景基础信息')
    return
  }
  const attempt = submissions.begin('scenario:create', JSON.stringify(scenarioDraft))
  if (!attempt) return
  savingScenario.value = true
  let succeeded = false
  try {
    await createScenario({ ...scenarioDraft }, attempt.key)
    succeeded = true
    ElMessage.success('场景已创建')
    createVisible.value = false
    Object.assign(scenarioDraft, { scenarioCode: '', scenarioName: '', providerId: 0, apiId: 0 })
    await load()
  } catch {
    if (!errors.latest) errors.capture({ code: 'SCENARIO_CREATE_FAILED', message: '场景创建失败' })
  } finally {
    savingScenario.value = false
    attempt.finish(succeeded)
  }
}

async function openVersions(row: Scenario, create = false) {
  actionId.value = row.id
  errors.clear()
  try {
    const detail = await getScenario(row.id)
    selected.value = detail
    versions.value = detail.versions ?? []
    if (create) await loadContractVersions(detail.apiId)
    versionVisible.value = true
  } catch {
    if (!errors.latest) errors.capture({ code: 'SCENARIO_DETAIL_FAILED', message: '场景版本加载失败' })
  } finally {
    actionId.value = null
  }
}

async function submitVersion() {
  if (savingVersion.value) return
  if (!selected.value || versionDraft.contractVersionId <= 0) {
    ElMessage.warning('Contract Version ID 必须大于 0')
    return
  }
  let payload: ScenarioVersionMutation
  try {
    payload = {
      contractVersionId: versionDraft.contractVersionId,
      flowDefinitionVersionId: versionDraft.flowDefinitionVersionId
        ? Number(versionDraft.flowDefinitionVersionId)
        : undefined,
      priority: versionDraft.priority,
      effectiveFrom: versionDraft.effectiveFrom || undefined,
      effectiveTo: versionDraft.effectiveTo || undefined,
      scope: parseJson('Scope', versionDraft.scope),
      matchRules: parseJson('Match Rules', versionDraft.matchRules),
      response: parseJson('Response', versionDraft.response),
      callbacks: parseJson('Callbacks', versionDraft.callbacks),
    }
  } catch (failure) {
    ElMessage.warning(failure instanceof Error ? failure.message : '场景 JSON 解析失败')
    return
  }
  const attempt = submissions.begin(`scenario-version:create:${selected.value.id}`, JSON.stringify(payload))
  if (!attempt) return
  savingVersion.value = true
  let succeeded = false
  try {
    await createScenarioVersion(selected.value.id, payload, attempt.key)
    succeeded = true
    ElMessage.success('场景版本草稿已创建')
    await openVersions(selected.value)
  } catch {
    if (!errors.latest) errors.capture({ code: 'SCENARIO_VERSION_CREATE_FAILED', message: '场景版本创建失败' })
  } finally {
    savingVersion.value = false
    attempt.finish(succeeded)
  }
}

async function runVersionAction(version: ScenarioVersion, action: 'validate' | 'submit') {
  if (actionId.value) return
  const attempt = submissions.begin(`scenario-version:${action}:${version.id}`)
  if (!attempt) return
  actionId.value = version.id
  let succeeded = false
  errors.clear()
  try {
    if (action === 'validate') {
      await validateScenarioVersion(version.id, attempt.key)
      ElMessage.success(`场景 v${version.versionNo} 校验通过`)
    } else {
      await ElMessageBox.confirm('提交后审批将固定当前 checksum，确认继续？', '提交审批', {
        type: 'warning',
      })
      await submitScenarioApproval(version.id, attempt.key)
      ElMessage.success(`场景 v${version.versionNo} 已提交审批`)
    }
    succeeded = true
    if (selected.value) await openVersions(selected.value)
  } catch (failure) {
    if (failure !== 'cancel' && !errors.latest) {
      errors.capture({ code: 'SCENARIO_ACTION_FAILED', message: '场景版本操作失败' })
    }
  } finally {
    actionId.value = null
    attempt.finish(succeeded)
  }
}

function versionTag(status: ScenarioVersion['status']) {
  if (status === 'PUBLISHED' || status === 'APPROVED') return 'success'
  if (status === 'PENDING_APPROVAL' || status === 'VALIDATED') return 'warning'
  if (status === 'DISABLED') return 'info'
  return 'primary'
}

onMounted(load)
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">VERSIONED SCENARIO</p>
        <h2>场景管理</h2>
        <p>场景内容按版本校验与审批；Runtime 只消费 Release 固定的发布版本。</p>
      </div>
      <el-button type="primary" :disabled="!canEdit" @click="createVisible = true">新建场景</el-button>
    </div>
    <HttpErrorAlert />

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="scenarios" row-key="id">
        <el-table-column prop="scenarioCode" label="场景编码" min-width="190" />
        <el-table-column prop="scenarioName" label="名称" min-width="180" />
        <el-table-column prop="providerId" label="Provider ID" width="120" />
        <el-table-column prop="apiId" label="API ID" width="100" />
        <el-table-column prop="currentDraftVersion" label="当前草稿" width="110" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><el-tag effect="plain">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" min-width="170" />
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="actionId === row.id" @click="openVersions(row)">版本</el-button>
            <el-button link type="primary" :disabled="!canEdit" @click="openVersions(row, true)">新建版本</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无场景，请先创建发布态 Contract" /></template>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="新建场景" width="600px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="场景编码" required><el-input v-model="scenarioDraft.scenarioCode" /></el-form-item>
          <el-form-item label="场景名称" required><el-input v-model="scenarioDraft.scenarioName" /></el-form-item>
          <el-form-item label="Provider" required>
            <el-select v-model="scenarioDraft.providerId" filterable @change="loadApis">
              <el-option v-for="provider in providers" :key="provider.id" :label="`${provider.providerCode} · ${provider.providerName} · ${provider.status}`" :value="provider.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="API" required>
            <el-select v-model="scenarioDraft.apiId" filterable>
              <el-option v-for="api in apis" :key="api.id" :label="`${api.apiCode} · ${api.apiName} · ${api.status}`" :value="api.id" />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingScenario" :disabled="!canEdit" @click="submitScenario">创建</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="versionVisible" :title="`${selected?.scenarioCode ?? ''} · 版本管理`" size="840px">
      <el-alert type="info" :closable="false" title="版本提交审批后由 checksum 固定；修改请创建新版本。" />
      <el-card class="filter-card" shadow="never">
        <el-form label-position="top">
          <div class="form-grid">
            <el-form-item label="Contract Version" required>
              <el-select v-model="versionDraft.contractVersionId" filterable placeholder="选择已发布契约版本">
                <el-option v-for="version in contractVersions" :key="version.id" :label="`v${version.versionNo} · ${version.status} · ${version.checksum.slice(0, 10)}`" :value="version.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="Flow Definition Version ID（可选）"><el-input v-model="versionDraft.flowDefinitionVersionId" /></el-form-item>
            <el-form-item label="优先级"><el-input-number v-model="versionDraft.priority" :min="0" :max="100000" /></el-form-item>
            <el-form-item label="生效区间">
              <div class="inline-fields">
                <el-date-picker v-model="versionDraft.effectiveFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" placeholder="开始时间" />
                <el-date-picker v-model="versionDraft.effectiveTo" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" placeholder="结束时间" />
              </div>
            </el-form-item>
          </div>
          <div class="schema-grid">
            <el-form-item label="Scope JSON"><JsonEditor v-model="versionDraft.scope" :rows="8" /></el-form-item>
            <el-form-item label="Match Rules JSON"><JsonEditor v-model="versionDraft.matchRules" :rows="8" /></el-form-item>
            <el-form-item label="Response JSON"><JsonEditor v-model="versionDraft.response" :rows="9" /></el-form-item>
            <el-form-item label="Callbacks JSON"><JsonEditor v-model="versionDraft.callbacks" :rows="9" /></el-form-item>
          </div>
          <el-button type="primary" :loading="savingVersion" :disabled="!canEdit" @click="submitVersion">创建版本草稿</el-button>
        </el-form>
      </el-card>

      <el-table :data="versions" row-key="id">
        <el-table-column label="版本" width="80"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
        <el-table-column label="状态" width="150"><template #default="{ row }"><el-tag :type="versionTag(row.status)" effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="priority" label="优先级" width="90" />
        <el-table-column prop="checksum" label="Checksum" min-width="220" show-overflow-tooltip />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" :loading="actionId === row.id" :disabled="!canEdit || row.status !== 'DRAFT' || Boolean(actionId)" @click="runVersionAction(row, 'validate')">校验</el-button>
            <el-button link type="primary" :loading="actionId === row.id" :disabled="!canEdit || row.status !== 'VALIDATED' || Boolean(actionId)" @click="runVersionAction(row, 'submit')">审批</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无版本" /></template>
      </el-table>
    </el-drawer>
  </section>
</template>
