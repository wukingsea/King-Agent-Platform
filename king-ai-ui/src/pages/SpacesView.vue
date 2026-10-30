<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Delete,
  EditPen,
  OfficeBuilding,
  Plus,
  Switch,
  UserFilled,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { spaceApi, userApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { SpaceMemberUpdate, SpaceRole, SpaceView, UserOption } from '@/api/types'
import { fromNow } from '@/utils/format'

const auth = useAuthStore()
const router = useRouter()

const loading = ref(false)
const spaces = ref<SpaceView[]>([])
const memberCounts = ref<Record<number, number>>({})

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({
  name: '',
  code: '',
  description: '',
  status: 0,
})

const memberVisible = ref(false)
const memberSaving = ref(false)
const memberSpace = ref<SpaceView | null>(null)
const memberLoading = ref(false)
const memberRoles = ref<Record<number, SpaceRole>>({})
const userOptions = ref<UserOption[]>([])
const memberKeyword = ref('')

const spaceRoleOptions: Array<{ label: string; value: SpaceRole }> = [
  { label: '所有者', value: 'OWNER' },
  { label: '管理员', value: 'ADMIN' },
  { label: '编辑者', value: 'EDITOR' },
  { label: '访客', value: 'VIEWER' },
  { label: '审计员', value: 'AUDITOR' },
]

const memberCount = computed(() => Object.keys(memberRoles.value).length)
const memberEditable = computed(
  () =>
    auth.isAdmin ||
    memberSpace.value?.role === 'OWNER' ||
    memberSpace.value?.role === 'ADMIN',
)
const canAssignOwner = computed(
  () => auth.isAdmin || memberSpace.value?.role === 'OWNER',
)

const rules: FormRules = {
  name: [
    { required: true, message: '请输入空间名称', trigger: 'blur' },
    { max: 100, message: '名称不能超过 100 个字符', trigger: 'blur' },
  ],
  code: [
    { required: true, message: '请输入空间编码', trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9_-]{1,31}$/,
      message: '以小写字母开头，2-32 位小写字母、数字、_ 或 -',
      trigger: 'blur',
    },
  ],
}

const enabledCount = computed(() => spaces.value.filter((space) => space.status === 0).length)

const filteredUsers = computed(() => {
  const kw = memberKeyword.value.trim().toLowerCase()
  if (!kw) return userOptions.value
  return userOptions.value.filter(
    (user) =>
      user.username.toLowerCase().includes(kw) ||
      (user.nickname || '').toLowerCase().includes(kw),
  )
})

function resetForm() {
  editingId.value = null
  Object.assign(form, { name: '', code: '', description: '', status: 0 })
}

