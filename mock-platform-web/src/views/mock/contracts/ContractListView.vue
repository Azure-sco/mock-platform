<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import ContractDiffDialog from './ContractDiffDialog.vue'
import ContractEditorDialog from './ContractEditorDialog.vue'
import ContractImportDialog, { type ContractImportPayload } from './ContractImportDialog.vue'
import {
  createContract,
  getContracts,
  getProviderApis,
  getProviders,
  importContract,
  publishContract,
  validateContract,
} from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type { ContractMutation, ContractVersion, MockApi, Provider } from '../../../types/admin'
import { LatestRequestGate, SubmissionCoordinator } from '../../../utils/requestControl'

const route = useRoute()
const router = useRouter()
const errors = useErrorStore()
const session = useSessionStore()
const canEdit = computed(() => session.hasRole('MOCK_ADMIN'))
const loading = ref(false)
const apiLoading = ref(false)
const saving = ref(false)
const importing = ref(false)
const actionId = ref<number | null>(null)
const providers = ref<Provider[]>([])
const apis = ref<MockApi[]>([])
const contracts = shallowRef<ContractVersion[]>([])
const selectedProviderId = ref<number | null>(null)
const selectedApiId = ref<number | null>(null)
const createDialogVisible = ref(false)
const importDialogVisible = ref(false)
const diffDialogVisible = ref(false)
const diffSource = shallowRef<ContractVersion | null>(null)
const apiGate = new LatestRequestGate()
const contractGate = new LatestRequestGate()
const submissions = new SubmissionCoordinator()

function queryId(value: unknown): number | null {
  return typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : null
}

async function loadApis(preferredApiId: number | null = null) {
  const providerId = selectedProviderId.value
  const request = apiGate.next()
  apis.value = []
  selectedApiId.value = null
  contracts.value = []
  contractGate.invalidate()
  if (!providerId) return
  apiLoading.value = true
  try {
    const result = await getProviderApis(providerId, { page: 1, size: 200 })
    if (!apiGate.isLatest(request)) return
    apis.value = result.records
    selectedApiId.value = result.records.some((item) => item.id === preferredApiId)
      ? preferredApiId
      : (result.records[0]?.id ?? null)
  } finally {
    if (apiGate.isLatest(request)) apiLoading.value = false
  }
}

async function loadContracts() {
  const apiId = selectedApiId.value
  const request = contractGate.next()
  contracts.value = []
  if (!apiId) return
  loading.value = true
  errors.clear()
  try {
    const result = await getContracts(apiId)
    if (!contractGate.isLatest(request)) return
    contracts.value = [...result].sort((left, right) => right.versionNo - left.versionNo)
  } catch {
    if (contractGate.isLatest(request) && !errors.latest) {
      errors.capture({ code: 'CONTRACT_LOAD_FAILED', message: '契约版本加载失败' })
    }
  } finally {
    if (contractGate.isLatest(request)) loading.value = false
  }
}

async function initialize() {
  loading.value = true
  errors.clear()
  try {
    providers.value = (await getProviders({ page: 1, size: 200 })).records
    const requestedProviderId = queryId(route.query.providerId)
    selectedProviderId.value = providers.value.some((item) => item.id === requestedProviderId)
      ? requestedProviderId
      : (providers.value[0]?.id ?? null)
    await loadApis(queryId(route.query.apiId))
    await changeApi()
  } catch {
    if (!errors.latest) errors.capture({ code: 'CONTRACT_INITIALIZE_FAILED', message: '契约页面初始化失败' })
  } finally {
    loading.value = false
  }
}

async function changeProvider() {
  errors.clear()
  try {
    await loadApis()
    await changeApi()
  } catch {
    if (!errors.latest) errors.capture({ code: 'API_LOAD_FAILED', message: 'API 选项加载失败' })
  }
}

async function changeApi() {
  const provider = providers.value.find((item) => item.id === selectedProviderId.value)
  const api = apis.value.find((item) => item.id === selectedApiId.value)
  await router.replace({
    path: '/mock/contracts',
    query: api
      ? { providerId: selectedProviderId.value, providerCode: provider?.providerCode, apiId: api.id, apiCode: api.apiCode }
      : selectedProviderId.value ? { providerId: selectedProviderId.value } : {},
  })
  await loadContracts()
}

async function submitDraft(payload: ContractMutation) {
  if (saving.value || !selectedApiId.value) return
  const attempt = submissions.begin(`contract:create:${selectedApiId.value}`, JSON.stringify(payload))
  if (!attempt) return
  saving.value = true
  let succeeded = false
  try {
    await createContract(selectedApiId.value, payload, attempt.key)
    succeeded = true
    ElMessage.success('契约草稿已创建')
    createDialogVisible.value = false
    await loadContracts()
  } catch {
    if (!errors.latest) errors.capture({ code: 'CONTRACT_CREATE_FAILED', message: '契约草稿创建失败' })
  } finally {
    saving.value = false
    attempt.finish(succeeded)
  }
}

