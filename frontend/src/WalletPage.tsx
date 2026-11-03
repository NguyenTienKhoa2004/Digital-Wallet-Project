import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Drawer, Empty, Form, Input, Pagination, Skeleton, Tag, Modal } from 'antd'
import {
  BarChartOutlined,
  BankOutlined,
  BellOutlined,
  CopyOutlined,
  CreditCardOutlined,
  EyeInvisibleOutlined,
  EyeOutlined,
  FileTextOutlined,
  GiftOutlined,
  HomeOutlined,
  MobileOutlined,
  QrcodeOutlined,
  SendOutlined,
  ArrowDownOutlined,
  ArrowRightOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  HistoryOutlined,
  LogoutOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  WalletOutlined,
} from '@ant-design/icons'
import Brand from './Brand'
import { ThemeToggle } from './ThemeProvider'
import {
  ApiError,
  createTopUp,
  createWallet,
  getTopUp,
  getTopUpByKey,
  getTopUps,
  getWallet,
} from './api'
import type { TopUp, TopUpHistory, TopUpPayload, User, Wallet } from './api'
import { centsFromInput, decimalAmount, maxAmount, money } from './money'
import TransferDrawer from './TransferDrawer'
import TransferHistoryPanel from './TransferHistoryPanel'
import './wallet.css'

interface Props {
  token: string
  user: User
  onSignOut: () => void
  onExpired: () => void
}
interface PendingOperation extends TopUpPayload {
  key: string
}
interface TopUpFields {
  amount: string
  description: string
}
function dateTime(value: string) {
  return new Intl.DateTimeFormat('en-US', { dateStyle: 'short', timeStyle: 'short' }).format(
    new Date(value),
  )
}

function readOperation(storageKey: string): PendingOperation | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey) || 'null')
    if (
      value &&
      typeof value.key === 'string' &&
      value.key.length > 0 &&
      value.key.length <= 255 &&
      typeof value.amount === 'string' &&
      /^\d{1,19}$/.test(value.amount) &&
      BigInt(value.amount) > 0n &&
      BigInt(value.amount) <= maxAmount &&
      typeof value.description === 'string' &&
      value.description.length <= 255
    )
      return value
  } catch {
    /* A new request is allowed only after successfully saving its recovery information. */
  }
  return null
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Unable to load data. Please try again.'
}

function Status({ status }: { status: TopUp['status'] }) {
  const options = {
    PENDING: { color: 'gold', icon: <ClockCircleOutlined />, label: 'Pending' },
    SUCCESS: { color: 'success', icon: <CheckCircleOutlined />, label: 'Successful' },
    FAILED: { color: 'error', icon: <CloseCircleOutlined />, label: 'Failed' },
  }
  const item = options[status]
  return (
    <Tag color={item.color} icon={item.icon}>
      {item.label}
    </Tag>
  )
}

