import { App, Button, Card, Col, Row, Segmented, Select, Switch } from 'antd'
import {
  BulbOutlined,
  DashboardOutlined,
  MoonOutlined,
  ReloadOutlined,
  SunOutlined
} from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import { usePreferences } from '@/contexts/PreferencesContext'
import type { ColorMode } from '@/utils/preferences'

const HOME_OPTIONS = [
  { value: '/workbench', label: '数据概览' },
  { value: '/workbench/operations', label: '运行概览' },
  { value: '/workbench/todos', label: '我的待办' },
  { value: '/workbench/recent', label: '最近访问' }
]

export default function SettingsPage() {
  const { message } = App.useApp()
  const {
    colorMode,
    reduceMotion,
    homePath,
    updatePreferences,
    resetPreferences
  } = usePreferences()

  const handleReset = () => {
    resetPreferences()
    message.success('系统设置已恢复默认')
  }

  return (
    <div className="settings-page">
      <PageHeader description="管理当前浏览器中的界面与使用偏好；修改会即时生效并自动保存。" />

      <Row gutter={[16, 16]} className="settings-grid">
        <Col xs={24} lg={14}>
          <Card className="settings-card" title="外观" extra={<BulbOutlined />}>
            <div className="setting-row setting-row-stacked">
              <div className="setting-copy">
                <strong>界面主题</strong>
                <span>在深色与浅色界面之间切换，业务状态色和页面结构保持一致。</span>
              </div>
              <Segmented<ColorMode>
                className="theme-mode-control"
                value={colorMode}
                onChange={(value) => updatePreferences({ colorMode: value })}
                options={[
                  { value: 'dark', label: <span><MoonOutlined /> 深色</span> },
                  { value: 'light', label: <span><SunOutlined /> 浅色</span> }
                ]}
              />
            </div>

            <div className={`theme-preview is-${colorMode}`} aria-hidden="true">
              <div className="theme-preview-nav">
                <i />
                <span />
                <span />
                <span />
              </div>
              <div className="theme-preview-body">
                <div className="theme-preview-card">
                  <b />
                  <span />
                  <span />
                </div>
                <div className="theme-preview-card">
                  <b />
                  <span />
                  <span />
                </div>
              </div>
            </div>
          </Card>
        </Col>

        <Col xs={24} lg={10}>
          <Card className="settings-card" title="使用偏好" extra={<DashboardOutlined />}>
            <div className="setting-row">
              <div className="setting-copy">
                <strong>默认首页</strong>
                <span>点击左上角品牌标识时进入的页面。</span>
              </div>
              <Select
                className="settings-select"
                value={homePath}
                options={HOME_OPTIONS}
                onChange={(value) => updatePreferences({ homePath: value })}
              />
            </div>

            <div className="setting-row">
              <div className="setting-copy">
                <strong>减少动态效果</strong>
                <span>缩短界面动画，降低视觉移动带来的干扰。</span>
              </div>
              <Switch
                checked={reduceMotion}
                onChange={(checked) => updatePreferences({ reduceMotion: checked })}
                aria-label="减少动态效果"
              />
            </div>

            <div className="settings-reset">
              <Button icon={<ReloadOutlined />} onClick={handleReset}>恢复默认设置</Button>
              <span>恢复为深色主题、标准动态效果和数据概览首页。</span>
            </div>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
