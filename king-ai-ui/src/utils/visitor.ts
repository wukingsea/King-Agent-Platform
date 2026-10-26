const VISITOR_KEY = 'king_ai_visitor'

function randomId(): string {
  const bytes = new Uint8Array(12)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}

export function getVisitorId(): string {
  let value = localStorage.getItem(VISITOR_KEY)
  if (!value) {
    value = `visitor_${randomId()}`
    localStorage.setItem(VISITOR_KEY, value)
  }
  return value
}
