import { useEffect, useRef, useState } from 'react'
import { Button, Input } from 'antd'
import { SearchOutlined, UserOutlined } from '@ant-design/icons'
import { ApiError, searchRecipients } from './api'
import type { Recipient } from './api'

interface Props {
  token: string
  value?: Recipient
  onChange?: (recipient: Recipient | undefined) => void
  disabled?: boolean
  onExpired: () => void
}

export default function RecipientPicker({ token, value, onChange, disabled, onExpired }: Props) {
  const [query, setQuery] = useState('')
  const [recipients, setRecipients] = useState<Recipient[]>([])
  const [loading, setLoading] = useState(false)
  const [searched, setSearched] = useState(false)
  const [error, setError] = useState('')
  // Ignore an old response if the input changes or the component is removed.
  const requestNumber = useRef(0)

  useEffect(() => {
    return () => { requestNumber.current += 1 }
  }, [token])

  function changeQuery(nextQuery: string) {
    requestNumber.current += 1
    setQuery(nextQuery)
    setRecipients([])
    setSearched(false)
    setLoading(false)
    setError('')
    onChange?.(undefined)
  }

  async function findRecipients() {
    const keyword = query.trim()
    if (disabled || loading) return
    if (!keyword || (!keyword.includes('@') && keyword.length < 3)) {
      setError('Enter at least 3 characters of a username, or an exact email address.')
      return
    }

    const currentRequest = ++requestNumber.current
    setLoading(true)
    setSearched(false)
    setError('')
    setRecipients([])
    onChange?.(undefined)
    try {
      const results = await searchRecipients(token, keyword)
      if (currentRequest !== requestNumber.current) return
      setRecipients(results)
      setSearched(true)
    } catch (cause) {
      if (currentRequest !== requestNumber.current) return
      if (cause instanceof ApiError && cause.status === 401) {
        onExpired()
        return
      }
      setError(cause instanceof Error ? cause.message : 'Unable to find recipients. Please try again.')
    } finally {
      if (currentRequest === requestNumber.current) setLoading(false)
    }
  }

  function selectRecipient(recipient: Recipient) {
    requestNumber.current += 1
    setLoading(false)
    setRecipients([])
    setSearched(false)
    setError('')
    onChange?.(recipient)
  }

  return <div className="recipient-picker">
    <div className="recipient-search">
      <Input
        aria-label="Recipient username or email"
        size="large"
        prefix={<UserOutlined />}
        placeholder="Enter username or email"
        value={query}
        disabled={disabled}
        maxLength={320}
        autoComplete="off"
        spellCheck={false}
        onChange={(event) => changeQuery(event.target.value)}
        onPressEnter={(event) => { event.preventDefault(); void findRecipients() }}
      />
      <Button size="large" htmlType="button" icon={<SearchOutlined />} loading={loading} disabled={disabled}
        onClick={() => void findRecipients()}>Search</Button>
    </div>
    <div aria-live="polite">
      {error && <p className="recipient-error" role="alert">{error}</p>}
      {searched && recipients.length === 0 && <p className="recipient-help">No recipient found. Check the exact email or username.</p>}
      {recipients.length > 0 && <>
        <p className="recipient-help">Choose the correct person. For matching names, search by exact email.</p>
        <ul className="recipient-results" aria-label="Recipient search results">
          {recipients.map((recipient) => <li key={recipient.walletId}>
            <button type="button" disabled={disabled} onClick={() => selectRecipient(recipient)}>
              <span><strong>{recipient.username}</strong><small>{recipient.maskedEmail}</small></span>
              <span className="recipient-currency">{recipient.currency}</span>
            </button>
          </li>)}
        </ul>
        {recipients.length === 10 && <p className="recipient-help">Showing up to 10 people. Enter more of the username to narrow your search.</p>}
      </>}
      {value && <div className="recipient-selected">
        <span><strong>{value.username}</strong><small>{value.maskedEmail}</small></span>
        <Button htmlType="button" type="text" disabled={disabled} onClick={() => changeQuery('')}>Change</Button>
      </div>}
    </div>
  </div>
}
