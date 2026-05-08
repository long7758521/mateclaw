<template>
  <div class="mc-page-shell">
    <div class="mc-page-frame">
      <div class="mc-page-inner workflows-page">
        <div class="mc-page-header">
          <div>
            <div class="mc-page-kicker">{{ t('workflows.kicker') }}</div>
            <h1 class="mc-page-title">{{ t('workflows.title') }}</h1>
            <p class="mc-page-desc">{{ t('workflows.desc') }}</p>
          </div>
          <button class="btn-primary" @click="openCreate">{{ t('workflows.newWorkflow') }}</button>
        </div>

        <div class="workflows-grid">
          <!-- left: list -->
          <aside class="workflows-list mc-surface-card">
            <div class="list-header">
              <span>{{ t('workflows.defined', { count: workflows.length }) }}</span>
              <button class="btn-ghost" @click="reload">{{ t('workflows.refresh') }}</button>
            </div>
            <ul class="list-body">
              <li
                v-for="wf in workflows"
                :key="wf.id"
                class="list-row"
                :class="{ active: selectedId === wf.id }"
                @click="select(wf.id)"
              >
                <div class="list-row-name">
                  {{ wf.name || t('workflows.unnamed') }}
                  <span v-if="wf.latestRevisionId" class="badge published">{{ t('workflows.publishedBadge', { rev: wf.latestRevisionId }) }}</span>
                  <span v-else class="badge draft">{{ t('workflows.draftBadge') }}</span>
                </div>
                <div class="list-row-desc">{{ wf.description || '-' }}</div>
              </li>
              <li v-if="!workflows.length" class="list-empty">{{ t('workflows.listEmpty') }}</li>
            </ul>
          </aside>

          <!-- middle: editor -->
          <section class="workflows-editor mc-surface-card" v-if="selected">
            <header class="editor-header">
              <input v-model="selected.name" class="editor-name" :placeholder="t('workflows.namePlaceholder')" />
              <input v-model="selected.description" class="editor-desc" :placeholder="t('workflows.descPlaceholder')" />
              <div class="editor-actions">
                <button class="btn-ghost" :disabled="busy" @click="saveMeta">{{ t('workflows.actions.saveMeta') }}</button>
                <button class="btn-ghost" :disabled="busy" @click="saveDraft">{{ t('workflows.actions.saveDraft') }}</button>
                <button class="btn-ghost" :disabled="busy" @click="compile">{{ t('workflows.actions.compile') }}</button>
                <button class="btn-primary" :disabled="busy" @click="publish">{{ t('workflows.actions.publish') }}</button>
                <button class="btn-danger" :disabled="busy" @click="remove">{{ t('workflows.actions.delete') }}</button>
              </div>
            </header>
            <div class="editor-toolbar">
              <label class="template-picker">
                <span>{{ t('workflows.templates.label') }}</span>
                <select v-model="templateChoice" @change="insertTemplate">
                  <option value="">{{ t('workflows.templates.placeholder') }}</option>
                  <option value="sequential">{{ t('workflows.templates.sequential') }}</option>
                  <option value="fan_out">{{ t('workflows.templates.fan_out') }}</option>
                  <option value="collect">{{ t('workflows.templates.collect') }}</option>
                  <option value="conditional">{{ t('workflows.templates.conditional') }}</option>
                  <option value="await_approval">{{ t('workflows.templates.await_approval') }}</option>
                  <option value="dispatch_channel">{{ t('workflows.templates.dispatch_channel') }}</option>
                  <option value="write_memory">{{ t('workflows.templates.write_memory') }}</option>
                </select>
              </label>
              <span class="json-hint" :class="jsonHintKind">{{ jsonHint }}</span>
            </div>
            <textarea
              v-model="draftJson"
              class="editor-body"
              spellcheck="false"
              :placeholder="t('workflows.bodyPlaceholder')"
            />
            <div v-if="compileErrors.length" class="errors-panel">
              <div class="errors-title">{{ t('workflows.compileErrorsTitle', { count: compileErrors.length }) }}</div>
              <ul>
                <li v-for="(err, idx) in compileErrors" :key="idx">
                  <code>{{ err.code }}</code>
                  <span class="err-path">@ {{ err.path }}</span>
                  <span class="err-msg">— {{ err.message }}</span>
                </li>
              </ul>
            </div>
            <div v-else-if="lastStatus" class="status-panel" :class="lastStatusKind">
              {{ lastStatus }}
            </div>
          </section>

          <section class="workflows-empty mc-surface-card" v-else>
            <p>{{ t('workflows.selectHint') }}</p>
          </section>

          <!-- right: runs -->
          <aside class="workflows-runs mc-surface-card" v-if="selected">
            <header class="runs-header">
              <span>{{ t('workflows.runs.header', { count: runs.length }) }}</span>
              <button class="btn-ghost" @click="reloadRuns">{{ t('workflows.refresh') }}</button>
            </header>
            <ul class="runs-list">
              <li v-for="run in runs" :key="run.id" class="run-row" @click="loadRun(run.id)">
                <div class="run-row-line">
                  <span class="run-state" :class="'state-' + run.state">{{ run.state }}</span>
                  <span class="run-time">{{ formatTime(run.startedAt) }}</span>
                </div>
                <div class="run-row-meta">
                  <span>{{ t('workflows.runs.runHash', { id: run.id }) }}</span>
                  <span v-if="run.triggeredBy">· {{ run.triggeredBy }}</span>
                  <span v-if="run.errorMessage" class="run-err">· {{ run.errorMessage }}</span>
                </div>
              </li>
              <li v-if="!runs.length" class="runs-empty">{{ t('workflows.runs.empty') }}</li>
            </ul>
            <section v-if="runDetail" class="run-detail">
              <div class="run-detail-title">{{ t('workflows.runs.detailTitle', { id: runDetail.run.id, state: runDetail.run.state }) }}</div>
              <ol class="run-steps">
                <li v-for="step in runDetail.steps" :key="step.id">
                  <span class="step-state" :class="'state-' + step.state">{{ step.state }}</span>
                  <span class="step-name">{{ step.stepName || t('workflows.unnamed') }}</span>
                  <span v-if="step.durationMs != null" class="step-duration">{{ step.durationMs }} ms</span>
                  <span v-if="step.errorMessage" class="step-err">{{ step.errorMessage }}</span>
                </li>
              </ol>
            </section>
          </aside>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  workflowApi,
  type WorkflowSummary,
  type WorkflowRun,
  type WorkflowRunStep,
  type WorkflowCompileError,
  type WorkflowCompileFailure,
} from '@/api'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'

