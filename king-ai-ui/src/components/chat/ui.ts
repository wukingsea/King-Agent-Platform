import type { ChatMessageView } from '@/api/types'

export interface UiMessage extends Omit<ChatMessageView, 'id'> {
  id?: number
  key: string
  streaming?: boolean
  error?: string
  notice?: string
  retrying?: boolean
  totalTokens?: number
}
