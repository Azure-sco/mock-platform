<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import JsonEditor from '../../../components/JsonEditor.vue'
import {
  createSecurityPolicy,
  getSecurityPolicies,
  getSecurityPolicyBinding,
  getSecurityPolicyVersions,
  publishSecurityPolicyVersion,
  submitSecurityPolicyApproval,
  validateSecurityPolicyVersion,
} from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type {
  JsonValue,
  SecurityPolicy,
  SecurityPolicyBinding,
  SecurityPolicyMutation,
  SecurityPolicyType,
  SecurityPolicyVersion,
} from '../../../types/admin'
import { SubmissionCoordinator } from '../../../utils/requestControl'

const policyTypes: SecurityPolicyType[] = [
  'APP_ACL',
  'PROVIDER_ENVIRONMENT',
  'SDK_HEADER_FILTER',
  'CALLBACK_ALLOWLIST',
  'CALLBACK_SIGNATURE',
  'SDK_FALLBACK_REAL',
]

const errors = useErrorStore()
const session = useSessionStore()
const canPublish = computed(() => session.hasRole('MOCK_ADMIN'))
const loading = ref(false)
const actionId = ref<number | null>(null)
const saving = ref(false)
const policies = ref<SecurityPolicy[]>([])
const versions = ref<SecurityPolicyVersion[]>([])
const bindings = ref<SecurityPolicyBinding[]>([])
const selected = ref<SecurityPolicy | null>(null)
const createVisible = ref(false)
const filterType = ref<SecurityPolicyType | ''>('')
const filterScope = ref('')
const draft = reactive({
  policyType: 'APP_ACL' as SecurityPolicyType,
  scopeKey: 'TEST:sample-jdk17',
  sourceAuditRef: 'local-m2-acceptance',
  config: '{\n  "environment": "TEST",\n  "appCode": "sample-jdk17",\n  "providers": []\n}',
})
const submissions = new SubmissionCoordinator()

function parseJson(value: string): JsonValue {
  try {
    return JSON.parse(value) as JsonValue
  } catch {
    throw new Error('策略配置不是合法 JSON')
  }
}

async function load() {
  loading.value = true
  errors.clear()
  try {
    const type = filterType.value || undefined
    const policyResult = await getSecurityPolicies(type, filterScope.value || undefined)
    const bindingResult = await Promise.all(
      policyResult.map((policy) => getSecurityPolicyBinding(policy.policyType, policy.scopeKey)),
    )
    policies.value = policyResult
    bindings.value = bindingResult.filter((binding): binding is SecurityPolicyBinding => binding !== null)
  } catch {
    if (!errors.latest) errors.capture({ code: 'SECURITY_POLICY_LOAD_FAILED', message: '安全策略加载失败' })
  } finally {
    loading.value = false
  }
}

async function openVersions(row: SecurityPolicy) {
  selected.value = row
  actionId.value = row.latestVersionId ?? null
  errors.clear()
  try {
    versions.value = await getSecurityPolicyVersions(row.policyId)
  } catch {
    if (!errors.latest) errors.capture({ code: 'SECURITY_POLICY_VERSION_LOAD_FAILED', message: '策略版本加载失败' })
  } finally {
    actionId.value = null
  }
}

async function submitCreate() {
  if (saving.value) return
  let payload: SecurityPolicyMutation
  try {
    payload = {
      policyType: draft.policyType,
      scopeKey: draft.scopeKey.trim(),
      config: parseJson(draft.config),
      sourceAuditRef: draft.sourceAuditRef.trim() || undefined,
    }
  } catch (failure) {
    ElMessage.warning(failure instanceof Error ? failure.message : '策略配置错误')
    return
  }
  if (!payload.scopeKey) {
    ElMessage.warning('Scope Key 不能为空')
    return
  }
  const attempt = submissions.begin('policy:create', JSON.stringify(payload))
  if (!attempt) return
  saving.value = true
  let succeeded = false
  try {
    await createSecurityPolicy(payload, attempt.key)
    succeeded = true
    ElMessage.success('安全策略草稿已创建')
    createVisible.value = false
    await load()
  } catch {
    if (!errors.latest) errors.capture({ code: 'SECURITY_POLICY_CREATE_FAILED', message: '安全策略创建失败' })
  } finally {
    saving.value = false
    attempt.finish(succeeded)
  }
}

function bindingFor(version: SecurityPolicyVersion) {
  return bindings.value.find((binding) => binding.policyType === version.policyType && binding.scopeKey === version.scopeKey)
}

function bindingForPolicy(policy: SecurityPolicy) {
  return bindings.value.find((binding) => binding.policyType === policy.policyType && binding.scopeKey === policy.scopeKey)
}

