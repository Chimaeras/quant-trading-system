<template>
  <el-card shadow="hover">
    <template #header>数据导入导出</template>
    <el-tabs>
      <el-tab-pane label="CSV 导入">
        <el-upload
          drag
          :auto-upload="false"
          :limit="1"
          accept=".csv"
          @change="handleFileChange"
        >
          <el-icon class="el-icon--upload"><UploadFilled /></el-icon>
          <div>拖拽 CSV 文件到此处，或点击上传</div>
          <template #tip>
            <div class="el-upload__tip">格式：股票代码,日期,开盘,最高,最低,收盘,成交量</div>
          </template>
        </el-upload>
        <div v-if="importResult" class="result">
          <el-alert :title="`导入完成：${importResult.count} 条`" type="success" />
        </div>
      </el-tab-pane>
      <el-tab-pane label="数据导出">
        <el-button type="primary" @click="exportTrades">导出交易记录</el-button>
        <el-button type="primary" @click="exportStocks">导出股票列表</el-button>
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { dataApi } from '@/api/data'

const importResult = ref<{ count: number } | null>(null)

const handleFileChange = async (file: { raw?: File }) => {
  if (!file?.raw) return
  try {
    const res = await dataApi.importCSV(file.raw)
    importResult.value = { count: res.count }
    ElMessage.success(`导入成功 ${res.count} 条`)
  } catch (e) {
    ElMessage.error('导入失败')
  }
}

const exportTrades = () => {
  ElMessage.info('导出功能开发中')
}
const exportStocks = () => {
  ElMessage.info('导出功能开发中')
}
</script>

<style scoped>
.result { margin-top: 16px; }
</style>
