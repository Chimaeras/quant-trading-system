/**
 * IndexedDB 离线存储封装
 */
import { openDB, DBSchema, IDBPDatabase } from 'idb'

const DB_NAME = 'quant-offline-db'
const DB_VERSION = 1

export interface QuantDBSchema extends DBSchema {
  stocks: {
    key: string
    value: { stock_code: string; exchange_type: string; [key: string]: unknown }
    indexes: { 'by-code': string; 'by-exchange': string }
  }
  market_data: {
    key: string
    value: { stock_code: string; trade_date: string; [key: string]: unknown }
    indexes: { 'by-stock': string; 'by-date': string }
  }
  strategies: {
    key: number
    value: Record<string, unknown>
  }
  trades: {
    key: number
    value: Record<string, unknown>
    indexes: { 'by-date': string }
  }
  positions: {
    key: string
    value: Record<string, unknown>
  }
  settings: {
    key: string
    value: unknown
  }
}

let dbInstance: IDBPDatabase<QuantDBSchema> | null = null

export async function getDB() {
  if (!dbInstance) {
    dbInstance = await openDB<QuantDBSchema>(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains('stocks')) {
          const stockStore = db.createObjectStore('stocks', {
            keyPath: ['stock_code', 'exchange_type']
          })
          stockStore.createIndex('by-code', 'stock_code')
          stockStore.createIndex('by-exchange', 'exchange_type')
        }
        if (!db.objectStoreNames.contains('market_data')) {
          const dataStore = db.createObjectStore('market_data', {
            keyPath: ['stock_code', 'trade_date']
          })
          dataStore.createIndex('by-stock', 'stock_code')
          dataStore.createIndex('by-date', 'trade_date')
        }
        if (!db.objectStoreNames.contains('strategies')) {
          db.createObjectStore('strategies', { keyPath: 'id', autoIncrement: true })
        }
        if (!db.objectStoreNames.contains('trades')) {
          const tradeStore = db.createObjectStore('trades', {
            keyPath: 'id',
            autoIncrement: true
          })
          tradeStore.createIndex('by-date', 'trade_time')
        }
        if (!db.objectStoreNames.contains('positions')) {
          db.createObjectStore('positions', { keyPath: 'stock_code' })
        }
        if (!db.objectStoreNames.contains('settings')) {
          db.createObjectStore('settings', { keyPath: 'key' })
        }
      }
    })
  }
  return dbInstance
}
