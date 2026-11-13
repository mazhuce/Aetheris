import { useMemo, useState, type ReactNode } from 'react'
import {
  Alert,
  App,
  Button,
  Card,
  Input,
  Popconfirm,
  Select,
  Switch,
  Table,
  Tag,
  Tooltip,
  type TableColumnsType
} from 'antd'
import {
  ArrowLeftOutlined,
  ArrowDownOutlined,
  ArrowUpOutlined,
  ReloadOutlined,
  UndoOutlined
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import { useNavigationConfig } from '@/contexts/NavigationContext'
import {
  allNavChildren,
  defaultModuleOf,
  isItemVisible,
  isModuleVisible,
  navModules,
  type NavChild
} from '@/constants/navigation'

interface ModuleRow {
  key: string
  label: string
  icon: ReactNode
  visible: boolean
  childLabels: string[]
  defaultHidden: boolean
}

interface ItemRow extends NavChild {
  moduleKey: string
  visible: boolean
  defaultModule: string
}

export default function NavigationConfigPage() {
  const navigate = useNavigate()
  const { message } = App.useApp()
  const { config, patchConfig, resetConfig } = useNavigationConfig()
  const [keyword, setKeyword] = useState('')
  const [moduleFilter, setModuleFilter] = useState<string>('all')

  const moduleOptions = navModules.map((module) => ({
    value: module.key,
    label: isModuleVisible(module.key, config) ? module.label : `${module.label}（已隐藏）`
  }))

  /** 二级项当前归属：优先取覆盖值，否则取出厂归属。 */
  const moduleOf = (childKey: string) => config.itemModule[childKey] ?? defaultModuleOf(childKey) ?? ''

  /** 同一个归属解析，但允许传入任意配置（供 useMemo 内部使用，避免闭包依赖）。 */
  const moduleOfIn = (source: typeof config) => (childKey: string) =>
    source.itemModule[childKey] ?? defaultModuleOf(childKey) ?? ''

  const moduleRows = useMemo<ModuleRow[]>(() => {
    const resolveModule = moduleOfIn(config)
    return navModules.map((module) => {
      const owned = allNavChildren().filter(
        (child) => child.status === 'available' && resolveModule(child.key) === module.key
      )
      return {
        key: module.key,
        label: module.label,
        icon: module.icon,
        visible: isModuleVisible(module.key, config),
        childLabels: owned.map((child) => child.label),
        defaultHidden: module.defaultVisible === false
      }
    })
  }, [config])

  const itemRows = useMemo<ItemRow[]>(() => {
    const resolveModule = moduleOfIn(config)
    const rows = allNavChildren()
      .filter((child) => child.status === 'available')
      .map((child) => ({
        ...child,
        moduleKey: resolveModule(child.key),
        visible: isItemVisible(child.key, config),
        defaultModule: defaultModuleOf(child.key) ?? ''
      }))

    const trimmed = keyword.trim().toLowerCase()
    const rank = new Map((config.itemOrder[moduleFilter] ?? []).map((key, index) => [key, index]))

    return rows
      .filter((row) => (moduleFilter === 'all' ? true : row.moduleKey === moduleFilter))
      .filter((row) => {
        if (!trimmed) return true
        return row.label.toLowerCase().includes(trimmed)
          || row.path.toLowerCase().includes(trimmed)
          || row.key.toLowerCase().includes(trimmed)
      })
      .sort((a, b) => {
        const byModule = config.moduleOrder.indexOf(a.moduleKey) - config.moduleOrder.indexOf(b.moduleKey)
        if (byModule !== 0) return byModule
        return (rank.get(a.key) ?? 0) - (rank.get(b.key) ?? 0)
      })
  }, [config, keyword, moduleFilter])

  const toggleModule = (moduleKey: string, next: boolean) => {
    const defaultHidden = navModules.find((module) => module.key === moduleKey)?.defaultVisible === false
    patchConfig({
      visibleModules: defaultHidden
        ? (next ? [...new Set([...config.visibleModules, moduleKey])] : config.visibleModules.filter((key) => key !== moduleKey))
        : config.visibleModules,
      hiddenModules: next
        ? config.hiddenModules.filter((key) => key !== moduleKey)
        : (defaultHidden ? config.hiddenModules : [...new Set([...config.hiddenModules, moduleKey])])
    })
  }

  const toggleItem = (childKey: string, next: boolean) => {
    patchConfig({
      hiddenItems: next
        ? config.hiddenItems.filter((key) => key !== childKey)
        : [...new Set([...config.hiddenItems, childKey])]
    })
  }

  const moveModule = (moduleKey: string, direction: -1 | 1) => {
    const order = [...config.moduleOrder]
    const index = order.indexOf(moduleKey)
    const target = index + direction
    if (index < 0 || target < 0 || target >= order.length) return
    ;[order[index], order[target]] = [order[target], order[index]]
    patchConfig({ moduleOrder: order })
  }

  const moveItem = (childKey: string, direction: -1 | 1) => {
    const moduleKey = moduleOf(childKey)
    const order = [...(config.itemOrder[moduleKey] ?? [])]
    const index = order.indexOf(childKey)
    if (index < 0) return
    const target = index + direction
    if (target < 0 || target >= order.length) return
    ;[order[index], order[target]] = [order[target], order[index]]
    patchConfig({ itemOrder: { ...config.itemOrder, [moduleKey]: order } })
  }

  const reassignItem = (childKey: string, targetModule: string) => {
    const from = moduleOf(childKey)
    if (from === targetModule) return
    const fromOrder = (config.itemOrder[from] ?? []).filter((key) => key !== childKey)
    const targetOrder = [...(config.itemOrder[targetModule] ?? []).filter((key) => key !== childKey), childKey]
    patchConfig({
      itemModule: { ...config.itemModule, [childKey]: targetModule },
      itemOrder: { ...config.itemOrder, [from]: fromOrder, [targetModule]: targetOrder }
    })
    const label = navModules.find((module) => module.key === targetModule)?.label ?? targetModule
    message.success(`已调整为「${label}」下的菜单项`)
  }

  const resetItemModule = (childKey: string) => {
    const next = { ...config.itemModule }
    delete next[childKey]
    const from = moduleOf(childKey)
    const target = defaultModuleOf(childKey) ?? from
    patchConfig({
      itemModule: next,
      itemOrder: {
        ...config.itemOrder,
        [from]: (config.itemOrder[from] ?? []).filter((key) => key !== childKey),
        [target]: [...(config.itemOrder[target] ?? []).filter((key) => key !== childKey), childKey]
      }
    })
  }

  const handleReset = () => {
    resetConfig()
    message.success('菜单已恢复出厂配置')
  }

  const moduleColumns: TableColumnsType<ModuleRow> = [
    {
      title: '顺序',
      dataIndex: 'key',
      width: 84,
      render: (key: string, _row, index) => (
        <div className="nav-config-move">
          <Tooltip title="上移">
            <Button
              size="small"
              type="text"
              icon={<ArrowUpOutlined />}
              disabled={index === 0}
              onClick={() => moveModule(key, -1)}
              aria-label="上移一级菜单"
            />
          </Tooltip>
          <Tooltip title="下移">
            <Button
              size="small"
              type="text"
              icon={<ArrowDownOutlined />}
              disabled={index === moduleRows.length - 1}
              onClick={() => moveModule(key, 1)}
              aria-label="下移一级菜单"
            />
          </Tooltip>
        </div>
      )
    },
    {
      title: '一级菜单',
      dataIndex: 'label',
      render: (_value, row) => (
        <div className="nav-config-module">
          <span className="nav-config-icon">{row.icon}</span>
          <span className="nav-config-title">{row.label}</span>
          {row.defaultHidden && <Tag bordered={false}>默认隐藏</Tag>}
        </div>
      )
    },
    {
      // 一眼看清这个一级菜单下面挂了什么，比只给数量更有用；超出 4 个折叠为 +N。
      title: '包含二级菜单',
      dataIndex: 'childLabels',
      render: (labels: string[]) => {
        const shown = labels.slice(0, 4)
        const rest = labels.length - shown.length
        return (
          <div className="nav-config-children">
            {shown.map((label) => (
              <span key={label} className="nav-config-child-chip">{label}</span>
            ))}
            {rest > 0 && <span className="nav-config-children-more">+{rest}</span>}
          </div>
        )
      }
    },
    {
      title: '在顶栏展示',
      dataIndex: 'visible',
      width: 130,
      align: 'right',
      render: (visible: boolean, row) => (
        <Switch
          size="small"
          checked={visible}
          onChange={(next) => toggleModule(row.key, next)}
          aria-label={`${row.label}是否展示`}
        />
      )
    }
  ]

  const itemColumns: TableColumnsType<ItemRow> = [
    {
      title: '二级菜单',
      dataIndex: 'label',
      render: (_value, row) => (
        <div className="nav-config-item">
          <span className="nav-config-title">{row.label}</span>
          <code className="nav-config-key">{row.key}</code>
        </div>
      )
    },
    {
      title: '路径',
      dataIndex: 'path',
      width: 240,
      render: (path: string) => <code className="nav-config-path">{path}</code>
    },
    {
      title: '归属一级菜单',
      dataIndex: 'moduleKey',
      width: 190,
      render: (moduleKey: string, row) => (
        <Select
          size="small"
          className="nav-config-select"
          value={moduleKey}
          options={moduleOptions}
          onChange={(value) => reassignItem(row.key, value)}
          popupMatchSelectWidth={false}
          aria-label={`${row.label}归属`}
        />
      )
    },
    {
      title: '顺序',
      dataIndex: 'key',
      width: 84,
      render: (key: string) => (
        <div className="nav-config-move">
          <Tooltip title="上移">
            <Button size="small" type="text" icon={<ArrowUpOutlined />} onClick={() => moveItem(key, -1)} aria-label="上移二级菜单" />
          </Tooltip>
          <Tooltip title="下移">
            <Button size="small" type="text" icon={<ArrowDownOutlined />} onClick={() => moveItem(key, 1)} aria-label="下移二级菜单" />
          </Tooltip>
        </div>
      )
    },
    {
      title: '展示',
      dataIndex: 'visible',
      width: 92,
      align: 'right',
      render: (visible: boolean, row) => (
        <Switch
          size="small"
          checked={visible}
          onChange={(next) => toggleItem(row.key, next)}
          aria-label={`${row.label}是否展示`}
        />
      )
    },
    {
      title: '',
      dataIndex: 'defaultModule',
      width: 56,
      render: (defaultModule: string, row) => (
        row.moduleKey !== defaultModule ? (
          <Tooltip title="恢复到出厂归属">
            <Button size="small" type="text" icon={<UndoOutlined />} onClick={() => resetItemModule(row.key)} aria-label="恢复出厂归属" />
          </Tooltip>
        ) : null
      )
    }
  ]

  return (
    <div className="nav-config-page">
      <PageHeader
        title="菜单配置"
        description="调整顶栏结构与二级菜单归属。修改保存在当前浏览器并立即生效；隐藏只是不展示入口，页面本身始终可通过地址访问。"
        extra={
          <div className="nav-config-actions">
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/settings')}>返回设置</Button>
            <Popconfirm title="恢复到出厂菜单结构？" description="所有隐藏、归属与顺序调整都会被清除。" onConfirm={handleReset}>
              <Button icon={<ReloadOutlined />}>恢复默认</Button>
            </Popconfirm>
          </div>
        }
      />

      <Alert
        className="nav-config-hint"
        type="info"
        showIcon
        message="一级菜单控制顶栏是否出现，二级菜单控制该菜单项是否出现在对应的一级菜单下。改归属后，该页面会立刻出现在新的二级导航里。"
      />

      <Card className="settings-card nav-config-card" title="一级菜单" extra={<span className="nav-config-count">{moduleRows.filter((row) => row.visible).length} / {moduleRows.length} 展示</span>}>
        <Table<ModuleRow>
          rowKey="key"
          size="small"
          pagination={false}
          columns={moduleColumns}
          dataSource={moduleRows}
          className="nav-config-table"
        />
      </Card>

      <Card
        className="settings-card nav-config-card"
        title="二级菜单"
        extra={
          <div className="nav-config-filters">
            <Input.Search
              allowClear
              placeholder="搜索名称或路径"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              className="nav-config-search"
            />
            <Select
              value={moduleFilter}
              onChange={setModuleFilter}
              options={[{ value: 'all', label: '全部一级菜单' }, ...moduleOptions]}
              className="nav-config-filter"
              popupMatchSelectWidth={false}
            />
          </div>
        }
      >
        <Table<ItemRow>
          rowKey="key"
          size="small"
          pagination={false}
          columns={itemColumns}
          dataSource={itemRows}
          className="nav-config-table"
          scroll={{ y: 480 }}
        />
      </Card>
    </div>
  )
}
