import type { ReactNode } from 'react'
import { Button, Dropdown, Tooltip } from 'antd'
import { MoreOutlined } from '@ant-design/icons'

export interface RowActionItem {
  key: string
  label: ReactNode
  icon?: ReactNode
  danger?: boolean
  disabled?: boolean
  onClick: () => void
}

interface RowActionsProps {
  primary?: ReactNode
  items: RowActionItem[]
  label?: string
}

export default function RowActions({ primary, items, label = '更多操作' }: RowActionsProps) {
  const handlers = new Map(items.map((item) => [item.key, item.onClick]))

  return (
    <div className="row-actions" onClick={(event) => event.stopPropagation()}>
      {primary}
      {items.length > 0 && (
        <Dropdown
          trigger={['click']}
          menu={{
            items: items.map(({ key, label: itemLabel, icon, danger, disabled }) => ({
              key,
              label: itemLabel,
              icon,
              danger,
              disabled
            })),
            onClick: ({ key, domEvent }) => {
              domEvent.stopPropagation()
              handlers.get(key)?.()
            }
          }}
        >
          <Tooltip title={label}>
            <Button type="text" size="small" icon={<MoreOutlined />} aria-label={label} />
          </Tooltip>
        </Dropdown>
      )}
    </div>
  )
}
