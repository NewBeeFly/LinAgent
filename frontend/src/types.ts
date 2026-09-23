export interface SseEvent {
  event: string
  data: any
}

export interface TurnRecord {
  id: number
  seq: number
  status: string
  finishReason?: string
  messages: MessageRecord[]
}

export interface MessageRecord {
  seq: number
  msgType: 'USER' | 'THINKING' | 'TEXT' | 'TOOL_CALL' | 'TOOL_RESULT' | 'ERROR' | 'SUMMARY'
  content?: string
  callId?: string
  toolName?: string
  arguments?: string
  result?: string
  success?: boolean
  durationMs?: number
}

export interface ChatTurn {
  /** 轮次 id：meta 事件 / 历史回放回填（409 恢复按 id 定位挂起轮）；发送瞬间未知 */
  id?: number
  userText: string
  thinking: string
  text: string
  tools: ToolEvent[]
  status: 'streaming' | 'waitingApproval' | 'done' | 'error'
  /** 审批分区（approval_request 事件 / GET pending 恢复）；决议续跑后仅作留痕不再渲染 */
  approval?: PendingApproval
  /** 错误提示行（网络失败 / SSE error 事件 / 历史回放 ERROR 消息），渲染为红色提示 */
  errorText?: string
}

export interface ToolEvent {
  callId: string
  toolName: string
  arguments?: string
  result?: string
  success?: boolean
  durationMs?: number
}

/** 单个子命令判定明细（source：BUILTIN / session / user，未命中为 null） */
export interface SubVerdict {
  segment: string
  allowed: boolean
  source?: string | null
}

/** 待审批项（与后端 PermissionRuleEngine.PendingItem 同构：approval_request 事件 / GET pending 共用） */
export interface ApprovalItem {
  callId: string
  toolName: string
  /** 工具原始参数 JSON（展示数据源） */
  arguments: string
  /** 提取后的命令/路径（卡片主展示） */
  payload?: string
  subVerdicts?: SubVerdict[]
  /** 「永久允许」将生成的规则预览（服务端重算落库，前端不回传 pattern） */
  suggestedRule?: string
}

/** 审批分区：{turnId, items}，status=waitingApproval 时渲染审批卡片 */
export interface PendingApproval {
  turnId: number
  items: ApprovalItem[]
}

/** 单项决议（整批提交，callId 与 pending 逐一匹配） */
export interface ApprovalItemDecision {
  callId: string
  decision: 'approve' | 'reject'
  reason?: string
}

/** POST /approvals 请求体：remember 为整批档位（forever > session > once） */
export interface ApprovalDecisionPayload {
  items: ApprovalItemDecision[]
  remember: 'once' | 'session' | 'forever'
}