export default function WalletPage({ token, user, onSignOut, onExpired }: Props) {
  const storageKey = `flowpay:pending-topup:${user.userId}`
  const [operation, setOperation] = useState<PendingOperation | null>(() =>
    readOperation(storageKey),
  )
  const [wallet, setWallet] = useState<Wallet | null | undefined>()
  const [walletError, setWalletError] = useState('')
  const [history, setHistory] = useState<TopUpHistory>()
  const [historyError, setHistoryError] = useState('')
  const [page, setPage] = useState(0)
  const [reload, setReload] = useState(0)
  const [refreshing, setRefreshing] = useState(false)
  const [creatingWallet, setCreatingWallet] = useState(false)
  const [sending, setSending] = useState(false)
  const [active, setActive] = useState<TopUp | null>(null)
  const [operationError, setOperationError] = useState('')
  const [selected, setSelected] = useState<TopUp | null>(null)
  const [form] = Form.useForm<TopUpFields>()
  const [topUpOpen, setTopUpOpen] = useState(false)
  const [transferOpen, setTransferOpen] = useState(false)
  const [historyTab, setHistoryTab] = useState<'topups' | 'transfers'>('topups')
  const [balanceVisible, setBalanceVisible] = useState(true)
  const [utility, setUtility] = useState<'receive' | 'notifications' | 'profile' | 'stats' | null>(
    null,
  )
  const [comingSoon, setComingSoon] = useState('')
  const [copied, setCopied] = useState(false)
  const [activeNav, setActiveNav] = useState('home')
  const busy = useRef(false)
  const walletBusy = useRef(false)
  const alive = useRef(false)
  const expire = useRef(onExpired)
  expire.current = onExpired
  const amountInput = useRef<React.ComponentRef<typeof Input>>(null)

  useEffect(() => {
    alive.current = true
    document.title = 'FlowPay · My wallet'
    return () => {
      alive.current = false
    }
  }, [])

  function clearOperation() {
    try {
      sessionStorage.removeItem(storageKey)
    } catch {
      /* Terminal results remain safe to look up again. */
    }
    setOperation(null)
  }

  // Poll read endpoints only. An original POST response can still contain stale PENDING status.
  useEffect(() => {
    let cancelled = false
    let timer: number | undefined
    let hadPendingHistory = false
    async function sync() {
      if (cancelled) return
      setRefreshing(true)
      const results = await Promise.allSettled([
        getWallet(token),
        getTopUps(token, page),
        operation ? getTopUpByKey(token, operation.key) : Promise.resolve(null),
        selected ? getTopUp(token, selected.reference) : Promise.resolve(null),
      ] as const)
      if (cancelled) return
      setRefreshing(false)
      if (
        results.some(
          (result) =>
            result.status === 'rejected' &&
            result.reason instanceof ApiError &&
            result.reason.status === 401,
        )
      ) {
        expire.current()
        return
      }
      const [walletResult, historyResult, operationResult, detailResult] = results
      if (walletResult.status === 'fulfilled') {
        setWallet(walletResult.value)
        setWalletError('')
      } else if (
        walletResult.reason instanceof ApiError &&
        walletResult.reason.code === 'WALLET_NOT_FOUND'
      ) {
        setWallet(null)
        setWalletError('')
      } else setWalletError(errorMessage(walletResult.reason))

      if (historyResult.status === 'fulfilled') {
        setHistory(historyResult.value)
        setHistoryError('')
      } else setHistoryError(errorMessage(historyResult.reason))

      if (!busy.current && operationResult.status === 'fulfilled' && operationResult.value) {
        const result = operationResult.value
        setActive((previous) =>
          previous?.reference === result.reference && previous.status !== 'PENDING'
            ? previous
            : result,
        )
        setOperationError('')
        if (result.status !== 'PENDING') clearOperation()
      } else if (!busy.current && operationResult.status === 'rejected')
        setOperationError(errorMessage(operationResult.reason))

      if (detailResult.status === 'fulfilled' && detailResult.value) {
        const result = detailResult.value
        setSelected((previous) => (previous?.reference === result.reference ? result : previous))
        if (selected?.status === 'PENDING' && result.status !== 'PENDING')
          setReload((value) => value + 1)
      } else if (detailResult.status === 'rejected')
        setHistoryError(errorMessage(detailResult.reason))

      const pendingHistory =
        historyResult.status === 'fulfilled' &&
        historyResult.value.items.some((item) => item.status === 'PENDING')
      const pendingDetail =
        detailResult.status === 'fulfilled' && detailResult.value?.status === 'PENDING'
      const retryableReadError = results.some(
        (result, index) =>
          result.status === 'rejected' &&
          !(
            index === 0 &&
            result.reason instanceof ApiError &&
            result.reason.code === 'WALLET_NOT_FOUND'
          ),
      )
      if (operation || pendingHistory || hadPendingHistory || pendingDetail || retryableReadError) {
        timer = window.setTimeout(sync, 5000)
      }
      hadPendingHistory = pendingHistory
    }
    void sync()
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [token, page, operation?.key, reload, selected?.reference])

  async function openWallet() {
    if (walletBusy.current) return
    walletBusy.current = true
    setCreatingWallet(true)
    setWalletError('')
    try {
      const result = await createWallet(token)
      if (alive.current) setWallet(result)
    } catch (error) {
      if (!alive.current) return
      if (error instanceof ApiError && error.status === 401) expire.current()
      else if (error instanceof ApiError && error.code === 'WALLET_ALREADY_EXISTS')
        setReload((value) => value + 1)
      else {
        setWalletError(
          `${errorMessage(error)} You can refresh your wallet details before trying again.`,
        )
        setReload((value) => value + 1)
      }
    } finally {
      walletBusy.current = false
      if (alive.current) setCreatingWallet(false)
    }
  }

  async function sendTopUp(pending: PendingOperation) {
    if (busy.current) return
    busy.current = true
    setSending(true)
    setOperationError('')
    try {
      const result = await createTopUp(token, pending.key, pending)
      if (!alive.current) return
      setActive((previous) =>
        previous?.reference === result.reference && previous.status !== 'PENDING'
          ? previous
          : result,
      )
      setPage(0)
      if (result.status !== 'PENDING') clearOperation()
    } catch (error) {
      if (!alive.current) return
      if (error instanceof ApiError && error.status === 401) {
        expire.current()
        return
      }
      setOperationError(errorMessage(error))
      if (
        error instanceof ApiError &&
        [
          'INVALID_AMOUNT',
          'VALIDATION_FAILED',
          'WALLET_NOT_ACTIVE',
          'WALLET_NOT_FOUND',
          'FORBIDDEN',
        ].includes(error.code)
      )
        clearOperation()
    } finally {
      busy.current = false
      if (alive.current) {
        setSending(false)
        setReload((value) => value + 1)
      }
    }
  }

  function submit(values: TopUpFields) {
    if (busy.current || operation || wallet?.status !== 'ACTIVE' || walletError) return
    const amount = centsFromInput(values.amount)
    if (!amount) return
    const pending = {
      key: crypto.randomUUID(),
      amount,
      description: values.description?.trim() || '',
    }
    // Save before POST so a timeout/reload can be recovered with the same key and payload.
    try {
      sessionStorage.setItem(storageKey, JSON.stringify(pending))
    } catch {
      setOperationError(
        'Your browser could not save the request recovery details. Please allow session storage and try again.',
      )
      return
    }
    setOperation(pending)
    setActive(null)
    void sendTopUp(pending)
  }

  function focusTopUp() {
    setTopUpOpen(true)
  }

  const locked = sending || !!operation || !wallet || wallet.status !== 'ACTIVE' || !!walletError
  const displayedAmount = operation?.amount || active?.amount

  const pendingCount = history?.items.filter((item) => item.status === 'PENDING').length || 0
  const successfulItems = history?.items.filter((item) => item.status === 'SUCCESS') || []
  const pageTotal = successfulItems
    .reduce((total, item) => total + BigInt(item.amount), 0n)
    .toString()

  async function copyWalletId() {
    if (!wallet) return
    try {
      await navigator.clipboard.writeText(wallet.walletId)
      setCopied(true)
    } catch {
      setCopied(false)
    }
  }

  function navigateTo(section: string) {
    setActiveNav(section)
    if (section === 'profile') setUtility('profile')
    else if (section === 'stats') setUtility('stats')
    else
      document
        .getElementById(section === 'history' ? 'topup-history' : 'wallet-overview')
        ?.scrollIntoView({
          behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches
            ? 'instant'
            : 'smooth',
          block: 'start',
        })
  }

  return (
    <div className="wallet-app">
      <div className="ambient-light" aria-hidden="true" />
      <aside className="wallet-sidebar">
        <div className="sidebar-brand">
          <Brand to="/account" />
        </div>
        <nav className="sidebar-nav" aria-label="Main navigation">
          {[
            { key: 'home', label: 'Home', icon: <HomeOutlined /> },
            { key: 'history', label: 'Transactions', icon: <HistoryOutlined /> },
            { key: 'stats', label: 'Statistics', icon: <BarChartOutlined /> },
          ].map((item) => (
            <button
              type="button"
              title={item.label}
              key={item.key}
              className={activeNav === item.key ? 'nav-active' : ''}
              aria-current={activeNav === item.key ? 'page' : undefined}
              onClick={() => navigateTo(item.key)}
            >
              {item.icon}
              <span>{item.label}</span>
            </button>
          ))}
        </nav>
        <footer className="sidebar-footer">
          <button
            type="button"
            className="sidebar-account"
            aria-label={`Open profile for ${user.username}`}
            aria-current={activeNav === 'profile' ? 'page' : undefined}
            title={user.username}
            onClick={() => navigateTo('profile')}
          >
            <span className="sidebar-account-avatar" aria-hidden="true">
              {user.username.slice(0, 1).toUpperCase()}
            </span>
            <span className="sidebar-account-info">
              <strong>{user.username}</strong>
              <small>{user.email}</small>
            </span>
            <ArrowRightOutlined className="sidebar-account-arrow" />
          </button>
          <button type="button" className="sidebar-signout" title="Sign out" onClick={onSignOut}>
            <LogoutOutlined />
            <span>Sign out</span>
          </button>
        </footer>
      </aside>
      <header className="wallet-topbar">
        <span className="topbar-caption">YOUR MONEY, IN FLOW.</span>
        <div className="topbar-actions">
          <span className="session-badge">
            <i /> Your space
          </span>
          <ThemeToggle />
          <button
            className="header-avatar"
            aria-label="Open profile"
            onClick={() => navigateTo('profile')}
          >
            {user.username.slice(0, 1).toUpperCase()}
          </button>
        </div>
      </header>
      <main className="wallet-main" id="wallet-overview">
        <header className="dashboard-header">
          <div>
            <div className="step-label">
              <span /> A FRESH FLOW, EVERY DAY
            </div>
            <h1>
              Hello, <span>{user.username}.</span>
            </h1>
            <p>Every connection. One wallet.</p>
          </div>
          <div className="dashboard-header-actions">
            <Button
              className="refresh-button"
              icon={<ReloadOutlined spin={refreshing} />}
              aria-label="Refresh wallet details"
              onClick={() => setReload((value) => value + 1)}
              disabled={refreshing || sending}
            />
            <button
              className="notification-button"
              aria-label={
                pendingCount
                  ? `Notifications: ${pendingCount} pending transactions on this page`
                  : 'Open notifications'
              }
              onClick={() => setUtility('notifications')}
            >
              <BellOutlined />
              {pendingCount > 0 && <i />}
            </button>
          </div>
        </header>
        {walletError && (
          <Alert
            className="dashboard-alert"
            type="error"
            showIcon
            title={walletError}
            action={
              <Button size="small" onClick={() => setReload((value) => value + 1)}>
                Reload wallet
              </Button>
            }
          />
        )}
        <section className="wallet-overview" aria-label="Wallet details">
          {wallet === undefined ? (
            <div className="dashboard-panel wallet-loading">
              <Skeleton active paragraph={{ rows: 5 }} />
            </div>
          ) : wallet === null ? (
            <div className="create-wallet-card dashboard-panel">
              <span className="create-wallet-icon">
                <WalletOutlined />
              </span>
              <span className="step-label">GET STARTED WITH FLOWPAY</span>
              <h2>
                One wallet.
                <br />
                Endless possibilities.
              </h2>
              <p>Create a USD wallet to add funds and track your balance.</p>
              <Button
                type="primary"
                size="large"
                icon={<PlusOutlined />}
                loading={creatingWallet}
                onClick={() => void openWallet()}
              >
                Create my wallet
              </Button>
            </div>
          ) : (
            <div className="balance-card">
              <div className="balance-top">
                <span>
                  <WalletOutlined /> FLOWPAY WALLET <span className="card-currency">USD</span>
                </span>
                <span
                  className={`wallet-state ${wallet.status === 'ACTIVE' ? '' : 'wallet-frozen'}`}
                >
                  <i />
                  {wallet.status === 'ACTIVE' ? 'Active' : 'Frozen'}
                </span>
              </div>
              <div className="balance-label">
                Available balance{walletError ? ' · Not updated' : ''}
                <button
                  className="balance-visibility"
                  onClick={() => setBalanceVisible((value) => !value)}
                  aria-label={balanceVisible ? 'Hide balance' : 'Show balance'}
                  aria-pressed={!balanceVisible}
                >
                  {balanceVisible ? <EyeOutlined /> : <EyeInvisibleOutlined />}
                </button>
              </div>
              <div className="balance-value" aria-live="polite">
                {balanceVisible ? money(wallet.balance) : '••••••'} <span>USD</span>
              </div>
              <div className="balance-bottom">
                <span>
                  WALLET HOLDER<strong>{user.username}</strong>
                </span>
                <button className="funding-source" onClick={focusTopUp}>
                  <span className="source-icon">
                    <BankOutlined />
                  </span>
                  <span>
                    Funding source<strong>FlowPay wallet · USD</strong>
                  </span>
                  <ArrowRightOutlined />
                </button>
              </div>
              <div className="balance-watermark" aria-hidden="true">
                f<span>↗</span>
              </div>
            </div>
          )}
          <aside className="flow-companion dashboard-panel">
            <span className="companion-eyebrow">
              <span /> THE FLOW STATE
            </span>
            <div className="flow-orb" aria-hidden="true">
              <span />
              <WalletOutlined />
            </div>
            <h2>
              A simple tap.
              <br />
              <em>A fuller life.</em>
            </h2>
            <p>
              Add funds, track your balance.
              <br />
              Everything at your fingertips.
            </p>
            <span className="companion-footer">
              <SafetyCertificateOutlined /> Clarity in every transaction
            </span>
          </aside>
        </section>
        <section className="quick-actions dashboard-panel" aria-label="Quick actions">
          <button onClick={() => setTransferOpen(true)}>
            <span className="action-icon">
              <SendOutlined />
            </span>
            <span>Send money</span>
            <ArrowRightOutlined className="action-arrow" />
          </button>
          <button
            onClick={() => {
              setCopied(false)
              setUtility('receive')
            }}
          >
            <span className="action-icon">
              <ArrowDownOutlined />
            </span>
            <span>Receive money</span>
            <ArrowRightOutlined className="action-arrow" />
          </button>
          <button onClick={() => setComingSoon('Scan QR')}>
            <span className="action-icon">
              <QrcodeOutlined />
            </span>
            <span>Scan QR</span>
            <ArrowRightOutlined className="action-arrow" />
          </button>
          <button className="topup-action" onClick={focusTopUp}>
            <span className="action-icon">
              <PlusOutlined />
            </span>
            <span>Add funds</span>
            <ArrowRightOutlined className="action-arrow" />
          </button>
        </section>
        <TransferDrawer
          token={token}
          userId={user.userId}
          wallet={wallet}
          walletError={walletError}
          balanceVisible={balanceVisible}
          open={transferOpen}
          onOpen={() => setTransferOpen(true)}
          onClose={() => setTransferOpen(false)}
          onExpired={onExpired}
          onChanged={() => {
            setReload((value) => value + 1)
            setHistoryTab('transfers')
          }}
        />
        {(operation || active) && (
          <button className="pending-banner" onClick={focusTopUp}>
            <ClockCircleOutlined />
            <span>
              {active?.status === 'SUCCESS'
                ? 'Funds added successfully'
                : active?.status === 'FAILED'
                  ? 'Top-up failed'
                  : 'A top-up request is being tracked'}
              <small>View request details and status</small>
            </span>
            <ArrowRightOutlined />
          </button>
        )}
        <section className="services-section" aria-labelledby="services-title">
          <div className="section-heading">
            <div>
              <span className="section-kicker">MAKE EVERY DAY EASIER</span>
              <h2 id="services-title">Everyday services</h2>
            </div>
            <span className="subtle-label">Daily essentials</span>
          </div>
          <div className="service-grid">
            {[
              {
                title: 'Bill payments',
                subtitle: 'Electricity, water & internet',
                icon: <FileTextOutlined />,
                tone: 'blue',
              },
              {
                title: 'Mobile top-up',
                subtitle: 'Stay connected',
                icon: <MobileOutlined />,
                tone: 'orange',
              },
              {
                title: 'Banking',
                subtitle: 'Manage funding sources',
                icon: <BankOutlined />,
                tone: 'cyan',
              },
            ].map((service) => (
              <button
                className={`service-card dashboard-panel service-${service.tone}`}
                key={service.title}
                onClick={() => setComingSoon(service.title)}
              >
                <span className="service-icon">{service.icon}</span>
                <span>
                  <strong>{service.title}</strong>
                  <small>{service.subtitle}</small>
                </span>
                <ArrowRightOutlined />
              </button>
            ))}
          </div>
        </section>
        <section
          className="history-panel dashboard-panel"
          id="topup-history"
          aria-labelledby="history-title"
        >
          <div className="history-heading">
            <div>
              <h2 id="history-title">Recent transactions</h2>
              <p>Your top-ups and internal wallet transfers.</p>
            </div>
            {historyTab === 'topups' && (
              <span>{history ? `${history.totalElements} top-ups` : '—'}</span>
            )}
          </div>
          <div className="history-tabs" role="group" aria-label="Transaction type">
            <button aria-pressed={historyTab === 'topups'} onClick={() => setHistoryTab('topups')}>
              Top-ups
            </button>
            <button
              aria-pressed={historyTab === 'transfers'}
              onClick={() => setHistoryTab('transfers')}
            >
              Transfers
            </button>
          </div>
          {historyTab === 'transfers' ? (
            <TransferHistoryPanel
              token={token}
              walletId={wallet?.walletId}
              refresh={reload}
              onExpired={onExpired}
              onSend={() => setTransferOpen(true)}
            />
          ) : (
            <>
              {historyError && (
                <Alert
                  className="dashboard-alert"
                  type="warning"
                  showIcon
                  title={historyError}
                  action={
                    <Button size="small" onClick={() => setReload((value) => value + 1)}>
                      Reload
                    </Button>
                  }
                />
              )}
              {!history ? (
                <Skeleton active paragraph={{ rows: 3 }} />
              ) : history.items.length === 0 ? (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="No top-ups yet. Your requests will appear here."
                />
              ) : (
                <>
                  <div className="history-table-scroll">
                    <table className="history-table">
                      <thead>
                        <tr>
                          <th>Transaction</th>
                          <th>Recipient / Date</th>
                          <th>Amount</th>
                          <th>Status</th>
                          <th>
                            <span className="sr-only">Details</span>
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        {history.items.map((item) => (
                          <tr key={item.transactionId}>
                            <td>
                              <div className="history-transaction">
                                <span className="transaction-icon">
                                  <ArrowDownOutlined />
                                </span>
                                <span>
                                  <strong>{item.description || 'Add funds to wallet'}</strong>
                                  <small title={item.reference}>{item.reference}</small>
                                </span>
                              </div>
                            </td>
                            <td>
                              <span className="recipient-name">{user.username}</span>
                              <span className="transaction-date">{dateTime(item.createdAt)}</span>
                            </td>
                            <td className="history-amount">{money(item.amount)}</td>
                            <td>
                              <Status status={item.status} />
                            </td>
                            <td>
                              <Button
                                type="text"
                                icon={<ArrowRightOutlined />}
                                aria-label={`Transaction details ${item.reference}`}
                                onClick={() => setSelected(item)}
                              />
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div className="history-pagination">
                    <Pagination
                      current={page + 1}
                      total={history.totalElements}
                      pageSize={5}
                      showSizeChanger={false}
                      onChange={(next) => {
                        setPage(next - 1)
                        setHistory(undefined)
                      }}
                    />
                  </div>
                </>
              )}
            </>
          )}
        </section>
        <section className="offers-section" aria-labelledby="offers-title">
          <div className="section-heading">
            <div>
              <span className="section-kicker">A LITTLE MORE JOY</span>
              <h2 id="offers-title">Just for you</h2>
            </div>
            <span className="subtle-label">
              <GiftOutlined /> Offers & discoveries
            </span>
          </div>
          <div className="offers-grid">
            <article className="offer-card offer-blue">
              <div>
                <span className="offer-tag">DISCOVER FLOWPAY</span>
                <h3>
                  Small wallet.
                  <br />
                  Big beginnings.
                </h3>
                <p>Add funds to your wallet and find your flow.</p>
                <button onClick={focusTopUp}>
                  Add funds now <ArrowRightOutlined />
                </button>
              </div>
              <div className="offer-illustration" aria-hidden="true">
                <CreditCardOutlined />
                <span>f↗</span>
              </div>
            </article>
            <article className="offer-card offer-orange">
              <div>
                <span className="offer-tag">COMING SOON</span>
                <h3>
                  Good things are coming.
                  <br />
                  Stay tuned with FlowPay.
                </h3>
                <p>New offers will appear here when available.</p>
                <span className="offer-note">No offers available right now</span>
              </div>
              <GiftOutlined className="gift-illustration" aria-hidden="true" />
            </article>
          </div>
        </section>
        <footer className="dashboard-footer">
          <span>© {new Date().getFullYear()} FlowPay</span>
          <span>
            <SafetyCertificateOutlined /> YOUR MONEY, IN FLOW.
          </span>
        </footer>
      </main>
      <Drawer
        title="Add funds to wallet"
        open={topUpOpen}
        onClose={() => setTopUpOpen(false)}
        size={480}
        className="topup-drawer"
        afterOpenChange={(open) => {
          if (open) amountInput.current?.focus({ preventScroll: true })
        }}
      >
        <section className="topup-panel dashboard-panel" id="topup" aria-labelledby="topup-title">
          <div className="panel-heading">
            <span className="panel-icon">
              <ArrowDownOutlined />
            </span>
            <div>
              <h2 id="topup-title">Add funds to wallet</h2>
              <p>Add funds and get ready for your next transaction.</p>
            </div>
          </div>
          <div className="mock-payment-note">
            <span /> Simulated top-up <span className="currency-label">USD</span>
          </div>
          {wallet?.status === 'FROZEN' && (
            <Alert
              className="dashboard-alert"
              type="warning"
              showIcon
              title="Your wallet is frozen. You can still view history and track pending requests."
            />
          )}
          {!wallet && (
            <p className="topup-instruction">
              {wallet === null
                ? 'Create your wallet before adding funds.'
                : 'Loading wallet details…'}
            </p>
          )}
          <Form<TopUpFields>
            form={form}
            layout="vertical"
            requiredMark={false}
            disabled={locked}
            onFinish={submit}
            initialValues={{
              amount: operation ? decimalAmount(operation.amount) : '',
              description: operation?.description || '',
            }}
          >
            <Form.Item
              name="amount"
              label="Top-up amount"
              rules={[
                { required: true, message: 'Please enter a top-up amount.' },
                {
                  validator: (_, value: string) =>
                    !value || centsFromInput(value)
                      ? Promise.resolve()
                      : Promise.reject(
                          new Error(
                            'Enter an amount greater than zero, with up to 2 decimal places and within the allowed limit.',
                          ),
                        ),
                },
              ]}
              extra="Enter USD, e.g. 10.50 or 10,50. Do not use thousands separators."
            >
              <Input
                ref={amountInput}
                className="amount-input"
                size="large"
                prefix="$"
                suffix="USD"
                placeholder="0.00"
                inputMode="decimal"
                autoComplete="off"
                maxLength={20}
              />
            </Form.Item>
            <div className="quick-amounts" aria-label="Choose a preset amount">
              {['10', '25', '50', '100'].map((amount) => (
                <Button
                  key={amount}
                  disabled={locked}
                  onClick={() => {
                    form.setFieldValue('amount', `${amount}.00`)
                    void form.validateFields(['amount']).catch(() => {})
                  }}
                >
                  ${amount}
                </Button>
              ))}
            </div>
            <Form.Item
              name="description"
              label="Note (optional)"
              rules={[{ max: 255, message: 'Note must be 255 characters or fewer.' }]}
            >
              <Input.TextArea
                placeholder="For example: Add funds to wallet"
                rows={2}
                maxLength={255}
                showCount
              />
            </Form.Item>
            <Button
              type="primary"
              size="large"
              htmlType="submit"
              block
              loading={sending}
              icon={<ArrowRightOutlined />}
              iconPlacement="end"
            >
              {sending ? 'Sending request' : 'Add funds'}
            </Button>
          </Form>
          {operationError && (
            <Alert
              className="dashboard-alert topup-feedback"
              type="warning"
              showIcon
              title={operationError}
            />
          )}
          {(active || operation) && (
            <div
              className={`topup-result result-${active?.status.toLowerCase() || 'pending'}`}
              aria-live="polite"
            >
              <div className="result-heading">
                <strong>
                  {active?.status === 'SUCCESS'
                    ? 'Funds added successfully'
                    : active?.status === 'FAILED'
                      ? 'Top-up failed'
                      : active
                        ? 'Your request is being processed'
                        : 'Confirming your request'}
                </strong>
                {active && <Status status={active.status} />}
              </div>
              {displayedAmount && (
                <div className="result-amount">
                  {money(displayedAmount)} <span>USD</span>
                </div>
              )}
              <p>
                {active?.status === 'SUCCESS'
                  ? 'Funds have been added to your wallet. Your balance is being refreshed.'
                  : active?.status === 'FAILED'
                    ? 'The request failed. This transaction did not change your wallet balance.'
                    : 'Funds have not yet been confirmed in your wallet. Status updates automatically every 5 seconds.'}
              </p>
              {active && <span className="result-reference">{active.reference}</span>}
              {active?.failureCode && <small>Reason code: {active.failureCode}</small>}
              {operation ? (
                <div className="result-actions">
                  <Button
                    size="small"
                    icon={<ReloadOutlined />}
                    disabled={refreshing || sending}
                    onClick={() => setReload((value) => value + 1)}
                  >
                    Check status
                  </Button>
                  {!active && (
                    <Button
                      size="small"
                      loading={sending}
                      onClick={() => void sendTopUp(operation)}
                    >
                      Resend same request
                    </Button>
                  )}
                </div>
              ) : (
                active && (
                  <Button
                    size="small"
                    onClick={() => {
                      setActive(null)
                      setOperationError('')
                      form.resetFields()
                      form.setFieldsValue({ amount: '', description: '' })
                      focusTopUp()
                    }}
                  >
                    Add funds again
                  </Button>
                )
              )}
            </div>
          )}
          <p className="topup-disclaimer">
            <ClockCircleOutlined /> Requests are processed automatically. This is a simulation; no
            real money is used.
          </p>
        </section>
      </Drawer>
      <Drawer
        title="Top-up details"
        open={!!selected}
        onClose={() => setSelected(null)}
        size={440}
        className="topup-detail"
      >
        {selected && (
          <>
            <span className="panel-icon">
              <ArrowDownOutlined />
            </span>
            <h2>
              {money(selected.amount)} <small>USD</small>
            </h2>
            <Status status={selected.status} />
            <dl className="detail-list">
              <div>
                <dt>Transaction reference</dt>
                <dd className="mono">{selected.reference}</dd>
              </div>
              <div>
                <dt>Recipient wallet ID</dt>
                <dd className="mono">{selected.destinationWalletId}</dd>
              </div>
              <div>
                <dt>Created</dt>
                <dd>{dateTime(selected.createdAt)}</dd>
              </div>
              <div>
                <dt>Completed</dt>
                <dd>{selected.completedAt ? dateTime(selected.completedAt) : 'Not completed'}</dd>
              </div>
              <div>
                <dt>Note</dt>
                <dd>{selected.description || 'None'}</dd>
              </div>
              {selected.failureCode && (
                <div>
                  <dt>Reason code</dt>
                  <dd>{selected.failureCode}</dd>
                </div>
              )}
            </dl>
            {selected.status === 'PENDING' && (
              <Alert
                type="info"
                showIcon
                title="Transaction pending"
                description="Status updates automatically. Funds have not yet been confirmed in your wallet."
              />
            )}
            <Button
              block
              icon={<ReloadOutlined />}
              disabled={refreshing}
              onClick={() => setReload((value) => value + 1)}
            >
              Refresh status
            </Button>
          </>
        )}
      </Drawer>
      <Drawer
        title={
          utility === 'receive'
            ? 'Receive money'
            : utility === 'notifications'
              ? 'Notifications'
              : utility === 'stats'
                ? 'Top-up statistics'
                : 'Profile details'
        }
        open={!!utility}
        onClose={() => {
          setUtility(null)
          setActiveNav('home')
        }}
        size={440}
        className="utility-drawer"
      >
        {utility === 'profile' && (
          <>
            <div className="profile-summary">
              <span className="user-avatar">{user.username.slice(0, 1).toUpperCase()}</span>
              <h2>{user.username}</h2>
              <p>{user.email}</p>
            </div>
            {wallet && (
              <dl className="detail-list">
                <div>
                  <dt>Wallet ID</dt>
                  <dd className="mono">{wallet.walletId}</dd>
                </div>
                <div>
                  <dt>Wallet created</dt>
                  <dd>{dateTime(wallet.createdAt)}</dd>
                </div>
                <div>
                  <dt>Currency</dt>
                  <dd>USD · US dollar</dd>
                </div>
              </dl>
            )}
            <Button block icon={<LogoutOutlined />} onClick={onSignOut}>
              Sign out
            </Button>
          </>
        )}
        {utility === 'receive' && (
          <>
            <span className="utility-icon">
              <ArrowDownOutlined />
            </span>
            <h2>Recipient wallet details</h2>
            <p className="utility-description">
              Share this wallet ID with another FlowPay user so they can send USD to you. No bank
              details are needed.
            </p>
            {wallet ? (
              <>
                <dl className="detail-list">
                  <div>
                    <dt>Wallet holder</dt>
                    <dd>{user.username}</dd>
                  </div>
                  <div>
                    <dt>Wallet ID</dt>
                    <dd className="mono">{wallet.walletId}</dd>
                  </div>
                </dl>
                <Button block icon={<CopyOutlined />} onClick={() => void copyWalletId()}>
                  {copied ? 'Wallet ID copied' : 'Copy wallet ID'}
                </Button>
              </>
            ) : (
              <Empty description="Create a wallet to view your wallet ID." />
            )}
          </>
        )}
        {utility === 'notifications' && (
          <>
            {historyError && <Alert type="warning" showIcon title={historyError} />}
            {!history ? (
              <Skeleton active />
            ) : pendingCount === 0 ? (
              <Empty description="No pending requests on the current transaction page." />
            ) : (
              history.items
                .filter((item) => item.status === 'PENDING')
                .map((item) => (
                  <button
                    key={item.reference}
                    className="notification-item"
                    onClick={() => {
                      setUtility(null)
                      setSelected(item)
                    }}
                  >
                    <ClockCircleOutlined />
                    <span>
                      <strong>Top-up request for {money(item.amount)}</strong>
                      <small>{dateTime(item.createdAt)} · Pending</small>
                    </span>
                    <ArrowRightOutlined />
                  </button>
                ))
            )}
          </>
        )}
        {utility === 'stats' && (
          <>
            <p className="utility-description">
              Summary of up to 5 top-ups on the top-up history page (page {page + 1}).
            </p>
            {historyError && <Alert type="warning" showIcon title={historyError} />}
            {!history ? (
              <Skeleton active />
            ) : (
              <>
                <div className="stat-card">
                  <span>Successful top-ups on this page</span>
                  <strong>
                    {balanceVisible ? money(pageTotal) : '••••••'} <small>USD</small>
                  </strong>
                </div>
                <dl className="detail-list">
                  <div>
                    <dt>Successful</dt>
                    <dd>{successfulItems.length} transactions</dd>
                  </div>
                  <div>
                    <dt>Pending</dt>
                    <dd>{pendingCount} transactions</dd>
                  </div>
                  <div>
                    <dt>Failed</dt>
                    <dd>
                      {history.items.filter((item) => item.status === 'FAILED').length} transactions
                    </dd>
                  </div>
                  <div>
                    <dt>Total top-ups in history</dt>
                    <dd>{history.totalElements}</dd>
                  </div>
                </dl>
              </>
            )}
          </>
        )}
      </Drawer>
      <Modal
        title={comingSoon}
        open={!!comingSoon}
        onCancel={() => setComingSoon('')}
        footer={
          <Button type="primary" onClick={() => setComingSoon('')}>
            Got it
          </Button>
        }
      >
        <p>
          The {comingSoon.toLowerCase()} feature is under development. You can add funds and track
          transactions on FlowPay now.
        </p>
      </Modal>
    </div>
  )
}
