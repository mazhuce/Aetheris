import type { ThemeConfig } from 'antd'

export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: '#1677ff',
    colorInfo: '#1677ff',
    colorSuccess: '#52c41a',
    colorWarning: '#faad14',
    colorError: '#ff4d4f',
    colorBgLayout: '#f0f2f5',
    colorBgContainer: '#ffffff',
    colorText: '#1f2329',
    colorTextSecondary: '#646a73',
    colorBorder: '#e5e6eb',
    borderRadius: 4,
    fontSize: 13,
    fontFamily: "'Inter', 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif"
  },
  components: {
    Layout: {
      bodyBg: '#f0f2f5',
      siderBg: '#001529',
      headerBg: '#ffffff'
    },
    Menu: {
      darkItemBg: '#001529',
      darkSubMenuItemBg: '#000c17',
      darkItemSelectedBg: '#1677ff'
    },
    Card: {
      borderRadiusLG: 6
    },
    Table: {
      headerBg: '#fafafa'
    }
  }
}
