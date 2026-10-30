<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Document,
  EditPen,
  Files,
  Folder,
  FolderOpened,
  Lock,
  MagicStick,
  Plus,
  Search,
  Delete,
} from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import EmptyState from '@/components/EmptyState.vue'
import { skillApi } from '@/api'
import { ApiError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import type { SkillDetail, SkillFileView, SkillSaveRequest, SkillView } from '@/api/types'
import { fromNow } from '@/utils/format'

interface TreeNode extends SkillFileView {
  children: TreeNode[]
  depth: number
}

const auth = useAuthStore()
const route = useRoute()

const loading = ref(false)
const keyword = ref('')
const skills = ref<SkillView[]>([])
const skillsPage = ref(1)
const skillsTotal = ref(0)
const skillsPageSize = 20
const selectedSkillId = ref<number | null>(null)
const detail = ref<SkillDetail | null>(null)
const detailLoading = ref(false)
const selectedFileId = ref<number | null>(null)
const editorContent = ref('')
const savedContent = ref('')
const savingFile = ref(false)
const expandedIds = ref<Set<number>>(new Set())

const skillDialogVisible = ref(false)
const skillSaving = ref(false)
const skillEditingId = ref<number | null>(null)
const skillFormRef = ref<FormInstance>()
const skillForm = reactive<SkillSaveRequest>({
  name: '',
  description: '',
  version: '1.0',
  status: 0,
})

const fileDialogVisible = ref(false)
const fileSaving = ref(false)
const fileForm = reactive({
  name: '',
  type: 1,
  parentId: 0,
  content: '',
})

const renameDialogVisible = ref(false)
const renameSaving = ref(false)
const renameValue = ref('')
const renameTarget = ref<SkillFileView | null>(null)

const skillRules: FormRules = {
  name: [
    { required: true, message: '请输入技能名称', trigger: 'blur' },
    { max: 100, message: '名称不能超过 100 个字符', trigger: 'blur' },
  ],
}

const selectedSkill = computed(() => detail.value?.skill || null)
const files = computed<SkillFileView[]>(() => detail.value?.files || [])
const isDirty = computed(
  () => !!selectedFile.value && editorContent.value !== savedContent.value,
)

const tree = computed<TreeNode[]>(() => {
  const byParent = new Map<number, SkillFileView[]>()
  for (const file of files.value) {
    const parent = file.parentId || 0
    const list = byParent.get(parent) || []
    list.push(file)
    byParent.set(parent, list)
  }
  const build = (parentId: number, depth: number): TreeNode[] => {
    const list = byParent.get(parentId) || []
    return [...list]
      .sort((a, b) => {
        if (a.type !== b.type) return a.type === 0 ? -1 : 1
        return (a.sortOrder || 0) - (b.sortOrder || 0) || a.id - b.id
      })
      .map((file) => ({ ...file, depth, children: build(file.id, depth + 1) }))
  }
  return build(0, 0)
})

const flatTree = computed<TreeNode[]>(() => {
  const result: TreeNode[] = []
  const walk = (nodes: TreeNode[]) => {
    for (const node of nodes) {
      result.push(node)
      if (node.type === 0 && expandedIds.value.has(node.id)) {
        walk(node.children)
      }
    }
  }
  walk(tree.value)
  return result
})

const selectedFile = computed<SkillFileView | null>(
  () => files.value.find((file) => file.id === selectedFileId.value) || null,
)

const fileCount = computed(() => files.value.filter((file) => file.type === 1).length)
const directoryCount = computed(() => files.value.filter((file) => file.type === 0).length)

function resetSkillForm() {
  skillEditingId.value = null
  Object.assign(skillForm, { name: '', description: '', version: '1.0', status: 0 })
}

async function loadSkills(selectId?: number) {
  loading.value = true
  try {
    const result = await skillApi.page({
      page: skillsPage.value,
      size: skillsPageSize,
      keyword: keyword.value.trim() || undefined,
    })
    skills.value = result.list
    skillsTotal.value = result.total
    if (!result.list.length && result.total > 0 && skillsPage.value > 1) {
      skillsPage.value = Math.max(1, Math.ceil(result.total / skillsPageSize))
      await loadSkills(selectId)
      return
    }
    const targetId = selectId ?? selectedSkillId.value
    if (targetId && skills.value.some((skill) => skill.id === targetId)) {
      await selectSkill(targetId, true)
    } else if (skills.value.length) {
      await selectSkill(skills.value[0].id, true)
    } else {
      selectedSkillId.value = null
      detail.value = null
      selectedFileId.value = null
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '加载技能失败')
  } finally {
    loading.value = false
  }
}

function searchSkills() {
  skillsPage.value = 1
  loadSkills()
}

function changeSkillsPage(page: number) {
  skillsPage.value = page
  loadSkills()
}

async function confirmDiscard(): Promise<boolean> {
  if (!isDirty.value) return true
  try {
    await ElMessageBox.confirm('当前文件有未保存的修改，确定放弃吗？', '未保存的修改', {
      type: 'warning',
      confirmButtonText: '放弃修改',
      cancelButtonText: '继续编辑',
    })
    return true
  } catch {
    return false
  }
}

async function selectSkill(id: number, force = false) {
  if (!force && id === selectedSkillId.value) return
  if (!force && !(await confirmDiscard())) return
  selectedSkillId.value = id
  detailLoading.value = true
  try {
    detail.value = await skillApi.get(id)
    expandedIds.value = new Set(
      detail.value.files.filter((file) => file.type === 0).map((file) => file.id),
    )
    const root =
      detail.value.files.find((file) => file.locked === 1) ||
      detail.value.files.find((file) => file.type === 1) ||
      detail.value.files[0]
    pickFile(root || null)
  } catch (error) {
    detail.value = null
    ElMessage.error(error instanceof ApiError ? error.message : '加载技能详情失败')
  } finally {
    detailLoading.value = false
  }
}

function pickFile(file: SkillFileView | null) {
  if (!file || file.type === 0) {
    selectedFileId.value = null
    editorContent.value = ''
    savedContent.value = ''
    return
  }
  selectedFileId.value = file.id
  editorContent.value = file.content || ''
  savedContent.value = file.content || ''
}

async function selectFile(file: TreeNode) {
  if (file.type === 0) {
    toggleFolder(file.id)
    return
  }
  if (file.id === selectedFileId.value) return
  if (!(await confirmDiscard())) return
  pickFile(file)
}

function toggleFolder(id: number) {
  const next = new Set(expandedIds.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expandedIds.value = next
}

function openCreateSkill() {
  resetSkillForm()
  skillDialogVisible.value = true
}

function openEditSkill() {
  if (!selectedSkill.value) return
  skillEditingId.value = selectedSkill.value.id
  Object.assign(skillForm, {
    name: selectedSkill.value.name,
    description: selectedSkill.value.description || '',
    version: selectedSkill.value.version || '1.0',
    status: selectedSkill.value.status,
  })
  skillDialogVisible.value = true
}

async function submitSkill() {
  const valid = await skillFormRef.value?.validate().catch(() => false)
  if (!valid) return
  skillSaving.value = true
  const payload: SkillSaveRequest = {
    name: skillForm.name.trim(),
    description: skillForm.description?.trim(),
    version: skillForm.version?.trim() || '1.0',
    status: skillForm.status,
  }
  try {
    if (skillEditingId.value) {
      await skillApi.update(skillEditingId.value, payload)
      ElMessage.success('技能信息已更新')
      skillDialogVisible.value = false
      await loadSkills(skillEditingId.value)
    } else {
      const created = await skillApi.create(payload)
      ElMessage.success('技能已创建，已自动生成 SKILL.md')
      skillDialogVisible.value = false
      skillsPage.value = 1
      await loadSkills(created.skill.id)
    }
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    skillSaving.value = false
  }
}

async function removeSkill() {
  if (!selectedSkill.value) return
  const skill = selectedSkill.value
  try {
    await ElMessageBox.confirm(
      `确认删除技能「${skill.name}」及其全部文件？此操作不可撤销。`,
      '删除技能',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await skillApi.remove(skill.id)
    ElMessage.success('技能已删除')
    selectedSkillId.value = null
    await loadSkills()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

async function saveFile() {
  if (!selectedSkill.value || !selectedFile.value) return
  savingFile.value = true
  try {
    const updated = await skillApi.updateFile(
      selectedSkill.value.id,
      selectedFile.value.id,
      { content: editorContent.value },
    )
    savedContent.value = editorContent.value
    const index = detail.value?.files.findIndex((file) => file.id === updated.id) ?? -1
    if (detail.value && index >= 0) detail.value.files[index] = updated
    ElMessage.success('文件已保存')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '保存失败')
  } finally {
    savingFile.value = false
  }
}

function openCreateFile(type: number, parentId = 0) {
  Object.assign(fileForm, {
    name: '',
    type,
    parentId,
    content: type === 1 ? '' : '',
  })
  fileDialogVisible.value = true
}

async function submitFile() {
  if (!selectedSkill.value) return
  if (!fileForm.name.trim()) {
    ElMessage.warning('请输入名称')
    return
  }
  fileSaving.value = true
  try {
    const created = await skillApi.createFile(selectedSkill.value.id, {
      parentId: fileForm.parentId || 0,
      name: fileForm.name.trim(),
      type: fileForm.type,
      content: fileForm.type === 1 ? fileForm.content : undefined,
    })
    fileDialogVisible.value = false
    await reloadDetail()
    if (created.type === 1) {
      const file = files.value.find((item) => item.id === created.id)
      if (file) pickFile(file)
    } else {
      expandedIds.value = new Set([...expandedIds.value, created.id])
    }
    ElMessage.success(fileForm.type === 0 ? '目录已创建' : '文件已创建')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '创建失败')
  } finally {
    fileSaving.value = false
  }
}

async function reloadDetail() {
  if (!selectedSkill.value) return
  const keepFile = selectedFileId.value
  detail.value = await skillApi.get(selectedSkill.value.id)
  const file = detail.value.files.find((item) => item.id === keepFile)
  if (file) {
    pickFile(file)
  } else {
    const root =
      detail.value.files.find((item) => item.locked === 1) ||
      detail.value.files.find((item) => item.type === 1) ||
      null
    pickFile(root)
  }
}

function openRename(file: SkillFileView) {
  renameTarget.value = file
  renameValue.value = file.name
  renameDialogVisible.value = true
}

async function submitRename() {
  if (!selectedSkill.value || !renameTarget.value) return
  const name = renameValue.value.trim()
  if (!name) {
    ElMessage.warning('请输入名称')
    return
  }
  renameSaving.value = true
  try {
    await skillApi.updateFile(selectedSkill.value.id, renameTarget.value.id, { name })
    renameDialogVisible.value = false
    await reloadDetail()
    ElMessage.success('名称已更新')
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '重命名失败')
  } finally {
    renameSaving.value = false
  }
}

async function removeFile(file: SkillFileView) {
  if (!selectedSkill.value) return
  try {
    await ElMessageBox.confirm(
      file.type === 0
        ? `确认删除目录「${file.name}」？目录必须为空。`
        : `确认删除文件「${file.name}」？`,
      '删除文件',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await skillApi.removeFile(selectedSkill.value.id, file.id)
    ElMessage.success('已删除')
    if (selectedFileId.value === file.id) {
      selectedFileId.value = null
      editorContent.value = ''
      savedContent.value = ''
    }
    await reloadDetail()
  } catch (error) {
    ElMessage.error(error instanceof ApiError ? error.message : '删除失败')
  }
}

function iconOf(file: SkillFileView) {
  if (file.type === 0) return Folder
  if (file.locked === 1) return Lock
  return Document
}

onMounted(async () => {
  await loadSkills()
  const target = Number(route.query.skillId)
  if (target && skills.value.some((skill) => skill.id === target)) {
    await selectSkill(target)
  }
})
</script>

<template>
  <div class="page skill-page">
    <PageHeader
      title="技能库"
      description="用 SKILL.md 定义可复用的工作方法，附带参考资料，让智能体按专业规范完成任务。"
      eyebrow="Skills"
    >
      <template #actions>
        <el-button v-if="auth.canEditSpace" type="primary" @click="openCreateSkill">
          <el-icon><Plus /></el-icon>
          新建技能
        </el-button>
      </template>
    </PageHeader>

    <div class="skill-layout">
      <aside class="skill-side panel">
        <div class="side-head">
          <div class="search-box">
            <el-input
              v-model="keyword"
              placeholder="搜索技能"
              clearable
              @keyup.enter="searchSkills"
              @clear="searchSkills"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
          </div>
          <span class="side-count">{{ skillsTotal }} 个技能</span>
        </div>

        <div v-loading="loading" class="skill-list">
          <button
            v-for="skill in skills"
            :key="skill.id"
            type="button"
            class="skill-item"
            :class="{ active: skill.id === selectedSkillId }"
            @click="selectSkill(skill.id)"
          >
            <span class="skill-glyph"><el-icon><MagicStick /></el-icon></span>
            <span class="skill-info">
              <span class="skill-name">{{ skill.name }}</span>
              <span class="skill-desc">{{ skill.description || '暂无描述' }}</span>
              <span class="skill-meta">
                v{{ skill.version || '1.0' }} · {{ skill.fileCount }} 项
              </span>
            </span>
            <span class="skill-status" :class="skill.status === 0 ? 'on' : 'off'">
              {{ skill.status === 0 ? '启用' : '停用' }}
            </span>
          </button>

          <EmptyState
            v-if="!skills.length && !loading"
            :icon="MagicStick"
            title="还没有技能"
            description="创建一个技能，编写 SKILL.md 与参考资料，然后在智能体中绑定。"
          />
        </div>

        <div v-if="skillsTotal > skillsPageSize" class="side-pagination">
          <el-pagination
            small
            background
            layout="prev, pager, next"
            :current-page="skillsPage"
            :page-size="skillsPageSize"
            :total="skillsTotal"
            @current-change="changeSkillsPage"
          />
        </div>
      </aside>

      <section class="skill-main panel">
        <template v-if="selectedSkill">
          <header class="main-head">
            <div class="main-title">
              <h2>{{ selectedSkill.name }}</h2>
              <span class="version-pill">v{{ selectedSkill.version || '1.0' }}</span>
              <span class="badge" :class="selectedSkill.status === 0 ? 'published' : 'draft'">
                {{ selectedSkill.status === 0 ? '启用' : '停用' }}
              </span>
            </div>
            <p class="main-desc">{{ selectedSkill.description || '暂无技能描述' }}</p>
            <div class="main-meta">
              <span>{{ fileCount }} 个文件</span>
              <span>{{ directoryCount }} 个目录</span>
              <span>{{ fromNow(selectedSkill.createdAt) }}创建</span>
            </div>
            <div v-if="auth.canEditSpace" class="head-actions">
              <el-button size="small" @click="openEditSkill">
                <el-icon><EditPen /></el-icon>
                编辑信息
              </el-button>
              <el-button size="small" type="danger" plain @click="removeSkill">
                <el-icon><Delete /></el-icon>
                删除技能
              </el-button>
            </div>
          </header>

          <div v-loading="detailLoading" class="editor-shell">
            <div class="file-pane">
              <div class="pane-head">
                <span class="pane-title">
                  <el-icon><Files /></el-icon>
                  文件
                </span>
                <div v-if="auth.canEditSpace" class="pane-actions">
                  <el-tooltip content="新建文件" placement="top">
                    <el-button size="small" circle text @click="openCreateFile(1)">
                      <el-icon><Document /></el-icon>
                    </el-button>
                  </el-tooltip>
                  <el-tooltip content="新建目录" placement="top">
                    <el-button size="small" circle text @click="openCreateFile(0)">
                      <el-icon><Folder /></el-icon>
                    </el-button>
                  </el-tooltip>
                </div>
              </div>

              <div class="file-tree">
                <div v-for="node in flatTree" :key="node.id" class="tree-row">
                  <button
                    type="button"
                    class="tree-item"
                    :class="{ active: node.id === selectedFileId, folder: node.type === 0 }"
                    :style="{ paddingLeft: `${10 + node.depth * 16}px` }"
                    @click="selectFile(node)"
                  >
                    <el-icon class="tree-icon">
                      <FolderOpened v-if="node.type === 0 && expandedIds.has(node.id)" />
                      <component :is="iconOf(node)" v-else />
                    </el-icon>
                    <span class="tree-name">{{ node.name }}</span>
                    <span v-if="node.locked === 1" class="lock-tag">内置</span>
                  </button>
                  <div v-if="auth.canEditSpace" class="tree-tools">
                    <el-dropdown
                      trigger="click" @command="(cmd: string) => {
                        if (cmd === 'rename') openRename(node)
                        if (cmd === 'delete') removeFile(node)
                      }"
                    >
                      <button type="button" class="tree-more">···</button>
                      <template #dropdown>
                        <el-dropdown-menu>
                          <el-dropdown-item
                            v-if="node.locked !== 1"
                            command="rename"
                          >
                            重命名
                          </el-dropdown-item>
                          <el-dropdown-item
                            v-if="node.locked !== 1"
                            command="delete"
                            divided
                          >
                            删除
                          </el-dropdown-item>
                          <el-dropdown-item v-if="node.locked === 1" disabled>
                            内置文件不可修改
                          </el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </div>
                </div>
                <div v-if="!flatTree.length" class="tree-empty">暂无文件</div>
              </div>

              <div v-if="auth.canEditSpace" class="pane-foot">
                <el-button size="small" text @click="openCreateFile(1)">
                  <el-icon><Plus /></el-icon>
                  新文件
                </el-button>
                <el-button size="small" text @click="openCreateFile(0)">
                  <el-icon><Plus /></el-icon>
                  新目录
                </el-button>
              </div>
            </div>

            <div class="editor-pane">
              <template v-if="selectedFile">
                <div class="editor-head">
                  <div class="editor-path">
                    <span class="mono">{{ selectedFile.name }}</span>
                    <span v-if="isDirty" class="dirty-dot">未保存</span>
                    <span v-else-if="selectedFile.locked === 1" class="hint">内置技能说明</span>
                  </div>
                  <div class="editor-actions">
                    <el-button size="small" @click="openRename(selectedFile)" :disabled="selectedFile.locked === 1 && !auth.canEditSpace">
                      重命名
                    </el-button>
                    <el-button v-if="auth.canEditSpace" type="primary" size="small" :loading="savingFile" :disabled="!isDirty" @click="saveFile">
                      保存
                    </el-button>
                  </div>
                </div>
                <div v-if="!auth.canEditSpace" class="readonly-banner">
                  <el-icon><Lock /></el-icon>
                  只读模式：需要当前空间的编辑权限才能修改技能文件。
                </div>
                <el-input
                  v-model="editorContent"
                  type="textarea"
                  class="code-editor"
                  :readonly="!auth.canEditSpace"
                  placeholder="在这里编写技能说明（Markdown）"
                  spellcheck="false"
                />
                <div class="editor-foot">
                  <span>{{ editorContent.length }} 字符</span>
                  <span class="mono">SKILL.md 会注入到智能体系统提示词中</span>
                </div>
              </template>
              <EmptyState
                v-else
                :icon="Document"
                title="选择一个文件开始编辑"
                description="左侧选择技能文件；SKILL.md 会自动拼接到智能体的系统提示词中。"
              />

              <div v-if="selectedFile && selectedFile.locked !== 1 && selectedFile.type === 1" class="ref-hint">
                <el-icon><Files /></el-icon>
                <span>该文件会登记为技能参考文件，模型可调用 skill_read_file 工具按需读取。</span>
              </div>
            </div>
          </div>
        </template>

        <EmptyState
          v-else-if="!loading"
          :icon="MagicStick"
          title="选择或创建一个技能"
          description="技能由 SKILL.md 与参考文件组成，绑定到智能体后即可指导其工作方式。"
        >
          <el-button v-if="auth.canEditSpace" type="primary" @click="openCreateSkill">
            <el-icon><Plus /></el-icon>
            新建技能
          </el-button>
        </EmptyState>
      </section>
    </div>

    <el-dialog
      v-model="skillDialogVisible"
      :title="skillEditingId ? '编辑技能信息' : '新建技能'"
      width="520px"
    >
      <el-form ref="skillFormRef" :model="skillForm" :rules="skillRules" label-position="top">
        <el-form-item label="技能名称" prop="name">
          <el-input v-model="skillForm.name" placeholder="例如：技术方案评审" maxlength="100" />
        </el-form-item>
        <el-form-item label="技能描述">
          <el-input
            v-model="skillForm.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="说明技能适用的任务、触发场景与产出物"
          />
        </el-form-item>
        <div class="form-grid">
          <el-form-item label="版本">
            <el-input v-model="skillForm.version" placeholder="1.0" maxlength="20" />
          </el-form-item>
          <el-form-item label="状态">
            <el-switch
              v-model="skillForm.status"
              :active-value="0"
              :inactive-value="1"
              active-text="启用"
              inactive-text="停用"
            />
          </el-form-item>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="skillDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="skillSaving" @click="submitSkill">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="fileDialogVisible"
      :title="fileForm.type === 0 ? '新建目录' : '新建文件'"
      width="520px"
    >
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="父级目录">
          <el-select v-model="fileForm.parentId" style="width: 100%">
            <el-option label="根目录" :value="0" />
            <el-option
              v-for="dir in files.filter((file) => file.type === 0)"
              :key="dir.id"
              :label="dir.name"
              :value="dir.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="fileForm.type === 0 ? '目录名称' : '文件名称'">
          <el-input
            v-model="fileForm.name"
            :placeholder="fileForm.type === 0 ? '例如：reference' : '例如：checklist.md'"
            maxlength="200"
            @keyup.enter="submitFile"
          />
        </el-form-item>
        <el-form-item v-if="fileForm.type === 1" label="初始内容">
          <el-input
            v-model="fileForm.content"
            type="textarea"
            :rows="8"
            placeholder="可以直接粘贴 Markdown 内容"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fileDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="fileSaving" @click="submitFile">创建</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="renameDialogVisible" title="重命名" width="420px">
      <el-input v-model="renameValue" maxlength="200" @keyup.enter="submitRename" />
      <template #footer>
        <el-button @click="renameDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="renameSaving" @click="submitRename">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.skill-page {
  display: flex;
  flex-direction: column;
}

.skill-layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.skill-side {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 560px;
  max-height: calc(100vh - 220px);
}

.side-head {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.side-count {
  font-size: 12px;
  color: var(--text-faint);
  letter-spacing: 0.04em;
}

.skill-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding-right: 2px;
}

.side-pagination {
  display: flex;
  justify-content: center;
  padding-top: 6px;
}

.skill-item {
  position: relative;
  display: flex;
  gap: 12px;
  padding: 12px;
  border-radius: 14px;
  border: 1px solid transparent;
  background: rgba(10, 14, 30, 0.5);
  color: inherit;
  text-align: left;
  cursor: pointer;
  transition: all 0.16s ease;
}

.skill-item:hover {
  border-color: rgba(124, 92, 255, 0.25);
  background: rgba(124, 92, 255, 0.08);
}

.skill-item.active {
  border-color: rgba(124, 92, 255, 0.5);
  background: linear-gradient(135deg, rgba(124, 92, 255, 0.18), rgba(56, 189, 248, 0.07));
  box-shadow: 0 0 0 1px rgba(124, 92, 255, 0.2) inset;
}

.skill-glyph {
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  background: rgba(124, 92, 255, 0.15);
  color: #b9a8ff;
  font-size: 17px;
}

.skill-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.skill-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-strong);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.skill-desc {
  font-size: 12px;
  color: var(--text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  line-height: 1.5;
}

.skill-meta {
  font-size: 11px;
  color: var(--text-faint);
  margin-top: 2px;
}

.skill-status {
  position: absolute;
  top: 10px;
  right: 10px;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 999px;
  letter-spacing: 0.04em;
}

.skill-status.on {
  background: rgba(52, 211, 153, 0.12);
  color: #6ee7b7;
}

.skill-status.off {
  background: rgba(148, 163, 184, 0.12);
  color: var(--text-faint);
}

.skill-main {
  padding: 22px;
  min-height: 560px;
}

.main-head {
  position: relative;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--border-subtle);
  margin-bottom: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.main-title {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.main-title h2 {
  margin: 0;
  font-size: 20px;
  color: var(--text-strong);
  font-weight: 650;
}

.version-pill {
  font-family: var(--font-mono);
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(56, 189, 248, 0.12);
  color: #7dd3fc;
  border: 1px solid rgba(56, 189, 248, 0.24);
}

.main-desc {
  margin: 0;
  font-size: 13px;
  color: var(--text-muted);
  line-height: 1.6;
  max-width: 760px;
}

.main-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--text-faint);
  flex-wrap: wrap;
}