const { t } = useI18n()
const workspaceStore = useWorkspaceStore()
const workspaceId = computed(() => workspaceStore.currentWorkspaceId)

const workflows = ref<WorkflowSummary[]>([])
const selectedId = ref<number | null>(null)
const selected = ref<WorkflowSummary | null>(null)
const draftJson = ref('')
const compileErrors = ref<WorkflowCompileError[]>([])
const lastStatus = ref('')
const lastStatusKind = ref<'ok' | 'err'>('ok')
const busy = ref(false)

const runs = ref<WorkflowRun[]>([])
const runDetail = ref<{ run: WorkflowRun; steps: WorkflowRunStep[] } | null>(null)

const templateChoice = ref('')

// Live JSON-syntax check on the textarea so the operator sees parse errors
// immediately, instead of waiting for compile to round-trip.
const jsonHint = computed(() => {
  if (!draftJson.value.trim()) return ''
  try {
    JSON.parse(draftJson.value)
    return t('workflows.jsonOk')
  } catch (e) {
    return t('workflows.jsonInvalid', { msg: (e as Error).message })
  }
})
const jsonHintKind = computed(() => (jsonHint.value === t('workflows.jsonOk') ? 'ok' : 'err'))

const STEP_TEMPLATES: Record<string, object> = {
  sequential: {
    name: 'step-sequential',
    agentName: 'agent-name',
    mode: { type: 'sequential' },
    promptTemplate: 'Process: {{ inputs.payload }}',
  },
  fan_out: {
    name: 'step-fan',
    agentName: 'agent-name',
    mode: { type: 'fan_out' },
    promptTemplate: 'Branch task',
  },
  collect: {
    name: 'step-collect',
    agentName: 'agent-name',
    mode: { type: 'collect' },
    promptTemplate: 'Combine: {{ inputs.payload }}',
  },
  conditional: {
    name: 'step-conditional',
    agentName: 'agent-name',
    mode: { type: 'conditional', expression: '{{ inputs.payload != null }}' },
    promptTemplate: 'Run only when condition holds',
  },
  await_approval: {
    name: 'step-approval',
    mode: {
      type: 'await_approval',
      approvalKind: 'manual',
      approvalMessage: 'Please review and approve',
      timeoutSecs: 3600,
    },
  },
  dispatch_channel: {
    name: 'step-dispatch',
    mode: {
      type: 'dispatch_channel',
      channels: ['feishu'],
      targets: { feishu: 'group-id-here' },
      content: 'Notification: {{ inputs.payload }}',
    },
  },
  write_memory: {
    name: 'step-memory',
    mode: {
      type: 'write_memory',
      employeeId: 'employee-id',
      file: 'workspace.md',
      mergeStrategy: 'append',
      content: '{{ inputs.payload }}',
    },
  },
}

