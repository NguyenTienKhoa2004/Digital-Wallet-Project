import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Drawer, Empty, Pagination, Skeleton, Tag } from 'antd'
import { ArrowDownOutlined, ArrowRightOutlined, ArrowUpOutlined, CheckCircleOutlined, ClockCircleOutlined, CloseCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { ApiError, getTransfer, getTransfers } from './api'
import type { Transfer, TransferHistory } from './api'
import { money } from './money'

interface Props { token: string; walletId?: string; refresh: number; onExpired: () => void; onSend: () => void }
const dateTime = (value: string) => new Date(value).toLocaleString('en-US', { dateStyle: 'short', timeStyle: 'short' })
const shortId = (value: string) => `${value.slice(0, 8)}…${value.slice(-6)}`

function Status({ status }: { status: Transfer['status'] }) {
  const item = {
    SUCCESS: { color: 'success', icon: <CheckCircleOutlined />, label: 'Successful' },
    FAILED: { color: 'error', icon: <CloseCircleOutlined />, label: 'Failed' },
    PENDING: { color: 'gold', icon: <ClockCircleOutlined />, label: 'Pending' },
  }[status]
  return <Tag color={item.color} icon={item.icon}>{item.label}</Tag>
}

export default function TransferHistoryPanel({ token, walletId, refresh, onExpired, onSend }: Props) {
  const [history, setHistory] = useState<TransferHistory>()
  const [error, setError] = useState('')
  const [page, setPage] = useState(0)
  const [reload, setReload] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [selected, setSelected] = useState<Transfer | null>(null)
  const [detailError, setDetailError] = useState('')
  const [detailLoading, setDetailLoading] = useState(false)
  const expire = useRef(onExpired)
  expire.current = onExpired

  useEffect(() => {
    if (!walletId) return
    let cancelled = false
    let timer: number | undefined
    async function load() {
      setRefreshing(true)
      try {
        const result = await getTransfers(token, page)
        if (cancelled) return
        setHistory(result)
        setError('')
        if (result.items.some((item) => item.status === 'PENDING')) timer = window.setTimeout(load, 5000)
      } catch (cause) {
        if (cancelled) return
        if (cause instanceof ApiError && cause.status === 401) { expire.current(); return }
        setError(cause instanceof Error ? cause.message : 'Unable to load transfer history.')
      } finally { if (!cancelled) setRefreshing(false) }
    }
    void load()
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [token, walletId, page, refresh, reload])

  useEffect(() => {
    if (!selected) return
    let cancelled = false
    let timer: number | undefined
    const reference = selected.reference
    async function load() {
      setDetailLoading(true)
      try {
        const result = await getTransfer(token, reference)
        if (cancelled) return
        setSelected((previous) => previous?.reference === reference ? result : previous)
        setDetailError('')
        if (result.status === 'PENDING') timer = window.setTimeout(load, 5000)
      } catch (cause) {
        if (cancelled) return
        if (cause instanceof ApiError && cause.status === 401) { expire.current(); return }
        setDetailError(cause instanceof Error ? cause.message : 'Unable to refresh transfer details.')
      } finally { if (!cancelled) setDetailLoading(false) }
    }
    void load()
    return () => { cancelled = true; window.clearTimeout(timer) }
  }, [token, selected?.reference, reload, refresh])

  if (!walletId) return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Your transfer history will appear once your wallet is available." />
  const received = selected?.destinationWalletId === walletId
  const failure = selected?.failureCode === 'INSUFFICIENT_BALANCE' ? 'There were not enough funds in the sender wallet.'
    : selected?.failureCode === 'BALANCE_LIMIT_EXCEEDED' ? 'The recipient wallet had reached its balance limit.' : selected?.failureCode

  return <>
    <div className="transfer-history-toolbar"><span>Sent & received{history ? ` · ${history.totalElements} transfers` : ''}</span><Button size="small" type="text" icon={<ReloadOutlined spin={refreshing} />} disabled={refreshing} onClick={() => setReload((value) => value + 1)}>Refresh</Button></div>
    {error && <Alert className="dashboard-alert" type="warning" showIcon title="Unable to refresh transfer history" description={error} action={<Button size="small" onClick={() => setReload((value) => value + 1)}>Retry</Button>} />}
    {!history ? !error && <Skeleton active paragraph={{ rows: 3 }} /> : history.items.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="No transfers yet. Money you send or receive will appear here."><Button onClick={onSend}>Send your first transfer</Button></Empty> : <>
      <div className="history-table-scroll"><table className="history-table transfer-history-table"><thead><tr><th>Transfer</th><th>Other wallet / Date</th><th>Amount</th><th>Status</th><th><span className="sr-only">Details</span></th></tr></thead><tbody>{history.items.map((item) => {
        const incoming = item.destinationWalletId === walletId
        const otherWallet = incoming ? item.sourceWalletId : item.destinationWalletId
        return <tr key={item.transactionId}>
          <td><div className="history-transaction"><span className={`transaction-icon ${incoming ? 'transfer-in' : 'transfer-out'}`}>{incoming ? <ArrowDownOutlined /> : <ArrowUpOutlined />}</span><span><strong>{incoming ? 'Received money' : 'Sent money'}</strong><small title={item.description || item.reference}>{item.description || item.reference}</small></span></div></td>
          <td><span className="recipient-name mono" title={otherWallet}>{incoming ? 'From' : 'To'} {shortId(otherWallet)}</span><span className="transaction-date">{dateTime(item.createdAt)}</span></td>
          <td className={`history-amount ${incoming ? 'transfer-credit' : ''}`}>{item.status === 'SUCCESS' ? incoming ? '+' : '−' : ''}{money(item.amount)}</td>
          <td><Status status={item.status} /></td><td><Button type="text" icon={<ArrowRightOutlined />} aria-label={`Transfer details ${item.reference}`} onClick={() => { setSelected(item); setDetailError('') }} /></td>
        </tr>
      })}</tbody></table></div>
      <div className="history-pagination"><Pagination current={page + 1} total={history.totalElements} pageSize={5} showSizeChanger={false} onChange={(next) => { setPage(next - 1); setHistory(undefined); setError('') }} /></div>
    </>}
    <Drawer title="Transfer details" open={!!selected} onClose={() => setSelected(null)} size={460} className="transfer-detail-drawer">
      {selected && <><div className={`transfer-detail-direction ${received ? 'transfer-in' : 'transfer-out'}`}>{received ? <ArrowDownOutlined /> : <ArrowUpOutlined />}</div><p className="transfer-detail-label">{received ? 'Received money' : 'Sent money'}</p><h2 className="transfer-detail-amount">{money(selected.amount)} <small>USD</small></h2><Status status={selected.status} />
        {detailError && <Alert className="transfer-alert" type="warning" showIcon title="Unable to refresh details" description={detailError} />}
        <dl className="detail-list"><div><dt>Transaction reference</dt><dd className="mono">{selected.reference}</dd></div><div><dt>Sender wallet{!received ? ' · Your wallet' : ''}</dt><dd className="mono">{selected.sourceWalletId}</dd></div><div><dt>Recipient wallet{received ? ' · Your wallet' : ''}</dt><dd className="mono">{selected.destinationWalletId}</dd></div><div><dt>Created</dt><dd>{dateTime(selected.createdAt)}</dd></div><div><dt>Completed</dt><dd>{selected.completedAt ? dateTime(selected.completedAt) : 'Not completed'}</dd></div><div><dt>Transfer fee</dt><dd>$0.00</dd></div><div><dt>Note</dt><dd>{selected.description || 'No note'}</dd></div></dl>
        {selected.status === 'FAILED' && <Alert className="transfer-alert" type="error" showIcon title="No money was moved" description={failure} />}
        {selected.status === 'PENDING' && <Alert className="transfer-alert" type="info" showIcon title="Transfer not yet confirmed" description="The status is checked automatically every 5 seconds." />}
        <Button block icon={<ReloadOutlined spin={detailLoading} />} disabled={detailLoading} onClick={() => setReload((value) => value + 1)}>Refresh status</Button>
      </>}
    </Drawer>
  </>
}
