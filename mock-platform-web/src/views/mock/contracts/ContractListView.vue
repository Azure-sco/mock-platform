<script setup lang="ts">
import { computed, onMounted, reactive, ref, shallowRef } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import {
  createContract,
  diffContract,
  getContracts,
  getProviderApis,
  getProviders,
  importContract,
  publishContract,
  validateContract,
} from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type {
  ContractDiff,
  ContractMutation,
  ContractVersion,
  JsonValue,
  MockApi,
  Provider,
} from '../../../types/admin'

interface ContractDraftForm {
  requestSchema: string
  responseSchema: string
  examples: string
  errorCodes: string
  businessKeyExtractor: string
  signatureMetadata: string
}

const route = useRoute()
const router = useRouter()
const errors = useErrorStore()
const session = useSessionStore()
const canEdit = computed(() => session.hasRole('MOCK_ADMIN'))
const loading = ref(false)
const saving = ref(false)
const actionId = ref<number | null>(null)
const providers = ref<Provider[]>([])
const apis = ref<MockApi[]>([])
const contracts = shallowRef<ContractVersion[]>([])
const selectedProviderId = ref<number | null>(null)
const selectedApiId = ref<number | null>(null)
const createDialogVisible = ref(false)
const importDialogVisible = ref(false)
const importing = ref(false)
const importFile = shallowRef<File | null>(null)
const importOptions = reactive({ path: '', method: '', target: 'REQUEST' as 'REQUEST' | 'RESPONSE' })
const diffDialogVisible = ref(false)
const diffLoading = ref(false)
const diffSource = shallowRef<ContractVersion | null>(null)
const compareVersionId = ref<number | null>(null)
const diffResult = shallowRef<ContractDiff | null>(null)
const draft = reactive<ContractDraftForm>(emptyDraft())

function emptyDraft(): ContractDraftForm {
  return {
    requestSchema: '{\n  "type": "object",\n  "properties": {}\n}',
    responseSchema: '{\n  "type": "object",\n  "properties": {}\n}',
    examples: '[]',
    errorCodes: '[]',
    businessKeyExtractor: '{}',
    signatureMetadata: '{}',
  }
}

function queryId(value: unknown): number | null {
  if (typeof value !== 'string' || !/^\d+$/.test(value)) return null
  return Number(value)
}

function parseJson(label: string, raw: string): JsonValue {
  try {
    return JSON.parse(raw) as JsonValue
  } catch {
    throw new Error(`${label} 不是合法 JSON`)
  }
}

function formatJson(value: unknown): string {
  if (value === undefined || value === null) return '—'
  return typeof value === 'string' ? value : JSON.stringify(value, null, 2)
}

async function loadProviders() {
  const result = await getProviders({ page: 1, size: 200 })
  providers.value = result.records
}

async function loadApis() {
  if (!selectedProviderId.value) {
    apis.value = []
    selectedApiId.value = null
    return
  }
  const result = await getProviderApis(selectedProviderId.value, { page: 1, size: 200 })
  apis.value = result.records
}

async function loadContracts() {
  if (!selectedApiId.value) {
    contracts.value = []
    return
  }
  loading.value = true
  errors.clear()
  try {
    const result = await getContracts(selectedApiId.value)
    contracts.value = [...result].sort((left, right) => right.versionNo - left.versionNo)
  } catch {
    if (!errors.latest) errors.capture({ code: 'CONTRACT_LOAD_FAILED', message: '契约版本加载失败' })
  } finally {
    loading.value = false
  }
}

async function initialize() {
  loading.value = true
  errors.clear()
  try {
    await loadProviders()
    const requestedProviderId = queryId(route.query.providerId)
    selectedProviderId.value = providers.value.some((item) => item.id === requestedProviderId)
      ? requestedProviderId
      : (providers.value[0]?.id ?? null)
    await loadApis()
    const requestedApiId = queryId(route.query.apiId)
    selectedApiId.value = apis.value.some((item) => item.id === requestedApiId)
      ? requestedApiId
      : (apis.value[0]?.id ?? null)
    await loadContracts()
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
    selectedApiId.value = apis.value[0]?.id ?? null
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
      ? {
          providerId: selectedProviderId.value,
          providerCode: provider?.providerCode,
          apiId: api.id,
          apiCode: api.apiCode,
        }
      : { providerId: selectedProviderId.value },
  })
  await loadContracts()
}

function openCreate() {
  Object.assign(draft, emptyDraft())
  createDialogVisible.value = true
}

function openImport() {
  importFile.value = null
  importOptions.path = ''
  importOptions.method = ''
  importOptions.target = 'REQUEST'
  importDialogVisible.value = true
}

function selectImportFile(event: Event) {
  const input = event.target as HTMLInputElement
  importFile.value = input.files?.[0] ?? null
}

