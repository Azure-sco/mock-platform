import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface HttpFailure {
  code: string
  message: string
  requestId?: string
}

export const useErrorStore = defineStore('errors', () => {
  const latest = ref<HttpFailure | null>(null)

  function capture(failure: HttpFailure) {
    latest.value = failure
  }

  function clear() {
    latest.value = null
  }

  return { latest, capture, clear }
})
