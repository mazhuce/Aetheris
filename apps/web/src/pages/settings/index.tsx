import { App, Button, Card, Col, Row, Segmented, Select, Switch } from 'antd'
import {
  BulbOutlined,
  DashboardOutlined,
  DeploymentUnitOutlined,
  MoonOutlined,
  RightOutlined,
  ReloadOutlined,
  SunOutlined
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import { usePreferences } from '@/contexts/PreferencesContext'
import { useNavigationConfig } from '@/contexts/NavigationContext'
import { isItemVisible, isModuleVisible, navModules } from '@/constants/navigation'
import type { ColorMode } from '@/utils/preferences'

const HOME_OPTIONS = [
  { value: '/learning/overview', label: '数据概览' },
  { value: '/operations/overview', label: '运行概览' },
  { value: '/workbench/todos', label: '我的待办' },
  { value: '/workbench/recent', label: '最近访问' }
]

export default function SettingsPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const {
    colorMode,
    reduceMotion,
    homePath,
    updatePreferences,
    resetPreferences
  } = usePreferences()
  const { config } = useNavigationConfig()

  const visibleModuleCount = navModules.filter((module) => isModuleVisible(module.key, config)).length
  const visibleItemCount = navModules
    .flatMap((module) => module.children)
    .filter((child) => child.status === 'available' && isItemVisible(child.key, config)).length

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
              <span>恢复为深色主题、标准动态效果和学习路径首页。</span>
            </div>
          </Card>

          <Card
            className="settings-card settings-nav-entry"
            title="菜单配置"
            extra={<DeploymentUnitOutlined />}
          >
            <div className="setting-copy">
              <strong>自定义顶栏与二级菜单</strong>
              <span>
                当前展示 {visibleModuleCount} 个一级菜单、{visibleItemCount} 个二级菜单。
                可以隐藏任意入口、调整二级菜单归属与顺序，修改后立即生效。
              </span>
            </div>
            <Button className="settings-nav-button" type="primary" onClick={() => navigate('/settings/navigation')}>
              进入菜单配置 <RightOutlined />
            </Button>
          </Card>
        </Col>
      </Row>
    </div>
  )
}