.head-actions {
  position: absolute;
  top: 0;
  right: 0;
  display: flex;
  gap: 8px;
}

.editor-shell {
  display: grid;
  grid-template-columns: 250px minmax(0, 1fr);
  gap: 16px;
  min-height: 420px;
}

.file-pane {
  border: 1px solid var(--border-subtle);
  border-radius: 14px;
  background: rgba(9, 13, 28, 0.5);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.pane-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--border-subtle);
}

.pane-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--text-faint);
}

.pane-actions {
  display: flex;
  gap: 2px;
}

.file-tree {
  flex: 1;
  padding: 8px;
  overflow-y: auto;
  max-height: 460px;
}

.tree-row {
  display: flex;
  align-items: center;
  border-radius: 8px;
}

.tree-row:hover {
  background: rgba(124, 92, 255, 0.08);
}

.tree-item {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 8px;
  border: none;
  background: none;
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  border-radius: 8px;
}

.tree-item.folder {
  color: var(--text-strong);
  font-weight: 550;
}

.tree-item.active {
  background: rgba(124, 92, 255, 0.2);
  color: #e9e3ff;
}

.tree-icon {
  font-size: 15px;
  color: #9d8cff;
  flex: 0 0 auto;
}

.tree-item.folder .tree-icon {
  color: #7dd3fc;
}

