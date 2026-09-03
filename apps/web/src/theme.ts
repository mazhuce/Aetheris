import { theme as antdTheme, type ThemeConfig } from 'antd'
import type { ColorMode } from '@/utils/preferences'

const fontFamily = "'DM Sans Variable', 'Noto Sans SC Variable', 'Noto Sans SC', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei UI', 'Microsoft YaHei', system-ui, sans-serif"
const fontFamilyCode = "'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace"

/**
 * Ant Design 与 CSS 语义变量共用同一套深浅色中性色。
 * 成功、警告和错误色在两种主题中保持一致，避免状态含义随主题变化。
 */
export function getAppTheme(mode: ColorMode): ThemeConfig {
  const dark = mode === 'dark'

  const palette = dark
    ? {
        primary: '#f0f0f2',
        primaryHover: '#ffffff',
        primaryActive: '#d9dade',
        primaryText: '#101114',
        info: '#c9cbd1',
        app: '#101114',
        nav: '#131417',
        surface: '#16171b',
        elevated: '#1b1c21',
        spotlight: '#25262c',
        subtle: '#1a1b20',
        input: '#191a1f',
        hover: '#1c1d22',
        tableHeader: '#18191e',
        tableHeaderText: '#858993',
        tableBorder: 'rgba(255,255,255,.065)',
        buttonText: '#c9cbd1',
        modal: '#18191e',
        tabItem: '#858993',
        tabHover: '#c9cbd1',
        tagBg: '#1d1e23',
        text: '#eeeef1',
        textSecondary: '#a8abb3',
        textTertiary: '#777b85',
        textDisabled: '#565a63',
        border: 'rgba(255,255,255,.12)',
        borderSubtle: 'rgba(255,255,255,.075)',
        fillSecondary: 'rgba(255,255,255,.055)',
        fillTertiary: 'rgba(255,255,255,.035)',
        selected: 'rgba(255,255,255,.09)',
        focus: 'rgba(255,255,255,.14)',
        progressTrack: '#24252b',
        shadow: '0 10px 28px rgba(0,0,0,.28)',
        shadowLarge: '0 18px 48px rgba(0,0,0,.38)'
      }
    : {
        primary: '#202226',
        primaryHover: '#08090a',
        primaryActive: '#383b41',
        primaryText: '#ffffff',
        info: '#59606b',
        app: '#f5f6f8',
        nav: '#ffffff',
        surface: '#ffffff',
        elevated: '#ffffff',
        spotlight: '#25272b',
        subtle: '#f6f7f9',
        input: '#f8f9fa',
        hover: '#eef0f3',
        tableHeader: '#f6f7f9',
        tableHeaderText: '#737b87',
        tableBorder: 'rgba(15,23,42,.075)',
        buttonText: '#4f5560',
        modal: '#ffffff',
        tabItem: '#737b87',
        tabHover: '#4f5560',
        tagBg: '#f2f3f5',
        text: '#181a1f',
        textSecondary: '#4f5560',
        textTertiary: '#737b87',
        textDisabled: '#a1a7b0',
        border: 'rgba(15,23,42,.14)',
        borderSubtle: 'rgba(15,23,42,.085)',
        fillSecondary: 'rgba(15,23,42,.055)',
        fillTertiary: 'rgba(15,23,42,.035)',
        selected: 'rgba(15,23,42,.08)',
        focus: 'rgba(15,23,42,.15)',
        progressTrack: '#e6e8ec',
        shadow: '0 10px 28px rgba(15,23,42,.12)',
        shadowLarge: '0 18px 48px rgba(15,23,42,.16)'
      }

  return {
    algorithm: [dark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm, antdTheme.compactAlgorithm],
    token: {
      colorPrimary: palette.primary,
      colorPrimaryHover: palette.primaryHover,
      colorPrimaryActive: palette.primaryActive,
      colorInfo: palette.info,
      colorSuccess: '#65b98f',
      colorWarning: '#d6a85f',
      colorError: '#d96b72',
      colorBgBase: palette.app,
      colorBgLayout: palette.app,
      colorBgContainer: palette.surface,
      colorBgElevated: palette.elevated,
      colorBgSpotlight: palette.spotlight,
      colorFillAlter: palette.elevated,
      colorFillSecondary: palette.fillSecondary,
      colorFillTertiary: palette.fillTertiary,
      colorText: palette.text,
      colorTextSecondary: palette.textSecondary,
      colorTextTertiary: palette.textTertiary,
      colorTextQuaternary: palette.textDisabled,
      colorBorder: palette.border,
      colorBorderSecondary: palette.borderSubtle,
      colorSplit: palette.borderSubtle,
      borderRadius: 6,
      borderRadiusLG: 8,
      borderRadiusSM: 4,
      controlHeight: 32,
      controlHeightLG: 36,
      controlHeightSM: 28,
      fontSize: 13,
      fontSizeSM: 12,
      fontFamily,
      fontFamilyCode,
      lineHeight: 1.5,
      lineWidth: 1,
      motionDurationFast: '0.14s',
      motionDurationMid: '0.18s',
      boxShadow: palette.shadow,
      boxShadowSecondary: palette.shadowLarge
    },
    components: {
      Layout: {
        bodyBg: palette.app,
        headerBg: palette.nav
      },
      Card: {
        colorBgContainer: palette.surface,
        headerBg: 'transparent',
        paddingLG: 16
      },
      Table: {
        colorBgContainer: 'transparent',
        headerBg: palette.tableHeader,
        headerColor: palette.tableHeaderText,
        rowHoverBg: palette.hover,
        borderColor: palette.tableBorder,
        cellPaddingBlockSM: 8,
        cellPaddingInlineSM: 12,
        fontSize: 12
      },
      Button: {
        primaryShadow: 'none',
        primaryColor: palette.primaryText,
        defaultShadow: 'none',
        dangerShadow: 'none',
        defaultBg: palette.subtle,
        defaultBorderColor: palette.border,
        defaultColor: palette.buttonText,
        textHoverBg: palette.fillSecondary
      },
      Input: {
        colorBgContainer: palette.input,
        activeShadow: `0 0 0 2px ${palette.focus}`
      },
      InputNumber: {
        colorBgContainer: palette.input,
        activeShadow: `0 0 0 2px ${palette.focus}`
      },
      Select: {
        colorBgContainer: palette.input,
        optionSelectedBg: palette.selected,
        activeOutlineColor: palette.focus
      },
      DatePicker: {
        colorBgContainer: palette.input,
        activeShadow: `0 0 0 2px ${palette.focus}`
      },
      Modal: {
        contentBg: palette.modal,
        headerBg: palette.modal
      },
      Drawer: {
        colorBgElevated: palette.surface
      },
      Tabs: {
        itemColor: palette.tabItem,
        itemSelectedColor: palette.text,
        itemHoverColor: palette.tabHover,
        inkBarColor: palette.primary
      },
      Tag: {
        defaultBg: palette.tagBg,
        defaultColor: palette.textSecondary
      },
      Progress: {
        defaultColor: palette.primary,
        remainingColor: palette.progressTrack
      },
      Tooltip: {
        colorBgSpotlight: palette.spotlight
      }
    }
  }
}