async function loadSpaces() {
  loading.value = true
  try {
    spaces.value = await spaceApi.list()
    const counts = await Promise.allSettled(
      spaces.value.map((space) => spaceApi.members(space.id)),
    )
    const next: Record<number, number> = {}
    counts.forEach((result, index) => {
      if (result.status === 'fulfilled') {
        next[spaces.value[index].id] = result.value.length
      }
    })
    memberCounts.value = next
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载业务空间失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(space: SpaceView) {
  resetForm()
  editingId.value = space.id
  Object.assign(form, {
    name: space.name,
    code: space.code,
    description: space.description || '',
    status: space.status,
  })
  dialogVisible.value = true
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editingId.value) {
      await spaceApi.update(editingId.value, {
        name: form.name.trim(),
        description: form.description.trim(),
        status: form.status,
      })
      ElMessage.success('空间已更新')
    } else {
      await spaceApi.create({
        name: form.name.trim(),
        code: form.code.trim(),
        description: form.description.trim(),
        status: form.status,
      })
      ElMessage.success('业务空间已创建')
    }
    dialogVisible.value = false
    await loadSpaces()
    await auth.fetchMe()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

async function removeSpace(space: SpaceView) {
  try {
    await ElMessageBox.confirm(
      `确认删除业务空间「${space.name}」？空间内的智能体、知识库、模型等资源必须先清理。`,
      '删除业务空间',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await spaceApi.remove(space.id)
    ElMessage.success('业务空间已删除')
    await loadSpaces()
    await auth.fetchMe()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

async function openMembers(space: SpaceView) {
  memberSpace.value = space
  memberVisible.value = true
  memberLoading.value = true
  memberKeyword.value = ''
  memberRoles.value = {}
  try {
    const [members, options] = await Promise.all([
      spaceApi.members(space.id),
      userApi.options(),
    ])
    memberRoles.value = Object.fromEntries(
      members.map((member) => [member.userId, member.role]),
    )
    userOptions.value = options
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载成员失败')
  } finally {
    memberLoading.value = false
  }
}

async function saveMembers() {
  if (!memberSpace.value) return
  const members: SpaceMemberUpdate[] = Object.entries(memberRoles.value).map(
    ([userId, role]) => ({ userId: Number(userId), role }),
  )
  memberSaving.value = true
  try {
    await spaceApi.setMembers(memberSpace.value.id, members)
    ElMessage.success('空间成员已更新')
    memberVisible.value = false
    await loadSpaces()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    memberSaving.value = false
  }
}

function isMember(id: number) {
  return Object.prototype.hasOwnProperty.call(memberRoles.value, id)
}

function toggleMember(id: number) {
  if (!memberEditable.value) return
  if (isMember(id)) {
    delete memberRoles.value[id]
  } else {
    memberRoles.value[id] = 'VIEWER'
  }
}

function roleOfMember(id: number): SpaceRole {
  return memberRoles.value[id] || 'VIEWER'
}

function canEditMemberRole(id: number): boolean {
  if (!memberEditable.value) return false
  return roleOfMember(id) !== 'OWNER' || canAssignOwner.value
}

function canManageMembers(space: SpaceView): boolean {
  return (
    auth.isAdmin ||
    space.role === 'OWNER' ||
    space.role === 'ADMIN' ||
    space.role === 'AUDITOR'
  )
}

function canEditSpace(space: SpaceView): boolean {
  return auth.isAdmin || space.role === 'OWNER'
}

function canOwnSpace(space: SpaceView): boolean {
  return auth.isAdmin || space.role === 'OWNER'
}

function spaceRoleLabel(role?: SpaceRole): string {
  return spaceRoleOptions.find((item) => item.value === role)?.label || '访客'
}

function switchTo(space: SpaceView) {
  if (space.status !== 0) {
    ElMessage.warning('该空间已停用')
    return
  }
  if (space.id === auth.currentSpaceId) {
    ElMessage.info('当前已在该空间')
    return
  }
  auth.switchSpace(space.id)
  router.push({ name: 'dashboard' })
  ElMessage.success(`已切换到「${space.name}」`)
}

function roleLabel(user: UserOption): string {
  return user.role === 'ADMIN' ? '管理员' : '成员'
}

onMounted(loadSpaces)
</script>

<template>
  <div class="page">
    <PageHeader
      title="业务空间"
      description="以空间为单位隔离智能体、模型、知识库与会话数据，并控制成员访问范围。"
      eyebrow="Workspaces"
    >
      <template #actions>
        <el-button v-if="auth.isAdmin" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          新建空间
        </el-button>
      </template>
    </PageHeader>

    <div class="summary-row">
      <div class="summary-chip">
        <span class="chip-dot"></span>
        共 {{ spaces.length }} 个空间
      </div>
      <div class="summary-chip">
        <span class="chip-dot green"></span>
        {{ enabledCount }} 个启用中
      </div>
      <div class="summary-chip">
        <span class="chip-dot cyan"></span>
        当前：{{ auth.currentSpace?.name || '未选择' }}
      </div>
    </div>

    <div v-loading="loading" class="space-wrap">
      <div v-if="spaces.length" class="space-grid">
        <article
          v-for="space in spaces"
          :key="space.id"
          class="space-card panel"
          :class="{ current: space.id === auth.currentSpaceId }"
        >
          <div class="space-glow"></div>
          <div class="space-top">
            <div class="space-icon">
              <el-icon><OfficeBuilding /></el-icon>
            </div>
            <div class="space-heading">
              <div class="space-title-row">
                <h3>{{ space.name }}</h3>
                <span class="badge" :class="space.status === 0 ? 'published' : 'draft'">
                  {{ space.status === 0 ? '启用' : '停用' }}
                </span>
                <span v-if="space.id === auth.currentSpaceId" class="current-tag">当前空间</span>
                <span class="role-tag">{{ auth.isAdmin ? '系统管理员' : spaceRoleLabel(space.role) }}</span>
              </div>
              <p class="space-code mono">{{ space.code }}</p>
            </div>
          </div>

          <p class="space-desc">{{ space.description || '暂无空间描述' }}</p>

          <div class="space-stats">
            <div class="stat">
              <span class="stat-value">{{ memberCounts[space.id] ?? '—' }}</span>
              <span class="stat-label">成员</span>
            </div>
            <div class="stat">
              <span class="stat-value">{{ fromNow(space.createdAt) }}</span>
              <span class="stat-label">创建于</span>
            </div>
          </div>

          <div class="space-actions">
            <el-button size="small" type="primary" plain :disabled="space.status !== 0" @click="switchTo(space)">
              <el-icon><Switch /></el-icon>
              切换
            </el-button>
            <el-button v-if="canManageMembers(space)" size="small" @click="openMembers(space)">
              <el-icon><UserFilled /></el-icon>
              成员
            </el-button>
            <div class="spacer"></div>
            <el-button v-if="canEditSpace(space)" size="small" text @click="openEdit(space)">
              <el-icon><EditPen /></el-icon>
              编辑
            </el-button>
            <el-button
              v-if="canOwnSpace(space)"
              size="small"
              text
              type="danger"
              @click="removeSpace(space)"
            >
              <el-icon><Delete /></el-icon>
              删除
            </el-button>
          </div>
        </article>
      </div>
      <EmptyState
        v-else-if="!loading"
        :icon="OfficeBuilding"
        title="还没有业务空间"
        description="创建第一个空间，开始配置模型、知识库与智能体。"
      >
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          新建空间
        </el-button>
      </EmptyState>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="editingId ? '编辑业务空间' : '新建业务空间'"
      width="540px"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="空间名称" prop="name">
          <el-input v-model="form.name" placeholder="例如：产品研发空间" maxlength="100" />
        </el-form-item>
        <el-form-item label="空间编码" prop="code">
          <el-input
            v-model="form.code"
            :disabled="!!editingId"
            placeholder="例如：product-rd"
            maxlength="32"
            class="mono-input"
          />
          <div class="form-hint">编码创建后不可修改，用于数据隔离与接口标识。</div>
        </el-form-item>
        <el-form-item label="空间描述">
          <el-input
            v-model="form.description"
            type="textarea"
            :rows="3"
            maxlength="255"
            placeholder="说明该空间的使用团队与业务范围"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.status"
            :active-value="0"
            :inactive-value="1"
            active-text="启用"
            inactive-text="停用"
          />
          <div class="form-hint">停用后成员将无法切换到该空间，空间数据会保留。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer
      v-model="memberVisible"
      :title="`成员授权 · ${memberSpace?.name || ''}`"
      size="480px"
      destroy-on-close
    >
      <div v-loading="memberLoading" class="member-body">
        <div class="member-summary">
          <el-icon><UserFilled /></el-icon>
          <span>已授权 {{ memberCount }} 人</span>
          <span class="member-space mono">{{ memberSpace?.code }}</span>
        </div>
        <el-input v-model="memberKeyword" placeholder="搜索账号或昵称" clearable class="member-search">
          <template #prefix><el-icon><UserFilled /></el-icon></template>
        </el-input>

        <div class="member-list">
          <button
            v-for="user in filteredUsers"
            :key="user.id"
            type="button"
            class="member-item"
            :class="{ active: isMember(user.id), disabled: user.status !== 0 }"
            :disabled="user.status !== 0"
            @click="toggleMember(user.id)"
          >
            <span class="member-avatar">{{ (user.nickname || user.username).slice(0, 1).toUpperCase() }}</span>
            <span class="member-info">
              <span class="member-name">
                {{ user.nickname || user.username }}
                <span class="member-role">{{ roleLabel(user) }}</span>
                <span v-if="user.status !== 0" class="member-disabled">已停用</span>
              </span>
              <span class="member-account mono">@{{ user.username }}</span>
            </span>
            <el-select
              v-if="isMember(user.id)"
              :model-value="roleOfMember(user.id)"
              size="small"
              class="role-select"
              :disabled="!canEditMemberRole(user.id)"
              @click.stop
              @update:model-value="(role: SpaceRole) => (memberRoles[user.id] = role)"
            >
              <el-option
                v-for="option in spaceRoleOptions"
                :key="option.value"
                :label="option.label"
                :value="option.value"
                :disabled="option.value === 'OWNER' && !canAssignOwner"
              />
            </el-select>
            <span class="check" :class="{ on: isMember(user.id) }">
              {{ isMember(user.id) ? '✓' : '' }}
            </span>
          </button>
          <div v-if="!filteredUsers.length" class="member-empty">没有匹配的用户</div>
        </div>

        <p class="member-hint">
          所有者可管理空间与所有权；管理员可管理成员与全部会话；编辑者可维护内容；
          访客只读；审计员可查看全部会话与审计记录。成员变更即时生效。
        </p>
      </div>
      <template #footer>
        <el-button @click="memberVisible = false">{{ memberEditable ? '取消' : '关闭' }}</el-button>
        <el-button
          v-if="memberEditable"
          type="primary"
          :loading="memberSaving"
          @click="saveMembers"
        >
          保存成员
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
.space-wrap {
  min-height: 240px;
}

.space-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
}

.space-card {
  position: relative;
  padding: 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  overflow: hidden;
  transition: transform 0.18s ease, border-color 0.18s ease;
}

.space-card:hover {
  transform: translateY(-2px);
  border-color: rgba(124, 92, 255, 0.34);
}

.space-card.current {
  border-color: rgba(56, 189, 248, 0.42);
}

.space-glow {
  position: absolute;
  top: -80px;
  right: -60px;
  width: 180px;
  height: 180px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(124, 92, 255, 0.28), transparent 68%);
  pointer-events: none;
}

.space-top {
  display: flex;
  gap: 14px;
  position: relative;
}

.space-icon {
  width: 46px;
  height: 46px;
  flex: 0 0 46px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  font-size: 22px;
  color: var(--k-violet);
  background: linear-gradient(135deg, rgba(124, 92, 255, 0.28), rgba(56, 189, 248, 0.14));
  border: 1px solid rgba(124, 92, 255, 0.32);
}

.space-heading {
  flex: 1;
  min-width: 0;
}

.space-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.space-title-row h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 650;
  color: var(--text-strong);
}

.current-tag {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(56, 189, 248, 0.14);
  border: 1px solid rgba(56, 189, 248, 0.3);
  color: var(--k-sky);
  letter-spacing: 0.04em;
}

.role-tag {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(124, 92, 255, 0.12);
  border: 1px solid rgba(124, 92, 255, 0.28);
  color: var(--k-violet);
}

.space-code {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--text-faint);
}

.space-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--text-muted);
  min-height: 42px;
  position: relative;
}

