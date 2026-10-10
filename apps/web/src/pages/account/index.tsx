import { useCallback, useEffect, useState } from 'react'
import {
  App,
  Button,
  Card,
  Col,
  Descriptions,
  Form,
  Input,
  Row
} from 'antd'
import { SaveOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import StatusBadge from '@/components/StatusBadge'
import {
  changeMyPassword,
  getMyProfile,
  logout,
  updateMyProfile,
  type AuthUser,
  type UpdateProfileInput
} from '@/api/auth'
import { markLoggedOut } from '@/auth/session'
import { useNavigate } from 'react-router-dom'
import { formatDateTime } from '@/utils/format'

const roleMeta: Record<string, { text: string }> = {
  admin: { text: '管理员' },
  viewer: { text: '只读用户' }
}

const statusMeta: Record<string, { text: string }> = {
  active: { text: '启用' },
  disabled: { text: '禁用' }
}

interface ProfileFormValues {
  nickname?: string
  email?: string
  phone?: string
}

interface PasswordFormValues {
  currentPassword: string
  newPassword: string
  confirmPassword: string
}

export default function AccountPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [profile, setProfile] = useState<AuthUser | null>(null)
  const [profileForm] = Form.useForm<ProfileFormValues>()
  const [passwordForm] = Form.useForm<PasswordFormValues>()
  const [loading, setLoading] = useState(true)
  const [savingProfile, setSavingProfile] = useState(false)
  const [changingPassword, setChangingPassword] = useState(false)

  const loadProfile = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getMyProfile()
      setProfile(data)
      profileForm.setFieldsValue({ nickname: data.nickname, email: data.email, phone: data.phone })
    } catch {
      message.error('账户资料加载失败')
    } finally {
      setLoading(false)
    }
  }, [message, profileForm])

  useEffect(() => {
    void loadProfile()
  }, [loadProfile])

  const handleSaveProfile = async (values: ProfileFormValues) => {
    setSavingProfile(true)
    try {
      const payload: UpdateProfileInput = {
        nickname: values.nickname || undefined,
        email: values.email || undefined,
        phone: values.phone || undefined
      }
      const updated = await updateMyProfile(payload)
      setProfile(updated)
      message.success('资料已保存')
    } catch {
      message.error('保存失败，请稍后再试')
    } finally {
      setSavingProfile(false)
    }
  }

  const handleChangePassword = async (values: PasswordFormValues) => {
    setChangingPassword(true)
    try {
      const result = await changeMyPassword(values.currentPassword, values.newPassword)
      message.success(result.message)
      passwordForm.resetFields()
      // 改密成功后旧 JWT 在过期前仍有效，但按约定提示重新登录，避免继续使用旧凭据。
      if (result.reloginRequired) {
        setTimeout(async () => {
          // Clear the server-side refresh session as well as the in-memory
          // identity; an HttpOnly cookie cannot be removed by JavaScript.
          await logout().catch(() => undefined)
          markLoggedOut()
          navigate('/login', { replace: true })
        }, 1200)
      }
    } catch (error) {
      const detail = (error as { response?: { data?: { message?: string } } })
        ?.response?.data?.message
      message.error(detail || '密码修改失败')
    } finally {
      setChangingPassword(false)
    }
  }

  return (
    <>
      <PageHeader
        description="维护个人资料与登录密码；用户名、角色与状态由管理员统一管理。"
      />

      <Row gutter={16} className="account-grid">
        <Col xs={24} lg={12}>
          <Card size="small" className="account-card" title="个人资料" loading={loading}>
            <Descriptions className="account-summary" column={1} size="small">
              <Descriptions.Item label="用户名">{profile?.username ?? '—'}</Descriptions.Item>
              <Descriptions.Item label="角色">
                {profile ? <StatusBadge tone={profile.role === 'admin' ? 'info' : 'neutral'}>{roleMeta[profile.role]?.text ?? profile.role}</StatusBadge> : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                {profile ? <StatusBadge tone={profile.status === 'active' ? 'success' : 'neutral'}>{statusMeta[profile.status]?.text ?? profile.status}</StatusBadge> : '—'}
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">{formatDateTime(profile?.createdAt)}</Descriptions.Item>
            </Descriptions>

            <Form form={profileForm} layout="vertical" onFinish={(values) => void handleSaveProfile(values)}>
              <Form.Item name="nickname" label="昵称" rules={[{ max: 64, message: '昵称最多 64 个字符' }]}>
                <Input placeholder="例如：数据分析师" />
              </Form.Item>
              <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '邮箱格式不正确' }]}>
                <Input placeholder="name@company.com" />
              </Form.Item>
              <Form.Item name="phone" label="手机号" rules={[{ max: 32, message: '手机号最多 32 个字符' }]}>
                <Input placeholder="可选" />
              </Form.Item>
              <Button type="primary" htmlType="submit" icon={<SaveOutlined />} loading={savingProfile}>
                保存资料
              </Button>
            </Form>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card size="small" className="account-card account-security-card" title="修改密码">
            <Form form={passwordForm} layout="vertical" onFinish={(values) => void handleChangePassword(values)}>
              <Form.Item name="currentPassword" label="当前密码" rules={[{ required: true, message: '请输入当前密码' }]}>
                <Input.Password autoComplete="current-password" />
              </Form.Item>
              <Form.Item
                name="newPassword"
                label="新密码"
                rules={[
                  { required: true, message: '请输入新密码' },
                  { min: 12, message: '密码至少 12 位' },
                  { max: 128, message: '密码最多 128 位' }
                ]}
              >
                <Input.Password autoComplete="new-password" placeholder="至少 12 位" />
              </Form.Item>
              <Form.Item
                name="confirmPassword"
                label="确认新密码"
                dependencies={['newPassword']}
                rules={[
                  { required: true, message: '请再次输入新密码' },
                  ({ getFieldValue }) => ({
                    validator(_, value) {
                      if (!value || getFieldValue('newPassword') === value) return Promise.resolve()
                      return Promise.reject(new Error('两次输入的新密码不一致'))
                    }
                  })
                ]}
              >
                <Input.Password autoComplete="new-password" />
              </Form.Item>
              <Button type="primary" htmlType="submit" loading={changingPassword}>
                修改密码
              </Button>
            </Form>
          </Card>
        </Col>
      </Row>
    </>
  )
}
