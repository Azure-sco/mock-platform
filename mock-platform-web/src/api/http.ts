import axios from 'axios'
import { pinia } from '../stores'
import { useErrorStore } from '../stores/errors'
import { useSessionStore } from '../stores/session'
import type { ApiResponse } from '../types/platform'

export const http = axios.create({
  baseURL: '/api',
  timeout: 5000,
})

http.interceptors.request.use((config) => {
  const session = useSessionStore(pinia)
  config.headers.set('X-Operator-Id', session.operatorId)
  config.headers.set('X-Operator-Roles', session.roles.join(','))
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    const errors = useErrorStore(pinia)
    if (axios.isAxiosError<ApiResponse<never>>(error)) {
      const payload = error.response?.data
      errors.capture({
        code: payload?.code || 'HTTP_ERROR',
        message: payload?.message || error.message,
        requestId: payload?.requestId || error.response?.headers['x-request-id'],
      })
    } else {
      errors.capture({ code: 'UNEXPECTED_ERROR', message: '请求处理失败' })
    }
    return Promise.reject(error)
  },
)