async function submitImport() {
  if (!selectedApiId.value || !importFile.value) {
    ElMessage.warning('请先选择 API 和导入文件')
    return
  }
  if (importFile.value.size > 5 * 1024 * 1024) {
    ElMessage.warning('文件不能超过 5 MB')
    return
  }
  const path = importOptions.path.trim()
  const method = importOptions.method.trim()
  if ((path && !method) || (!path && method)) {
    ElMessage.warning('OpenAPI Path 和 Method 必须同时填写')
    return
  }
  importing.value = true
  errors.clear()
  try {
    await importContract(selectedApiId.value, importFile.value, {
      path: path || undefined,
      method: method || undefined,
      target: importOptions.target,
    })
    ElMessage.success('契约文件已导入为草稿')
    importDialogVisible.value = false
    await loadContracts()
  } catch {
    if (!errors.latest) errors.capture({ code: 'CONTRACT_IMPORT_FAILED', message: '契约文件导入失败' })
  } finally {
    importing.value = false
  }
}

async function submitDraft() {
  if (!selectedApiId.value) {
    ElMessage.warning('请先选择 API')
    return
  }
  const apiId = selectedApiId.value
  let payload: ContractMutation
  try {
    payload = {
      requestSchema: parseJson('Request Schema', draft.requestSchema),
      responseSchema: parseJson('Response Schema', draft.responseSchema),
      examples: parseJson('Examples', draft.examples),
      errorCodes: parseJson('Error Codes', draft.errorCodes),
      businessKeyExtractor: parseJson('Business Key Extractor', draft.businessKeyExtractor),
      signatureMetadata: parseJson('Signature Metadata', draft.signatureMetadata),
      sourceType: 'MANUAL',
    }
  } catch (failure) {
    ElMessage.warning(failure instanceof Error ? failure.message : '契约 JSON 解析失败')
    return
  }

  saving.value = true
  errors.clear()
  try {
    await createContract(apiId, payload)
    ElMessage.success('契约草稿已创建')
    createDialogVisible.value = false
    await loadContracts()
  } catch {
    if (!errors.latest) errors.capture({ code: 'CONTRACT_CREATE_FAILED', message: '契约草稿创建失败' })
  } finally {
    saving.value = false
  }
}

async function validate(version: ContractVersion) {
  actionId.value = version.id
  errors.clear()
  try {
    await validateContract(version.id)
    ElMessage.success(`v${version.versionNo} 校验完成`)
    await loadContracts()
  } catch {
    if (!errors.latest) errors.capture({ code: 'CONTRACT_VALIDATE_FAILED', message: '契约校验失败' })
  } finally {
    actionId.value = null
  }
}

