<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, shallowRef, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useSessionStore } from '../../../stores/session'
import { useErrorStore } from '../../../stores/errors'
import { getProviders, getProviderApis, getContracts, getScenarios, getScenario, getApprovals, decideApproval,
  getActiveRelease, createRelease, validateRelease, submitScenarioApproval, getReleases } from '../../../api/admin'
import { activateResponseRelease, assertSameBase, getActivationDetail, getReleaseDetail, type ActivationDetail, type ReleaseDetail } from '../../../api/admin/interfaceWorkflow'
import { editableResponse, inScope, mergeResponseVersions } from '../../../utils/interfaceWorkflow'
import type { ActiveRelease, ApprovalRequest, ContractVersion, MockApi, Provider, Scenario, ScenarioVersion } from '../../../types/admin'
import PageHeader from '../../../components/PageHeader.vue'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import InterfaceCatalogDialog from './InterfaceCatalogDialog.vue'
import ResponseEditor from './ResponseEditor.vue'

const session = useSessionStore()
const errors = useErrorStore()
const app = ref('sample-jdk17')
const providerId = ref<number>()
const apiId = ref<number>()
const providers = shallowRef<Provider[]>([])
const apis = shallowRef<MockApi[]>([])
const contracts = shallowRef<ContractVersion[]>([])
const rows = shallowRef<{ scenario: Scenario; version: ScenarioVersion }[]>([])
const approvals = shallowRef<ApprovalRequest[]>([])
const active = shallowRef<ActiveRelease | null>(null)
const activeDetail = shallowRef<ReleaseDetail | null>(null)
const loaded = ref(false)
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const message = ref('')
const chosen = ref<number[]>([])
const catalogVisible = ref(false)
const configuringContract = ref(false)
const editorVisible = ref(false)
const editing = shallowRef<{ scenario: Scenario; version: ScenarioVersion }>()
const activation = ref<ActivationDetail>()
const activationId = ref('')
const retryRelease = ref<{ id: string; expected: number; requestId: string }>()
let releaseAttempt: { fingerprint: string; code: string } | undefined
let generation = 0
let apiGeneration = 0
let selectingCreatedApi = false
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
const api = computed(() => apis.value.find(item => item.id === apiId.value))
const canEdit = computed(() => session.roles.includes('MOCK_ADMIN'))
const canApprove = computed(() => session.roles.some(role => ['MOCK_ADMIN', 'MOCK_APPROVER'].includes(role)))
const locked = computed(() => busy.value || editorVisible.value || catalogVisible.value)
const activeIds = computed(() => new Set(activeDetail.value?.items.filter(i => i.itemType === 'SCENARIO').map(i => i.objectVersionId)))
const selectedVersions = computed(() => rows.value.filter(row => chosen.value.includes(row.version.id)).map(row => row.version))
const readyContract = computed(() => contracts.value.some(c => c.status === 'PUBLISHED'))
const stateLabel: Record<string, string> = { DRAFT: '草稿待校验', VALIDATED: '草稿已校验', PENDING_APPROVAL: '待审批', APPROVED: '待发布', PUBLISHED: '已批准', DISABLED: '已停用' }

