import { useState } from 'react'
import { App, Button, Card, Form, Input, Typography } from 'antd'
import { ArrowRightOutlined, CheckCircleFilled, LockOutlined, UserOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { login } from '@/api/auth'
import { ROLE_KEY, TOKEN_KEY, USERNAME_KEY } from '@/api/http'
import BrandMark from '@/components/BrandMark'

interface LoginForm {
  username: string
  password: string
}

export default function LoginPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)

  const handleSubmit = async (values: LoginForm) => {
    setLoading(true)
    try {
      const result = await login(values.username, values.password)
      localStorage.setItem(TOKEN_KEY, result.token)
      localStorage.setItem(USERNAME_KEY, result.username)
      localStorage.setItem(ROLE_KEY, result.role)
      message.success('登录成功')
      navigate('/dashboard', { replace: true })
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
        <div className="login-brand"><BrandMark /><span>Aetheris</span></div>
        <div className="login-copy">
          <h1>把复杂的数据工作，<br />变成清晰的流动。</h1>
          <p>连接、治理、编排与洞察，在一个安静而高效的工作空间里完成。</p>
          <div className="login-proof">
            <span><CheckCircleFilled /> 统一数据资产</span>
            <span><CheckCircleFilled /> 实时任务状态</span>
            <span><CheckCircleFilled /> 企业级权限</span>
          </div>
        </div>
        <div className="login-legal">© 2026 Aetheris · Built for modern data teams</div>
      </section>
      <section className="login-panel">
        <Card className="login-card" variant="borderless">
          <div className="login-card-head">
            <Typography.Title level={2}>欢迎回来</Typography.Title>
            <Typography.Text type="secondary">登录你的工作空间以继续</Typography.Text>
          </div>
          <Form layout="vertical" requiredMark={false} onFinish={(values) => void handleSubmit(values as LoginForm)}>
            <Form.Item label="用户名" name="username" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input prefix={<UserOutlined />} placeholder="name@company.com" size="large" autoComplete="username" />
            </Form.Item>
            <Form.Item label="密码" name="password" rules={[{ required: true, message: '请输入密码' }]}>
              <Input.Password prefix={<LockOutlined />} placeholder="输入密码" size="large" autoComplete="current-password" />
            </Form.Item>
            <Button className="login-submit" type="primary" htmlType="submit" block size="large" loading={loading}>
              登录 <ArrowRightOutlined />
            </Button>
          </Form>
          <div className="login-help">无法登录？请联系你的系统管理员</div>
        </Card>
      </section>
    </main>
  )
}
