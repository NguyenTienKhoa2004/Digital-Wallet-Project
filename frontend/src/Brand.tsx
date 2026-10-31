import { Link } from 'react-router-dom'

export default function Brand({ to = '/login' }: { to?: string }) {
  return <Link className="brand" to={to} aria-label="FlowPay — Trang chủ"><span className="brand-mark" aria-hidden="true"><i /><i /></span>flowpay<span className="brand-dot">.</span></Link>
}
