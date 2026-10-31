// Apply the saved theme before the app paints, including on a full page reload.
(() => {
  let preference
  try { preference = localStorage.getItem('flowpay:theme') } catch { /* Storage may be disabled. */ }
  const dark = preference !== 'light'
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
  document.documentElement.style.colorScheme = dark ? 'dark' : 'light'
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', dark ? '#080f23' : '#f0f5fd')
})()