.space-stats {
  display: flex;
  gap: 32px;
  padding: 14px 16px;
  border-radius: 12px;
  background: var(--k-code-bg);
  border: 1px solid var(--border-subtle);
}

.stat {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.stat-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-strong);
  font-family: var(--font-mono);
}

.stat-label {
  font-size: 11px;
  color: var(--text-faint);
  letter-spacing: 0.05em;
}

.space-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  padding-top: 12px;
  border-top: 1px solid var(--border-subtle);
}

.spacer {
  flex: 1;
}

.form-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--text-faint);
  line-height: 1.5;
}

.mono-input :deep(input) {
  font-family: var(--font-mono);
}

.member-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 300px;
}

.member-summary {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  border-radius: 12px;
  background: rgba(124, 92, 255, 0.08);
  border: 1px solid rgba(124, 92, 255, 0.2);
  color: var(--k-violet);
  font-size: 13px;
}

.member-space {
  margin-left: auto;
  font-size: 11px;
  color: var(--text-faint);
}

.member-search :deep(input) {
  font-size: 13px;
}

.member-list {
  flex: 1;
  overflow-y: auto;
  max-height: 430px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-right: 2px;
}

.member-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid var(--border-subtle);
  background: var(--k-code-bg);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s ease;
}

.member-item:hover {
  border-color: rgba(124, 92, 255, 0.3);
}

