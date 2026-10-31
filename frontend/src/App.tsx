import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Form, Input } from 'antd'
import { ArrowRightOutlined, CheckOutlined, CheckCircleFilled, LockOutlined, MailOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons'
import { Link, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom'
import { ApiError, login, register } from './api'
import type { Credentials, LoginResult } from './api'
import Brand from './Brand'
import WalletPage from './WalletPage'
import { ThemeToggle } from './ThemeProvider'

type Session = LoginResult & { expiresAt: number }
type AuthFields = Credentials & { username?: string; confirmPassword?: string }

function Showcase() {
  return <aside className="showcase" aria-label="Giới thiệu FlowPay">
    <div className="showcase-grid" aria-hidden="true" />
    <div className="showcase-content">
      <span className="eyebrow"><span /> CHUYỂN ĐỘNG CÙNG BẠN</span>
      <h1>Dòng tiền thông suốt.<br /><span>Cuộc sống thảnh thơi.</span></h1>
      <p>Một nơi cho ví của bạn. Chuyển tiền, nạp tiền và theo dõi giao dịch thật dễ dàng.</p>
      <div className="flow-art" aria-hidden="true">
        <div className="orbit orbit-one" /><div className="orbit orbit-two" />
        <div className="floating-label label-top"><span className="mini-icon">↗</span> Chuyển tiền dễ dàng <CheckCircleFilled /></div>
        <div className="wallet-card">
          <div className="wallet-top"><span>flowpay.</span><span className="card-chip"><i /><i /><i /></span></div>
          <span className="wallet-caption">MỌI KẾT NỐI. MỘT CHẠM.</span>
          <div className="wallet-symbol">f<span>↗</span></div>
          <div className="wallet-bottom"><span>YOUR EVERYDAY WALLET</span><span>••••</span></div>
        </div>
        <div className="floating-label label-bottom"><span className="mini-icon"><CheckOutlined /></span><div>Luôn trong tầm tay<small>Theo dõi từng giao dịch</small></div></div>
      </div>
      <div className="showcase-benefits"><span><CheckOutlined /> Thao tác đơn giản</span><span><CheckOutlined /> Giao dịch minh bạch</span></div>
    </div>
    <div className="showcase-footer"><span>YOUR MONEY, IN FLOW.</span><span className="footer-line" /></div>
  </aside>
}

function AuthPage({ onLogin }: { onLogin: (result: LoginResult) => void }) {
  const location = useLocation()
  const navigate = useNavigate()
  const isRegister = location.pathname === '/register'
  const [form] = Form.useForm<AuthFields>()
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const submitting = useRef(false)
  const mounted = useRef(true)
  const success = location.state?.registered === true

  useEffect(() => {
    mounted.current = true
    document.title = `FlowPay · ${isRegister ? 'Đăng ký' : 'Đăng nhập'}`
    return () => { mounted.current = false }
  }, [isRegister])

  async function submit(values: AuthFields) {
    if (submitting.current) return
    submitting.current = true
    setPending(true)
    setError('')
    try {
      const credentials = { email: values.email.trim(), password: values.password }
      if (isRegister) {
        await register({ ...credentials, username: values.username!.trim() })
        if (mounted.current) navigate('/login', { replace: true, state: { registered: true, email: credentials.email } })
      } else {
        const result = await login(credentials)
        if (mounted.current) {
          onLogin(result)
          navigate('/account', { replace: true })
        }
      }
    } catch (cause) {
      if (mounted.current) {
        setError(cause instanceof Error ? cause.message : 'Đã xảy ra lỗi. Vui lòng thử lại.')
        if (cause instanceof ApiError && cause.code === 'EMAIL_ALREADY_EXISTS') {
          form.setFields([{ name: 'email', errors: ['Email này đã được đăng ký.'] }])
        }
      }
    } finally {
      submitting.current = false
      if (mounted.current) setPending(false)
    }
  }

  return <div className="auth-layout">
    <Showcase />
    <section className="auth-side">
      <header className="auth-top"><Brand /><div className="auth-header-actions"><span className="top-caption">Ví điện tử, thật đơn giản.</span><ThemeToggle /></div></header>
      <main className="form-container">
        <div className="step-label"><span /> {isRegister ? 'BẮT ĐẦU HÀNH TRÌNH' : 'CHÀO MỪNG TRỞ LẠI'}</div>
        <h2>{isRegister ? 'Tạo tài khoản' : 'Đăng nhập vào FlowPay'}</h2>
        <p className="form-description">{isRegister ? 'Một bước nhỏ để quản lý dòng tiền dễ dàng hơn.' : 'Tiếp tục hành trình tài chính của bạn.'}</p>
        <nav className="auth-switch" aria-label="Chọn đăng nhập hoặc đăng ký">
          <Link aria-current={!isRegister ? 'page' : undefined} to="/login">Đăng nhập</Link>
          <Link aria-current={isRegister ? 'page' : undefined} to="/register">Đăng ký</Link>
        </nav>
        {success && <Alert className="form-alert" type="success" showIcon title="Tạo tài khoản thành công!" description="Đăng nhập để bắt đầu sử dụng FlowPay." />}
        {location.state?.expired && <Alert className="form-alert" type="info" showIcon title="Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại." />}
        {error && <div role="alert"><Alert className="form-alert" type="error" showIcon title={error} /></div>}
        <Form<AuthFields> form={form} layout="vertical" size="large" onFinish={submit} disabled={pending}
          initialValues={{ email: location.state?.email || '' }} requiredMark={false} autoComplete="on">
          {isRegister && <Form.Item name="username" label="Tên hiển thị" rules={[
            { required: true, whitespace: true, message: 'Vui lòng nhập tên hiển thị.' },
            { max: 255, message: 'Tên hiển thị tối đa 255 ký tự.' },
          ]}><Input prefix={<UserOutlined />} placeholder="Tên của bạn" autoComplete="nickname" maxLength={255} /></Form.Item>}
          <Form.Item name="email" label="Email" rules={[
            { required: true, whitespace: true, message: 'Vui lòng nhập email.' },
            { type: 'email', transform: (value: string) => value.trim(), message: 'Vui lòng nhập email hợp lệ.' },
            { max: 320, message: 'Email tối đa 320 ký tự.' },
          ]}><Input prefix={<MailOutlined />} placeholder="ban@example.com" autoComplete="email" inputMode="email" maxLength={320} autoCapitalize="none" spellCheck={false} /></Form.Item>
          <Form.Item name="password" label="Mật khẩu" extra={isRegister ? 'Ít nhất 8 ký tự, tối đa 72 byte UTF-8.' : undefined} rules={[
            { required: true, whitespace: true, message: 'Vui lòng nhập mật khẩu.' },
            ...(isRegister ? [{ min: 8, message: 'Mật khẩu cần ít nhất 8 ký tự.' }] : []),
            { validator: (_, value: string) => !value || new TextEncoder().encode(value).length <= 72
              ? Promise.resolve() : Promise.reject(new Error('Mật khẩu vượt quá 72 byte UTF-8. Vui lòng rút ngắn.')) },
          ]}><Input.Password prefix={<LockOutlined />} placeholder={isRegister ? 'Tạo mật khẩu của bạn' : 'Nhập mật khẩu của bạn'} autoComplete={isRegister ? 'new-password' : 'current-password'} maxLength={72} /></Form.Item>
          {isRegister && <Form.Item name="confirmPassword" label="Xác nhận mật khẩu" dependencies={['password']} rules={[
            { required: true, message: 'Vui lòng nhập lại mật khẩu.' },
            ({ getFieldValue }) => ({ validator: (_, value: string) => !value || getFieldValue('password') === value
              ? Promise.resolve() : Promise.reject(new Error('Mật khẩu xác nhận chưa khớp.')) }),
          ]}><Input.Password prefix={<LockOutlined />} placeholder="Nhập lại mật khẩu" autoComplete="new-password" maxLength={72} /></Form.Item>}
          <Button className="submit-button" type="primary" htmlType="submit" block loading={pending} icon={!pending && <ArrowRightOutlined />} iconPlacement="end">
            {isRegister ? 'Tạo tài khoản' : 'Đăng nhập'}
          </Button>
        </Form>
        <p className="alternative">{isRegister ? 'Đã có tài khoản?' : 'Bạn mới biết đến FlowPay?'} <Link to={isRegister ? '/login' : '/register'}>{isRegister ? 'Đăng nhập' : 'Tạo tài khoản miễn phí'} <ArrowRightOutlined /></Link></p>
        <div className="security-note"><SafetyCertificateOutlined /><span>Mật khẩu được bảo vệ khi lưu trữ.<br />Thông tin đăng nhập chỉ dành cho bạn.</span></div>
      </main>
      <footer className="auth-footer"><span>© {new Date().getFullYear()} FlowPay</span><span><span className="language-dot" /> Tiếng Việt</span></footer>
    </section>
  </div>
}

export default function App() {
  // The backend issues access tokens only. Keep them in memory, never browser storage.
  const [session, setSession] = useState<Session | null>(null)
  const navigate = useNavigate()
  useEffect(() => {
    if (!session) return
    const timeout = window.setTimeout(() => {
      setSession(null)
      navigate('/login', { replace: true, state: { expired: true } })
    }, Math.max(0, session.expiresAt - Date.now()))
    return () => window.clearTimeout(timeout)
  }, [session, navigate])

  function signedIn(result: LoginResult) {
    setSession({ ...result, expiresAt: Date.now() + result.expiresIn * 1000 })
  }
  function signOut() {
    setSession(null)
    navigate('/login', { replace: true, state: null })
  }
  function sessionExpired() {
    setSession(null)
    navigate('/login', { replace: true, state: { expired: true } })
  }
  return <Routes>
    <Route path="/login" element={session ? <Navigate to="/account" replace /> : <AuthPage key="login" onLogin={signedIn} />} />
    <Route path="/register" element={session ? <Navigate to="/account" replace /> : <AuthPage key="register" onLogin={signedIn} />} />
    <Route path="/account" element={session ? <WalletPage key={session.user.userId} token={session.accessToken} user={session.user} onSignOut={signOut} onExpired={sessionExpired} /> : <Navigate to="/login" replace />} />
    <Route path="*" element={<Navigate to={session ? '/account' : '/login'} replace />} />
  </Routes>
}
