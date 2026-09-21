import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({ gfm: true, breaks: true })

/**
 * 助手答案 Markdown → 安全 HTML。
 * 流式期间会收到不完整片段，marked 对片段的解析是渐进稳定的
 * （未闭合的列表/加粗按原文显示，闭合后立即成形）。
 */
export function renderAnswer(markdown: string): string {
  const html = marked.parse(markdown, { async: false }) as string
  return DOMPurify.sanitize(html)
}
