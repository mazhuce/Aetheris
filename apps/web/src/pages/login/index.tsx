import { useState } from 'react'
import { Alert, App, Button, Card, Form, Input, Typography } from 'antd'
import { ArrowRightOutlined, LockOutlined, UserOutlined } from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import { login, logout } from '@/api/auth'
import { logoutIntent } from '@/auth/authState'
import { useAuthSession } from '@/hooks/useAuthSession'
import { useHomePath } from '@/contexts/PreferencesContext'
import BrandMark from '@/components/BrandMark'

interface LoginForm {
  username: string
  password: string
}

export default function LoginPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const location = useLocation()
  const homePath = useHomePath()
  const [loading, setLoading] = useState(false)
  const [retryingLogout, setRetryingLogout] = useState(false)
  useAuthSession()
  const revocation = logoutIntent()?.revocation
  const logoutUnconfirmed = revocation !== 'confirmed' && (revocation === 'pending' || revocation === 'unconfirmed'
    || (location.state as { logoutUnconfirmed?: boolean } | null)?.logoutUnconfirmed === true)

  const retryLogout = async () => {
    setRetryingLogout(true)
    try { await logout() } catch { /* Persisted state keeps the unconfirmed warning visible. */ }
    finally { setRetryingLogout(false) }
  }

  const handleSubmit = async (values: LoginForm) => {
    setLoading(true)
    try {
      await login(values.username, values.password)
      message.success('登录成功').then(() => undefined, () => undefined)
      await navigate(homePath, { replace: true })
    } catch (error) {
      // 原实现把所有异常都报成「用户名或密码错误」，导致网络故障、服务未启动、
      // 账号被锁定等情况全部显示为凭据错误，排查时被误导。这里按实际状态码区分。
      message.error(describeLoginFailure(error))
    } finally {
      setLoading(false)
    }
  }

  /** 把请求异常翻译成可操作的提示，而不是一律归咎于密码错误。 */
  function describeLoginFailure(error: unknown): string {
    if ((error as { code?: string })?.code === 'SESSION_UNAVAILABLE') return (error as Error).message
    const response = (error as { response?: { status?: number; data?: { message?: string } } })
      ?.response
    if (!response) {
      // 没有 response 说明请求根本没到服务端：服务未启动、端口不通或被代理拦截。
      return '无法连接服务器，请确认服务已启动'
    }
    const detail = response.data?.message
    switch (response.status) {
      case 401:
        return detail || '用户名或密码错误'
      case 423:
      case 429:
        return detail || '登录尝试过于频繁，账号已临时锁定，请稍后再试'
      case 403:
        // 403 的成因不止账号停用：旧版 JWT 不含 role 声明时会被网关当作 viewer，
        // 访问管理接口即被拒。因此提示里要给出「重新登录」这条可操作路径。
        return detail || '登录凭据无效或权限不足，请清除浏览器缓存后重试'
      default:
        return detail || `登录失败（HTTP ${response.status}）`
    }
  }

  return (
    <main className="login-page">
      <section className="login-story">
        <div className="login-brand">
          <BrandMark />
          <strong>Aetheris</strong>
        </div>
        <div className="login-copy">
          <h1>让数据有序流动，<br /><em>让价值清晰发生。</em></h1>
          <p>连接数据、沉淀标准、编排任务、发布服务，在统一工作空间里完成从数据到决策的闭环。</p>
          <div className="login-proof">
            <span><small>01</small><strong>统一数据资产</strong><em>多源汇聚，一处掌握</em></span>
            <span><small>02</small><strong>可信开发治理</strong><em>标准贯穿，全程可溯</em></span>
            <span><small>03</small><strong>敏捷分析服务</strong><em>快速交付，持续更新</em></span>
          </div>
        </div>
        <div className="login-legal"><span>© 2026 Aetheris</span><span>Secure workspace · v0.2</span></div>
      </section>
      <section className="login-panel">
        <Card className="login-card" variant="borderless">
          <div className="login-card-head">
            <Typography.Title level={2}>欢迎回来</Typography.Title>
          </div>
          {logoutUnconfirmed && <Alert type="warning" showIcon
            message="本地已退出，服务端会话撤销尚未确认"
            description="服务恢复后可重试退出。确认前请勿将服务端会话视为已撤销。"
            action={<Button loading={retryingLogout} disabled={loading} onClick={() => { retryLogout().catch(() => undefined) }}>重试退出</Button>} />}
          <Form layout="vertical" requiredMark={false} onFinish={(values) => void handleSubmit(values as LoginForm)}>
            <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input prefix={<UserOutlined />} placeholder="name@company.com" size="large" autoComplete="username" />
            </Form.Item>
            <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password prefix={<LockOutlined />} placeholder="输入密码" size="large" autoComplete="current-password" />
            </Form.Item>
            <Button className="login-submit" type="primary" htmlType="submit" block size="large" loading={loading}>
              进入工作空间 <ArrowRightOutlined />
            </Button>
          </Form>
          <div className="login-help">无法登录？请联系你的系统管理员</div>
        </Card>
      </section>
    </main>
  )
}
