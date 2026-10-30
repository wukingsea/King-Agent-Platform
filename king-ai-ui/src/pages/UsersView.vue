<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Delete, EditPen, Key, Plus, Search, User } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { spaceApi, userApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { SpaceView, UserCreateRequest, UserView } from '@/api/types'
import { formatDate, fromNow } from '@/utils/format'
import { PASSWORD_PATTERN, PASSWORD_RULE_MESSAGE } from '@/utils/password'

const auth = useAuthStore()

const loading = ref(false)
const keyword = ref('')
const users = ref<UserView[]>([])
const spaces = ref<SpaceView[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)

const dialogVisible = ref(false)
const saving = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const form = reactive({
  username: '',
  password: '',
  nickname: '',
  email: '',
  role: 'USER',
  status: 0,
  spaceIds: [] as number[],
})

const passwordVisible = ref(false)
const passwordSaving = ref(false)
const passwordTarget = ref<UserView | null>(null)
const newPassword = ref('')

const rules: FormRules = {
  username: [
    { required: true, message: '请输入登录账号', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z0-9_][a-zA-Z0-9_.-]{2,31}$/,
      message: '3-32 位字母、数字或 _ . -',
      trigger: 'blur',
    },
  ],
  password: [
    {
      validator: (_rule, value: string, callback) => {
        if (editingId.value) return callback()
        if (!value) return callback(new Error('请设置初始密码'))
        if (!PASSWORD_PATTERN.test(value)) return callback(new Error(PASSWORD_RULE_MESSAGE))
        callback()
      },
      trigger: 'blur',
    },
  ],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }],
}

const adminCount = computed(() => users.value.filter((user) => user.role === 'ADMIN').length)
const activeCount = computed(() => users.value.filter((user) => user.status === 0).length)

const spaceNames = computed(() => {
  const map = new Map<number, string>()
  spaces.value.forEach((space) => map.set(space.id, space.name))
  return map
})

function resetForm() {
  editingId.value = null
  Object.assign(form, {
    username: '',
    password: '',
    nickname: '',
    email: '',
    role: 'USER',
    status: 0,
    spaceIds: [],
  })
}

async function loadUsers() {
  loading.value = true
  try {
    const result = await userApi.list({
      page: pageNo.value,
      size: pageSize.value,
      keyword: keyword.value.trim() || undefined,
    })
    users.value = result.list
    total.value = result.total
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载用户失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  resetForm()
  form.spaceIds = auth.currentSpaceId ? [auth.currentSpaceId] : []
  dialogVisible.value = true
}

function openEdit(user: UserView) {
  resetForm()
  editingId.value = user.id
  Object.assign(form, {
    username: user.username,
    password: '',
    nickname: user.nickname || '',
    email: user.email || '',
    role: user.role,
    status: user.status,
    spaceIds: [...(user.spaceIds || [])],
  })
  dialogVisible.value = true
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  saving.value = true
  try {
    if (editingId.value) {
      await userApi.update(editingId.value, {
        nickname: form.nickname.trim(),
        email: form.email.trim(),
        role: form.role,
        status: form.status,
        spaceIds: form.spaceIds,
      })
      ElMessage.success('用户已更新')
      if (editingId.value === auth.user?.id) {
        await auth.fetchMe()
      }
    } else {
      const payload: UserCreateRequest = {
        username: form.username.trim(),
        password: form.password,
        nickname: form.nickname.trim(),
        email: form.email.trim(),
        role: form.role,
        status: form.status,
        spaceIds: form.spaceIds,
      }
      await userApi.create(payload)
      ElMessage.success('用户已创建')
    }
    dialogVisible.value = false
    await loadUsers()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    saving.value = false
  }
}

function openPassword(user: UserView) {
  passwordTarget.value = user
  newPassword.value = ''
  passwordVisible.value = true
}

async function submitPassword() {
  if (!passwordTarget.value) return
  if (!PASSWORD_PATTERN.test(newPassword.value)) {
    ElMessage.warning(PASSWORD_RULE_MESSAGE)
    return
  }
  passwordSaving.value = true
  try {
    await userApi.resetPassword(passwordTarget.value.id, newPassword.value)
    ElMessage.success('密码已重置，该用户已登录的会话已全部失效')
    passwordVisible.value = false
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '重置失败')
  } finally {
    passwordSaving.value = false
  }
}