function insertTemplate() {
  const choice = templateChoice.value
  if (!choice) return
  const stepBlock = STEP_TEMPLATES[choice]
  if (!stepBlock) return
  // Try to inject into an existing draft's `steps` array; fall back to a
  // fresh skeleton if the current text isn't valid JSON or doesn't have
  // the expected shape.
  let next: string
  try {
    const parsed = JSON.parse(draftJson.value || '{}') as { steps?: unknown[] }
    if (!Array.isArray(parsed.steps)) parsed.steps = []
    parsed.steps.push(stepBlock)
    next = JSON.stringify(parsed, null, 2)
  } catch {
    next = JSON.stringify({ steps: [stepBlock] }, null, 2)
  }
  draftJson.value = next
  templateChoice.value = ''
}

async function reload() {
  if (!workspaceId.value) return
  try {
    const res = await workflowApi.list(workspaceId.value)
    workflows.value = (res.data as unknown as WorkflowSummary[]) ?? []
  } catch (e) {
    console.error('listWorkflows failed', e)
  }
}

async function select(id: number) {
  selectedId.value = id
  try {
    const res = await workflowApi.get(id)
    selected.value = res.data as unknown as WorkflowSummary
    draftJson.value = selected.value?.draftJson ?? ''
    compileErrors.value = []
    lastStatus.value = ''
    await reloadRuns()
  } catch (e) {
    console.error('getWorkflow failed', e)
  }
}

async function reloadRuns() {
  if (!selectedId.value) return
  try {
    const res = await workflowApi.runs(selectedId.value, 50)
    runs.value = (res.data as unknown as WorkflowRun[]) ?? []
  } catch (e) {
    console.error('listRuns failed', e)
  }
}

async function loadRun(runId: number) {
  try {
    const res = await workflowApi.runDetail(runId)
    runDetail.value = res.data as unknown as { run: WorkflowRun; steps: WorkflowRunStep[] }
  } catch (e) {
    console.error('getRun failed', e)
  }
}

async function openCreate() {
  if (!workspaceId.value) return
  const name = window.prompt(t('workflows.prompts.newName'), t('workflows.prompts.defaultName'))
  if (!name) return
  busy.value = true
  try {
    const res = await workflowApi.create({
      workspaceId: workspaceId.value,
      name,
      enabled: true,
    })
    const created = res.data as unknown as WorkflowSummary
    await reload()
    if (created?.id) await select(created.id)
  } catch (e) {
    setStatus(t('workflows.status.createFailed', { msg: (e as Error).message }), 'err')
  } finally {
    busy.value = false
  }
}

async function saveMeta() {
  if (!selected.value) return
  busy.value = true
  try {
    await workflowApi.update(selected.value.id, {
      name: selected.value.name,
      description: selected.value.description,
      enabled: selected.value.enabled,
    })
    setStatus(t('workflows.status.metaSaved'), 'ok')
    await reload()
  } catch (e) {
    setStatus(t('workflows.status.saveFailed', { msg: (e as Error).message }), 'err')
  } finally {
    busy.value = false
  }
}

