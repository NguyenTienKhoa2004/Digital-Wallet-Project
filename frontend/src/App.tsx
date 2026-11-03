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
  return <aside className="showcase" aria-label="About FlowPay">
    <div className="showcase-grid" aria-hidden="true" />
    <header className="showcase-brand"><Brand /></header>
    <div className="showcase-content">
      <span className="eyebrow"><span /> MOVING WITH YOU</span>
      <h1>Money in flow.<br /><span>Life at ease.</span></h1>
      <p>One place for your wallet. Send money, add funds, and track transactions with ease.</p>
      <div className="flow-art" aria-hidden="true">
        <div className="orbit orbit-one" /><div className="orbit orbit-two" />
        <div className="floating-label label-top"><span className="mini-icon">↗</span> Easy transfers <CheckCircleFilled /></div>
        <div className="wallet-card">
          <div className="wallet-top"><span>flowpay.</span><span className="card-chip"><i /><i /><i /></span></div>
          <span className="wallet-caption">EVERY CONNECTION. ONE TAP.</span>
          <div className="wallet-symbol">f<span>↗</span></div>
          <div className="wallet-bottom"><span>YOUR EVERYDAY WALLET</span><span>••••</span></div>
        </div>
        <div className="floating-label label-bottom"><span className="mini-icon"><CheckOutlined /></span><div>Always within reach<small>Track every transaction</small></div></div>
      </div>
      <div className="showcase-benefits"><span><CheckOutlined /> Simple to use</span><span><CheckOutlined /> Clear transactions</span></div>
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
    document.title = `FlowPay · ${isRegister ? 'Sign up' : 'Sign in'}`
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
        setError(cause instanceof Error ? cause.message : 'Something went wrong. Please try again.')
        if (cause instanceof ApiError && cause.code === 'EMAIL_ALREADY_EXISTS') {
          form.setFields([{ name: 'email', errors: ['This email is already registered.'] }])
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
      <header className="auth-top">
        <div className="auth-mobile-brand"><Brand /></div>
        <div className="auth-header-actions">
          <span className="top-caption">Your wallet, made simple.</span>
          <ThemeToggle />
        </div>
      </header>
      <main className="form-container">
        <div className="step-label"><span /> {isRegister ? 'START YOUR JOURNEY' : 'WELCOME BACK'}</div>
        <h2>{isRegister ? 'Create account' : 'Sign in to FlowPay'}</h2>
        <p className="form-description">{isRegister ? 'One small step toward easier money management.' : 'Continue your financial journey.'}</p>
        <nav className="auth-switch" aria-label="Sign in or create an account">
          <Link aria-current={!isRegister ? 'page' : undefined} to="/login">Sign in</Link>
          <Link aria-current={isRegister ? 'page' : undefined} to="/register">Sign up</Link>
        </nav>
        {success && <Alert className="form-alert" type="success" showIcon title="Account created successfully!" description="Sign in to get started with FlowPay." />}
        {location.state?.expired && <Alert className="form-alert" type="info" showIcon title="Your session has expired. Please sign in again." />}
        {error && <div role="alert"><Alert className="form-alert" type="error" showIcon title={error} /></div>}
        <Form<AuthFields> className="auth-form" form={form} layout="vertical" variant="underlined" size="large" onFinish={submit} disabled={pending}
          initialValues={{ email: location.state?.email || '' }} requiredMark={false} autoComplete="on">
          {isRegister && <Form.Item name="username" label="Display name" rules={[
            { required: true, whitespace: true, message: 'Please enter your display name.' },
            { max: 255, message: 'Display name must be 255 characters or fewer.' },
          ]}><Input prefix={<UserOutlined />} placeholder="Display name" autoComplete="nickname" maxLength={255} /></Form.Item>}
          <Form.Item name="email" label="Email" rules={[
            { required: true, whitespace: true, message: 'Please enter your email address.' },
            { type: 'email', transform: (value: string) => value.trim(), message: 'Please enter a valid email address.' },
            { max: 320, message: 'Email must be 320 characters or fewer.' },
          ]}><Input prefix={<MailOutlined />} placeholder="you@example.com" autoComplete="email" inputMode="email" maxLength={320} autoCapitalize="none" spellCheck={false} /></Form.Item>
          <Form.Item name="password" label="Password" extra={isRegister ? 'At least 8 characters, up to 72 UTF-8 bytes.' : undefined} rules={[
            { required: true, whitespace: true, message: 'Please enter your password.' },
            ...(isRegister ? [{ min: 8, message: 'Password must be at least 8 characters.' }] : []),
            { validator: (_, value: string) => !value || new TextEncoder().encode(value).length <= 72
              ? Promise.resolve() : Promise.reject(new Error('Password exceeds 72 UTF-8 bytes. Please use a shorter password.')) },
          ]}><Input.Password prefix={<LockOutlined />} placeholder={isRegister ? 'Create your password' : 'Enter your password'} autoComplete={isRegister ? 'new-password' : 'current-password'} maxLength={72} /></Form.Item>
          {isRegister && <Form.Item name="confirmPassword" label="Confirm password" dependencies={['password']} rules={[
            { required: true, message: 'Please confirm your password.' },
            ({ getFieldValue }) => ({ validator: (_, value: string) => !value || getFieldValue('password') === value
              ? Promise.resolve() : Promise.reject(new Error('Passwords do not match.')) }),
          ]}><Input.Password prefix={<LockOutlined />} placeholder="Re-enter your password" autoComplete="new-password" maxLength={72} /></Form.Item>}
          <Button className="submit-button" type="primary" htmlType="submit" block loading={pending} icon={!pending && <ArrowRightOutlined />} iconPlacement="end">
            {isRegister ? 'Create account' : 'Sign in'}
          </Button>
        </Form>
        <p className="alternative">{isRegister ? 'Already have an account?' : 'New to FlowPay?'} <Link to={isRegister ? '/login' : '/register'}>{isRegister ? 'Sign in' : 'Create a free account'} <ArrowRightOutlined /></Link></p>
        <div className="security-note"><SafetyCertificateOutlined /><span>Your password is securely stored.<br />Your sign-in details are yours alone.</span></div>
      </main>
      <footer className="auth-footer"><span>© {new Date().getFullYear()} FlowPay</span><span><span className="language-dot" /> English</span></footer>
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
