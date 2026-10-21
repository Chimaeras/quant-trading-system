/**
 * 注册模块解析钩子（供 `node --import` 使用）。
 * 用绝对 file:// URL 注册，避免 Windows 路径被当成包名。
 */
import { register } from 'node:module'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
register(pathToFileURL(path.join(HERE, 'harness-loader.mjs')).href)