.tree-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.lock-tag {
  margin-left: auto;
  font-size: 10px;
  color: #fbbf24;
  border: 1px solid rgba(251, 191, 36, 0.3);
  border-radius: 999px;
  padding: 1px 6px;
}

.tree-tools {
  opacity: 0;
  transition: opacity 0.15s ease;
}

.tree-row:hover .tree-tools {
  opacity: 1;
}

.tree-more {
  border: none;
  background: none;
  color: var(--text-faint);
  cursor: pointer;
  padding: 4px 6px;
  font-size: 12px;
  letter-spacing: 1px;
}

.tree-empty {
  padding: 24px 12px;
  text-align: center;
  font-size: 12px;
  color: var(--text-faint);
}

.pane-foot {
  border-top: 1px solid var(--border-subtle);
  padding: 8px;
  display: flex;
  gap: 4px;
}

.editor-pane {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.editor-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.editor-path {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--text-strong);
  min-width: 0;
}

.editor-path .mono {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.dirty-dot {
  font-size: 11px;
  color: #fbbf24;
  border: 1px solid rgba(251, 191, 36, 0.32);
  background: rgba(251, 191, 36, 0.1);
  border-radius: 999px;
  padding: 1px 8px;
  flex: 0 0 auto;
}

.editor-path .hint {
  font-size: 11px;
  color: var(--text-faint);
}

.editor-actions {
  display: flex;
  gap: 8px;
  flex: 0 0 auto;
}

.readonly-banner {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(148, 163, 184, 0.08);
  border: 1px solid var(--border-subtle);
  color: var(--text-muted);
  font-size: 12px;
}

.code-editor :deep(textarea) {
  font-family: var(--font-mono);
  font-size: 13px !important;
  line-height: 1.72 !important;
  background: rgba(6, 9, 20, 0.72) !important;
  color: #d8e2ff !important;
  border-radius: 12px;
  padding: 16px !important;
  min-height: 380px !important;
  resize: vertical;
}

.editor-foot {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 11px;
  color: var(--text-faint);
  flex-wrap: wrap;
}

.ref-hint {
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 10px;
  background: rgba(56, 189, 248, 0.06);
  border: 1px dashed rgba(56, 189, 248, 0.22);
  color: #8bd8f4;
  font-size: 12px;
  line-height: 1.5;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 1080px) {
  .skill-layout {
    grid-template-columns: 1fr;
  }

  .skill-side {
    max-height: none;
    min-height: auto;
  }

  .skill-list {
    max-height: 300px;
  }
}

@media (max-width: 760px) {
  .editor-shell {
    grid-template-columns: 1fr;
  }

  .head-actions {
    position: static;
    margin-top: 6px;
  }

  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
