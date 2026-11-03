import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Drawer, Form, Input, Steps } from 'antd'
import { ArrowRightOutlined, CheckCircleOutlined, ClockCircleOutlined, CloseCircleOutlined, EditOutlined, ReloadOutlined, SafetyCertificateOutlined, SendOutlined, WalletOutlined } from '@ant-design/icons'
import { ApiError, createTransfer, getTransferByKey } from './api'
import type { TransferPayload, Wallet } from './api'
import { centsFromInput, money } from './money'
import { readPendingTransfer, resolveTransfer, walletIdPattern } from './transfer-operation'
import type { PendingTransfer, TransferOutcome } from './transfer-operation'
import './transfer.css'

interface Props {
  token: string
  userId: string
  wallet: Wallet | null | undefined
  walletError: string
  balanceVisible: boolean
  open: boolean
  onOpen: () => void
  onClose: () => void
  onExpired: () => void
  onChanged: () => void
}
interface Fields { destinationWalletId: string; amount: string; description: string }
interface Completed { operation: PendingTransfer; outcome: TransferOutcome }

export default function TransferDrawer(props: Props) {
  const { token, userId, wallet, walletError, balanceVisible, open, onOpen, onClose } = props
  const storageKey = `flowpay:pending-transfer:${userId}`
  const [pending, setPending] = useState<PendingTransfer | null>(() => {
    try { return readPendingTransfer(sessionStorage.getItem(storageKey)) } catch { return null }
  })
  const [review, setReview] = useState<TransferPayload | null>(null)
  const [completed, setCompleted] = useState<Completed | null>(null)
  const [error, setError] = useState('')
  const [working, setWorking] = useState<'sending' | 'checking' | null>(null)
  const [form] = Form.useForm<Fields>()
  const busy = useRef(false)
  const alive = useRef(false)
  const pendingRef = useRef(pending)
  const callbacks = useRef(props)
  callbacks.current = props
  const canSend = !!wallet && wallet.status === 'ACTIVE' && !walletError
  const sourceUnavailable = walletError ? 'Refresh your wallet before sending money.'
    : wallet === undefined ? 'Loading your wallet…'
    : wallet === null ? 'Create a wallet on the home screen before sending money.'
    : wallet.status !== 'ACTIVE' ? 'Your wallet is frozen. Sending money is unavailable.' : ''
  const reviewUnavailable = sourceUnavailable || (review && wallet && BigInt(review.amount) > BigInt(wallet.balance) ? 'Your available balance is no longer sufficient. Edit the amount or add funds.' : '')

  useEffect(() => {
    alive.current = true
    return () => { alive.current = false }
  }, [])

  async function run(operation: PendingTransfer, mode: 'sending' | 'checking') {
    if (busy.current || pendingRef.current?.key !== operation.key) return
    busy.current = true
    setWorking(mode)
    setError('')
    try {
      const outcome = await resolveTransfer(() => mode === 'sending'
        ? createTransfer(token, operation.key, operation)
        : getTransferByKey(token, operation.key))
      if (!alive.current || pendingRef.current?.key !== operation.key) return
      if (outcome.kind === 'confirmed' && outcome.transfer.status === 'PENDING') return
      // Release recovery data only after the server gives a definitive outcome.
      try { sessionStorage.removeItem(storageKey) } catch { /* Replaying this saved key remains safe. */ }
      pendingRef.current = null
      setPending(null)
      setCompleted({ operation, outcome })
      callbacks.current.onChanged()
    } catch (cause) {
      if (!alive.current) return
      if (cause instanceof ApiError && cause.status === 401) { callbacks.current.onExpired(); return }
      setError(cause instanceof Error ? cause.message : 'The transfer outcome could not be confirmed. Check its status before trying again.')
    } finally {
      busy.current = false
      if (alive.current) setWorking(null)
    }
  }
  const runRef = useRef(run)
  runRef.current = run

  useEffect(() => {
    if (!pending) return
    let cancelled = false
    let timer: number | undefined
    const operation = pending
    async function check() {
      if (cancelled) return
      await runRef.current(operation, 'checking')
      if (!cancelled && pendingRef.current?.key === operation.key) timer = window.setTimeout(check, 5000)
    }
    void check()
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [token, pending?.key])

  function reviewTransfer(fields: Fields) {
    if (busy.current || pendingRef.current || !canSend) return
    const amount = centsFromInput(fields.amount)
    if (!amount || !wallet || BigInt(amount) > BigInt(wallet.balance)) return
    setError('')
    setReview({ destinationWalletId: fields.destinationWalletId.trim().toLowerCase(), amount, description: fields.description || '' })
  }

  function confirmTransfer() {
    if (busy.current || pendingRef.current || !review || !canSend || reviewUnavailable) return
    const operation = { ...review, key: crypto.randomUUID() }
    // Persist the exact, reviewed payload before any request can reach the server.
    try { sessionStorage.setItem(storageKey, JSON.stringify(operation)) }
    catch {
      setError('Your browser could not save request recovery details. Allow session storage before sending money.')
      return
    }
    pendingRef.current = operation
    setPending(operation)
    setReview(null)
    setCompleted(null)
    void run(operation, 'sending')
  }

  function startAnother() {
    if (pendingRef.current || busy.current) return
    setCompleted(null)
    setReview(null)
    setError('')
    form.resetFields()
  }

  const outcome = completed?.outcome
  const result = outcome?.kind === 'confirmed' ? outcome.transfer : null
  const failed = outcome?.kind === 'rejected' || result?.status === 'FAILED'
  const resultPayload = pending || completed?.operation
  const reference = result?.reference || (outcome?.kind === 'rejected' ? outcome.reference : undefined)
  const step = pending || completed ? 2 : review ? 1 : 0

  return <>
    {(pending || completed) && <button className="pending-banner transfer-banner" onClick={onOpen}>
      {pending ? <ClockCircleOutlined /> : failed ? <CloseCircleOutlined /> : <CheckCircleOutlined />}
      <span>{pending ? 'Your transfer is being tracked' : failed ? 'Transfer not completed' : 'Money sent successfully'}<small>{pending ? 'View the request and check its status' : 'View your transfer receipt'}</small></span><ArrowRightOutlined />
    </button>}
    <Drawer title="Send money" open={open} onClose={onClose} size={500} className="transfer-drawer" forceRender>
      <div className="transfer-intro"><span className="transfer-heading-icon"><SendOutlined /></span><span className="section-kicker">FLOWPAY TO FLOWPAY</span><h2>A little closer.<br /><em>With every transfer.</em></h2><p>Send USD directly to another FlowPay wallet.</p></div>
      <Steps className="transfer-steps" size="small" current={step} items={[{ title: 'Details' }, { title: 'Review' }, { title: 'Result' }]} />
      <div className="transfer-source"><span className="source-icon"><WalletOutlined /></span><div><span>From your FlowPay wallet</span><strong>{wallet ? wallet.status === 'ACTIVE' ? `${balanceVisible ? money(wallet.balance) : '••••••'} USD available` : 'Wallet frozen' : 'No wallet available'}</strong></div><span className="transfer-currency">USD</span></div>
      {!pending && !completed && sourceUnavailable && <Alert className="transfer-alert" type="warning" showIcon title={sourceUnavailable} />}
      {error && <div role="alert"><Alert className="transfer-alert" type={pending ? 'warning' : 'error'} showIcon title={pending ? 'Outcome not yet confirmed' : 'Unable to send money'} description={error} /></div>}
      <div hidden={!!review || !!pending || !!completed}>
        <Form<Fields> form={form} layout="vertical" requiredMark={false} disabled={!canSend} onFinish={reviewTransfer} initialValues={{ destinationWalletId: '', amount: '', description: '' }}>
          <Form.Item name="destinationWalletId" label="Recipient wallet ID" extra="Ask the recipient to copy their wallet ID from Receive money." rules={[
            { required: true, whitespace: true, message: 'Enter the recipient wallet ID.' },
            { validator: (_, value: string) => !value || walletIdPattern.test(value.trim()) ? Promise.resolve() : Promise.reject(new Error('Enter a complete wallet UUID, for example xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx.')) },
            { validator: (_, value: string) => !value || value.trim().toLowerCase() !== wallet?.walletId.toLowerCase() ? Promise.resolve() : Promise.reject(new Error('You cannot send money to your own wallet.')) },
          ]}><Input size="large" prefix={<WalletOutlined />} placeholder="Paste recipient wallet ID" autoComplete="off" spellCheck={false} maxLength={38} /></Form.Item>
          <Form.Item name="amount" label="Amount to send" extra="USD, up to 2 decimal places. No transfer fee." rules={[
            { required: true, message: 'Enter an amount to send.' },
            { validator: (_, value: string) => {
              if (!value) return Promise.resolve()
              const cents = centsFromInput(value)
              if (!cents) return Promise.reject(new Error('Enter an amount greater than zero, with up to 2 decimal places and within the allowed limit.'))
              return wallet && BigInt(cents) > BigInt(wallet.balance) ? Promise.reject(new Error('This amount exceeds your available balance.')) : Promise.resolve()
            } },
          ]}><Input size="large" className="transfer-amount-input" prefix="$" suffix="USD" placeholder="0.00" inputMode="decimal" autoComplete="off" maxLength={20} /></Form.Item>
          <div className="quick-amounts" aria-label="Choose a transfer amount">{['10', '25', '50', '100'].map((amount) => <Button key={amount} disabled={!canSend || !!wallet && BigInt(amount) * 100n > BigInt(wallet.balance)} onClick={() => { form.setFieldValue('amount', `${amount}.00`); void form.validateFields(['amount']).catch(() => {}) }}>${amount}</Button>)}</div>
          <Form.Item name="description" label="Note (optional)" rules={[{ max: 255, message: 'Keep your note within 255 characters.' }]}><Input.TextArea placeholder="What is this transfer for?" maxLength={255} showCount rows={2} /></Form.Item>
          <Button className="transfer-primary" type="primary" size="large" htmlType="submit" block icon={<ArrowRightOutlined />} iconPlacement="end">Review transfer</Button>
        </Form>
      </div>
      {review && <div className="transfer-review">
        <div className="transfer-review-amount"><span>YOU ARE SENDING</span><h3>{money(review.amount)} <small>USD</small></h3></div>
        <dl className="transfer-summary"><div><dt>Recipient wallet</dt><dd className="mono">{review.destinationWalletId}</dd></div><div><dt>From</dt><dd>Your FlowPay wallet</dd></div><div><dt>Note</dt><dd>{review.description || 'No note'}</dd></div><div><dt>Transfer fee</dt><dd>$0.00</dd></div><div className="transfer-total"><dt>Total debit</dt><dd>{money(review.amount)} USD</dd></div></dl>
        <p className="transfer-footnote"><SafetyCertificateOutlined /> Check the wallet ID carefully. FlowPay does not look up the recipient name.</p>
        {reviewUnavailable && !sourceUnavailable && <Alert className="transfer-alert" type="warning" showIcon title={reviewUnavailable} />}
        <Button className="transfer-primary" type="primary" size="large" block disabled={!!reviewUnavailable} icon={<SendOutlined />} onClick={confirmTransfer}>Confirm & send {money(review.amount)}</Button>
        <Button className="transfer-secondary" block icon={<EditOutlined />} onClick={() => { setReview(null); setError('') }}>Edit details</Button>
      </div>}
      {resultPayload && <div className={`transfer-result ${pending ? 'transfer-pending' : failed ? 'transfer-failed' : 'transfer-success'}`} aria-live="polite">
        <span className="transfer-result-icon">{pending ? <ClockCircleOutlined /> : failed ? <CloseCircleOutlined /> : <CheckCircleOutlined />}</span>
        <h3>{pending ? working === 'sending' ? 'Sending your money…' : 'Confirming your transfer' : failed ? 'Transfer not completed' : 'Money sent. Just like that.'}</h3>
        <div className="transfer-result-amount">{money(resultPayload.amount)} <small>USD</small></div>
        <p>{pending ? 'The outcome is not confirmed yet. We check automatically every 5 seconds. Your original request is saved.' : outcome?.kind === 'rejected' ? outcome.message : failed ? 'No money was moved for this transfer.' : 'Your transfer has been confirmed by FlowPay.'}</p>
        <dl className="transfer-summary"><div><dt>Recipient wallet</dt><dd className="mono">{resultPayload.destinationWalletId}</dd></div>{reference && <div><dt>Transaction reference</dt><dd className="mono">{reference}</dd></div>}<div><dt>Note</dt><dd>{resultPayload.description || 'No note'}</dd></div>{result?.completedAt && <div><dt>Completed</dt><dd>{new Date(result.completedAt).toLocaleString('en-US')}</dd></div>}{result?.failureCode && <div><dt>Reason</dt><dd>{result.failureCode}</dd></div>}</dl>
        {pending ? <><Button block icon={<ReloadOutlined spin={working === 'checking'} />} disabled={!!working} onClick={() => void run(pending, 'checking')}>Check status</Button><Button className="transfer-secondary" block loading={working === 'sending'} disabled={!!working} onClick={() => void run(pending, 'sending')}>Retry the same transfer</Button><p className="transfer-recovery-note">Retrying keeps the same request ID and details to prevent sending twice.</p></> : <><Button className="transfer-primary" type="primary" size="large" block onClick={startAnother}>{failed ? 'Start a new transfer' : 'Send another transfer'}</Button><Button className="transfer-secondary" block onClick={onClose}>Back to wallet</Button></>}
      </div>}
      {!pending && !completed && <p className="transfer-footnote"><SafetyCertificateOutlined /> Only the wallet you are signed in to can send funds.</p>}
    </Drawer>
  </>
}