.member-item.active {
  border-color: rgba(124, 92, 255, 0.46);
  background: rgba(124, 92, 255, 0.12);
}

.member-item.disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.member-avatar {
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border-radius: 9px;
  display: grid;
  place-items: center;
  font-size: 12px;
  font-weight: 600;
  color: var(--k-violet-soft);
  background: rgba(124, 92, 255, 0.2);
}

.member-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.member-name {
  font-size: 13px;
  color: var(--text-strong);
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.member-role {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.12);
  color: var(--text-faint);
}

.member-disabled {
  font-size: 10px;
  color: var(--k-red);
}

.member-account {
  font-size: 11px;
  color: var(--text-faint);
}

.role-select {
  width: 88px;
  flex: 0 0 88px;
}

.check {
  width: 20px;
  height: 20px;
  flex: 0 0 20px;
  border-radius: 6px;
  border: 1px solid var(--border-subtle);
  display: grid;
  place-items: center;
  font-size: 12px;
  color: var(--king-bg);
}

.check.on {
  background: linear-gradient(135deg, #7c5cff, #38bdf8);
  border-color: transparent;
  color: #fff;
}

.member-empty {
  padding: 24px;
  text-align: center;
  font-size: 12px;
  color: var(--text-faint);
}

.member-hint {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-faint);
}

@media (max-width: 720px) {
  .space-grid {
    grid-template-columns: 1fr;
  }
}
</style>
