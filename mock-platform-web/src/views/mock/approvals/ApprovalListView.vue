<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import HttpErrorAlert from '../../../components/HttpErrorAlert.vue'
import { decideApproval, getApprovals } from '../../../api/admin'
import { useErrorStore } from '../../../stores/errors'
import { useSessionStore } from '../../../stores/session'
import type { ApprovalRequest } from '../../../types/admin'
import { SubmissionCoordinator } from '../../../utils/requestControl'

const errors = useErrorStore()
const session = useSessionStore()
const canApprove = computed(() => session.hasRole('MOCK_ADMIN'))
const loading = ref(false)
const actionId = ref<number | null>(null)
const approvals = ref<ApprovalRequest[]>([])
const submissions = new SubmissionCoordinator()

async function load() {
  loading.value = true
  errors.clear()
  try {
    approvals.value = await getApprovals()
  } catch {
    if (!errors.latest) errors.capture({ code: 'APPROVAL_LOAD_FAILED', message: '审批列表加载失败' })
  } finally {
    loading.value = false
  }
}

async function decide(row: ApprovalRequest, decision: 'approve' | 'reject') {
  if (actionId.value) return
  let comment = ''
  try {
    const result = await ElMessageBox.prompt(
      decision === 'approve' ? '确认审批该 checksum？可填写意见。' : '请填写拒绝原因。',
      decision === 'approve' ? '审批确认' : '拒绝确认',
      {
        confirmButtonText: decision === 'approve' ? '批准' : '拒绝',
        cancelButtonText: '取消',
        inputValidator: (value) => decision === 'approve' || Boolean(value?.trim()) || '拒绝原因不能为空',
        type: decision === 'approve' ? 'warning' : 'error',
      },
    )
    comment = result.value
  } catch {
    return
  }

  const attempt = submissions.begin(`approval:${decision}:${row.id}`)
  if (!attempt) return
  actionId.value = row.id
  let succeeded = false
  errors.clear()
  try {
    await decideApproval(row.id, decision, { comment: comment || undefined }, attempt.key)
    succeeded = true
    ElMessage.success(decision === 'approve' ? '审批已通过' : '审批已拒绝')
    await load()
  } catch {
    if (!errors.latest) errors.capture({ code: 'APPROVAL_DECISION_FAILED', message: '审批操作失败' })
  } finally {
    actionId.value = null
    attempt.finish(succeeded)
  }
}

function statusType(status: ApprovalRequest['status']) {
  if (status === 'APPROVED') return 'success'
  if (status === 'REJECTED') return 'danger'
  return 'warning'
}

onMounted(load)
</script>

<template>
  <section class="management-page">
    <div class="page-heading">
      <div>
        <p class="eyebrow">CHECKSUM APPROVAL</p>
        <h2>审批中心</h2>
        <p>审批绑定对象 checksum；同一评审人不重复计数，拒绝会终止当前审批。</p>
      </div>
      <el-button type="primary" plain @click="load">刷新</el-button>
    </div>
    <HttpErrorAlert />
    <el-card class="table-card" shadow="never">
      <el-table v-loading="loading" :data="approvals" row-key="id">
        <el-table-column prop="objectType" label="对象类型" min-width="160" />
        <el-table-column prop="objectId" label="对象 ID" width="105" />
        <el-table-column prop="objectChecksum" label="Checksum" min-width="240" show-overflow-tooltip>
          <template #default="{ row }"><code>{{ row.objectChecksum }}</code></template>
        </el-table-column>
        <el-table-column label="进度" width="110">
          <template #default="{ row }">{{ row.approvedCount ?? 0 }} / {{ row.requiredCount }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="requestedBy" label="提交人" width="130" />
        <el-table-column prop="requestedAt" label="提交时间" min-width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :loading="actionId === row.id" :disabled="!canApprove || row.status !== 'PENDING'" @click="decide(row, 'approve')">批准</el-button>
            <el-button link type="danger" :loading="actionId === row.id" :disabled="!canApprove || row.status !== 'PENDING'" @click="decide(row, 'reject')">拒绝</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="暂无待处理审批" /></template>
      </el-table>
    </el-card>
  </section>
</template>