async function saveDraft() {
  if (!selected.value) return
  busy.value = true
  try {
    await workflowApi.saveDraft(selected.value.id, draftJson.value)
    setStatus(t('workflows.status.draftSaved'), 'ok')
  } catch (e) {
    setStatus(t('workflows.status.saveDraftFailed', { msg: (e as Error).message }), 'err')
  } finally {
    busy.value = false
  }
}

async function compile() {
  if (!selected.value) return
  busy.value = true
  compileErrors.value = []
  try {
    await workflowApi.saveDraft(selected.value.id, draftJson.value)
    await workflowApi.compile(selected.value.id)
    setStatus(t('workflows.status.compileOk'), 'ok')
  } catch (e) {
    handleCompileError(e)
  } finally {
    busy.value = false
  }
}

async function publish() {
  if (!selected.value) return
  busy.value = true
  compileErrors.value = []
  try {
    await workflowApi.saveDraft(selected.value.id, draftJson.value)
    const note = window.prompt(t('workflows.prompts.publishNote'), '') ?? undefined
    await workflowApi.publish(selected.value.id, note)
    setStatus(t('workflows.status.published'), 'ok')
    await reload()
  } catch (e) {
    handleCompileError(e)
  } finally {
    busy.value = false
  }
}

async function remove() {
  if (!selected.value) return
  if (!window.confirm(t('workflows.prompts.deleteConfirm', { name: selected.value.name ?? '' }))) return
  busy.value = true
  try {
    await workflowApi.delete(selected.value.id)
    selected.value = null
    selectedId.value = null
    draftJson.value = ''
    await reload()
    setStatus(t('workflows.status.deleted'), 'ok')
  } catch (e) {
    setStatus(t('workflows.status.deleteFailed', { msg: (e as Error).message }), 'err')
  } finally {
    busy.value = false
  }
}

function handleCompileError(e: unknown) {
  // The HTTP layer rejects with an `Error` whose message is the body's
  // msg field. The structured errors list lives on the response body's
  // data field; we have to dig it out of the raw axios error if present.
  const err = e as { response?: { data?: { data?: WorkflowCompileFailure } }; message?: string }
  const failure = err.response?.data?.data
  if (failure?.errors?.length) {
    compileErrors.value = failure.errors
    setStatus(t('workflows.status.compileFailed', { count: failure.errorCount ?? failure.errors.length }), 'err')
  } else {
    setStatus(err.message || t('workflows.status.compileFallback'), 'err')
  }
}

function setStatus(msg: string, kind: 'ok' | 'err') {
  lastStatus.value = msg
  lastStatusKind.value = kind
}

function formatTime(iso?: string) {
  if (!iso) return '-'
  return iso.replace('T', ' ').slice(0, 19)
}

onMounted(reload)
watch(workspaceId, reload)
</script>