function approvalFor(version: ScenarioVersion) {
  return approvals.value.find(a => a.objectType === 'SCENARIO_VERSION' && a.objectId === version.id)
}
function label(version: ScenarioVersion) {
  if (activeIds.value.has(version.id)) return active.value?.state === 'APPLIED' ? '已生效' : '发布中'
  if (approvalFor(version)?.status === 'REJECTED') return '审批未通过'
  return stateLabel[version.status] ?? version.status
}
function eligible(version: ScenarioVersion) {
  return ['APPROVED', 'PUBLISHED'].includes(version.status) && !activeIds.value.has(version.id)
}
function describeRules(version: ScenarioVersion) {
  const rules = version.matchRules as { type: string; key: string; operator: string; value: unknown }[]
  return rules.length ? rules.map(r => `${r.key} ${r.operator === 'EQ' ? '等于' : r.operator} ${String(r.value ?? '')}`).join('；') : '默认响应'
}
function responseBody(version: ScenarioVersion) {
  const body = (version.response as { bodyTemplate?: string }).bodyTemplate ?? ''
  try { return JSON.stringify(JSON.parse(body), null, 2) }
  catch { return body }
}
function scopeReset() {
  clearTimeout(timer); activationId.value = ''; activation.value = undefined; retryRelease.value = undefined
  loaded.value = false; active.value = null; activeDetail.value = null; chosen.value = []; rows.value = []; message.value = ''
  releaseAttempt = undefined
}
async function loadProviders() {
  providers.value = (await getProviders({ page: 1, size: 10000, status: 'ENABLED' })).records
}
async function loadApis() {
  const ticket = ++apiGeneration
  apis.value = []; apiId.value = undefined; contracts.value = []; scopeReset()
  if (!providerId.value) return
  try {
    const result = await getProviderApis(providerId.value, { page: 1, size: 10000 })
    if (ticket !== apiGeneration) return
    apis.value = result.records.filter(a => a.status === 'ENABLED')
  } catch { error.value = '接口列表加载失败，请重新选择服务商' }
}
async function load() {
  const ticket = ++generation
  loaded.value = false; error.value = ''; loading.value = true
  const scope = { api: apiId.value, app: app.value.trim(), environment: session.environment }
  try {
    if (!scope.app) throw new Error('请填写已接入的应用编码')
    const [current, requests, roots, versions] = await Promise.all([
      getActiveRelease(scope.environment, scope.app), getApprovals(), getScenarios(),
      scope.api ? getContracts(scope.api) : Promise.resolve([]),
    ])
    const [release, details] = await Promise.all([
      current ? getReleaseDetail(current.releaseId) : Promise.resolve(null),
      Promise.all(roots.filter(s => s.apiId === scope.api && s.status === 'ENABLED').map(s => getScenario(s.id))),
    ])
    if (ticket !== generation) return
    active.value = current; activeDetail.value = release; approvals.value = requests; contracts.value = versions
    const activeContractId = release?.items.find(i => i.itemType === 'CONTRACT' && i.objectId === scope.api)?.objectVersionId
    contracts.value = versions.slice().sort((a, b) => Number(b.id === activeContractId) - Number(a.id === activeContractId) || b.versionNo - a.versionNo)
    rows.value = details.flatMap(scenario => {
      const version = scenario.versions?.filter(v => inScope(v, scope.environment, scope.app)).sort((a, b) => b.versionNo - a.versionNo)[0]
      return version ? [{ scenario, version }] : []
    })
    chosen.value = rows.value.filter(row => eligible(row.version)).map(row => row.version.id)
    loaded.value = true
    if (current?.state === 'ACTIVATING' && !activationId.value) {
      clearTimeout(timer)
      timer = setTimeout(() => { if (!disposed && !locked.value) void load() }, 2000)
    }
  } catch (failure) {
    if (ticket === generation) { error.value = failure instanceof Error ? failure.message : '加载失败'; rows.value = []; chosen.value = [] }
  } finally { if (ticket === generation) loading.value = false }
}
function edit(row?: { scenario: Scenario; version: ScenarioVersion }) {
  editing.value = row; editorVisible.value = true
}
async function savedApi(value: MockApi) {
  await loadProviders()
  selectingCreatedApi = true
  providerId.value = value.providerId
  await nextTick()
  selectingCreatedApi = false
  await loadApis()
  apiId.value = value.id
}
async function decide(version: ScenarioVersion, reject = false) {
  const approval = approvalFor(version)
  if (!approval || busy.value) return
  try {
    const answer = await ElMessageBox.prompt(reject ? '填写拒绝原因' : '确认当前响应内容和匹配条件后批准', reject ? '拒绝响应' : '批准响应', {
      inputValidator: value => !reject || Boolean(value?.trim()) || '请填写原因',
    })
    busy.value = true
    await decideApproval(approval.id, reject ? 'reject' : 'approve', { comment: answer.value || undefined })
    message.value = reject ? '审批已拒绝，创建者可修改后重新提交' : '审批已通过，具有发布权限的管理员可以在本页发布'
    await load()
  } catch (failure) { if (failure !== 'cancel' && failure !== 'close') error.value = failure instanceof Error ? failure.message : '审批失败' }
  finally { busy.value = false }
}
async function submit(version: ScenarioVersion) {
  if (busy.value) return
  busy.value = true
  try { await submitScenarioApproval(version.id); message.value = '已提交发布，等待另一位审批人批准'; await load() }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '提交失败' }
  finally { busy.value = false }
}
function refreshActivation() { clearTimeout(timer); void poll() }
async function poll() {
  const id = activationId.value
  if (!id || disposed) return
  try {
    const result = await getActivationDetail(id)
    if (disposed || id !== activationId.value) return
    activation.value = result
    if (result.activation.status === 'APPLIED') { message.value = '已生效，Runtime 已确认加载新响应'; await load(); return }
    if (result.targets.some(t => t.status === 'FAILED') || ['FAILED', 'PARTIAL'].includes(result.activation.status)) {
      error.value = '发布尚未完整生效，请查看下方节点状态，处理后刷新；不要重复创建发布'; return
    }
    timer = setTimeout(() => void poll(), 2000)
  } catch { if (!disposed && id === activationId.value) error.value = '发布状态读取失败，请点击刷新发布状态；不要重复发布' }
}
async function publish() {
  if (busy.value || !loaded.value) return
  busy.value = true; error.value = ''; errors.clear()
  try {
    const scope = { app: app.value.trim(), environment: session.environment, api: apiId.value }
    const requireScope = () => {
      if (scope.app !== app.value.trim() || scope.environment !== session.environment || scope.api !== apiId.value) {
        throw new Error('当前应用或环境已切换，请重新核对后发布')
      }
    }
    const base = active.value
    if (base && base.state !== 'APPLIED') throw new Error('当前发布尚未完成，请先处理或等待其生效')
    const changes = selectedVersions.value
    if (changes.some(v => !eligible(v))) throw new Error('只能发布已批准的响应')
    const ids = mergeResponseVersions(activeDetail.value?.items ?? [], changes)
    const retained = ids.length - changes.length
    await ElMessageBox.confirm(`应用 ${app.value.trim()} / ${session.environment}：发布 ${changes.length} 个响应，保留 ${retained} 个现有响应。\n${rows.value.filter(r => chosen.value.includes(r.version.id)).map(r => r.scenario.scenarioName).join('、')}`, '确认发布变更')
    requireScope()
    assertSameBase(base, await getActiveRelease(scope.environment, scope.app))
    requireScope()
    const fingerprint = JSON.stringify([scope, base?.activationVersion ?? 0, ids.slice().sort((a, b) => a - b)])
    if (releaseAttempt?.fingerprint !== fingerprint) releaseAttempt = { fingerprint, code: `interface-${crypto.randomUUID()}` }
    const payload = { releaseCode: releaseAttempt.code, environment: scope.environment,
      appCode: scope.app, scenarioVersionIds: ids, releaseNote: `接口 Mock：${api.value?.apiName}` }
    await validateRelease(payload)
    requireScope()
    let release
    try { release = await createRelease(payload) }
    catch (failure) {
      const recovered = (await getReleases()).find(r => r.releaseCode === payload.releaseCode)
      if (recovered?.status === 'FAILED') releaseAttempt = undefined
      release = recovered?.status === 'READY' ? recovered : undefined
      if (!release) throw failure
    }
    requireScope()
    retryRelease.value = { id: release.id, expected: base?.activationVersion ?? 0, requestId: `interface-${crypto.randomUUID()}` }
    await activatePrepared()
  } catch (failure) { if (failure !== 'cancel' && failure !== 'close') error.value = failure instanceof Error ? failure.message : '发布失败' }
  finally { busy.value = false }
}
async function activatePrepared() {
  const prepared = retryRelease.value
  if (!prepared) return
  const result = await activateResponseRelease(prepared.id, prepared.expected, prepared.requestId)
  if (retryRelease.value?.requestId !== prepared.requestId) return
  activation.value = result
  activationId.value = result.activation.id; retryRelease.value = undefined
  message.value = '发布中，正在等待 Runtime 确认'
  clearTimeout(timer); await poll()
}
async function retryActivation() {
  busy.value = true; error.value = ''
  try { await activatePrepared() }
  catch (failure) { error.value = failure instanceof Error ? failure.message : '激活失败，请查看发布与回滚' }
  finally { busy.value = false }
}
watch(providerId, () => { if (!selectingCreatedApi) void loadApis() })
watch([apiId, () => session.environment, app], () => { scopeReset(); void load() })
watch(() => session.operatorId, () => { if (!busy.value && !editorVisible.value) void load() })
onMounted(async () => { try { await loadProviders(); await load() } catch { error.value = '服务商加载失败，请刷新页面' } })
onUnmounted(() => { disposed = true; generation++; apiGeneration++; clearTimeout(timer) })
</script>

