import { createContext, useContext, useLayoutEffect, useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { Button, ConfigProvider, theme } from 'antd'
import { MoonOutlined, SunOutlined } from '@ant-design/icons'
import enUS from 'antd/locale/en_US'

type Mode = 'light' | 'dark'
const storageKey = 'flowpay:theme'
const ThemeContext = createContext<{ mode: Mode; toggle: () => void } | null>(null)

function savedPreference(): Mode | null {
  try {
    const saved = localStorage.getItem(storageKey)
    if (saved === 'light' || saved === 'dark') return saved
  } catch { /* Keep the switch usable even when storage is unavailable. */ }
  return null
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [preference, setPreference] = useState<Mode | null>(savedPreference)
  const mode = preference || 'dark'
  const dark = mode === 'dark'

  useEffect(() => {
    const storageChanged = (event: StorageEvent) => {
      if (event.key === storageKey || event.key === null) setPreference(savedPreference())
    }
    window.addEventListener('storage', storageChanged)
    return () => {
      window.removeEventListener('storage', storageChanged)
    }
  }, [])

  useLayoutEffect(() => {
    document.documentElement.dataset.theme = mode
    document.documentElement.style.colorScheme = mode
    document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#080f23' : '#f0f5fd')
  }, [mode, dark])

  function toggle() {
    const next = dark ? 'light' : 'dark'
    setPreference(next)
    try { localStorage.setItem(storageKey, next) } catch { /* The current session still changes theme. */ }
  }

  return <ThemeContext.Provider value={{ mode, toggle }}>
    <ConfigProvider locale={enUS} theme={{
      algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm,
      token: {
        colorPrimary: dark ? '#86b6ff' : '#376fe0',
        colorText: dark ? '#eef4ff' : '#182a48',
        colorTextSecondary: dark ? '#a0b2d0' : '#647590',
        colorTextLightSolid: dark ? '#101f3c' : '#ffffff',
        colorBgBase: dark ? '#080f23' : '#ffffff',
        colorBgContainer: dark ? '#111e38' : '#ffffff',
        colorBgElevated: dark ? '#162642' : '#ffffff',
        colorBorder: dark ? '#2c3e5d' : '#d4def0',
        colorError: dark ? '#ee9385' : '#bd3f37',
        borderRadius: 3,
        borderRadiusXS: 3,
        borderRadiusSM: 3,
        borderRadiusLG: 3,
        borderRadiusOuter: 3,
        controlHeightLG: 50,
        fontFamily: '"Noto Serif", Georgia, "Times New Roman", serif',
        fontSize: 14,
      },
      components: {
        Button: { primaryShadow: 'none' },
        Input: { activeShadow: dark ? '0 0 0 3px #86b6ff16' : '0 0 0 3px #376fe00d' },
      },
    }}>{children}</ConfigProvider>
  </ThemeContext.Provider>
}

export function ThemeToggle() {
  const context = useContext(ThemeContext)
  if (!context) throw new Error('ThemeToggle must be inside ThemeProvider')
  const label = context.mode === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'
  return <Button className="theme-toggle" icon={context.mode === 'dark' ? <SunOutlined /> : <MoonOutlined />} onClick={context.toggle}
    aria-label={label} title={label} aria-pressed={context.mode === 'dark'} />
}
