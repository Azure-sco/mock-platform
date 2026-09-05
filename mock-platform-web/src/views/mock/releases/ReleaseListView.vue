<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import {
  createRelease,
  getActiveRelease,
  getReleases,
  publishRelease,
  rollbackRelease,
  validateRelease,
} from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type { ActiveRelease, Release, ReleaseMutation, ReleaseValidation } from '../../../types/admin'
import { LatestRequestGate, SubmissionCoordinator } from '../../../utils/requestControl'
import { activationVersionFor } from '../../../utils/releaseActivation'

const errors = useErrorStore()
const session = useSessionStore()
const canPublish = computed(() => session.hasRole('MOCK_ADMIN'))
const loading = ref(false)
const actionId = ref<string | null>(null)
const validating = ref(false)
const creating = ref(false)
const releases = ref<Release[]>([])
const active = ref<ActiveRelease | null>(null)
const activeLoaded = ref(false)
const appCode = ref('sample-jdk17')
const createVisible = ref(false)
const validation = ref<ReleaseValidation | null>(null)
const validatedFingerprint = ref('')
const scenarioIds = ref('')
const draft = reactive<Omit<ReleaseMutation, 'scenarioVersionIds'>>({
  releaseCode: '',
  environment: 'TEST',
  appCode: 'sample-jdk17',
  releaseNote: '',
})
const loadGate = new LatestRequestGate()
const submissions = new SubmissionCoordinator()
const visibleReleases = computed(() => releases.value.filter((release) => (
  release.environment === session.environment && (!appCode.value.trim() || release.appCode === appCode.value.trim())
)))

function parsedScenarioIds(): number[] {
  const ids = scenarioIds.value
    .split(/[\s,]+/)
    .filter(Boolean)
    .map(Number)
  if (!ids.length || ids.some((id) => !Number.isSafeInteger(id) || id <= 0)) {
    throw new Error('Scenario Version ID 必须是逗号分隔的正整数')
  }
  return [...new Set(ids)]
}

function payload(): ReleaseMutation {
  return { ...draft, scenarioVersionIds: parsedScenarioIds() }
}

async function load() {
  const request = loadGate.next()
  const environment = session.environment
  const app = appCode.value.trim()
  releases.value = []
  active.value = null
  activeLoaded.value = false
  loading.value = true
  errors.clear()
  try {
    const [releaseResult, activeResult] = await Promise.all([
      getReleases(),
      getActiveRelease(environment, app),
    ])
    if (!loadGate.isLatest(request)) return
    releases.value = releaseResult
    active.value = activeResult
    activeLoaded.value = true
  } catch {
    if (!errors.latest) errors.capture({ code: 'RELEASE_LOAD_FAILED', message: '发布列表加载失败' })
  } finally {
    if (loadGate.isLatest(request)) loading.value = false
  }
}

async function runValidation() {
  if (validating.value) return
  let candidate: ReleaseMutation
  try {
    candidate = payload()
  } catch (failure) {
    ElMessage.warning(failure instanceof Error ? failure.message : 'Release 输入错误')
    return
  }
  const attempt = submissions.begin('release:validate', JSON.stringify(candidate))
  if (!attempt) return
  validating.value = true
  let succeeded = false
  try {
    validation.value = null
    validatedFingerprint.value = ''
    validation.value = await validateRelease(candidate, attempt.key)
    validatedFingerprint.value = JSON.stringify(candidate)
    succeeded = true
    ElMessage.success(validation.value.valid ? 'Release 校验通过' : 'Release 校验未通过')
  } catch (failure) {
    if (failure instanceof Error && failure.message.startsWith('Scenario Version')) {
      ElMessage.warning(failure.message)
    } else if (!errors.latest) {
      errors.capture({ code: 'RELEASE_VALIDATE_FAILED', message: 'Release 校验失败' })
    }
  } finally {
    validating.value = false
    attempt.finish(succeeded)
  }
}

async function submitCreate() {
  if (creating.value) return
  if (!validation.value?.valid) {
    ElMessage.warning('请先完成并通过 Release 校验')
    return
  }
  let candidate: ReleaseMutation
  try {
    candidate = payload()
  } catch (failure) {
    ElMessage.warning(failure instanceof Error ? failure.message : 'Release 输入错误')
    return
  }
  if (JSON.stringify(candidate) !== validatedFingerprint.value) {
    validation.value = null
    ElMessage.warning('Release 内容已变化，请重新校验')
    return
  }
  const attempt = submissions.begin('release:create', validatedFingerprint.value)
  if (!attempt) return
  creating.value = true
  let succeeded = false
  try {
    await createRelease(candidate, attempt.key)
    succeeded = true
    ElMessage.success('不可变 Release 已创建')
    createVisible.value = false
    validation.value = null
    await load()
  } catch {
    if (!errors.latest) errors.capture({ code: 'RELEASE_CREATE_FAILED', message: 'Release 创建失败' })
  } finally {
    creating.value = false
    attempt.finish(succeeded)
  }
}