<template>
  <section class="interface-page">
    <PageHeader description="选择接口，填写响应，审批后发布。其他已生效响应会自动保留。"><template #title>接口 Mock</template>
      <el-button type="primary" :disabled="!canEdit || locked" @click="configuringContract = false; catalogVisible = true">新建接口</el-button>
    </PageHeader>
    <HttpErrorAlert />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="message" :title="message" type="info" :closable="false" />
    <el-form inline class="scope-bar" :disabled="locked">
      <el-form-item label="应用"><el-input v-model.lazy="app" placeholder="已接入的 App Code" /></el-form-item>
      <el-form-item label="环境"><el-tag>{{ session.environment }}</el-tag></el-form-item>
      <el-form-item label="服务商"><el-select v-model="providerId" filterable style="width: 210px"><el-option v-for="p in providers" :key="p.id" :label="`${p.providerName} (${p.providerCode})`" :value="p.id" /></el-select></el-form-item>
      <el-form-item label="接口"><el-select v-model="apiId" filterable style="width: 250px"><el-option v-for="a in apis" :key="a.id" :label="`${a.apiName} (${a.apiCode})`" :value="a.id" /></el-select></el-form-item>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </el-form>
    <p class="scope-help">应用需先完成 Token 接入。新建接口不会自动注册应用。</p>
    <p v-if="loaded && active">当前应用：{{ active.state === 'APPLIED' ? '已生效' : '发布未完成，请等待或到发布与回滚处理' }} · v{{ active.activationVersion }}</p>
    <template v-if="api">
      <div class="interface-heading"><div><h2>{{ api.apiName }}</h2><code>{{ api.httpMethod }} {{ api.path }}</code></div>
        <el-button :disabled="!canEdit || !loaded || !readyContract || locked" @click="edit()">添加响应</el-button></div>
      <el-alert v-if="loaded && !readyContract" type="warning" :closable="false" title="此接口还没有已发布契约，先从请求和响应示例确认格式。">
        <el-button :disabled="!canEdit || locked" @click="configuringContract = true; catalogVisible = true">配置接口格式</el-button>
      </el-alert>
      <el-table v-loading="loading" :data="rows" row-key="scenario.id">
        <el-table-column label="发布" width="65"><template #default="{ row }"><el-checkbox v-model="chosen" :value="row.version.id" :disabled="!canEdit || !eligible(row.version) || locked" :aria-label="`发布 ${row.scenario.scenarioName}`" /></template></el-table-column>
        <el-table-column label="响应" min-width="170"><template #default="{ row }"><strong>{{ row.scenario.scenarioName }}</strong><p>v{{ row.version.versionNo }}</p></template></el-table-column>
        <el-table-column label="匹配条件" min-width="210"><template #default="{ row }">{{ describeRules(row.version) }}</template></el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><el-tag>{{ label(row.version) }}</el-tag></template></el-table-column>
        <el-table-column label="操作" min-width="235"><template #default="{ row }">
          <el-button v-if="editableResponse(row.version, session.environment, app.trim())" link :disabled="!canEdit || locked" @click="edit(row)">编辑</el-button>
          <RouterLink v-else to="/mock/scenarios">高级编辑</RouterLink>
          <el-button v-if="row.version.status === 'VALIDATED'" link :disabled="!canEdit || locked" @click="submit(row.version)">提交发布</el-button>
          <template v-if="approvalFor(row.version)?.status === 'PENDING'">
            <el-button link :disabled="!canApprove || locked || approvalFor(row.version)?.requestedBy === session.operatorId || row.version.createdBy === session.operatorId" @click="decide(row.version)">批准</el-button>
            <el-button link :disabled="!canApprove || locked || approvalFor(row.version)?.requestedBy === session.operatorId || row.version.createdBy === session.operatorId" @click="decide(row.version, true)">拒绝</el-button>
          </template>
          <el-popover trigger="click" width="540"><template #reference><el-button link>查看内容</el-button></template><pre class="response-preview">{{ responseBody(row.version) }}</pre><small>HTTP 状态与响应头：{{ row.version.response.httpStatus }} · {{ JSON.stringify(row.version.response.headers) }}</small></el-popover>
        </template></el-table-column>
        <template #empty>暂无响应，点击“添加响应”配置成功或失败返回。</template>
      </el-table>
      <div class="publish-bar"><span>已选 {{ chosen.length }} 个响应；发布时保留其他已生效响应。</span>
        <el-button type="primary" :disabled="!canEdit || locked || !loaded || !chosen.length || Boolean(retryRelease)" :loading="busy" @click="publish">发布已批准响应</el-button></div>
    </template>
    <el-empty v-else description="选择一个接口，或新建接口开始配置" />
    <el-alert v-if="retryRelease" title="配置已准备，激活尚未确认；可重试同一次激活，或到发布与回滚查看。" type="warning" :closable="false">
      <el-button :disabled="busy || !canEdit" @click="retryActivation">重试激活</el-button><RouterLink to="/mock/releases">发布与回滚</RouterLink>
    </el-alert>
    <div v-if="activation" class="activation-status"><strong>发布状态：{{ activation.activation.status === 'APPLIED' ? '已生效' : activation.activation.status }}</strong>
      <el-button link @click="refreshActivation">刷新发布状态</el-button>
      <p v-for="target in activation.targets" :key="target.runtimeNodeId">{{ target.runtimeNodeId }}：{{ target.status }}</p></div>
    <details class="advanced-links"><summary>高级管理与历史记录</summary><p>跨应用规则、动态模板、Flow 和回调请使用高级管理。当前本地发布模式仅支持无状态响应。</p>
      <RouterLink to="/mock/contracts">契约管理</RouterLink> · <RouterLink to="/mock/scenarios">场景管理</RouterLink> · <RouterLink to="/mock/releases">发布与回滚</RouterLink></details>
    <InterfaceCatalogDialog v-model="catalogVisible" :providers="providers" :operator="session.operatorId" :api="configuringContract ? api : undefined" @saved="savedApi" />
    <ResponseEditor v-if="api" v-model="editorVisible" :api="api" :contracts="contracts" :app="app.trim()" :environment="session.environment"
      :scenario="editing?.scenario" :version="editing?.version" @saved="message = '响应已保存；提交后由另一位审批人批准，再在本页发布'; load()" />
  </section>
</template>

<style scoped>
.interface-page { display: grid; gap: 18px; }
.scope-bar { border-bottom: 1px solid var(--el-border-color); padding-bottom: 4px; }
.scope-help { margin: -10px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.interface-heading, .publish-bar { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
h2 { margin: 0 0 8px; font-size: 20px; }
.publish-bar { padding: 16px 0; border-top: 1px solid var(--el-border-color); }
.response-preview { white-space: pre-wrap; max-height: 350px; overflow: auto; }
summary { cursor: pointer; }
.advanced-links { color: var(--el-text-color-secondary); }
@media (max-width: 700px) { .publish-bar { align-items: flex-start; flex-direction: column; } }
</style>
