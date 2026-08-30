import type { ThemeConfig } from 'antd'

export const appTheme: ThemeConfig = {
  token: {
    colorPrimary: '#071a3d',
    colorInfo: '#071a3d',
    colorSuccess: '#071a3d',
    colorWarning: '#071a3d',
    colorError: '#071a3d',
    colorBgLayout: '#f7f7f8',
    colorBgContainer: '#ffffff',
    colorText: '#161618',
    colorTextSecondary: '#707075',
    colorBorder: '#e6e6e8',
    colorBorderSecondary: '#ededee',
    borderRadius: 8,
    borderRadiusLG: 12,
    controlHeight: 36,
    fontSize: 13,
    fontFamily: "Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
    boxShadowSecondary: '0 16px 40px rgba(0, 0, 0, 0.08)'
  },
  components: {
    Layout: {
      bodyBg: '#f7f7f8', siderBg: '#fafafa', headerBg: 'rgba(255,255,255,.86)'
    },
    Menu: {
      itemBg: 'transparent', itemColor: '#66666b', itemHoverBg: '#f0f0f1',
      itemHoverColor: '#161618', itemSelectedBg: '#e9e9eb', itemSelectedColor: '#111113',
      groupTitleColor: '#99999e', itemBorderRadius: 6, itemHeight: 34
    },
    Card: {
      headerBg: 'transparent', paddingLG: 20
    },
    Table: {
      headerBg: '#fafafa', headerColor: '#707075', rowHoverBg: '#fafafa'
    },
    Button: { primaryShadow: 'none', defaultShadow: 'none' },
    Input: { activeShadow: '0 0 0 3px rgba(7,26,61,.10)' },
    Select: { activeOutlineColor: 'rgba(7,26,61,.10)' },
    Progress: { defaultColor: '#071a3d', remainingColor: '#eeeeef' }
  }
}