<style scoped>
.workflows-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.workflows-grid {
  display: grid;
  grid-template-columns: 280px 1fr 320px;
  gap: 16px;
  align-items: stretch;
  min-height: 480px;
}
.workflows-list,
.workflows-editor,
.workflows-runs,
.workflows-empty {
  padding: 12px;
  display: flex;
  flex-direction: column;
}
.list-header,
.runs-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  opacity: 0.85;
}
.list-body,
.runs-list {
  list-style: none;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  flex: 1;
}
.list-row,
.run-row {
  padding: 8px 10px;
  border-radius: 6px;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.12s ease;
}
.list-row:hover,
.run-row:hover {
  background: var(--mc-surface-hover, rgba(0, 0, 0, 0.05));
}
.list-row.active {
  background: var(--mc-primary-bg, rgba(64, 132, 255, 0.18));
}
.list-row-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-weight: 500;
}
.list-row-desc {
  font-size: 12px;
  opacity: 0.7;
  margin-top: 2px;
}
.badge {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 999px;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  background: var(--mc-surface-hover, rgba(0, 0, 0, 0.06));
}
.badge.published {
  background: #2ecc71;
  color: white;
}
.badge.draft {
  background: #ffb84d;
  color: white;
}
.list-empty,
.runs-empty {
  font-size: 13px;
  opacity: 0.6;
  padding: 12px 4px;
}
.editor-header {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
}
.editor-name {
  flex: 0 0 200px;
  font-weight: 600;
}
.editor-desc {
  flex: 1;
}
.editor-name,
.editor-desc {
  padding: 6px 8px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.1));
  border-radius: 6px;
  background: transparent;
  color: inherit;
}
.editor-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.editor-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
  font-size: 12px;
}
.template-picker {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 500;
}
.template-picker select {
  padding: 4px 6px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  border-radius: 6px;
  background: transparent;
  color: inherit;
  font-size: 12px;
}
.json-hint {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  opacity: 0.85;
  flex: 1;
}
.json-hint.ok { color: #1e8449; }
.json-hint.err { color: #c0392b; }
.editor-body {
  flex: 1;
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  padding: 12px;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.1));
  border-radius: 6px;
  background: var(--mc-surface, rgba(0, 0, 0, 0.02));
  color: inherit;
  resize: vertical;
  min-height: 320px;
}
.errors-panel {
  margin-top: 12px;
  padding: 10px;
  border-radius: 6px;
  background: rgba(255, 80, 80, 0.08);
  border: 1px solid rgba(255, 80, 80, 0.4);
}
.errors-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.errors-panel ul {
  margin: 0;
  padding-left: 16px;
  font-size: 12px;
  list-style: disc;
}
.errors-panel code {
  font-weight: 600;
  margin-right: 6px;
}
.err-path {
  font-family: 'JetBrains Mono', Consolas, monospace;
  font-size: 11px;
  opacity: 0.85;
}
.err-msg {
  margin-left: 4px;
}
.status-panel {
  margin-top: 12px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 13px;
}
.status-panel.ok {
  background: rgba(46, 204, 113, 0.12);
  border: 1px solid rgba(46, 204, 113, 0.4);
}
.status-panel.err {
  background: rgba(255, 80, 80, 0.08);
  border: 1px solid rgba(255, 80, 80, 0.4);
}
.runs-list {
  max-height: 340px;
}
.run-row-line {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}
.run-state,
.step-state {
  text-transform: uppercase;
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.08);
}
.state-succeeded { background: rgba(46, 204, 113, 0.2); color: #1e8449; }
.state-failed    { background: rgba(231, 76, 60, 0.18); color: #c0392b; }
.state-paused    { background: rgba(255, 184, 77, 0.22); color: #b8730a; }
.state-skipped   { background: rgba(149, 165, 166, 0.22); color: #444; }
.state-running   { background: rgba(52, 152, 219, 0.18); color: #1a5276; }
.run-row-meta {
  font-size: 11px;
  opacity: 0.7;
  margin-top: 2px;
}
.run-err {
  color: #c0392b;
}
.run-detail {
  margin-top: 12px;
  padding: 10px;
  border-radius: 6px;
  background: var(--mc-surface, rgba(0, 0, 0, 0.04));
}
.run-detail-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.run-steps {
  list-style: none;
  margin: 0;
  padding: 0;
  font-size: 12px;
}
.run-steps li {
  padding: 4px 0;
  display: flex;
  align-items: center;
  gap: 6px;
  border-top: 1px dashed rgba(0, 0, 0, 0.06);
}
.step-name {
  font-weight: 500;
}
.step-duration,
.step-err {
  margin-left: auto;
  font-size: 11px;
  opacity: 0.7;
}
.step-err {
  color: #c0392b;
}
.btn-primary,
.btn-ghost,
.btn-danger {
  padding: 6px 12px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  background: transparent;
  color: inherit;
}
.btn-primary {
  background: var(--mc-primary, #4084ff);
  border-color: var(--mc-primary, #4084ff);
  color: white;
}
.btn-danger {
  background: rgba(231, 76, 60, 0.12);
  border-color: rgba(231, 76, 60, 0.6);
  color: #c0392b;
}
button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
@media (max-width: 1100px) {
  .workflows-grid {
    grid-template-columns: 1fr;
  }
}
</style>
