const shanghaiTime = new Intl.DateTimeFormat('sv-SE', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

export function formatShanghaiTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : shanghaiTime.format(date)
}

export function shanghaiDayStart(value: string): string {
  return new Date(value + 'T00:00:00+08:00').toISOString()
}

export function shanghaiDayAfter(value: string): string {
  return new Date(new Date(shanghaiDayStart(value)).getTime() + 86400000).toISOString()
}
