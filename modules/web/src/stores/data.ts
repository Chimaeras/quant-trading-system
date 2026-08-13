import { defineStore } from 'pinia'
import { ref } from 'vue'
import { dataApi } from '@/api/data'
import type { DataOverview } from '@/types'

export const useDataStore = defineStore('data', () => {
  const overview = ref<DataOverview | null>(null)
  const loading = ref(false)
  // 请求序号：连续调用 fetchOverview 时，后发请求序号更大，
  // 先返回的旧请求序号不匹配则丢弃结果，避免旧数据覆盖新数据。
  let reqSeq = 0

  async function fetchOverview() {
    const mySeq = ++reqSeq
    loading.value = true
    try {
      const data = await dataApi.getOverview()
      // 仅当本次请求仍是最新的才写入，防止竞态覆盖
      if (mySeq === reqSeq) {
        overview.value = data
      }
      return data
    } finally {
      if (mySeq === reqSeq) {
        loading.value = false
      }
    }
  }

  return { overview, loading, fetchOverview }
})