async function publish(version: ContractVersion) {
  try {
    await ElMessageBox.confirm(
      `确认发布契约 v${version.versionNo}？发布后该版本不可原地修改。`,
      '发布确认',
      { confirmButtonText: '确认发布', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  actionId.value = version.id
  errors.clear()
  try {
    await publishContract(version.id)
    ElMessage.success(`v${version.versionNo} 已发布`)
    await loadContracts()
  } catch {
    if (!errors.latest) errors.capture({ code: 'CONTRACT_PUBLISH_FAILED', message: '契约发布失败' })
  } finally {
    actionId.value = null
  }
}

function openDiff(version: ContractVersion) {
  const candidate = contracts.value.find((item) => item.id !== version.id)
  if (!candidate) {
    ElMessage.info('至少需要两个契约版本才能进行对比')
    return
  }
  diffSource.value = version
  compareVersionId.value = candidate.id
  diffResult.value = null
  diffDialogVisible.value = true
}

async function loadDiff() {
  if (!diffSource.value || !compareVersionId.value) return
  diffLoading.value = true
  errors.clear()
  try {
    diffResult.value = await diffContract(diffSource.value.id, compareVersionId.value)
  } catch {
    if (!errors.latest) errors.capture({ code: 'CONTRACT_DIFF_FAILED', message: '契约差异加载失败' })
  } finally {
    diffLoading.value = false
  }
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
      <div>
        <p class="eyebrow">M1 · CONTRACT VERSIONS</p>
        <h2>契约管理</h2>
        <p>契约按版本创建、校验和发布；发布版本保持不可变。</p>
      </div>
      <div class="heading-actions">
        <el-button
          :disabled="!canEdit || !selectedApiId"
          title="支持本地 OpenAPI 3.0 JSON/YAML 和 JSON Schema"
          @click="openImport"
        >导入契约文件</el-button>
        <el-button
          type="primary"
          :disabled="!canEdit || !selectedApiId"
          title="需要 MOCK_ADMIN 角色且必须先选择 API"
          @click="openCreate"
        >新建契约版本</el-button>
      </div>
    </div>

    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form inline>
        <el-form-item label="Provider">
          <el-select v-model="selectedProviderId" filterable placeholder="选择 Provider" style="width: 270px" @change="changeProvider">
            <el-option
              v-for="provider in providers"
              :key="provider.id"
              :label="`${provider.providerCode} · ${provider.providerName}`"
              :value="provider.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="API">
          <el-select v-model="selectedApiId" filterable placeholder="选择 API" style="width: 290px" @change="changeApi">
            <el-option v-for="api in apis" :key="api.id" :label="`${api.apiCode} · ${api.apiName}`" :value="api.id" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" plain :disabled="!selectedApiId" @click="loadContracts">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="contracts" row-key="id">
        <el-table-column label="版本" width="90">
          <template #default="{ row }"><strong>v{{ row.versionNo }}</strong></template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="校验" width="120">
          <template #default="{ row }">{{ row.status === 'DRAFT' ? '未校验' : '已通过' }}</template>
        </el-table-column>
        <el-table-column prop="checksum" label="Checksum" min-width="230" show-overflow-tooltip>
          <template #default="{ row }"><code>{{ row.checksum || '—' }}</code></template>
        </el-table-column>
        <el-table-column prop="sourceType" label="来源" width="110" />
        <el-table-column prop="createdBy" label="创建人" min-width="120">
          <template #default="{ row }">{{ row.createdBy || '—' }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="170">
          <template #default="{ row }">{{ row.createdAt || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="245" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :loading="actionId === row.id"
              :disabled="!canEdit || row.status !== 'DRAFT'"
              @click="validate(row)"
            >校验</el-button>
            <el-button
              link
              type="primary"
              :loading="actionId === row.id"
              :disabled="!canEdit || row.status !== 'VALIDATED'"
              @click="publish(row)"
            >发布</el-button>
            <el-button link type="primary" @click="openDiff(row)">对比</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="selectedApiId ? '该 API 暂无契约版本' : '请先创建或选择 API'" />
        </template>
      </el-table>
    </el-card>

    <el-dialog v-model="createDialogVisible" title="新建契约版本" width="820px" top="5vh">
      <el-alert type="info" :closable="false" show-icon title="创建后先执行校验，通过后才能发布。JSON Schema 外部引用不在 M1 页面开放。" />
      <el-form class="contract-form" label-position="top">
        <div class="schema-grid">
          <el-form-item label="Request Schema" required>
            <el-input v-model="draft.requestSchema" type="textarea" :rows="12" spellcheck="false" />
          </el-form-item>
          <el-form-item label="Response Schema" required>
            <el-input v-model="draft.responseSchema" type="textarea" :rows="12" spellcheck="false" />
          </el-form-item>
        </div>
        <div class="schema-grid">
          <el-form-item label="Examples">
            <el-input v-model="draft.examples" type="textarea" :rows="5" spellcheck="false" />
          </el-form-item>
          <el-form-item label="Error Codes">
            <el-input v-model="draft.errorCodes" type="textarea" :rows="5" spellcheck="false" />
          </el-form-item>
          <el-form-item label="Business Key Extractor">
            <el-input v-model="draft.businessKeyExtractor" type="textarea" :rows="5" spellcheck="false" />
          </el-form-item>
          <el-form-item label="Signature Metadata">
            <el-input v-model="draft.signatureMetadata" type="textarea" :rows="5" spellcheck="false" />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="!canEdit" @click="submitDraft">创建草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importDialogVisible" title="导入契约文件" width="620px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="仅支持不超过 5 MB 的 OpenAPI 3.0 JSON/YAML 或 JSON Schema；外部引用、压缩包和 YAML Alias 会被拒绝。"
      />
      <el-form class="contract-form" label-position="top">
        <el-form-item label="本地文件" required>
          <input type="file" accept=".json,.yaml,.yml" @change="selectImportFile">
          <span v-if="importFile" class="field-hint">{{ importFile.name }}</span>
        </el-form-item>
        <div class="schema-grid">
          <el-form-item label="OpenAPI Path（多操作文件必填）">
            <el-input v-model="importOptions.path" placeholder="/api/orders" />
          </el-form-item>
          <el-form-item label="OpenAPI Method（与 Path 同时填写）">
            <el-select v-model="importOptions.method" clearable placeholder="自动选择单一操作" style="width: 100%">
              <el-option v-for="method in ['GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS']" :key="method" :label="method" :value="method" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="JSON Schema 导入目标">
          <el-radio-group v-model="importOptions.target">
            <el-radio-button value="REQUEST">Request Schema</el-radio-button>
            <el-radio-button value="RESPONSE">Response Schema</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" :disabled="!canEdit || !importFile" @click="submitImport">导入草稿</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="diffDialogVisible" title="契约版本对比" width="760px">
      <div class="diff-toolbar">
        <span>v{{ diffSource?.versionNo }} 对比</span>
        <el-select v-model="compareVersionId" style="width: 220px" @change="diffResult = null">
          <el-option
            v-for="version in contracts.filter((item) => item.id !== diffSource?.id)"
            :key="version.id"
            :label="`v${version.versionNo} · ${version.status}`"
            :value="version.id"
          />
        </el-select>
        <el-button type="primary" plain :loading="diffLoading" @click="loadDiff">加载差异</el-button>
      </div>
      <el-empty v-if="!diffResult && !diffLoading" description="选择比较版本后加载字段级差异" />
      <pre v-else-if="diffResult" class="json-preview">{{ formatJson(diffResult) }}</pre>
    </el-dialog>
  </section>
</template>