async function removeUser(user: UserView) {
  try {
    await ElMessageBox.confirm(
      `确认删除用户「${user.nickname || user.username}」？该操作不可撤销。`,
      '删除用户',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await userApi.remove(user.id)
    ElMessage.success('用户已删除')
    if (users.value.length === 1 && pageNo.value > 1) pageNo.value -= 1
    await loadUsers()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

function search() {
  pageNo.value = 1
  loadUsers()
}

onMounted(async () => {
  await Promise.allSettled([
    loadUsers(),
    spaceApi.list().then((list) => {
      spaces.value = list
    }),
  ])
})
</script>

<template>
  <div class="page">
    <PageHeader
      title="用户与权限"
      description="管理平台账号、角色与空间授权。管理员可访问全部空间，普通用户仅能看到被授权的空间。"
      eyebrow="Access"
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          新建用户
        </el-button>
      </template>
    </PageHeader>

    <div class="summary-row">
      <div class="summary-chip">
        <span class="chip-dot"></span>
        共 {{ total }} 个账号
      </div>
      <div class="summary-chip">
        <span class="chip-dot green"></span>
        {{ activeCount }} 个启用
      </div>
      <div class="summary-chip">
        <span class="chip-dot cyan"></span>
        {{ adminCount }} 个管理员
      </div>
      <div class="summary-spacer"></div>
      <div class="search-box">
        <el-input
          v-model="keyword"
          placeholder="搜索账号 / 昵称 / 邮箱"
          clearable
          @keyup.enter="search"
          @clear="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button @click="search">搜索</el-button>
      </div>
    </div>

    <div class="panel table-panel">
      <el-table v-loading="loading" :data="users" style="width: 100%">
        <el-table-column label="用户" min-width="220">
          <template #default="{ row }">
            <div class="user-cell">
              <div class="avatar" :class="{ admin: row.role === 'ADMIN' }">
                {{ (row.nickname || row.username).slice(0, 1).toUpperCase() }}
              </div>
              <div>
                <div class="user-name">
                  {{ row.nickname || row.username }}
                  <span v-if="row.id === auth.user?.id" class="me-tag">我</span>
                </div>
                <div class="user-account mono">@{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="190">
          <template #default="{ row }">{{ row.email || '—' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="110">
          <template #default="{ row }">
            <span class="role-pill" :class="row.role === 'ADMIN' ? 'admin' : 'user'">
              {{ row.role === 'ADMIN' ? '管理员' : '普通用户' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="空间授权" min-width="200">
          <template #default="{ row }">
            <span v-if="row.role === 'ADMIN'" class="space-hint">全部空间</span>
            <div v-else class="space-tags">
              <span v-for="id in row.spaceIds" :key="id" class="space-tag">
                {{ spaceNames.get(id) || `空间 #${id}` }}
              </span>
              <span v-if="!row.spaceIds?.length" class="space-hint">未授权</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <span class="status-dot" :class="row.status === 0 ? 'on' : 'off'">
              {{ row.status === 0 ? '启用' : '停用' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="150">
          <template #default="{ row }">
            <span :title="formatDate(row.createdAt)">{{ fromNow(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text @click="openEdit(row)">
              <el-icon><EditPen /></el-icon>
              编辑
            </el-button>
            <el-button size="small" text @click="openPassword(row)">
              <el-icon><Key /></el-icon>
              重置密码
            </el-button>
            <el-button
              size="small"
              text
              type="danger"
              :disabled="row.id === auth.user?.id"
              @click="removeUser(row)"
            >
              <el-icon><Delete /></el-icon>
              删除
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <EmptyState
            :icon="User"
            title="没有匹配的用户"
            description="调整搜索关键词，或创建一个新的平台账号。"
          />
        </template>
      </el-table>

      <div v-if="total > pageSize" class="pagination-row">
        <el-pagination
          v-model:current-page="pageNo"
          v-model:page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="loadUsers"
        />
      </div>
    </div>

    <el-drawer
      v-model="dialogVisible"
      :title="editingId ? '编辑用户' : '新建用户'"
      size="520px"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="登录账号" prop="username">
          <el-input
            v-model="form.username"
            :disabled="!!editingId"
            placeholder="小写字母、数字或 _ . -"
            maxlength="32"
          />
        </el-form-item>
        <el-form-item v-if="!editingId" label="初始密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="8-72 位，含大小写字母和数字"
          />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="昵称">
            <el-input v-model="form.nickname" placeholder="显示名称" maxlength="50" />
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="form.email" placeholder="name@example.com" maxlength="100" />
          </el-form-item>
        </div>
        <el-form-item label="角色" prop="role">
          <el-radio-group v-model="form.role">
            <el-radio-button value="USER">普通用户</el-radio-button>
            <el-radio-button value="ADMIN">管理员</el-radio-button>
          </el-radio-group>
          <div class="form-hint">
            管理员可管理全部空间、用户与平台配置；普通用户只能访问被授权的空间。
          </div>
        </el-form-item>
        <el-form-item v-if="form.role !== 'ADMIN'" label="空间授权">
          <el-select v-model="form.spaceIds" multiple placeholder="选择可访问的空间" style="width: 100%">
            <el-option v-for="space in spaces" :key="space.id" :label="space.name" :value="space.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch
            v-model="form.status"
            :active-value="0"
            :inactive-value="1"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-drawer>

    <el-dialog v-model="passwordVisible" title="重置密码" width="440px">
      <p class="password-hint">
        将为用户 <strong>{{ passwordTarget?.nickname || passwordTarget?.username }}</strong>
        设置新密码，并使其所有已登录会话失效。
      </p>
      <el-input
        v-model="newPassword"
        type="password"
        show-password
        placeholder="8-72 位，含大小写字母和数字"
        @keyup.enter="submitPassword"
      />
      <template #footer>
        <el-button @click="passwordVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordSaving" @click="submitPassword">重置密码</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.table-panel {
  padding: 8px 12px 14px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 12px;
}

.avatar {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  font-size: 14px;
  font-weight: 600;
  color: var(--k-violet-soft);
  background: linear-gradient(135deg, rgba(124, 92, 255, 0.28), rgba(56, 189, 248, 0.12));
  border: 1px solid rgba(124, 92, 255, 0.3);
}

.avatar.admin {
  color: var(--k-amber);
  background: linear-gradient(135deg, rgba(250, 204, 21, 0.22), rgba(124, 92, 255, 0.14));
  border-color: rgba(250, 204, 21, 0.32);
}

.user-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-strong);
  display: flex;
  align-items: center;
  gap: 6px;
}

.user-account {
  font-size: 11px;
  color: var(--text-faint);
  margin-top: 2px;
}

.me-tag {
  font-size: 10px;
  padding: 1px 6px;
  border-radius: 999px;
  background: rgba(56, 189, 248, 0.14);
  color: var(--k-sky);
}

.role-pill {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 999px;
  letter-spacing: 0.04em;
}

.role-pill.admin {
  background: rgba(250, 204, 21, 0.12);
  border: 1px solid rgba(250, 204, 21, 0.28);
  color: var(--k-amber);
}

.role-pill.user {
  background: rgba(124, 92, 255, 0.12);
  border: 1px solid rgba(124, 92, 255, 0.26);
  color: var(--k-violet);
}

.space-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.space-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(148, 163, 184, 0.1);
  border: 1px solid rgba(148, 163, 184, 0.18);
  color: var(--text-muted);
}

.space-hint {
  font-size: 12px;
  color: var(--text-faint);
}

.status-dot {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
}

.status-dot::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status-dot.on {
  color: var(--k-green);
}

.status-dot.on::before {
  background: #34d399;
  box-shadow: 0 0 8px rgba(52, 211, 153, 0.8);
}

.status-dot.off {
  color: var(--text-faint);
}

.status-dot.off::before {
  background: #64748b;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

.form-hint {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--text-faint);
}

.password-hint {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.65;
  color: var(--text-muted);
}

.password-hint strong {
  color: var(--text-strong);
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

@media (max-width: 640px) {
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
