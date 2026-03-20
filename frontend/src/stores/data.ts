import { defineStore } from 'pinia'
import { ref } from 'vue'
import { dataApi } from '@/api/data'
import type { DataOverview } from '@/types'

export const useDataStore = defineStore('data', () => {
  const overview = ref<DataOverview | null>(null)
  const loading = ref(false)

  async function fetchOverview() {
    loading.value = true
    try {
      overview.value = await dataApi.getOverview()
      return overview.value
    } finally {
      loading.value = false
    }
  }

  return { overview, loading, fetchOverview }
})
