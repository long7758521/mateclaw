<script setup lang="ts">
import { ref, computed } from 'vue'
import { Connection, Loading, Select, CloseBold, WarningFilled, Clock, ArrowDown, Expand, Fold } from '@element-plus/icons-vue'
import { useToolLabel } from '@/composables/useToolLabel'
import type { Message, MessageSegment, DelegationNode, PlanMeta } from '@/types'
import PlanStepsPanel from './PlanStepsPanel.vue'
import DelegationNodeView from './DelegationNodeView.vue'

/**
 * Always-on "run overview" rail mounted beside the chat thread. It surfaces the
 * data the agent already streams — the execution plan and the live sub-agent
 * delegation tree — so long-running tasks can be tracked without scrolling back
 * through the message history.
 *
 * Purely derived from the reactive chat messages: the plan lives on the latest
 * assistant message's metadata, and each delegation timeline segment becomes a
 * top-level node in the sub-agent tree. No extra API calls — the sub-agent
 * REST surface is admin-gated, but the same state arrives over the chat stream.
 */
const props = defineProps<{
  messages: Message[]
  isGenerating: boolean
}>()

const { getToolLabel } = useToolLabel()

const collapsed = ref(false)

/** The most recent assistant message — the turn whose progress we track. */
const latestAssistant = computed<Message | undefined>(() => {
  for (let i = props.messages.length - 1; i >= 0; i--) {
    if (props.messages[i].role === 'assistant') return props.messages[i]
  }
  return undefined
})

const currentPlan = computed<PlanMeta | undefined>(() => latestAssistant.value?.metadata?.plan)

/** Delegation segments carry a "→" tool-name prefix injected upstream. */
function isDelegationSegment(seg: MessageSegment): boolean {
  return seg.type === 'tool_call' && (seg.toolName || '').startsWith('→')
}

function toNode(seg: MessageSegment): DelegationNode {
  const raw = (seg.toolName || '').replace(/^→\s*/, '').trim()
  const status: DelegationNode['status'] =
    seg.status === 'error' ? 'error' : seg.status === 'completed' ? 'completed' : 'running'
  return {
    subagentId: seg.subagentId || seg.id,
    agentName: getToolLabel(raw) || raw,
    status,
    depth: 1,
    task: seg.toolArgs,
    plan: seg.childTimeline?.plan,
    tools: seg.childTimeline?.tools,
    result: seg.toolResult,
    stale: seg.delegationStale,
    async: seg.delegationAsync,
    children: seg.childTimeline?.children || [],
  }
}

const subagentNodes = computed<DelegationNode[]>(() =>
  (latestAssistant.value?.metadata?.segments || []).filter(isDelegationSegment).map(toNode)
)

const runningSubagents = computed(() => subagentNodes.value.filter(n => n.status === 'running').length)

const planProgress = computed(() => {
  const p = currentPlan.value
  if (!p?.steps?.length) return ''
  const done = p.stepResults?.filter(r => r?.status === 'completed').length || 0
  return `${done}/${p.steps.length}`
})

const hasContent = computed(() => !!currentPlan.value || subagentNodes.value.length > 0)
</script>

<template>
  <aside
    v-if="hasContent"
    class="run-overview"
    :class="{ 'is-collapsed': collapsed }"
  >
    <!-- Collapsed rail: badges only -->
    <button
      v-if="collapsed"
      class="run-overview__rail"
      :title="$t('chat.runOverview.expand')"
      @click="collapsed = false"
    >
      <el-icon :size="16"><Expand /></el-icon>
      <span v-if="planProgress" class="run-overview__rail-badge">{{ planProgress }}</span>
      <span v-if="subagentNodes.length" class="run-overview__rail-badge is-sub">
        <el-icon :size="12"><Connection /></el-icon>{{ subagentNodes.length }}
      </span>
    </button>

    <template v-else>
      <header class="run-overview__header">
        <span class="run-overview__title">{{ $t('chat.runOverview.title') }}</span>
        <el-icon
          v-if="isGenerating"
          class="run-overview__live is-loading"
          :title="$t('chat.runOverview.running')"
          :size="13"
        ><Loading /></el-icon>
        <button
          class="run-overview__collapse"
          :title="$t('chat.runOverview.collapse')"
          @click="collapsed = true"
        ><el-icon :size="15"><Fold /></el-icon></button>
      </header>

      <div class="run-overview__body">
        <!-- Plan progress -->
        <section class="run-overview__section">
          <div class="run-overview__section-title">{{ $t('chat.runOverview.plan') }}</div>
          <PlanStepsPanel
            v-if="currentPlan"
            :plan="currentPlan"
            :is-generating="isGenerating"
          />
          <p v-else class="run-overview__empty">{{ $t('chat.runOverview.noPlan') }}</p>
        </section>

        <!-- Sub-agents -->
        <section class="run-overview__section">
          <div class="run-overview__section-title">
            {{ $t('chat.runOverview.subagents') }}
            <span v-if="runningSubagents" class="run-overview__count">
              {{ runningSubagents }} {{ $t('chat.runOverview.running') }}
            </span>
          </div>
          <div v-if="subagentNodes.length" class="run-overview__subagents">
            <DelegationNodeView
              v-for="node in subagentNodes"
              :key="node.subagentId"
              :node="node"
            />
          </div>
          <p v-else class="run-overview__empty">{{ $t('chat.runOverview.noSubagents') }}</p>
        </section>
      </div>
    </template>
  </aside>
</template>

<style scoped>
.run-overview {
  width: 320px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-left: 1px solid var(--mc-border-light);
  background: var(--mc-bg-muted, #f9f7f5);
  overflow: hidden;
  min-height: 0;
  transition: width 0.2s ease;
}
.run-overview.is-collapsed {
  width: 44px;
}

.run-overview__rail {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  width: 100%;
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--mc-text-tertiary);
}
.run-overview__rail:hover {
  color: var(--mc-primary);
}
.run-overview__rail-badge {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 11px;
  font-weight: 600;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-sunken, #f3f0ed);
  border: 1px solid var(--mc-border-light);
  border-radius: 10px;
  padding: 1px 6px;
}
.run-overview__rail-badge.is-sub {
  color: var(--mc-primary);
}

.run-overview__header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--mc-border-light);
  flex-shrink: 0;
}
.run-overview__title {
  font-weight: 600;
  font-size: 13px;
  color: var(--mc-text-primary);
}
.run-overview__live {
  color: var(--mc-primary);
}
.run-overview__collapse {
  margin-left: auto;
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  padding: 2px;
  border-radius: 4px;
}
.run-overview__collapse:hover {
  color: var(--mc-text-primary);
  background: var(--mc-bg-hover, #f0ece8);
}

.run-overview__body {
  flex: 1;
  overflow-y: auto;
  padding: 8px 12px 16px;
  min-height: 0;
}

.run-overview__section + .run-overview__section {
  margin-top: 14px;
}
.run-overview__section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--mc-text-tertiary);
  text-transform: uppercase;
  letter-spacing: 0.03em;
  margin-bottom: 6px;
}
.run-overview__count {
  font-weight: 500;
  text-transform: none;
  letter-spacing: 0;
  color: var(--mc-primary);
}

.run-overview__empty {
  margin: 0;
  padding: 10px 0;
  font-size: 12px;
  color: var(--mc-text-quaternary, #c0bfbc);
  text-align: center;
}

.run-overview__subagents {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

@media (max-width: 1280px) {
  .run-overview {
    display: none;
  }
}
</style>
