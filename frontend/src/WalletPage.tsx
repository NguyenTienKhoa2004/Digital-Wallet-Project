import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Drawer, Empty, Form, Input, Pagination, Skeleton, Tag, Modal } from 'antd'
import { AppstoreOutlined, BankOutlined, BellOutlined, CopyOutlined, CreditCardOutlined, EyeInvisibleOutlined, EyeOutlined, FileTextOutlined, GiftOutlined, HomeOutlined, MobileOutlined, QrcodeOutlined, SendOutlined, SwapOutlined, UserOutlined, ArrowDownOutlined, ArrowRightOutlined, CheckCircleOutlined, ClockCircleOutlined, CloseCircleOutlined, HistoryOutlined, LogoutOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined, WalletOutlined } from '@ant-design/icons'
import Brand from './Brand'
import { ThemeToggle } from './ThemeProvider'
import { ApiError, createTopUp, createWallet, getTopUp, getTopUpByKey, getTopUps, getWallet } from './api'
import type { TopUp, TopUpHistory, TopUpPayload, User, Wallet } from './api'
import './wallet.css'

interface Props { token: string; user: User; onSignOut: () => void; onExpired: () => void }
interface PendingOperation extends TopUpPayload { key: string }
interface TopUpFields { amount: string; description: string }
const maxAmount = 9223372036854775807n

function centsFromInput(value: string): string | null {
  const normalized = value.trim().replace(',', '.')
  if (!/^\d{1,17}(?:\.\d{1,2})?$/.test(normalized)) return null
  const [whole, decimal = ''] = normalized.split('.')
  const cents = BigInt(whole) * 100n + BigInt(decimal.padEnd(2, '0'))
  return cents > 0n && cents <= maxAmount ? cents.toString() : null
}

function decimalAmount(cents: string) {
  const amount = BigInt(cents)
  return `${amount / 100n}.${(amount % 100n).toString().padStart(2, '0')}`
}

function money(cents: string) {
  const amount = BigInt(cents)
  return `$${(amount / 100n).toLocaleString('en-US')}.${(amount % 100n).toString().padStart(2, '0')}`
}

function dateTime(value: string) {
  return new Intl.DateTimeFormat('vi-VN', { dateStyle: 'short', timeStyle: 'short' }).format(new Date(value))
}

function readOperation(storageKey: string): PendingOperation | null {
  try {
    const value = JSON.parse(sessionStorage.getItem(storageKey) || 'null')
    if (value && typeof value.key === 'string' && value.key.length > 0 && value.key.length <= 255
      && typeof value.amount === 'string' && /^\d{1,19}$/.test(value.amount)
      && BigInt(value.amount) > 0n && BigInt(value.amount) <= maxAmount
      && typeof value.description === 'string' && value.description.length <= 255) return value
  } catch { /* A new request is allowed only after successfully saving its recovery information. */ }
  return null
}

function errorMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Không thể tải dữ liệu. Vui lòng thử lại.'
}

function Status({ status }: { status: TopUp['status'] }) {
  const options = {
    PENDING: { color: 'gold', icon: <ClockCircleOutlined />, label: 'Đang xử lý' },
    SUCCESS: { color: 'success', icon: <CheckCircleOutlined />, label: 'Thành công' },
    FAILED: { color: 'error', icon: <CloseCircleOutlined />, label: 'Thất bại' },
  }
  const item = options[status]
  return <Tag color={item.color} icon={item.icon}>{item.label}</Tag>
}

