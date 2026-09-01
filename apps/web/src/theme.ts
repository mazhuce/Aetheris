import { theme as antdTheme, type ThemeConfig } from 'antd'

/**
 * Aetheris product theme.
 *
 * Ant Design receives the same semantic palette used by the CSS design tokens.
 * Keeping component defaults here prevents page-level overrides and preserves a
 * single visual language across tables, forms, dialogs and navigation.
 */
export const appTheme: ThemeConfig = {
  algorithm: [antdTheme.darkAlgorithm, antdTheme.compactAlgorithm],
  token: {
    colorPrimary: '#f0f0f2',
    colorPrimaryHover: '#ffffff',
    colorPrimaryActive: '#d9dade',
    colorInfo: '#c9cbd1',
    colorSuccess: '#65b98f',
    colorWarning: '#d6a85f',
    colorError: '#d96b72',
    colorBgBase: '#101114',
    colorBgLayout: '#101114',
    colorBgContainer: '#16171b',
    colorBgElevated: '#1b1c21',
    colorBgSpotlight: '#25262c',
    colorFillAlter: '#1b1c21',
    colorFillSecondary: 'rgba(255,255,255,.055)',
    colorFillTertiary: 'rgba(255,255,255,.035)',
    colorText: '#eeeef1',
    colorTextSecondary: '#a8abb3',
    colorTextTertiary: '#777b85',
    colorTextQuaternary: '#565a63',
    colorBorder: 'rgba(255,255,255,.12)',
    colorBorderSecondary: 'rgba(255,255,255,.075)',
    colorSplit: 'rgba(255,255,255,.075)',
    borderRadius: 6,
    borderRadiusLG: 8,
    borderRadiusSM: 4,
    controlHeight: 32,
    controlHeightLG: 36,
    controlHeightSM: 28,
    fontSize: 13,
    fontSizeSM: 12,
    fontFamily: "'DM Sans Variable', 'Noto Sans SC Variable', 'Noto Sans SC', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei UI', 'Microsoft YaHei', system-ui, sans-serif",
    fontFamilyCode: "'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace",
    lineHeight: 1.5,
    lineWidth: 1,
    motionDurationFast: '0.14s',
    motionDurationMid: '0.18s',
    boxShadow: '0 10px 28px rgba(0,0,0,.28)',
    boxShadowSecondary: '0 18px 48px rgba(0,0,0,.38)'
  },
  components: {
    Layout: {
      bodyBg: '#101114',
      headerBg: '#131417'
    },
    Card: {
      colorBgContainer: '#16171b',
      headerBg: 'transparent',
      paddingLG: 16
    },
    Table: {
      colorBgContainer: 'transparent',
      headerBg: '#18191e',
      headerColor: '#858993',
      rowHoverBg: '#1c1d22',
      borderColor: 'rgba(255,255,255,.065)',
      cellPaddingBlockSM: 8,
      cellPaddingInlineSM: 12,
      fontSize: 12
    },
    Button: {
      primaryShadow: 'none',
      primaryColor: '#101114',
      defaultShadow: 'none',
      dangerShadow: 'none',
      defaultBg: '#1a1b20',
      defaultBorderColor: 'rgba(255,255,255,.12)',
      defaultColor: '#c9cbd1',
      textHoverBg: 'rgba(255,255,255,.055)'
    },
    Input: {
      colorBgContainer: '#191a1f',
      activeShadow: '0 0 0 2px rgba(255,255,255,.14)'
    },
    InputNumber: {
      colorBgContainer: '#191a1f',
      activeShadow: '0 0 0 2px rgba(255,255,255,.14)'
    },
    Select: {
      colorBgContainer: '#191a1f',
      optionSelectedBg: 'rgba(255,255,255,.09)',
      activeOutlineColor: 'rgba(255,255,255,.14)'
    },
    DatePicker: {
      colorBgContainer: '#191a1f',
      activeShadow: '0 0 0 2px rgba(255,255,255,.14)'
    },
    Modal: {
      contentBg: '#18191e',
      headerBg: '#18191e'
    },
    Drawer: {
      colorBgElevated: '#16171b'
    },
    Tabs: {
      itemColor: '#858993',
      itemSelectedColor: '#eeeef1',
      itemHoverColor: '#c9cbd1',
      inkBarColor: '#f0f0f2'
    },
    Tag: {
      defaultBg: '#1d1e23',
      defaultColor: '#a8abb3'
    },
    Progress: {
      defaultColor: '#f0f0f2',
      remainingColor: '#24252b'
    },
    Tooltip: {
      colorBgSpotlight: '#25262c'
    }
  }
}
