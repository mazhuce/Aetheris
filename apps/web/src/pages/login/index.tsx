import { useState } from 'react'
import { App, Button, Card, Form, Input, Typography } from 'antd'
import { LockOutlined, UserOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import { login } from '@/api/auth'
import { TOKEN_KEY, USERNAME_KEY } from '@/api/http'

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
      message.success('登录成功')
      navigate('/dashboard', { replace: true })
    } catch {
      message.error('用户名或密码错误')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div
      style={{
        minHeight: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: '#f0f2f5'
      }}
    >
      <Card style={{ width: 380 }}>
        <div style={{ textAlign: 'center', marginBottom: 24 }}>
          <img src="/AETHRIS.png" alt="Aetheris" style={{ height: 44 }} />
          <Typography.Title level={4} style={{ marginTop: 12, marginBottom: 0 }}>
            Aetheris
          </Typography.Title>
          <Typography.Text type="secondary">登录以继续</Typography.Text>
        </div>
        <Form layout="vertical" onFinish={(values) => void handleSubmit(values as LoginForm)}>
          <Form.Item name="username" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input prefix={<UserOutlined />} placeholder="用户名" size="large" />
          </Form.Item>
          <Form.Item name="password" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password prefix={<LockOutlined />} placeholder="密码" size="large" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block size="large" loading={loading}>
            登录
          </Button>
        </Form>
      </Card>
    </div>
  )
}
