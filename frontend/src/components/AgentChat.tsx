import { useState, type FormEvent } from 'react'
import { askAgent, ApiError } from '../api/client'

interface ChatMessage {
  id: number
  role: 'user' | 'assistant'
  text: string
  toolsUsed?: string[]
  error?: boolean
  retryQuestion?: string
}

export default function AgentChat() {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [question, setQuestion] = useState('')
  const [loading, setLoading] = useState(false)

  async function submitQuestion(value: string, appendUserMessage: boolean) {
    const trimmed = value.trim()
    if (!trimmed || loading) return

    setLoading(true)
    if (appendUserMessage) {
      setMessages((current) => [...current, { id: Date.now(), role: 'user', text: trimmed }])
    }
    setQuestion('')
    try {
      const response = await askAgent(trimmed)
      setMessages((current) => [
        ...current,
        {
          id: Date.now(),
          role: 'assistant',
          text: response.answer,
          toolsUsed: response.toolsUsed,
        },
      ])
    } catch (error) {
      const message =
        error instanceof ApiError && error.code === 'AI_PROVIDER_UNAVAILABLE'
          ? 'El asistente no está disponible en este momento. Puedes volver a intentarlo.'
          : error instanceof ApiError && error.status === 0
            ? 'No se pudo conectar con el backend. Comprueba la conexión e inténtalo de nuevo.'
            : 'No fue posible completar la consulta. Puedes volver a intentarlo.'
      setMessages((current) => [
        ...current,
        { id: Date.now(), role: 'assistant', text: message, error: true, retryQuestion: trimmed },
      ])
    } finally {
      setLoading(false)
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void submitQuestion(question, true)
  }

  return (
    <section className="panel chat-panel" aria-labelledby="chat-title">
      <div className="panel-heading">
        <div>
          <span className="eyebrow">ASISTENTE</span>
          <h2 id="chat-title">Consulta tu flota</h2>
        </div>
        <span className="ai-badge">IA</span>
      </div>
      <div className="chat-messages" aria-live="polite">
        {messages.length === 0 && (
          <div className="chat-empty">
            <span className="chat-sparkle" aria-hidden="true">✳</span>
            <p>Pregunta por el estado o el historial de un vehículo.</p>
            <span>Ejemplo: “¿Dónde está el vehículo truck-17?”</span>
          </div>
        )}
        {messages.map((message) => (
          <article
            className={`chat-message ${message.role}${message.error ? ' is-error' : ''}`}
            key={message.id}
          >
            <span className="message-label">
              {message.role === 'user' ? 'TÚ' : 'FLEET AI'}
            </span>
            <p>{message.text}</p>
            {message.toolsUsed && message.toolsUsed.length > 0 && (
              <div className="tools-used">
                Fuentes consultadas: {message.toolsUsed.join(', ')}
              </div>
            )}
            {message.error && message.retryQuestion && (
              <button
                className="retry-button"
                type="button"
                disabled={loading}
                onClick={() => void submitQuestion(message.retryQuestion ?? '', false)}
              >
                Reintentar consulta
              </button>
            )}
          </article>
        ))}
        {loading && (
          <div className="chat-loading" role="status">
            <span className="typing-dots"><i /><i /><i /></span>
            Consultando telemetría…
          </div>
        )}
      </div>
      <form className="chat-form" onSubmit={handleSubmit}>
        <label className="sr-only" htmlFor="agent-question">Escribe una pregunta</label>
        <textarea
          id="agent-question"
          value={question}
          maxLength={2000}
          rows={2}
          placeholder="Pregunta sobre un vehículo..."
          disabled={loading}
          onChange={(event) => setQuestion(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              event.currentTarget.form?.requestSubmit()
            }
          }}
        />
        <button
          className="send-button"
          type="submit"
          aria-label="Enviar pregunta"
          disabled={loading || !question.trim()}
        >
          <span aria-hidden="true">↑</span>
        </button>
      </form>
      <p className="chat-footnote">Respuestas basadas en telemetría persistida.</p>
    </section>
  )
}