export default function WalletPage({ token, user, onSignOut, onExpired }: Props) {
  const storageKey = `flowpay:pending-topup:${user.userId}`
  const [operation, setOperation] = useState<PendingOperation | null>(() => readOperation(storageKey))
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
  const [balanceVisible, setBalanceVisible] = useState(true)
  const [utility, setUtility] = useState<'receive' | 'notifications' | 'profile' | 'stats' | null>(null)
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
    document.title = 'FlowPay · Ví của tôi'
    return () => { alive.current = false }
  }, [])

  function clearOperation() {
    try { sessionStorage.removeItem(storageKey) } catch { /* Terminal results remain safe to look up again. */ }
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
        getWallet(token), getTopUps(token, page),
        operation ? getTopUpByKey(token, operation.key) : Promise.resolve(null),
        selected ? getTopUp(token, selected.reference) : Promise.resolve(null),
      ] as const)
      if (cancelled) return
      setRefreshing(false)
      if (results.some((result) => result.status === 'rejected' && result.reason instanceof ApiError && result.reason.status === 401)) {
        expire.current()
        return
      }
      const [walletResult, historyResult, operationResult, detailResult] = results
      if (walletResult.status === 'fulfilled') {
        setWallet(walletResult.value)
        setWalletError('')
      } else if (walletResult.reason instanceof ApiError && walletResult.reason.code === 'WALLET_NOT_FOUND') {
        setWallet(null)
        setWalletError('')
      } else setWalletError(errorMessage(walletResult.reason))

      if (historyResult.status === 'fulfilled') {
        setHistory(historyResult.value)
        setHistoryError('')
      } else setHistoryError(errorMessage(historyResult.reason))

      if (!busy.current && operationResult.status === 'fulfilled' && operationResult.value) {
        const result = operationResult.value
        setActive((previous) => previous?.reference === result.reference && previous.status !== 'PENDING' ? previous : result)
        setOperationError('')
        if (result.status !== 'PENDING') clearOperation()
      } else if (!busy.current && operationResult.status === 'rejected') setOperationError(errorMessage(operationResult.reason))

      if (detailResult.status === 'fulfilled' && detailResult.value) {
        const result = detailResult.value
        setSelected((previous) => previous?.reference === result.reference ? result : previous)
        if (selected?.status === 'PENDING' && result.status !== 'PENDING') setReload((value) => value + 1)
      } else if (detailResult.status === 'rejected') setHistoryError(errorMessage(detailResult.reason))

      const pendingHistory = historyResult.status === 'fulfilled' && historyResult.value.items.some((item) => item.status === 'PENDING')
      const pendingDetail = detailResult.status === 'fulfilled' && detailResult.value?.status === 'PENDING'
      const retryableReadError = results.some((result, index) => result.status === 'rejected'
        && !(index === 0 && result.reason instanceof ApiError && result.reason.code === 'WALLET_NOT_FOUND'))
      if (operation || pendingHistory || hadPendingHistory || pendingDetail || retryableReadError) {
        timer = window.setTimeout(sync, 5000)
      }
      hadPendingHistory = pendingHistory
    }
    void sync()
    return () => { cancelled = true; window.clearTimeout(timer) }
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
      else if (error instanceof ApiError && error.code === 'WALLET_ALREADY_EXISTS') setReload((value) => value + 1)
      else {
        setWalletError(`${errorMessage(error)} Bạn có thể tải lại thông tin ví trước khi thử lại.`)
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
      setActive((previous) => previous?.reference === result.reference && previous.status !== 'PENDING' ? previous : result)
      setPage(0)
      if (result.status !== 'PENDING') clearOperation()
    } catch (error) {
      if (!alive.current) return
      if (error instanceof ApiError && error.status === 401) { expire.current(); return }
      setOperationError(errorMessage(error))
      if (error instanceof ApiError && ['INVALID_AMOUNT', 'VALIDATION_FAILED', 'WALLET_NOT_ACTIVE', 'WALLET_NOT_FOUND', 'FORBIDDEN'].includes(error.code)) clearOperation()
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
    const pending = { key: crypto.randomUUID(), amount, description: values.description?.trim() || '' }
    // Save before POST so a timeout/reload can be recovered with the same key and payload.
    try { sessionStorage.setItem(storageKey, JSON.stringify(pending)) }
    catch {
      setOperationError('Trình duyệt không thể lưu thông tin khôi phục yêu cầu. Vui lòng cho phép lưu trữ phiên rồi thử lại.')
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
  const pageTotal = successfulItems.reduce((total, item) => total + BigInt(item.amount), 0n).toString()

  async function copyWalletId() {
    if (!wallet) return
    try { await navigator.clipboard.writeText(wallet.walletId); setCopied(true) }
    catch { setCopied(false) }
  }

  function navigateTo(section: string) {
    setActiveNav(section)
    if (section === 'profile') setUtility('profile')
    else if (section === 'stats') setUtility('stats')
    else document.getElementById(section === 'history' ? 'topup-history' : 'wallet-overview')?.scrollIntoView({ behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth', block: 'start' })
  }

  return <div className="wallet-app">
    <div className="ambient-light" aria-hidden="true" />
    <header className="wallet-topbar">
      <Brand to="/account" />
      <span className="topbar-caption">YOUR MONEY, IN FLOW.</span>
      <div className="topbar-actions"><span className="session-badge"><i /> Không gian của bạn</span><ThemeToggle /><button className="header-avatar" aria-label="Mở thông tin cá nhân" onClick={() => setUtility('profile')}>{user.username.slice(0, 1).toUpperCase()}</button></div>
    </header>
    <main className="wallet-main" id="wallet-overview">
      <header className="dashboard-header">
        <div><div className="step-label"><span /> MỖI NGÀY, MỘT NHỊP SỐNG MỚI</div><h1>Xin chào, <span>{user.username}.</span></h1><p>Mọi kết nối. Một chiếc ví.</p></div>
        <div className="dashboard-header-actions"><Button className="refresh-button" icon={<ReloadOutlined spin={refreshing} />} aria-label="Làm mới thông tin ví" onClick={() => setReload((value) => value + 1)} disabled={refreshing || sending} /><button className="notification-button" aria-label={pendingCount ? `Thông báo: ${pendingCount} giao dịch đang xử lý trên trang này` : 'Mở thông báo'} onClick={() => setUtility('notifications')}><BellOutlined />{pendingCount > 0 && <i />}</button></div>
      </header>
      {walletError && <Alert className="dashboard-alert" type="error" showIcon title={walletError} action={<Button size="small" onClick={() => setReload((value) => value + 1)}>Tải lại ví</Button>} />}
      <section className="wallet-overview" aria-label="Thông tin ví">
        {wallet === undefined ? <div className="dashboard-panel wallet-loading"><Skeleton active paragraph={{ rows: 5 }} /></div> : wallet === null ? <div className="create-wallet-card dashboard-panel">
          <span className="create-wallet-icon"><WalletOutlined /></span><span className="step-label">BẮT ĐẦU VỚI FLOWPAY</span><h2>Một chiếc ví.<br />Vạn điều sẵn sàng.</h2><p>Tạo ví USD để nạp tiền và theo dõi số dư của bạn.</p><Button type="primary" size="large" icon={<PlusOutlined />} loading={creatingWallet} onClick={() => void openWallet()}>Tạo ví của tôi</Button>
        </div> : <div className="balance-card">
          <div className="balance-top"><span><WalletOutlined /> VÍ FLOWPAY <span className="card-currency">USD</span></span><span className={`wallet-state ${wallet.status === 'ACTIVE' ? '' : 'wallet-frozen'}`}><i />{wallet.status === 'ACTIVE' ? 'Đang hoạt động' : 'Đang khóa'}</span></div>
          <div className="balance-label">Số dư khả dụng{walletError ? ' · Chưa cập nhật' : ''}<button className="balance-visibility" onClick={() => setBalanceVisible((value) => !value)} aria-label={balanceVisible ? 'Ẩn số dư' : 'Hiện số dư'} aria-pressed={!balanceVisible}>{balanceVisible ? <EyeOutlined /> : <EyeInvisibleOutlined />}</button></div>
          <div className="balance-value" aria-live="polite">{balanceVisible ? money(wallet.balance) : '••••••'} <span>USD</span></div>
          <div className="balance-bottom"><span>CHỦ VÍ<strong>{user.username}</strong></span><button className="funding-source" onClick={focusTopUp}><span className="source-icon"><BankOutlined /></span><span>Nguồn tiền<strong>Ví FlowPay · USD</strong></span><ArrowRightOutlined /></button></div>
          <div className="balance-watermark" aria-hidden="true">f<span>↗</span></div>
        </div>}
        <aside className="flow-companion dashboard-panel"><span className="companion-eyebrow"><span /> THE FLOW STATE</span><div className="flow-orb" aria-hidden="true"><span /><WalletOutlined /></div><h2>Nhẹ một chạm.<br /><em>Trọn nhịp sống.</em></h2><p>Nạp tiền, theo dõi số dư.<br />Mọi thứ trong tầm tay bạn.</p><span className="companion-footer"><SafetyCertificateOutlined /> Rõ ràng trong từng giao dịch</span></aside>
      </section>
      <section className="quick-actions dashboard-panel" aria-label="Thao tác nhanh">
        <button onClick={() => setComingSoon('Chuyển tiền')}><span className="action-icon"><SendOutlined /></span><span>Chuyển tiền</span><ArrowRightOutlined className="action-arrow" /></button>
        <button onClick={() => { setCopied(false); setUtility('receive') }}><span className="action-icon"><ArrowDownOutlined /></span><span>Nhận tiền</span><ArrowRightOutlined className="action-arrow" /></button>
        <button onClick={() => setComingSoon('Quét QR')}><span className="action-icon"><QrcodeOutlined /></span><span>Quét QR</span><ArrowRightOutlined className="action-arrow" /></button>
        <button className="topup-action" onClick={focusTopUp}><span className="action-icon"><PlusOutlined /></span><span>Nạp tiền</span><ArrowRightOutlined className="action-arrow" /></button>
      </section>
      {(operation || active) && <button className="pending-banner" onClick={focusTopUp}><ClockCircleOutlined /><span>{active?.status === 'SUCCESS' ? 'Nạp tiền thành công' : active?.status === 'FAILED' ? 'Nạp tiền thất bại' : 'Bạn có một yêu cầu nạp tiền đang được theo dõi'}<small>Xem chi tiết và trạng thái yêu cầu</small></span><ArrowRightOutlined /></button>}
      <section className="services-section" aria-labelledby="services-title">
        <div className="section-heading"><div><span className="section-kicker">CHO NGÀY THÊM DỄ DÀNG</span><h2 id="services-title">Dịch vụ thường dùng</h2></div><span className="subtle-label">Tiện ích mỗi ngày</span></div>
        <div className="service-grid">
          {[{ title: 'Thanh toán hóa đơn', subtitle: 'Điện, nước & internet', icon: <FileTextOutlined />, tone: 'blue' }, { title: 'Nạp điện thoại', subtitle: 'Kết nối không gián đoạn', icon: <MobileOutlined />, tone: 'orange' }, { title: 'Ngân hàng', subtitle: 'Quản lý nguồn tiền', icon: <BankOutlined />, tone: 'cyan' }].map((service) => <button className={`service-card dashboard-panel service-${service.tone}`} key={service.title} onClick={() => setComingSoon(service.title)}><span className="service-icon">{service.icon}</span><span><strong>{service.title}</strong><small>{service.subtitle}</small></span><ArrowRightOutlined /></button>)}
        </div>
      </section>
      <section className="history-panel dashboard-panel" id="topup-history" aria-labelledby="history-title">
        <div className="history-heading"><div><h2 id="history-title">Giao dịch gần đây</h2><p>Lịch sử nạp tiền và trạng thái mới nhất của bạn.</p></div><span>{history ? `${history.totalElements} giao dịch` : '—'}</span></div>
        {historyError && <Alert className="dashboard-alert" type="warning" showIcon title={historyError} action={<Button size="small" onClick={() => setReload((value) => value + 1)}>Tải lại</Button>} />}
        {!history ? <Skeleton active paragraph={{ rows: 3 }} /> : history.items.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Chưa có giao dịch nạp tiền. Các yêu cầu của bạn sẽ xuất hiện tại đây." /> : <>
          <div className="history-table-scroll"><table className="history-table"><thead><tr><th>Giao dịch</th><th>Ví nhận / Thời gian</th><th>Số tiền</th><th>Trạng thái</th><th><span className="sr-only">Chi tiết</span></th></tr></thead><tbody>{history.items.map((item) => <tr key={item.transactionId}>
            <td><div className="history-transaction"><span className="transaction-icon"><ArrowDownOutlined /></span><span><strong>{item.description || 'Nạp tiền vào ví'}</strong><small title={item.reference}>{item.reference}</small></span></div></td>
            <td><span className="recipient-name">{user.username}</span><span className="transaction-date">{dateTime(item.createdAt)}</span></td><td className="history-amount">{money(item.amount)}</td><td><Status status={item.status} /></td><td><Button type="text" icon={<ArrowRightOutlined />} aria-label={`Chi tiết giao dịch ${item.reference}`} onClick={() => setSelected(item)} /></td>
          </tr>)}</tbody></table></div>
          <div className="history-pagination"><Pagination current={page + 1} total={history.totalElements} pageSize={5} showSizeChanger={false} onChange={(next) => { setPage(next - 1); setHistory(undefined) }} /></div>
        </>}
      </section>
      <section className="offers-section" aria-labelledby="offers-title">
        <div className="section-heading"><div><span className="section-kicker">THÊM MỘT CHÚT NIỀM VUI</span><h2 id="offers-title">Dành riêng cho bạn</h2></div><span className="subtle-label"><GiftOutlined /> Ưu đãi & khám phá</span></div>
        <div className="offers-grid"><article className="offer-card offer-blue"><div><span className="offer-tag">KHÁM PHÁ FLOWPAY</span><h3>Chiếc ví nhỏ.<br />Khởi đầu lớn.</h3><p>Nạp tiền vào ví và bắt đầu nhịp sống của bạn.</p><button onClick={focusTopUp}>Nạp tiền ngay <ArrowRightOutlined /></button></div><div className="offer-illustration" aria-hidden="true"><CreditCardOutlined /><span>f↗</span></div></article><article className="offer-card offer-orange"><div><span className="offer-tag">SẮP RA MẮT</span><h3>Điều hay đang đến.<br />Đón chờ cùng FlowPay.</h3><p>Ưu đãi mới sẽ xuất hiện tại đây khi có chương trình.</p><span className="offer-note">Hiện chưa có ưu đãi đang diễn ra</span></div><GiftOutlined className="gift-illustration" aria-hidden="true" /></article></div>
      </section>
      <footer className="dashboard-footer"><span>© {new Date().getFullYear()} FlowPay</span><span><SafetyCertificateOutlined /> YOUR MONEY, IN FLOW.</span></footer>
    </main>
    <nav className="bottom-nav" aria-label="Điều hướng chính">{[{ key: 'home', label: 'Trang chủ', icon: <HomeOutlined /> }, { key: 'history', label: 'Giao dịch', icon: <SwapOutlined /> }, { key: 'stats', label: 'Thống kê', icon: <AppstoreOutlined /> }, { key: 'profile', label: 'Cá nhân', icon: <UserOutlined /> }].map((item) => <button key={item.key} className={activeNav === item.key ? 'nav-active' : ''} aria-current={activeNav === item.key ? 'page' : undefined} onClick={() => navigateTo(item.key)}>{item.icon}<span>{item.label}</span></button>)}</nav>
    <Drawer title="Nạp tiền vào ví" open={topUpOpen} onClose={() => setTopUpOpen(false)} size={480} className="topup-drawer" afterOpenChange={(open) => { if (open) amountInput.current?.focus({ preventScroll: true }) }}>
        <section className="topup-panel dashboard-panel" id="topup" aria-labelledby="topup-title">
          <div className="panel-heading"><span className="panel-icon"><ArrowDownOutlined /></span><div><h2 id="topup-title">Nạp tiền vào ví</h2><p>Thêm số dư, sẵn sàng cho giao dịch tiếp theo.</p></div></div>
          <div className="mock-payment-note"><span /> Nạp tiền mô phỏng <span className="currency-label">USD</span></div>
          {wallet?.status === 'FROZEN' && <Alert className="dashboard-alert" type="warning" showIcon title="Ví đang bị khóa. Bạn vẫn có thể xem lịch sử và theo dõi yêu cầu đang xử lý." />}
          {!wallet && <p className="topup-instruction">{wallet === null ? 'Tạo ví của bạn trước khi nạp tiền.' : 'Đang tải thông tin ví…'}</p>}
          <Form<TopUpFields> form={form} layout="vertical" requiredMark={false} disabled={locked} onFinish={submit} initialValues={{ amount: operation ? decimalAmount(operation.amount) : '', description: operation?.description || '' }}>
            <Form.Item name="amount" label="Số tiền nạp" rules={[{ required: true, message: 'Vui lòng nhập số tiền nạp.' }, { validator: (_, value: string) => !value || centsFromInput(value) ? Promise.resolve() : Promise.reject(new Error('Nhập số tiền lớn hơn 0, tối đa 2 chữ số thập phân và trong giới hạn cho phép.')) }]} extra="Nhập USD, ví dụ 10.50 hoặc 10,50. Không dùng dấu phân cách hàng nghìn.">
              <Input ref={amountInput} className="amount-input" size="large" prefix="$" suffix="USD" placeholder="0.00" inputMode="decimal" autoComplete="off" maxLength={20} />
            </Form.Item>
            <div className="quick-amounts" aria-label="Chọn nhanh số tiền">{['10', '25', '50', '100'].map((amount) => <Button key={amount} disabled={locked} onClick={() => { form.setFieldValue('amount', `${amount}.00`); void form.validateFields(['amount']).catch(() => {}) }}>${amount}</Button>)}</div>
            <Form.Item name="description" label="Ghi chú (không bắt buộc)" rules={[{ max: 255, message: 'Ghi chú tối đa 255 ký tự.' }]}><Input.TextArea placeholder="Ví dụ: Nạp tiền vào ví" rows={2} maxLength={255} showCount /></Form.Item>
            <Button type="primary" size="large" htmlType="submit" block loading={sending} icon={<ArrowRightOutlined />} iconPlacement="end">{sending ? 'Đang gửi yêu cầu' : 'Nạp tiền'}</Button>
          </Form>
          {operationError && <Alert className="dashboard-alert topup-feedback" type="warning" showIcon title={operationError} />}
          {(active || operation) && <div className={`topup-result result-${active?.status.toLowerCase() || 'pending'}`} aria-live="polite">
            <div className="result-heading"><strong>{active?.status === 'SUCCESS' ? 'Nạp tiền thành công' : active?.status === 'FAILED' ? 'Nạp tiền thất bại' : active ? 'Yêu cầu đang được xử lý' : 'Đang xác nhận yêu cầu'}</strong>{active && <Status status={active.status} />}</div>
            {displayedAmount && <div className="result-amount">{money(displayedAmount)} <span>USD</span></div>}
            <p>{active?.status === 'SUCCESS' ? 'Tiền đã được cộng vào ví. Số dư đang được cập nhật từ hệ thống.' : active?.status === 'FAILED' ? 'Yêu cầu không thành công. Giao dịch này không làm thay đổi số dư ví.' : 'Chưa xác nhận tiền đã vào ví. Trạng thái được tự động cập nhật mỗi 5 giây.'}</p>
            {active && <span className="result-reference">{active.reference}</span>}
            {active?.failureCode && <small>Mã lý do: {active.failureCode}</small>}
            {operation ? <div className="result-actions"><Button size="small" icon={<ReloadOutlined />} disabled={refreshing || sending} onClick={() => setReload((value) => value + 1)}>Kiểm tra trạng thái</Button>{!active && <Button size="small" loading={sending} onClick={() => void sendTopUp(operation)}>Gửi lại cùng yêu cầu</Button>}</div> : active && <Button size="small" onClick={() => { setActive(null); setOperationError(''); form.resetFields(); form.setFieldsValue({ amount: '', description: '' }); focusTopUp() }}>Nạp lần khác</Button>}
          </div>}
          <p className="topup-disclaimer"><ClockCircleOutlined /> Yêu cầu được xử lý tự động. Đây là môi trường mô phỏng, không sử dụng tiền thật.</p>
        </section>
    </Drawer>
    <Drawer title="Chi tiết nạp tiền" open={!!selected} onClose={() => setSelected(null)} size={440} className="topup-detail">
      {selected && <><span className="panel-icon"><ArrowDownOutlined /></span><h2>{money(selected.amount)} <small>USD</small></h2><Status status={selected.status} />
        <dl className="detail-list"><div><dt>Mã giao dịch</dt><dd className="mono">{selected.reference}</dd></div><div><dt>Mã ví nhận</dt><dd className="mono">{selected.destinationWalletId}</dd></div><div><dt>Ngày tạo</dt><dd>{dateTime(selected.createdAt)}</dd></div><div><dt>Hoàn tất</dt><dd>{selected.completedAt ? dateTime(selected.completedAt) : 'Chưa hoàn tất'}</dd></div><div><dt>Ghi chú</dt><dd>{selected.description || 'Không có'}</dd></div>{selected.failureCode && <div><dt>Mã lý do</dt><dd>{selected.failureCode}</dd></div>}</dl>
        {selected.status === 'PENDING' && <Alert type="info" showIcon title="Giao dịch đang xử lý" description="Trạng thái được tự động cập nhật. Chưa thể xác nhận tiền đã vào ví." />}
        <Button block icon={<ReloadOutlined />} disabled={refreshing} onClick={() => setReload((value) => value + 1)}>Cập nhật trạng thái</Button>
      </>}
    </Drawer>
    <Drawer title={utility === 'receive' ? 'Nhận tiền' : utility === 'notifications' ? 'Thông báo' : utility === 'stats' ? 'Thống kê nạp tiền' : 'Thông tin cá nhân'} open={!!utility} onClose={() => { setUtility(null); setActiveNav('home') }} size={440} className="utility-drawer">
      {utility === 'profile' && <><div className="profile-summary"><span className="user-avatar">{user.username.slice(0, 1).toUpperCase()}</span><h2>{user.username}</h2><p>{user.email}</p></div>{wallet && <dl className="detail-list"><div><dt>Mã ví</dt><dd className="mono">{wallet.walletId}</dd></div><div><dt>Ngày tạo ví</dt><dd>{dateTime(wallet.createdAt)}</dd></div><div><dt>Loại tiền</dt><dd>USD · Đô la Mỹ</dd></div></dl>}<Button block icon={<LogoutOutlined />} onClick={onSignOut}>Đăng xuất</Button></>}
      {utility === 'receive' && <><span className="utility-icon"><ArrowDownOutlined /></span><h2>Thông tin ví nhận</h2><p className="utility-description">Mã định danh ví FlowPay của bạn. Tính năng chuyển và nhận tiền đang được phát triển.</p>{wallet ? <><dl className="detail-list"><div><dt>Chủ ví</dt><dd>{user.username}</dd></div><div><dt>Mã ví</dt><dd className="mono">{wallet.walletId}</dd></div></dl><Button block icon={<CopyOutlined />} onClick={() => void copyWalletId()}>{copied ? 'Đã sao chép mã ví' : 'Sao chép mã ví'}</Button></> : <Empty description="Tạo ví để xem mã ví của bạn." />}</>}
      {utility === 'notifications' && <>{historyError && <Alert type="warning" showIcon title={historyError} />}{!history ? <Skeleton active /> : pendingCount === 0 ? <Empty description="Không có yêu cầu đang xử lý trên trang giao dịch hiện tại." /> : history.items.filter((item) => item.status === 'PENDING').map((item) => <button key={item.reference} className="notification-item" onClick={() => { setUtility(null); setSelected(item) }}><ClockCircleOutlined /><span><strong>Yêu cầu nạp {money(item.amount)}</strong><small>{dateTime(item.createdAt)} · Đang xử lý</small></span><ArrowRightOutlined /></button>)}</>}
      {utility === 'stats' && <><p className="utility-description">Tổng hợp từ tối đa 5 giao dịch trên trang lịch sử hiện tại (trang {page + 1}).</p>{historyError && <Alert type="warning" showIcon title={historyError} />}{!history ? <Skeleton active /> : <><div className="stat-card"><span>Nạp tiền thành công trên trang này</span><strong>{balanceVisible ? money(pageTotal) : '••••••'} <small>USD</small></strong></div><dl className="detail-list"><div><dt>Thành công</dt><dd>{successfulItems.length} giao dịch</dd></div><div><dt>Đang xử lý</dt><dd>{pendingCount} giao dịch</dd></div><div><dt>Thất bại</dt><dd>{history.items.filter((item) => item.status === 'FAILED').length} giao dịch</dd></div><div><dt>Tổng giao dịch trong lịch sử</dt><dd>{history.totalElements}</dd></div></dl></>}</>}
    </Drawer>
    <Modal title={comingSoon} open={!!comingSoon} onCancel={() => setComingSoon('')} footer={<Button type="primary" onClick={() => setComingSoon('')}>Đã hiểu</Button>}><p>Tính năng {comingSoon.toLowerCase()} đang được phát triển. Bạn có thể nạp tiền và theo dõi giao dịch ngay trên FlowPay.</p></Modal>
  </div>
}