async function runAction(version: SecurityPolicyVersion, action: 'validate' | 'submit' | 'publish') {
  if (actionId.value) return
  const operation = `policy:${action}:${version.id}`
  const attempt = submissions.begin(operation)
  if (!attempt) return
  actionId.value = version.id
  let succeeded = false
  errors.clear()
  try {
    if (action === 'validate') {
      await validateSecurityPolicyVersion(version.id, attempt.key)
      ElMessage.success('策略校验通过')
    } else if (action === 'submit') {
      await submitSecurityPolicyApproval(version.id, version.policyType, 1, attempt.key)
      ElMessage.success('策略已提交 checksum 审批')
    } else {
      await ElMessageBox.confirm('确认发布该不可变策略版本？', '策略发布', { type: 'warning' })
      await publishSecurityPolicyVersion(version.id, bindingFor(version)?.bindingVersion ?? 0, attempt.key)
      ElMessage.success('策略已发布，Binding/消费者生效状态将独立更新')
    }
    succeeded = true
    if (selected.value) await openVersions(selected.value)
    await load()
  } catch (failure) {
    if (failure !== 'cancel' && !errors.latest) {
      errors.capture({ code: 'SECURITY_POLICY_ACTION_FAILED', message: '安全策略操作失败' })
    }
  } finally {
    actionId.value = null
    attempt.finish(succeeded)
  }
}

function statusType(status: SecurityPolicyVersion['status']) {
  if (status === 'PUBLISHED' || status === 'APPROVED') return 'success'
  if (status === 'VALIDATED') return 'warning'
  if (status === 'DEPRECATED') return 'info'
  return 'primary'
}

onMounted(load)
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">SECURITY POLICY</p>
        <h2>安全策略</h2>
        <p>高风险策略必须经过不可变版本、checksum 审批和签名发布；PUBLISHED、BOUND、EFFECTIVE 分开展示。</p>
      </div>
      <el-button type="primary" :disabled="!canPublish" @click="createVisible = true">新建策略</el-button>
    </div>
    <HttpErrorAlert />

    <el-card class="filter-card" shadow="never">
      <el-form inline>
        <el-form-item label="类型"><el-select v-model="filterType" clearable style="width: 230px"><el-option v-for="type in policyTypes" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <el-form-item label="Scope"><el-input v-model="filterScope" clearable style="width: 260px" /></el-form-item>
        <el-form-item><el-button type="primary" plain @click="load">查询</el-button></el-form-item>
      </el-form>
    </el-card>

    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="policies" row-key="policyId">
        <el-table-column prop="policyId" label="Policy ID" min-width="170" />
        <el-table-column prop="policyType" label="类型" min-width="185" />
        <el-table-column prop="scopeKey" label="Scope" min-width="210" />
        <el-table-column prop="latestVersionNo" label="版本" width="80"><template #default="{ row }">v{{ row.latestVersionNo }}</template></el-table-column>
        <el-table-column label="Version 状态" width="130"><template #default="{ row }"><el-tag effect="plain">{{ row.latestStatus }}</el-tag></template></el-table-column>
        <el-table-column label="Binding" min-width="170">
          <template #default="{ row }">
            <span v-if="bindingForPolicy(row)">
              v{{ bindingForPolicy(row)?.bindingVersion }} · {{ bindingForPolicy(row)?.status }}
            </span>
            <span v-else>未绑定</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="openVersions(row)">版本</el-button></template></el-table-column>
        <template #empty><el-empty description="暂无安全策略" /></template>
      </el-table>
    </el-card>

    <el-card v-if="selected" class="policy-version-card" shadow="never">
      <template #header><strong>{{ selected.policyId }} · 版本</strong></template>
      <el-table :data="versions" row-key="id">
        <el-table-column label="版本" width="80"><template #default="{ row }">v{{ row.versionNo }}</template></el-table-column>
        <el-table-column label="状态" width="130"><template #default="{ row }"><el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag></template></el-table-column>
        <el-table-column prop="checksum" label="Checksum" min-width="230" show-overflow-tooltip />
        <el-table-column prop="signatureKeyId" label="签名 Key" min-width="120" />
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="185">
          <template #default="{ row }">
            <el-button link type="primary" :loading="actionId === row.id" :disabled="!canPublish || row.status !== 'DRAFT' || Boolean(actionId)" @click="runAction(row, 'validate')">校验</el-button>
            <el-button link type="primary" :loading="actionId === row.id" :disabled="!canPublish || row.status !== 'VALIDATED' || Boolean(actionId)" @click="runAction(row, 'submit')">审批</el-button>
            <el-button link type="primary" :loading="actionId === row.id" :disabled="!canPublish || row.status !== 'APPROVED' || Boolean(actionId)" @click="runAction(row, 'publish')">发布</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="新建安全策略" width="720px">
      <el-form label-position="top">
        <div class="form-grid">
          <el-form-item label="策略类型" required><el-select v-model="draft.policyType"><el-option v-for="type in policyTypes" :key="type" :label="type" :value="type" /></el-select></el-form-item>
          <el-form-item label="Scope Key" required><el-input v-model="draft.scopeKey" /></el-form-item>
        </div>
        <el-form-item label="配置 JSON" required><JsonEditor v-model="draft.config" :rows="13" /></el-form-item>
        <el-form-item label="外部审计引用"><el-input v-model="draft.sourceAuditRef" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="createVisible = false">取消</el-button><el-button type="primary" :loading="saving" :disabled="!canPublish" @click="submitCreate">创建草稿</el-button></template>
    </el-dialog>
  </section>
</template>