async function submitImport(payload: ContractImportPayload) {
  if (importing.value || !selectedApiId.value) return
  const fingerprint = `${payload.file.name}:${payload.file.size}:${payload.file.lastModified}:${JSON.stringify(payload.options)}`
  const attempt = submissions.begin(`contract:import:${selectedApiId.value}`, fingerprint)
  if (!attempt) return
  importing.value = true
  let succeeded = false
  try {
    await importContract(selectedApiId.value, payload.file, payload.options, attempt.key)
    succeeded = true
    ElMessage.success('契约文件已导入为草稿')
    importDialogVisible.value = false
    await loadContracts()
  } catch {
    if (!errors.latest) errors.capture({ code: 'CONTRACT_IMPORT_FAILED', message: '契约文件导入失败' })
  } finally {
    importing.value = false
    attempt.finish(succeeded)
  }
}

async function runAction(version: ContractVersion, action: 'validate' | 'publish') {
  if (actionId.value) return
  if (action === 'publish') {
    try {
      await ElMessageBox.confirm(`确认发布契约 v${version.versionNo}？发布后不可原地修改。`, '发布确认', { type: 'warning' })
    } catch {
      return
    }
  }
  const attempt = submissions.begin(`contract:${action}:${version.id}`)
  if (!attempt) return
  actionId.value = version.id
  let succeeded = false
  try {
    if (action === 'validate') await validateContract(version.id, attempt.key)
    else await publishContract(version.id, attempt.key)
    succeeded = true
    ElMessage.success(`v${version.versionNo} ${action === 'validate' ? '校验完成' : '已发布'}`)
    await loadContracts()
  } catch {
    if (!errors.latest) errors.capture({ code: 'CONTRACT_ACTION_FAILED', message: '契约版本操作失败' })
  } finally {
    actionId.value = null
    attempt.finish(succeeded)
  }
}

function openDiff(version: ContractVersion) {
  if (contracts.value.length < 2) {
    ElMessage.info('至少需要两个契约版本才能进行对比')
    return
  }
  diffSource.value = version
  diffDialogVisible.value = true
}

function statusType(status: ContractVersion['status']) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'VALIDATED') return 'warning'
  if (status === 'DEPRECATED') return 'info'
  return 'primary'
}

onMounted(initialize)
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div><p class="eyebrow">CONTRACT VERSIONS</p><h2>契约管理</h2><p>契约按版本创建、校验和发布；发布版本保持不可变。</p></div>
      <div class="heading-actions">
        <el-button :disabled="!canEdit || !selectedApiId" @click="importDialogVisible = true">导入契约</el-button>
        <el-button type="primary" :disabled="!canEdit || !selectedApiId" @click="createDialogVisible = true">新建版本</el-button>
      </div>
    </div>
    <HttpErrorAlert />
    <el-card class="filter-card" shadow="never">
      <el-form inline>
        <el-form-item label="Provider">
          <el-select v-model="selectedProviderId" filterable placeholder="选择 Provider" style="width: 270px" @change="changeProvider">
            <el-option v-for="provider in providers" :key="provider.id" :label="`${provider.providerCode} · ${provider.providerName} · ${provider.status}`" :value="provider.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="API">
          <el-select v-model="selectedApiId" filterable :loading="apiLoading" placeholder="选择 API" style="width: 300px" @change="changeApi">
            <el-option v-for="api in apis" :key="api.id" :label="`${api.apiCode} · ${api.apiName} · ${api.status}`" :value="api.id" />
          </el-select>
        </el-form-item>
        <el-form-item><el-button type="primary" plain :disabled="!selectedApiId" @click="loadContracts">刷新</el-button></el-form-item>
      </el-form>
    </el-card>
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="contracts" row-key="id">
        <el-table-column label="版本" width="90"><template #default="{ row }"><strong>v{{ row.versionNo }}</strong></template></el-table-column>
        <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="checksum" label="Checksum" min-width="230" show-overflow-tooltip><template #default="{ row }"><code>{{ row.checksum || '—' }}</code></template></el-table-column>
        <el-table-column prop="sourceType" label="来源" width="110" />
        <el-table-column prop="createdBy" label="创建人" min-width="120"><template #default="{ row }">{{ row.createdBy || '—' }}</template></el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="170"><template #default="{ row }">{{ row.createdAt || '—' }}</template></el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="actionId === row.id" :disabled="!canEdit || row.status !== 'DRAFT' || Boolean(actionId)" @click="runAction(row, 'validate')">校验</el-button>
            <el-button link type="primary" :loading="actionId === row.id" :disabled="!canEdit || row.status !== 'VALIDATED' || Boolean(actionId)" @click="runAction(row, 'publish')">发布</el-button>
            <el-button link type="primary" :disabled="Boolean(actionId)" @click="openDiff(row)">对比</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty :description="selectedApiId ? '该 API 暂无契约版本' : '请先选择 Provider 和 API'" /></template>
      </el-table>
    </el-card>
    <ContractEditorDialog v-model="createDialogVisible" :submitting="saving" :disabled="!canEdit" @submit="submitDraft" />
    <ContractImportDialog v-model="importDialogVisible" :submitting="importing" :disabled="!canEdit" @submit="submitImport" />
    <ContractDiffDialog v-model="diffDialogVisible" :source="diffSource" :versions="contracts" />
  </section>
</template>