async function activate(row: Release, action: 'publish' | 'rollback') {
  if (actionId.value) return
  const operation = `release:${action}:${row.id}`
  const attempt = submissions.begin(operation)
  if (!attempt) return
  actionId.value = row.id
  errors.clear()
  let succeeded = false
  let authority: ActiveRelease | null
  try {
    authority = await getActiveRelease(row.environment, row.appCode)
  } catch {
    attempt.finish(false)
    actionId.value = null
    if (!errors.latest) errors.capture({ code: 'RELEASE_AUTHORITY_FAILED', message: '未取得目标环境与应用的权威激活状态，操作已禁止' })
    return
  }
  let version: number
  try {
    version = activationVersionFor(row, authority)
  } catch {
    attempt.finish(false)
    actionId.value = null
    if (!errors.latest) errors.capture({ code: 'RELEASE_AUTHORITY_MISMATCH', message: '权威状态与目标 Release 范围不一致，操作已禁止' })
    return
  }
  try {
    await ElMessageBox.confirm(
      `${action === 'publish' ? '发布' : '回滚到'} ${row.releaseCode}\n环境：${row.environment}\n应用：${row.appCode}\n目标版本：${row.id}\n当前 Activation Version：${version}`,
      action === 'publish' ? '发布确认' : '回滚确认',
      { type: 'warning' },
    )
  } catch {
    submissions.reset(operation)
    actionId.value = null
    return
  }
  try {
    const result = action === 'publish'
      ? await publishRelease(row.id, version, attempt.key)
      : await rollbackRelease(row.id, version, attempt.key)
    succeeded = true
    ElMessage.success(`Activation v${result.activationVersion} 已进入 ${result.state}`)
    await load()
  } catch {
    if (!errors.latest) errors.capture({ code: 'RELEASE_ACTIVATION_FAILED', message: 'Release 激活失败，请刷新权威版本后重试' })
  } finally {
    actionId.value = null
    attempt.finish(succeeded)
  }
}

function openCreate() {
  draft.environment = session.environment
  draft.appCode = appCode.value.trim()
  validation.value = null
  validatedFingerprint.value = ''
  submissions.reset('release:validate')
  submissions.reset('release:create')
  createVisible.value = true
}

function releaseTag(status: Release['status']) {
  if (status === 'PUBLISHED') return 'success'
  if (status === 'READY') return 'warning'
  if (status === 'FAILED') return 'danger'
  return 'primary'
}

onMounted(load)
watch(() => session.environment, () => {
  active.value = null
  activeLoaded.value = false
  draft.environment = session.environment
  validation.value = null
  validatedFingerprint.value = ''
  void load()
})
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">IMMUTABLE RELEASE</p>
        <h2>发布与回滚</h2>
        <p>MySQL Active Release 是权威状态；Redis、Runtime Cache 与节点 ACK 均为可恢复投影。</p>
      </div>
      <el-button type="primary" :disabled="!canPublish" @click="openCreate">创建 Release</el-button>
    </div>
    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form inline>
        <el-form-item label="Environment"><el-tag effect="plain">{{ session.environment }}</el-tag></el-form-item>
        <el-form-item label="App"><el-input v-model="appCode" style="width: 220px" /></el-form-item>
        <el-form-item><el-button type="primary" plain @click="load">查询权威状态</el-button></el-form-item>
        <el-form-item label="Active">
          <span v-if="!activeLoaded">—</span>
          <span v-else-if="active"><strong>#{{ active.releaseId }}</strong> · v{{ active.activationVersion }} · {{ active.state }}</span>
          <span v-else>尚未激活（权威状态已确认）</span>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="visibleReleases" row-key="id">
        <el-table-column prop="releaseCode" label="Release" min-width="180" />
        <el-table-column prop="environment" label="环境" width="90" />
        <el-table-column prop="appCode" label="App" min-width="140" />
        <el-table-column label="状态" width="120"><template #default="{ row }"><el-tag :type="releaseTag(row.status)" effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="checksum" label="Snapshot Checksum" min-width="250" show-overflow-tooltip><template #default="{ row }"><code>{{ row.checksum }}</code></template></el-table-column>
        <el-table-column prop="signatureKeyId" label="签名 Key" min-width="130" />
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="actionId === row.id" :disabled="!canPublish || row.status !== 'READY'" @click="activate(row, 'publish')">发布</el-button>
            <el-button link type="warning" :loading="actionId === row.id" :disabled="!canPublish || !['READY', 'PUBLISHED'].includes(row.status)" @click="activate(row, 'rollback')">回滚</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty :description="activeLoaded ? '当前环境与应用暂无 Release' : '正在加载发布状态'" /></template>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="创建不可变 Release" width="680px">
      <el-alert type="warning" :closable="false" title="仅可选择已 APPROVED 的 Scenario Version；创建前必须校验。" />
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="Release Code" required><el-input v-model="draft.releaseCode" /></el-form-item>
          <el-form-item label="App Code" required><el-input v-model="draft.appCode" /></el-form-item>
          <el-form-item label="Environment" required><el-select v-model="draft.environment" disabled><el-option label="TEST" value="TEST" /><el-option label="UAT" value="UAT" /></el-select></el-form-item>
          <el-form-item label="Scenario Version IDs" required><el-input v-model="scenarioIds" placeholder="例如 11, 12, 15" /></el-form-item>
        </div>
        <el-form-item label="发布说明"><el-input v-model="draft.releaseNote" type="textarea" :rows="3" /></el-form-item>
        <pre v-if="validation" class="json-preview">{{ JSON.stringify(validation, null, 2) }}</pre>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" plain :loading="validating" :disabled="creating" @click="runValidation">校验</el-button>
        <el-button type="primary" :loading="creating" :disabled="!canPublish || !validation?.valid || validating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </section>
</template>
