<template>
  <div class="watch-monitor terminal-page">
    <!-- ① 状态条 -->
    <el-card shadow="hover" class="status-bar">
      <div class="status-row">
        <div class="status-item">
          <span class="label">交易日</span>
          <el-select v-model="selectedDate" style="width: 140px" size="small" @change="onDateChange">
            <el-option v-for="d in days" :key="d" :label="d" :value="d" />
          </el-select>
        </div>
        <div class="status-item">
          <span class="label">模式</span>
          <el-tag :type="isLive ? 'danger' : 'info'" size="small">{{ isLive ? '模拟盘 live' : 'dry-run' }}</el-tag>
        </div>
        <div class="status-item">
          <span class="label">轮次</span>
          <span class="value">{{ lastCycle?.cycle ?? '—' }}</span>
        </div>
        <div class="status-item">
          <span class="label">最近状态</span>
          <el-tag :type="cycleStatusType" size="small">{{ (lastCycle?.detail as any)?.status ?? '—' }}</el-tag>
        </div>
        <div class="status-item">
          <span class="label">今日笔数</span>
          <span class="value">{{ state?.state?.day_trade_count ?? 0 }} / {{ maxTrades }}</span>
        </div>
        <div class="status-item">
          <span class="label">最后更新</span>
          <span class="value">{{ lastCycle?.ts ?? '—' }}</span>
        </div>
        <div class="status-item">
          <span class="label">L1覆盖</span>
          <span class="value">{{ l1Coverage }}</span>
        </div>
        <div class="status-item">
          <span class="label">L2活跃池</span>
          <span class="value">{{ marketSnapshot?.active_pool?.length ?? 0 }}</span>
        </div>
        <div class="status-item">
          <span class="label">策略扫描</span>
          <span class="value">{{ strategyScanCount }}</span>
        </div>
        <div class="status-item">
          <span class="label">行情覆盖</span>
          <span class="value">{{ poolQuoteCoverage }}</span>
        </div>
        <div class="status-item grow">
          <el-alert v-if="!serviceUp" type="warning" :closable="false" show-icon
            title="watch-serve 未启动：请在 compute-python 下运行 python -m paper.cli watch-serve" />
          <el-alert v-else-if="halted" type="error" :closable="false" show-icon
            title="STOP_RUN 已触发：程序已自动停止真实提交，仅 dry-run" />
        </div>
        <div class="status-item">
          <el-switch v-model="autoRefresh" active-text="自动刷新" size="small" />
        </div>
      </div>
    </el-card>

    <div class="main-grid">
      <!-- ② 观察池 -->
      <el-card shadow="never" class="panel universe-panel">
        <template #header>
          <div class="panel-header pool-header">
            <div class="panel-title">
              <span>观察池（{{ watchPool?.total ?? 0 }}）</span>
              <span class="panel-hint">横向滚动查看完整字段</span>
            </div>
            <div class="pool-add">
              <el-input v-model="addCode" maxlength="6" placeholder="证券代码" size="small"
                @keyup.enter="addSecurity" />
              <el-button type="primary" size="small" :loading="adding" @click="addSecurity">添加</el-button>
            </div>
          </div>
        </template>
        <div class="table-host">
          <el-table class="watch-table pool-table" :data="universeRows" size="small" @row-click="openDetail" highlight-current-row>
          <el-table-column prop="code" label="代码" min-width="84">
            <template #default="{ row }">
              <el-link type="primary" :underline="false">{{ row.code }}</el-link>
            </template>
          </el-table-column>
          <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="pool-name" :title="row.name || ''">{{ row.name || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最新价" min-width="90" align="right">
            <template #default="{ row }">{{ row.last?.toFixed(2) ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="涨跌幅" min-width="90" align="right">
            <template #default="{ row }">
              <span v-if="row.pct !== undefined" :class="row.pct >= 0 ? 'up' : 'down'">
                {{ row.pct >= 0 ? '+' : '' }}{{ row.pct.toFixed(2) }}%
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" min-width="100">
            <template #default="{ row }">
              <el-tooltip v-if="row.disabled" :content="row.disabled" placement="top">
                <el-tag type="info" size="small">禁用</el-tag>
              </el-tooltip>
              <el-tag v-else-if="row.active" type="success" size="small">L2分析</el-tag>
              <el-tag v-else type="info" size="small">L1扫描</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="来源" min-width="78">
            <template #default="{ row }">
              <el-tag :type="row.manual ? 'warning' : 'info'" size="small">
                {{ row.manual ? '手动' : '热门' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" min-width="64" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.manual" link type="danger" size="small" @click.stop="removeSecurity(row.code)">移除</el-button>
            </template>
          </el-table-column>
          </el-table>
        </div>
      </el-card>

      <!-- ③ 分析过程流 -->
      <el-card shadow="never" class="panel events-panel">
        <template #header>
          <div class="panel-header">
            <div class="panel-title">
              <span>分析过程流（显示 {{ filteredEvents.length }} / 缓存 {{ events.length }}）</span>
              <span class="panel-hint">横向滚动 / 展开箭头查看完整字段</span>
            </div>
            <div class="filters">
              <el-select v-model="typeFilter" clearable placeholder="事件类型" size="small" style="width: 130px">
                <el-option v-for="t in typeOptions" :key="t" :label="t" :value="t" />
              </el-select>
              <el-input v-model="codeFilter" placeholder="代码" clearable size="small" style="width: 100px" />
              <el-switch v-model="hideNoise" active-text="隐藏行情噪音" size="small" />
              <el-switch v-model="tradesOnly" active-text="只看买卖" size="small" />
            </div>
          </div>
        </template>
        <div class="table-host">
          <el-table class="watch-table events-table" :data="filteredEvents" size="small" border :row-key="(r: any) => r.seq">
          <el-table-column prop="ts" label="时间" min-width="106">
            <template #default="{ row }">{{ row.ts.slice(11) }}</template>
          </el-table-column>
          <el-table-column prop="seq" label="#" min-width="64" sortable />
          <el-table-column label="类型" min-width="100">
            <template #default="{ row }">
              <el-tag :type="typeTagType(row.type, row.detail)" size="small">{{ row.type }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="code" label="代码" min-width="84" />
          <el-table-column prop="name" label="名称" min-width="120" show-overflow-tooltip />
          <el-table-column label="内容" min-width="360" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="event-summary" :class="eventSummaryClass(row)">{{ eventSummary(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="event-detail">
                <template v-if="row.type === 'RISK_GATE'">
                  <div class="gates">
                    <el-tag v-for="(g, i) in ((row.detail as any).gates || [])" :key="i" size="small"
                      :type="g.result === 'PASS' ? 'success' : 'danger'">
                      {{ g.gate }}:{{ g.result }}
                    </el-tag>
                  </div>
                </template>
                <pre>{{ formatEventDetail(row) }}</pre>
              </div>
            </template>
          </el-table-column>
          </el-table>
        </div>
      </el-card>

      <div class="right-column">
        <!-- ④ 交易动作 -->
        <el-card shadow="never" class="panel submits-panel">
          <template #header>
            <div class="panel-header">
              <div class="panel-title">
                <span>交易动作（{{ tradeActionRows.length }}）</span>
                <span class="panel-hint">委托价、实际成交价和成交结果</span>
              </div>
            </div>
          </template>
          <div class="table-host">
            <el-table class="watch-table submits-table" :data="tradeActionRows" size="small">
            <el-table-column prop="ts" label="时间" min-width="100">
              <template #default="{ row }">{{ row.ts.slice(11) }}</template>
            </el-table-column>
            <el-table-column prop="code" label="代码" min-width="84" />
            <el-table-column prop="name" label="名称" min-width="112" show-overflow-tooltip />
            <el-table-column label="动作" min-width="68">
              <template #default="{ row }">
                <el-tag :type="row.type === 'FILL' ? 'success' : 'primary'" size="small">
                  {{ row.type === 'FILL' ? '成交' : '委托' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="方向" min-width="60">
              <template #default="{ row }">
                <span :class="(row.detail as any).side === 'buy' ? 'up' : 'down'">
                  {{ (row.detail as any).side === 'buy' ? '买' : '卖' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="qty" label="数量" min-width="78" align="right" />
            <el-table-column label="委托价" min-width="88" align="right">
              <template #default="{ row }">{{ fmtPrice(row.orderPrice) }}</template>
            </el-table-column>
            <el-table-column label="成交价" min-width="88" align="right">
              <template #default="{ row }">{{ fmtPrice(row.fillPrice) }}</template>
            </el-table-column>
            <el-table-column label="结果" min-width="120">
              <template #default="{ row }">
                <el-tag :type="submitTagType((row.detail as any).status)" size="small">
                  {{ (row.detail as any).status }}
                </el-tag>
                <div v-if="(row.detail as any).error_code" class="error-code">{{ (row.detail as any).error_code }}</div>
              </template>
            </el-table-column>
            </el-table>
          </div>
        </el-card>

        <!-- ⑤ 账户快照 -->
        <el-card shadow="never" class="panel account-panel">
          <template #header>
            <div class="panel-header">
              <span>盯盘模拟账户<template v-if="accountView.session_id"> · {{ accountView.session_id }}</template></span>
              <div class="account-meta">
                <span class="panel-hint">全程 · 最新会话</span>
                <span v-if="isHistoricalAccountView" class="panel-hint">账户为最新会话累计（与所选日期无关）</span>
                <el-tag v-if="accountPhaseLabel" size="small" effect="plain" :type="accountPhaseType">
                  {{ accountPhaseLabel }}
                </el-tag>
                <span v-if="latestAccountEvent" class="panel-hint">{{ latestAccountEvent.ts.slice(11) }}</span>
              </div>
            </div>
          </template>
          <div class="account-content">
            <div class="account-grid account-grid--finance">
              <div class="account-item">
                <span class="label">总资产</span>
                <span class="value">{{ fmtMoney(accountFinance.total_assets) }}</span>
              </div>
              <div class="account-item">
                <span class="label">可用资金</span>
                <span class="value">{{ fmtMoney(accountView.avail_balance) }}</span>
              </div>
              <div class="account-item">
                <span class="label">冻结资金</span>
                <span class="value">{{ fmtMoney(accountView.frozen_cash ?? 0) }}</span>
              </div>
              <div class="account-item">
                <span class="label">持仓市值</span>
                <span class="value">{{ fmtMoney(accountFinance.market_value) }}</span>
              </div>
              <div class="account-item">
                <span class="label">浮动盈亏</span>
                <span class="value" :class="numberClass(accountFinance.unrealized_pnl)">{{ formatSignedMoney(accountFinance.unrealized_pnl) }}</span>
              </div>
              <div class="account-item">
                <span class="label">已实现盈亏</span>
                <span class="value" :class="numberClass(accountView.realized_pnl)">{{ formatSignedMoney(accountView.realized_pnl) }}</span>
              </div>
              <div class="account-item">
                <span class="label">总收益率</span>
                <span class="value" :class="numberClass(accountFinance.total_return_pct)">{{ formatSignedPercent(accountFinance.total_return_pct) }}</span>
              </div>
              <div class="account-item">
                <span class="label">持仓数 / 在途委托</span>
                <span class="value">{{ accountView.position_count }} / {{ accountView.open_order_count }}</span>
              </div>
            </div>

            <el-tabs v-model="accountTab" class="account-tabs" stretch>
              <el-tab-pane label="持仓" name="positions">
                <section class="account-section">
                  <div class="account-section-header">
                    <span>当前持仓</span>
                    <span class="panel-hint">{{ persistPositions.length }} 条</span>
                  </div>
                  <el-table v-if="persistPositions.length" :data="persistPositions" size="small" border class="account-detail-table">
                    <el-table-column prop="code" label="代码" width="78" />
                    <el-table-column label="名称" min-width="100" show-overflow-tooltip>
                      <template #default="{ row }">{{ accountSecurityName(row) }}</template>
                    </el-table-column>
                    <el-table-column prop="qty" label="持仓" width="72" align="right" />
                    <el-table-column prop="avail_qty" label="可卖" width="72" align="right" />
                    <el-table-column label="成本价" width="88" align="right">
                      <template #default="{ row }">{{ fmtPrice(row.cost) }}</template>
                    </el-table-column>
                    <el-table-column label="当前价" width="88" align="right">
                      <template #default="{ row }">{{ fmtPrice(row.last) }}</template>
                    </el-table-column>
                    <el-table-column label="市值" width="100" align="right">
                      <template #default="{ row }">{{ fmtMoney(row.market_value) }}</template>
                    </el-table-column>
                    <el-table-column label="浮动盈亏" width="104" align="right">
                      <template #default="{ row }">
                        <span :class="numberClass(row.profit)">{{ formatSignedMoney(row.profit) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="盈亏率" width="88" align="right">
                      <template #default="{ row }">
                        <span :class="numberClass(row.profit)">{{ formatSignedPercent(positionProfitPct(row)) }}</span>
                      </template>
                    </el-table-column>
                  </el-table>
                  <div v-else class="account-empty">暂无持仓明细</div>
                </section>
              </el-tab-pane>

              <el-tab-pane label="委托历史" name="orders">
                <section class="account-section">
                  <div class="account-section-header">
                    <span>委托历史</span>
                    <span class="panel-hint">已加载 {{ orders.length }} 条{{ orders.length >= 500 ? '（仅最近 500 条）' : '' }}</span>
                    <el-select v-model="orderStatusFilter" size="small" placeholder="全部状态" clearable class="account-status-filter">
                      <el-option v-for="s in ORDER_STATUS_OPTIONS" :key="s" :label="s" :value="s" />
                    </el-select>
                  </div>
                  <el-table v-if="filteredOrders.length" :data="filteredOrders" size="small" border class="account-detail-table">
                    <el-table-column label="时间" width="72">
                      <template #default="{ row }">{{ compactTimestamp(row.created_at) }}</template>
                    </el-table-column>
                    <el-table-column prop="code" label="代码" width="78" />
                    <el-table-column label="名称" min-width="96" show-overflow-tooltip>
                      <template #default="{ row }">{{ accountSecurityName(row) }}</template>
                    </el-table-column>
                    <el-table-column label="方向" width="56" align="center">
                      <template #default="{ row }">
                        <span :class="accountSideClass(row.side)">{{ accountSideText(row.side) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="委托价" width="88" align="right">
                      <template #default="{ row }">{{ row.limit_price != null ? Number(row.limit_price).toFixed(2) : '—' }}</template>
                    </el-table-column>
                    <el-table-column prop="qty" label="数量" width="72" align="right" />
                    <el-table-column prop="filled_qty" label="已成" width="60" align="right" />
                    <el-table-column label="状态" width="108">
                      <template #default="{ row }">
                        <el-tag size="small" effect="plain" :type="orderStatusType(row.status)">{{ row.status }}</el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column type="expand">
                      <template #default="{ row }">
                        <div class="account-expand">
                          <div>委托号 {{ row.client_order_id || '—' }}<template v-if="row.broker_order_id"> / 券商号 {{ row.broker_order_id }}</template></div>
                          <div v-if="row.strategy">策略 {{ row.strategy }}</div>
                          <div v-if="row.avg_fill_price != null">成交均价 {{ Number(row.avg_fill_price).toFixed(3) }}，费用 {{ formatSignedMoney(row.total_fee) }}</div>
                          <div v-if="row.last_error_code || row.last_error_message">错误 {{ row.last_error_code || '' }} {{ row.last_error_message || '' }}</div>
                          <div v-if="Object.keys(row.metadata || {}).length">明细 {{ JSON.stringify(row.metadata) }}</div>
                        </div>
                      </template>
                    </el-table-column>
                  </el-table>
                  <div v-else class="account-empty">暂无委托记录</div>
                </section>
              </el-tab-pane>

              <el-tab-pane label="成交记录" name="fills">
                <section class="account-section">
                  <div class="account-section-header">
                    <span>成交记录</span>
                    <span class="panel-hint">{{ fills.length }} 条{{ fills.length >= 500 ? '（仅最近 500 条）' : '' }}</span>
                  </div>
                  <el-table v-if="fills.length" :data="fills" size="small" border class="account-detail-table">
                    <el-table-column label="时间" width="72">
                      <template #default="{ row }">{{ compactTimestamp(row.filled_at) }}</template>
                    </el-table-column>
                    <el-table-column prop="code" label="代码" width="78" />
                    <el-table-column label="名称" min-width="96" show-overflow-tooltip>
                      <template #default="{ row }">{{ accountSecurityName(row) }}</template>
                    </el-table-column>
                    <el-table-column label="方向" width="56" align="center">
                      <template #default="{ row }">
                        <span :class="accountSideClass(row.side)">{{ accountSideText(row.side) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column prop="qty" label="数量" width="72" align="right" />
                    <el-table-column label="成交价" width="88" align="right">
                      <template #default="{ row }">{{ fmtPrice(row.price) }}</template>
                    </el-table-column>
                    <el-table-column label="金额" width="104" align="right">
                      <template #default="{ row }">{{ fmtMoney(row.gross_amount) }}</template>
                    </el-table-column>
                    <el-table-column label="费用" width="92" align="right">
                      <template #default="{ row }">{{ fmtMoney(row.total_fee) }}</template>
                    </el-table-column>
                    <el-table-column type="expand">
                      <template #default="{ row }">
                        <div class="account-expand">
                          <div>成交号 {{ row.fill_id || '—' }} / 委托号 {{ row.client_order_id || '—' }}</div>
                          <div>佣金 {{ fmtMoney(row.commission) }}，印花税 {{ fmtMoney(row.stamp_tax) }}，来源 {{ row.price_source || '—' }}</div>
                          <div>成交时间 {{ row.filled_at }}</div>
                        </div>
                      </template>
                    </el-table-column>
                  </el-table>
                  <div v-else class="account-empty">暂无成交记录</div>
                </section>
              </el-tab-pane>

              <el-tab-pane label="收益分析" name="pnl">
                <section class="account-section">
                  <div class="account-section-header">
                    <span>每只收益</span>
                      <span class="panel-hint">
                        已实现 {{ formatSignedMoney(pnlTotalsView?.realized) }} · 未实现 {{ formatSignedMoney(pnlTotalsView?.unrealized) }} · 合计 {{ formatSignedMoney(pnlTotalsView?.total) }}
                        <template v-if="pnlTotalsView?.consistent"> · 与账户一致</template>
                        <template v-else-if="pnlTotalsView"> · <el-tag type="danger" size="small" effect="plain">重放与账户已实现有偏差</el-tag></template>
                      </span>
                  </div>
                  <el-table v-if="pnlCodesView.length" :data="pnlCodesView" size="small" border class="account-detail-table" :row-key="(r: any) => r.code">
                    <el-table-column prop="code" label="代码" width="78" />
                    <el-table-column label="名称" min-width="96" show-overflow-tooltip>
                      <template #default="{ row }">{{ accountSecurityName(row) }}</template>
                    </el-table-column>
                    <el-table-column label="现持仓" width="72" align="right">
                      <template #default="{ row }">{{ row.qty }}</template>
                    </el-table-column>
                    <el-table-column label="平均成本" width="88" align="right">
                      <template #default="{ row }">{{ fmtPrice(row.avg_cost) }}</template>
                    </el-table-column>
                    <el-table-column label="已实现" width="96" align="right">
                      <template #default="{ row }">
                        <span :class="numberClass(row.realized)">{{ formatSignedMoney(row.realized) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="未实现" width="96" align="right">
                      <template #default="{ row }">
                        <span :class="numberClass(row.unrealized)">{{ formatSignedMoney(row.unrealized) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="合计" width="104" align="right">
                      <template #default="{ row }">
                        <span :class="numberClass(row.total)">{{ formatSignedMoney(row.total) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="交易" width="60" align="right">
                      <template #default="{ row }">{{ row.trades.length }}</template>
                    </el-table-column>
                    <el-table-column type="expand">
                      <template #default="{ row }">
                        <div v-if="row.trades.length" class="account-expand">
                          <el-table :data="row.trades" size="small" border class="pnl-trades-table">
                            <el-table-column label="时间" width="120">
                              <template #default="{ row: t }">{{ compactTimestamp(t.ts) }}</template>
                            </el-table-column>
                            <el-table-column prop="qty" label="卖出数量" width="72" align="right" />
                            <el-table-column label="价格" width="88" align="right">
                              <template #default="{ row: t }">{{ fmtPrice(t.price) }}</template>
                            </el-table-column>
                            <el-table-column label="均价" width="88" align="right">
                              <template #default="{ row: t }">{{ fmtPrice(t.avg_cost) }}</template>
                            </el-table-column>
                            <el-table-column label="费用" width="80" align="right">
                              <template #default="{ row: t }">{{ fmtMoney(t.fee) }}</template>
                            </el-table-column>
                            <el-table-column label="已实现" width="96" align="right">
                              <template #default="{ row: t }">
                                <span :class="numberClass(t.realized)">{{ formatSignedMoney(t.realized) }}</span>
                              </template>
                            </el-table-column>
                          </el-table>
                        </div>
                        <div v-else class="account-empty">该标的仅买入，无已实现卖出</div>
                      </template>
                    </el-table-column>
                  </el-table>
                  <div v-else class="account-empty">暂无收益记录</div>
                </section>
              </el-tab-pane>
            </el-tabs>

            <div v-if="latestAccountEvent && Object.keys(accountBefore).length" class="account-after" :class="accountDeltaClass">
              相对轮次开始：可用资金 {{ formatSignedMoney(accountDelta.avail_balance) }}，持仓数 {{ formatSignedNumber(accountDelta.positions) }}
            </div>
          </div>
        </el-card>
      </div>
    </div>

    <!-- ⑥ 标的明细 -->
    <el-drawer v-model="detailVisible" size="62%" :title="`${detailCode} ${detailName} 盘中分析`">
      <div ref="chartRef" class="detail-chart" />
      <el-table :data="codeSignals(detailCode)" size="small" border class="signal-table">
        <el-table-column prop="ts" label="时间" width="100">
          <template #default="{ row }">{{ row.ts.slice(11) }}</template>
        </el-table-column>
        <el-table-column label="信号" width="90">
          <template #default="{ row }">
            <el-tag :type="(row.detail as any).action === 'buy' ? 'danger' : 'success'" size="small">
              {{ (row.detail as any).action }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指标值" min-width="240">
          <template #default="{ row }">{{ signalDetailText(row) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, nextTick } from 'vue'
import {
  addWatchPoolMember,
  fetchAccount,
  fetchDays,
  fetchEvents,
  fetchFills,
  fetchMarket,
  fetchOrders,
  fetchPnl,
  fetchState,
  fetchTicks,
  fetchWatchPool,
  removeWatchPoolMember,
  type WatchEvent,
  type WatchMarketSnapshot,
  type WatchPoolResponse,
  type WatchTickSummary,
  type WatchAccount,
  type WatchOrderRow,
  type WatchFillRow,
  type WatchPnl
} from '@/api/watch'
import { inferExchangeType, resolveSecurityName } from '@/utils/security'
import { ElMessage, ElMessageBox } from 'element-plus'

const MAX_EVENTS = 500
/** 表格只渲染最近 N 条，避免 el-table 全量重绘卡死主线程 */
const DISPLAY_EVENTS = 80
const DISPLAY_SUBMITS = 100
const REFRESH_MS = 5000
const POOL_REFRESH_TICKS = 6
const MARKET_QUOTE_LIMIT = 200
const NAME_LOOKUP_RETRY_MS = 5 * 60 * 1000
/** 行情类事件默认折叠，减少过程流噪音与 DOM 压力 */
const NOISE_TYPES = new Set(['QUOTE', 'QUOTE_FAIL', 'QUOTE_QUALITY'])
const TRADE_EVENT_QUERY = 'SUBMIT,FILL'

const days = shallowRef<string[]>([])
const selectedDate = ref('')
// 接口数据按快照整体替换；使用 shallowRef 避免 Vue 深度代理大型事件 detail。
const events = shallowRef<WatchEvent[]>([])
const tradeEvents = shallowRef<WatchEvent[]>([])
const state = shallowRef<Awaited<ReturnType<typeof fetchState>> | null>(null)
const marketSnapshot = shallowRef<WatchMarketSnapshot | null>(null)
const watchPool = shallowRef<WatchPoolResponse | null>(null)
const ticks = shallowRef<WatchTickSummary[]>([])
const serviceUp = ref(true)
const autoRefresh = ref(true)
const addCode = ref('')
const adding = ref(false)

const typeFilter = ref('')
const codeFilter = ref('')
const tradesOnly = ref(false)
const hideNoise = ref(true)
const typeOptions = ['CYCLE', 'READINESS', 'MARKET_DATA', 'ACTIVE_POOL', 'QUOTE', 'QUOTE_FAIL', 'QUOTE_QUALITY', 'BAR_CLOSED', 'SIGNAL', 'SIZING', 'RISK_GATE', 'ORDER', 'SUBMIT', 'FILL', 'CANCEL', 'ACCOUNT', 'ERROR']
let refreshTick = 0

const detailVisible = ref(false)
const detailCode = ref('')
const detailName = ref('')
const securityNames = shallowRef<Record<string, string>>({})
const nameLookupAt = new Map<string, number>()
const chartRef = ref<HTMLElement | null>(null)
type ChartRuntime = typeof import('@/utils/echartsTheme')
type ChartInstance = ReturnType<ChartRuntime['echarts']['init']>
let chartRuntimePromise: Promise<ChartRuntime> | null = null
let chart: ChartInstance | null = null
let timer: number | null = null
let cursor = 0
let refreshEpoch = 0
let refreshSequence = 0
let lastAppliedRefresh = 0
let disposed = false

const lastCycle = computed(() => {
  for (let i = events.value.length - 1; i >= 0; i -= 1) {
    const event = events.value[i]
    if (event.type === 'CYCLE' && (event.detail as any).phase === 'end') return event
  }
  return null
})
const isLive = computed(() => Boolean((lastCycle.value?.detail as any)?.live))
const cycleStatusType = computed(() => {
  const status = (lastCycle.value?.detail as any)?.status as string | undefined
  if (status === 'OK') return 'success'
  if (status === 'DEGRADED' || status === 'SLEEPING' || status === 'NO_UNIVERSE') return 'warning'
  return 'danger'
})
const halted = computed(() =>
  events.value.some((e) => e.type === 'ERROR' && String((e.detail as any).scope) === 'STOP_RUN')
)
const maxTrades = 20
const l1Coverage = computed(() => {
  const value = Number(marketSnapshot.value?.batch?.coverage)
  return Number.isFinite(value) ? `${(value * 100).toFixed(2)}%` : '—'
})
const strategyScanCount = computed(() => {
  const total = Number(watchPool.value?.total || 0)
  if (!total) return '—'
  return `${total} 只`
})
const poolQuoteCoverage = computed(() => {
  const items = watchPool.value?.items || []
  if (!items.length) return '—'
  const poolCodes = new Set(items.map((item) => String(item.code)))
  const covered = (marketSnapshot.value?.quotes || []).filter((quote) => {
    if (!poolCodes.has(String(quote.code))) return false
    const quality = String(quote.quality || quote.validity || '').toUpperCase()
    return !['INVALID', 'STALE', 'MISSING'].includes(quality)
  }).length
  return `${covered}/${items.length}`
})
const poolSecurityNames = computed<Record<string, string>>(() =>
  Object.fromEntries((watchPool.value?.items || []).map((item) => [item.code, item.name]))
)

const universeRows = computed(() => {
  const rows = new Map<string, { code: string; name?: string; last?: number; pct?: number; disabled?: string; active?: boolean; manual?: boolean }>()
  const active = new Set((marketSnapshot.value?.active_pool || []).map((item: any) => String(item.code)))
  for (const item of watchPool.value?.items || []) {
    rows.set(item.code, {
      code: item.code,
      name: item.name,
      active: active.has(item.code),
      manual: Boolean(item.manual)
    })
  }
  for (const quote of marketSnapshot.value?.quotes || []) {
    if (watchPool.value?.available && !rows.has(String(quote.code))) continue
    const existing = rows.get(String(quote.code))
    rows.set(String(quote.code), {
      code: String(quote.code),
      name: String(quote.name || existing?.name || ''),
      last: ['INVALID', 'STALE', 'MISSING'].includes(String(quote.quality))
        ? undefined : Number(quote.last),
      pct: quote.pct_chg == null ? undefined : Number(quote.pct_chg),
      disabled: ['INVALID', 'STALE', 'MISSING'].includes(String(quote.quality))
        ? String(quote.quality_reason || quote.quality) : undefined,
      active: active.has(String(quote.code)),
      manual: existing?.manual
    })
  }
  for (const e of events.value) {
    if (watchPool.value?.available && e.code && !rows.has(e.code)) continue
    if (e.type === 'QUOTE' && e.code && !rows.has(e.code)) {
      rows.set(e.code, {
        code: e.code,
        name: eventName(e),
        last: (e.detail as any).last,
        pct: (e.detail as any).pct_chg
      })
    } else if (e.type === 'ERROR' && e.code && (e.detail as any).scope === 'KLINE_DISABLED' && !rows.has(e.code)) {
      rows.set(e.code, { code: e.code, name: eventName(e), disabled: String((e.detail as any).message || 'KLINE_DISABLED') })
    }
  }
  return [...rows.values()]
})

const TRADE_TYPES = new Set(['SIGNAL', 'SIZING', 'RISK_GATE', 'ORDER', 'SUBMIT', 'FILL', 'CANCEL'])
function isBuyOrSellSignal(event: WatchEvent): boolean {
  if (event.type !== 'SIGNAL') return true
  const action = String((event.detail as any).action || '').trim().toLowerCase()
  return action === 'buy' || action === 'sell'
}

const filteredEvents = computed(() => {
  const result: Array<WatchEvent & { name: string }> = []
  for (let i = events.value.length - 1; i >= 0 && result.length < DISPLAY_EVENTS; i -= 1) {
    const event = events.value[i]
    if (isSupersededKlineEvent(event)) continue
    if (hideNoise.value && NOISE_TYPES.has(event.type)) continue
    if (typeFilter.value && event.type !== typeFilter.value) continue
    if (codeFilter.value && event.code !== codeFilter.value) continue
    if (tradesOnly.value && (!TRADE_TYPES.has(event.type) || !isBuyOrSellSignal(event))) continue
    result.push({ ...event, name: eventName(event) })
  }
  return result
})

const latestKlineEvents = computed(() => {
  const latest = new Map<string, WatchEvent>()
  for (const event of events.value) {
    if (event.type !== 'ERROR' || !event.code) continue
    const scope = String((event.detail as any).scope || '')
    if (!scope.startsWith('KLINE')) continue
    const previous = latest.get(event.code)
    if (!previous || event.seq > previous.seq) latest.set(event.code, event)
  }
  return latest
})

function isSupersededKlineEvent(event: WatchEvent): boolean {
  if (event.type !== 'ERROR' || !event.code) return false
  const scope = String((event.detail as any).scope || '')
  if (!scope.startsWith('KLINE')) return false
  const latest = latestKlineEvents.value.get(event.code)
  return Boolean(latest && latest.seq !== event.seq)
}

const tradeActionRows = computed(() => {
  const orderPrices = new Map<string, number>()
  for (const event of tradeEvents.value) {
    if (event.type !== 'SUBMIT') continue
    const detail = event.detail as any
    const orderId = String(detail.order_id || '')
    const price = Number(detail.price)
    if (orderId && Number.isFinite(price)) orderPrices.set(orderId, price)
  }
  return tradeEvents.value
    .filter((event) => event.type === 'SUBMIT' || event.type === 'FILL')
    .sort((left, right) => right.seq - left.seq)
    .slice(0, DISPLAY_SUBMITS)
    .map((event) => {
      const detail = event.detail as any
      const orderId = String(detail.order_id || detail.client_order_id || '')
      const orderPrice = event.type === 'SUBMIT'
        ? Number(detail.price)
        : orderPrices.get(orderId)
      return {
        ...event,
        name: eventName(event),
        qty: Number(detail.qty ?? detail.filled_qty ?? 0),
        orderPrice: Number.isFinite(orderPrice) ? orderPrice : undefined,
        fillPrice: event.type === 'FILL' && Number.isFinite(Number(detail.price))
          ? Number(detail.price) : undefined,
      }
    })
})

const accountEvents = computed(() => events.value.filter((e) => e.type === 'ACCOUNT'))
const latestAccountEvent = computed<WatchEvent | null>(() => {
  const list = accountEvents.value
  return list.length ? list[list.length - 1] : null
})
const latestAccountTick = computed<WatchTickSummary | null>(() => {
  let latest: WatchTickSummary | null = null
  for (const tick of ticks.value) {
    if (!tick.account || !Object.keys(tick.account).length) continue
    if (!latest || String(tick.finished_at) > String(latest.finished_at)) latest = tick
  }
  return latest
})
const latestTickAccount = computed<Record<string, any>>(() => {
  const account = latestAccountTick.value?.account as Record<string, any> | undefined
  if (!account) return {}
  for (const phase of ['after', 'after_match', 'before']) {
    const snapshot = account[phase]
    if (snapshot && typeof snapshot === 'object' && !Array.isArray(snapshot)) {
      return { ...snapshot, phase }
    }
  }
  return account
})
const currentAccount = computed<Record<string, any>>(() => ({
  ...((latestAccountEvent.value?.detail as any) || {}),
  ...latestTickAccount.value
}))
const accountPositionCount = computed(() => accountDetailCount(currentAccount.value.position_details ?? currentAccount.value.positions))
const accountOrderCount = computed(() => accountDetailCount(currentAccount.value.order_details ?? currentAccount.value.orders))
const accountBefore = computed<Record<string, any>>(() => {
  const list = accountEvents.value.filter((e) => (e.detail as any).phase === 'before')
  return list.length ? (list[list.length - 1].detail as any) : {}
})
const accountPhaseLabel = computed(() => {
  const phase = String((currentAccount.value as any).phase || '')
  if (phase === 'after_match') return '成交后'
  if (phase === 'after') return '提交后'
  if (phase === 'before') return '轮次开始'
  return ''
})
const accountPhaseType = computed<WatchTagType>(() => {
  if (accountPhaseLabel.value === '成交后') return 'success'
  if (accountPhaseLabel.value === '提交后') return 'primary'
  return 'info'
})
const accountDelta = computed(() => ({
  avail_balance: Number(currentAccount.value.avail_balance ?? 0) - Number(accountBefore.value.avail_balance ?? 0),
  positions: accountPositionCount.value - accountDetailCount(accountBefore.value.position_details ?? accountBefore.value.positions)
}))
const accountDeltaClass = computed(() => {
  if (accountDelta.value.avail_balance > 0) return 'positive'
  if (accountDelta.value.avail_balance < 0) return 'negative'
  return ''
})

// ---------- 持久化盯盘模拟账户（serve 只读端点，/account · /orders · /fills · /pnl） ----------
const account = shallowRef<WatchAccount | null>(null)
const orders = shallowRef<WatchOrderRow[]>([])
const fills = shallowRef<WatchFillRow[]>([])
const pnl = shallowRef<WatchPnl | null>(null)
const accountTab = ref('positions')
const orderStatusFilter = ref('')
const ORDER_STATUS_OPTIONS = ['SUBMITTED', 'PARTIAL', 'FILLED', 'CANCELLED', 'REJECTED', 'EXPIRED', 'SUBMITTING', 'CREATED', 'CANCEL_PENDING']

/** 账户总览：优先取 /account 持久化数据，未就绪退回事件/tick 快照。 */
const accountView = computed<Record<string, any>>(() => {
  const a = account.value
  const c = currentAccount.value
  if (a?.available && a.session_id) {
    return {
      total_assets: a.total_assets,
      initial_cash: a.initial_cash,
      avail_balance: a.avail_balance,
      cash: a.cash,
      frozen_cash: a.frozen_cash,
      market_value: a.market_value,
      unrealized_pnl: a.unrealized_pnl,
      realized_pnl: a.realized_pnl,
      total_return_pct: a.total_return_pct,
      position_count: a.position_count,
      open_order_count: a.open_order_count,
      session_id: a.session_id,
    }
  }
  return {
    total_assets: c.total_assets,
    initial_cash: null,
    avail_balance: c.avail_balance,
    cash: c.cash ?? c.avail_balance,
    frozen_cash: c.frozen ?? null,
    market_value: c.market_value,
    unrealized_pnl: null,
    realized_pnl: null,
    total_return_pct: null,
    position_count: accountPositionCount.value,
    open_order_count: accountOrderCount.value,
    session_id: '',
  }
})

/** 是否正查看历史交易日：账户/收益/委托/成交是「最新会话累计」，与所选日期无关。 */
const isHistoricalAccountView = computed(() => {
  const latest = days.value[0]
  return !!(latest && selectedDate.value && selectedDate.value !== latest)
})

/** 有效实时行情：code -> last。quality 无效/过期/缺失或 last 非正数都排除。 */
const liveQuotes = computed<Map<string, number>>(() => {  const quotes = new Map<string, number>()
  for (const quote of marketSnapshot.value?.quotes || []) {
    const quality = String(quote.quality || quote.validity || '')
    if (['INVALID', 'STALE', 'MISSING'].includes(quality)) continue
    const last = Number(quote.last)
    if (!Number.isFinite(last) || last <= 0) continue
    quotes.set(String(quote.code), last)
  }
  return quotes
})

/** 持仓 tab：以 /account positions 为权威，用 /market 实时行情覆盖现价重算市值/盈亏。 */
const persistPositions = computed<Record<string, any>[]>(() => {
  const quotes = liveQuotes.value
  return (account.value?.positions || []).map((row) => {
    const qty = Number(row.qty)
    const cost = Number(row.avg_cost)
    const current = quotes.get(String(row.code)) ?? Number(row.last_price)
    const hasCurrent = Number.isFinite(current)
    const hasPositionMath = hasCurrent && Number.isFinite(qty) && Number.isFinite(cost)
    return {
      code: row.code,
      name: row.name,
      qty,
      avail_qty: row.available_qty,
      cost,
      last: hasCurrent ? current : row.last_price,
      market_value: hasPositionMath ? current * qty : row.market_value,
      profit: hasPositionMath ? (current - cost) * qty : row.profit,
    }
  })
})

/** 账户财务口径：总览/收益分析统一用「实时行情覆盖后的持仓」聚合，避免与持仓 tab 口径不一。 */
const accountFinance = computed<Record<string, any>>(() => {
  const v = accountView.value
  const a = account.value
  if (a?.available && a.session_id) {
    const positionsValue = persistPositions.value.reduce((s, p) => s + Number(p.market_value || 0), 0)
    const positionsProfit = persistPositions.value.reduce((s, p) => s + Number(p.profit || 0), 0)
    const cash = Number(v.cash ?? 0)
    const initial = Number(a.initial_cash ?? 0)
    const totalAssets = cash + positionsValue
    return {
      market_value: positionsValue,
      unrealized_pnl: positionsProfit,
      total_assets: totalAssets,
      realized_pnl: Number(v.realized_pnl ?? 0),
      total_return_pct: initial > 0 ? (totalAssets / initial - 1) * 100 : 0,
    }
  }
  return {
    market_value: Number(v.market_value ?? 0),
    unrealized_pnl: Number(v.unrealized_pnl ?? 0),
    total_assets: Number(v.total_assets ?? 0),
    realized_pnl: Number(v.realized_pnl ?? 0),
    total_return_pct: Number(v.total_return_pct ?? 0),
  }
})

const filteredOrders = computed(() => {
  const status = orderStatusFilter.value
  if (!status) return orders.value
  return orders.value.filter((o) => o.status === status)
})

/** 收益分析 per_code：未实现用实时价覆盖（无实时回落端点快照价），保证与持仓 tab 一致。 */
const pnlCodesView = computed<Record<string, any>[]>(() => {
  const quotes = liveQuotes.value
  return (pnl.value?.per_code || []).map((item) => {
    const realized = Number(item.realized || 0)
    const current = quotes.get(String(item.code))
    const unrealized = current !== undefined
      ? (current - Number(item.avg_cost || 0)) * Number(item.qty || 0)
      : Number(item.unrealized || 0)
    return { ...item, unrealized, total: realized + unrealized }
  })
})

const pnlTotalsView = computed<Record<string, any> | null>(() => {
  const raw = pnl.value?.totals
  if (!raw) return null
  const codes = pnlCodesView.value
  if (!codes.length) return raw
  const realized = codes.reduce((s, i) => s + Number(i.realized || 0), 0)
  const unrealized = codes.reduce((s, i) => s + Number(i.unrealized || 0), 0)
  return {
    realized, unrealized, total: realized + unrealized,
    realized_account: raw.realized_account, consistent: raw.consistent,
  }
})

function orderStatusType(status: string): WatchTagType {
  if (status === 'FILLED') return 'success'
  if (status === 'REJECTED' || status === 'EXPIRED') return 'danger'
  if (status === 'CANCELLED' || status === 'CANCEL_PENDING') return 'info'
  if (status === 'SUBMITTED' || status === 'PARTIAL') return 'primary'
  return 'warning'
}

async function loadAccountData() {
  try {
    const [acc, ord, fl, pl] = await Promise.all([
      fetchAccount(),
      fetchOrders({ limit: 500 }),
      fetchFills({ limit: 500 }),
      fetchPnl()
    ])
    if (disposed || !acc.available) return
    account.value = acc
    orders.value = ord.orders || []
    fills.value = fl.fills || []
    pnl.value = pl.available ? pl : null
  } catch {
    // 数据未就绪或已停服时保持上一快照，避免闪空。
  }
}

function eventSummary(row: WatchEvent): string {
  const d = row.detail as any
  switch (row.type) {
    case 'QUOTE':
      return `${Number(d.last).toFixed(2)} ${d.pct_chg >= 0 ? '+' : ''}${Number(d.pct_chg).toFixed(2)}%`
    case 'QUOTE_FAIL':
      return String(d.message || '行情失败')
    case 'BAR_CLOSED':
      return `O ${d.open} C ${d.close} V ${d.volume}`
    case 'SIGNAL': {
      const ind = d.detail || {}
      const parts = Object.entries(ind).map(([k, v]) => `${k}=${v}`)
      return `${d.strategy} → ${d.action}${parts.length ? '（' + parts.join(', ') + '）' : ''}`
    }
    case 'SIZING':
      return d.side === 'buy'
        ? `可用 ${d.avail_balance} × 权重 ${d.weight} → 目标 ${d.target_qty} / 可负担 ${d.affordable_qty} → 定 ${d.final_qty} 股`
        : `可用 ${d.available_qty} 股 → 整手卖出 ${d.lot_qty} 股`
    case 'RISK_GATE':
      return d.result === 'PASS' ? `${d.intent} 通过全部闸门` : `${d.intent} 被拦：${d.reject_reason}`
    case 'SUBMIT':
      return `${d.side} ${d.qty} 股 @ ${d.price?.toFixed(2)}`
    case 'ORDER':
      return `${d.phase || '状态更新'}：${d.status || ''} ${d.client_order_id || ''}`
    case 'FILL':
      return `${d.side} 成交 ${d.qty} 股 @ ${Number(d.price).toFixed(3)}，费用 ${Number(d.total_fee || 0).toFixed(2)}，${d.status}`
    case 'CANCEL':
      return `${d.ok ? '撤单请求成功' : '撤单结果未知'}：${d.client_order_id || d.broker_order_id || ''} ${d.status || ''}`
    case 'ACCOUNT':
      return `${d.phase}：总资产 ${d.total_assets} 可用 ${d.avail_balance} 持仓 ${d.positions} 委托 ${d.open_orders}`
    case 'READINESS': {
      const warmup = (d.checks || []).find((item: any) => item.name === 'strategy_warmup')
      return `${d.ready ? '已就绪' : '未就绪'}，策略预热 ${warmup?.ready ?? 0}/${warmup?.total ?? 0}`
    }
    case 'MARKET_DATA':
      return `L1 ${d.returned_count}/${d.requested_count}，覆盖率 ${(Number(d.coverage || 0) * 100).toFixed(2)}%，耗时 ${d.elapsed_ms}ms`
    case 'ACTIVE_POOL':
      return `L2 ${d.active_count}，强制 ${d.mandatory_count}，晋级 ${(d.promoted || []).length}，淘汰 ${(d.demoted || []).length}`
    case 'CYCLE':
      return d.phase === 'start' ? `轮次开始（${d.live_requested ? '请求 live' : 'dry-run'}）` : `状态 ${d.status}，bar ${d.bars} 信号 ${d.signals} 提交 ${d.submits} 错误 ${d.errors}`
    case 'ERROR': {
      const scope = String(d.scope || 'ERROR')
      if (scope.startsWith('KLINE') && d.status === 'RECOVERED') {
        return `${scope}: 已恢复（${d.bars ?? 0} 根）`
      }
      const messages = Array.isArray(d.messages) ? d.messages.map(String) : []
      if (messages.length > 2) {
        return `${scope}: ${messages.slice(0, 2).join('; ')} …共 ${messages.length} 条`
      }
      const message = String(d.message || messages.join('; ') || '')
      return message.length > 160 ? `${scope}: ${message.slice(0, 160)}…` : `${scope}: ${message}`
    }
    default:
      return JSON.stringify(d).slice(0, 200)
  }
}
function formatEventDetail(row: WatchEvent): string {
  const detail = row.detail as any
  if (row.type === 'ERROR' && Array.isArray(detail?.messages) && detail.messages.length > 20) {
    return JSON.stringify({
      ...detail,
      messages: [...detail.messages.slice(0, 20), `…另有 ${detail.messages.length - 20} 条已省略`]
    }, null, 2)
  }
  if (row.type === 'READINESS' && Array.isArray(detail?.checks)) {
    return JSON.stringify({
      ready: detail.ready,
      strategy_ready_coverage: detail.strategy_ready_coverage,
      checks: detail.checks.map((item: any) => ({
        name: item.name,
        ok: item.ok,
        ready: item.ready,
        total: item.total,
        coverage: item.coverage
      }))
    }, null, 2)
  }
  const text = JSON.stringify(detail, null, 2)
  return text.length > 4000 ? `${text.slice(0, 4000)}\n…(已截断)` : text
}
function eventName(event: WatchEvent): string {
  return inlineEventName(event) || securityNames.value[event.code] || poolSecurityNames.value[event.code] || ''
}
function inlineEventName(event: WatchEvent): string {
  return event.name?.trim() || String((event.detail as any).stock_name || '').trim()
}
async function ensureSecurityNames(list: WatchEvent[]) {
  const now = Date.now()
  const missing = new Set<string>()
  for (const event of list) {
    const code = event.code
    if (!code || inlineEventName(event) || securityNames.value[code] || poolSecurityNames.value[code]) continue
    if (now - (nameLookupAt.get(code) || 0) >= NAME_LOOKUP_RETRY_MS) missing.add(code)
  }
  if (!missing.size) return
  for (const code of missing) nameLookupAt.set(code, now)
  const resolved = await Promise.all([...missing].map(async (code) => [code, await resolveSecurityName(code)] as const))
  const found = Object.fromEntries(resolved.filter(([, name]) => name))
  if (Object.keys(found).length) securityNames.value = { ...securityNames.value, ...found }
}
function eventSummaryClass(row: WatchEvent): string {
  if (row.type === 'SIGNAL' && (row.detail as any).action === 'buy') return 'up'
  if (row.type === 'SIGNAL' && (row.detail as any).action === 'sell') return 'down'
  if (row.type === 'RISK_GATE' && (row.detail as any).result === 'REJECT') return 'down'
  if (row.type === 'QUOTE' && Number((row.detail as any).pct_chg) < 0) return 'down'
  if (row.type === 'QUOTE' && Number((row.detail as any).pct_chg) > 0) return 'up'
  return ''
}
type WatchTagType = 'success' | 'warning' | 'info' | 'primary' | 'danger'
function typeTagType(type: string, detail: Record<string, unknown> = {}): WatchTagType {
  if (
    type === 'ERROR'
    && String(detail.scope || '').startsWith('KLINE')
    && detail.status === 'RECOVERED'
  ) return 'success'
  const map: Record<string, WatchTagType> = {
    SIGNAL: 'warning', ORDER: 'primary', SUBMIT: 'danger', FILL: 'success', CANCEL: 'warning', RISK_GATE: 'primary', SIZING: 'primary',
    MARKET_DATA: 'primary', ACTIVE_POOL: 'success', QUOTE_QUALITY: 'warning',
    BAR_CLOSED: 'info', QUOTE: 'info', QUOTE_FAIL: 'danger', ACCOUNT: 'success', READINESS: 'success', CYCLE: 'info', ERROR: 'danger'
  }
  return map[type] ?? 'info'
}
function submitTagType(status: string): WatchTagType {
  if (status === 'SUBMITTED' || status === 'FILLED') return 'success'
  if (status === 'REJECTED' || status === 'UNKNOWN_SUBMIT') return 'danger'
  if (status === 'DRY_RUN') return 'info'
  return 'warning'
}
function fmtPrice(value: unknown): string {
  if (value === undefined || value === null || value === '' || !Number.isFinite(Number(value))) return '—'
  return Number(value).toFixed(4).replace(/0+$/, '').replace(/\.$/, '')
}
function fmtMoney(value: unknown): string {
  if (value === undefined || value === null || !Number.isFinite(Number(value))) return '—'
  return Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function accountDetailCount(value: unknown): number {
  if (Array.isArray(value)) return value.length
  const count = Number(value)
  return Number.isFinite(count) ? count : 0
}
function accountSecurityName(row: Record<string, any>): string {
  const code = String(row.code || '')
  return String(
    row.name
    || securityNames.value[code]
    || poolSecurityNames.value[code]
    || universeRows.value.find((item) => item.code === code)?.name
    || code
    || '—'
  )
}
function compactTimestamp(value: unknown): string {
  const text = String(value || '')
  if (!text) return '—'
  return text.includes('T') ? text.slice(11, 19) : text.slice(-8)
}
function accountSideText(value: unknown): string {
  const side = String(value || '').toLowerCase()
  if (side === 'buy' || side === 'b' || side === '买') return '买'
  if (side === 'sell' || side === 's' || side === '卖') return '卖'
  return String(value || '—')
}
function accountSideClass(value: unknown): string {
  const side = accountSideText(value)
  return side === '买' ? 'up' : side === '卖' ? 'down' : ''
}
function formatSignedMoney(value: unknown): string {
  if (value === undefined || value === null || !Number.isFinite(Number(value))) return '—'
  const amount = Number(value)
  return `${amount >= 0 ? '+' : ''}${fmtMoney(amount)}`
}
function formatSignedNumber(value: unknown): string {
  if (value === undefined || value === null || !Number.isFinite(Number(value))) return '—'
  const amount = Number(value)
  return `${amount >= 0 ? '+' : ''}${amount}`
}
function formatSignedPercent(value: unknown): string {
  if (value === undefined || value === null || !Number.isFinite(Number(value))) return '—'
  const amount = Number(value)
  return `${amount >= 0 ? '+' : ''}${amount.toFixed(2)}%`
}
function numberClass(value: unknown): string {
  const amount = Number(value)
  return Number.isFinite(amount) && amount > 0 ? 'up' : Number.isFinite(amount) && amount < 0 ? 'down' : ''
}
function positionProfitPct(row: Record<string, any>): number | null {
  const cost = Number(row.cost)
  const last = Number(row.last)
  if (!Number.isFinite(cost) || cost <= 0 || !Number.isFinite(last)) return null
  return (last / cost - 1) * 100
}
function codeSignals(code: string): WatchEvent[] {
  return events.value.filter((e) => e.code === code && e.type === 'SIGNAL' && isBuyOrSellSignal(e))
}
function signalDetailText(row: WatchEvent): string {
  const d = (row.detail as any).detail || {}
  return Object.entries(d).map(([k, v]) => `${k}=${v}`).join('，') || '—'
}

function onDateChange() {
  refreshEpoch += 1
  events.value = []
  tradeEvents.value = []
  cursor = 0
  void refresh(true)
}

async function refresh(full = false) {
  const requestDate = selectedDate.value
  if (!requestDate) return
  const requestEpoch = refreshEpoch
  const requestCursor = cursor
  const requestId = ++refreshSequence
  let eventsChanged = false
  try {
    refreshTick += 1
    if (full) {
      const [{ events: all }, { events: trades }, st, market, pool, tickData] = await Promise.all([
        fetchEvents(requestDate, 0, { limit: MAX_EVENTS, latest: true }),
        fetchEvents(requestDate, 0, { type: TRADE_EVENT_QUERY, limit: DISPLAY_SUBMITS, latest: true }),
        fetchState(requestDate),
        fetchMarket(requestDate, MARKET_QUOTE_LIMIT),
        fetchWatchPool(),
        fetchTicks(requestDate).catch(() => ({ date: requestDate, ticks: ticks.value }))
      ])
      if (
        disposed || requestEpoch !== refreshEpoch || requestDate !== selectedDate.value
        || requestId < lastAppliedRefresh
      ) return
      events.value = all
      tradeEvents.value = trades
      eventsChanged = true
      cursor = all.length ? all[all.length - 1].seq : 0
      state.value = st
      marketSnapshot.value = market
      watchPool.value = pool
      ticks.value = tickData.ticks
      lastAppliedRefresh = requestId
      void ensureSecurityNames(all)
      void loadAccountData()
    } else {
      const refreshSnapshot = refreshTick % 2 === 0
      const refreshPool = refreshTick % POOL_REFRESH_TICKS === 0
      // 事件与快照并发拉取，避免代理耗时串联；观察池低频刷新即可。
      const [{ events: inc }, st, market, pool, tickData] = await Promise.all([
        fetchEvents(requestDate, requestCursor, { limit: 200 }),
        refreshSnapshot ? fetchState(requestDate) : Promise.resolve(state.value),
        refreshSnapshot ? fetchMarket(requestDate, MARKET_QUOTE_LIMIT) : Promise.resolve(marketSnapshot.value),
        refreshPool ? fetchWatchPool() : Promise.resolve(watchPool.value),
        refreshSnapshot
          ? fetchTicks(requestDate).catch(() => ({ date: requestDate, ticks: ticks.value }))
          : Promise.resolve({ date: requestDate, ticks: ticks.value })
      ])
      if (
        disposed || requestEpoch !== refreshEpoch || requestDate !== selectedDate.value
        || requestId < lastAppliedRefresh || requestCursor !== cursor
      ) return
      if (inc.length) {
        events.value = [...events.value, ...inc].slice(-MAX_EVENTS)
        const tradeInc = inc.filter((event) => event.type === 'SUBMIT' || event.type === 'FILL')
        if (tradeInc.length) {
          const bySeq = new Map([...tradeEvents.value, ...tradeInc].map((event) => [event.seq, event]))
          tradeEvents.value = [...bySeq.values()].sort((left, right) => left.seq - right.seq).slice(-DISPLAY_SUBMITS)
        }
        eventsChanged = true
        cursor = inc[inc.length - 1].seq
        void ensureSecurityNames(inc)
      }
      state.value = st
      marketSnapshot.value = market
      watchPool.value = pool
      if (tickData) ticks.value = tickData.ticks
      lastAppliedRefresh = requestId
      if (refreshSnapshot) void loadAccountData()
    }
    serviceUp.value = true
    if (eventsChanged && detailVisible.value && chart) renderChart()
  } catch {
    if (
      !disposed && requestEpoch === refreshEpoch && requestDate === selectedDate.value
      && requestId >= lastAppliedRefresh
    ) serviceUp.value = false
  }
}

async function initialLoad() {
  try {
    const { days: list } = await fetchDays()
    days.value = list
    serviceUp.value = true
    if (list.length) {
      selectedDate.value = list[0]
      refreshEpoch += 1
      await refresh(true)
    } else {
      watchPool.value = await fetchWatchPool()
    }
  } catch {
    serviceUp.value = false
  }
}

function openDetail(row: { code: string; name?: string }) {
  detailCode.value = row.code
  detailName.value = row.name || securityNames.value[row.code] || ''
  detailVisible.value = true
  if (!detailName.value) {
    void resolveSecurityName(row.code).then((name) => {
      if (name) {
        securityNames.value = { ...securityNames.value, [row.code]: name }
        if (detailCode.value === row.code) {
          detailName.value = name
          renderChart()
        }
      }
    })
  }
  nextTick(() => renderChart())
}

async function addSecurity() {
  const code = addCode.value.trim()
  if (!/^\d{6}$/.test(code)) {
    ElMessage.warning('请输入 6 位证券代码')
    return
  }
  adding.value = true
  try {
    const exchangeType = inferExchangeType(code)
    const exchange = exchangeType === 'SSE' ? 'SH' : exchangeType === 'BSE' ? 'BJ' : 'SZ'
    const name = await resolveSecurityName(code, exchangeType)
    await addWatchPoolMember({ code, name: name || code, exchange })
    watchPool.value = await fetchWatchPool()
    addCode.value = ''
    ElMessage.success(`${code}${name ? ` ${name}` : ''} 已加入观察池，下一轮开始扫描`)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '加入观察池失败')
  } finally {
    adding.value = false
  }
}

async function removeSecurity(code: string) {
  try {
    await ElMessageBox.confirm(`确认将 ${code} 从观察池移除？`, '移除观察标的', { type: 'warning' })
    await removeWatchPoolMember(code)
    watchPool.value = await fetchWatchPool()
    ElMessage.success(`${code} 已移出观察池`)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : '移除观察标的失败')
    }
  }
}

async function renderChart() {
  if (!chartRef.value) return
  chartRuntimePromise ??= import('@/utils/echartsTheme')
  const { echarts, CHART_THEME } = await chartRuntimePromise
  if (disposed || !chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value, CHART_THEME)
  const bars = events.value
    .filter((e) => e.code === detailCode.value && e.type === 'BAR_CLOSED')
    .map((e) => e.detail as any)
  const quotes = events.value.filter((e) => e.code === detailCode.value && e.type === 'QUOTE')
  const marks: any[] = events.value
    .filter((e) => e.code === detailCode.value && e.type === 'SIGNAL' && ['buy', 'sell'].includes((e.detail as any).action))
    .map((e) => ({
      coord: [String((e.detail as any).bar_ts).slice(11, 16), (e.detail as any).detail?.close ?? 0],
      symbol: (e.detail as any).action === 'buy' ? 'triangle' : 'pin',
      symbolSize: 12,
      itemStyle: { color: (e.detail as any).action === 'buy' ? '#f23645' : '#089981' },
      label: { show: true, formatter: (e.detail as any).action === 'buy' ? 'B' : 'S', color: '#fff', fontSize: 9 }
    }))

  const closes = bars.map((b) => Number(b.close))
  const ma = (period: number) => closes.map((_, i) =>
    i < period - 1 ? null : Number((closes.slice(i - period + 1, i + 1).reduce((a: number, b: number) => a + b, 0) / period).toFixed(3))
  )

  chart.setOption({
    animation: false,
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    legend: { data: ['K线', 'MA5', 'MA20'] },
    grid: { left: 60, right: 20, top: 40, bottom: 60 },
    xAxis: { type: 'category', data: bars.map((b) => String(b.end_ts).slice(11, 16)) },
    yAxis: { scale: true },
    dataZoom: [{ type: 'inside' }, { type: 'slider' }],
    series: [
      {
        name: 'K线',
        type: 'candlestick',
        data: bars.map((b) => [b.open, b.close, b.low, b.high]),
        itemStyle: { color: '#f23645', color0: '#089981', borderColor: '#f23645', borderColor0: '#089981' },
        markPoint: { data: marks }
      },
      { name: 'MA5', type: 'line', data: ma(5), showSymbol: false, lineStyle: { width: 1 } },
      { name: 'MA20', type: 'line', data: ma(20), showSymbol: false, lineStyle: { width: 1 } }
    ]
  }, true)
  if (quotes.length) {
    chart.setOption({ title: { text: `${detailName.value || '--'}（${detailCode.value}，快照 ${quotes.length} 次）`, left: 4, top: 2, textStyle: { fontSize: 12 } } })
  }
}

function scheduleRefresh() {
  if (disposed) return
  timer = window.setTimeout(async () => {
    if (autoRefresh.value && document.visibilityState === 'visible') await refresh()
    scheduleRefresh()
  }, REFRESH_MS)
}

function onVisibilityChange() {
  if (!disposed && autoRefresh.value && document.visibilityState === 'visible') void refresh()
}

onMounted(async () => {
  await initialLoad()
  document.addEventListener('visibilitychange', onVisibilityChange)
  scheduleRefresh()
})
onBeforeUnmount(() => {
  disposed = true
  document.removeEventListener('visibilitychange', onVisibilityChange)
  if (timer) window.clearTimeout(timer)
  chart?.dispose()
  chart = null
})
</script>

<style scoped>
.watch-monitor {
  height: 100%;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--sp-3);
  min-height: 0;
  /* 页面本身只负责纵向滚动，横向滚动交给各个 table-host。 */
  overflow-x: hidden;
  overflow-y: auto;
  scrollbar-gutter: stable both-edges;
  overscroll-behavior: contain;
}

.status-bar :deep(.el-card__body) {
  padding: var(--sp-2) var(--sp-3);
}

.status-row {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  flex-wrap: wrap;
  min-width: 0;
}

.status-item {
  display: flex;
  align-items: center;
  gap: var(--sp-1);
  white-space: nowrap;
}

.status-item .label {
  color: var(--text-secondary, #909399);
  font-size: 12px;
}

.status-item .value {
  font-size: 13px;
}

.status-item.grow {
  flex: 1;
  min-width: 200px;
}

.main-grid {
  flex: 1;
  min-height: 0;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  display: grid;
  /* 轨道允许收缩；表格的最小可读宽度由 table-host 内部横向滚动承接。 */
  grid-template-columns: minmax(0, 420px) minmax(0, 1fr) minmax(0, 430px);
  grid-template-rows: minmax(0, 1fr);
  gap: var(--sp-3);
  overflow: hidden;
  overscroll-behavior: contain;
}

.right-column {
  display: grid;
  /* 交易动作只保留紧凑的可滚动区域，把主要空间让给账户明细。 */
  grid-template-rows: minmax(180px, 2fr) minmax(300px, 5fr);
  gap: var(--sp-3);
  min-height: 0;
  min-width: 0;
}

.panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  overflow: hidden;
}

.panel :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  padding: var(--sp-2);
  min-width: 0;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--sp-2);
  min-width: 0;
  flex-wrap: wrap;
}

.panel-title {
  display: inline-flex;
  align-items: baseline;
  gap: var(--sp-2);
  min-width: 0;
}

.pool-header {
  gap: var(--sp-2);
}

.pool-add {
  display: flex;
  gap: var(--sp-1);
  width: min(220px, 100%);
  min-width: 190px;
}

.filters {
  display: flex;
  align-items: center;
  gap: var(--sp-2);
  flex-wrap: wrap;
  justify-content: flex-end;
}

.table-host {
  /* 让外层成为唯一滚动容器，避免 el-table 的 height=100% 把纵向滚动截留在内部，
     进而把横向滚动条推到有限高度之外。 */
  flex: 1 1 auto;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  min-height: 0;
  display: block;
  scrollbar-width: thin;
  overflow-x: auto;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable both-edges;
}

.watch-table {
  min-height: max-content;
}

/* 给表格保留真实内容宽度，字段不再被强行压扁；窄屏通过 table-host 横向滚动查看。 */
.pool-table { min-width: 700px; }
.events-table { min-width: 930px; }
.submits-table { min-width: 820px; }

.table-host::-webkit-scrollbar {
  width: 10px;
  height: 10px;
}

.table-host::-webkit-scrollbar-track {
  background: var(--surface-2);
  border-radius: var(--r-pill);
}

.table-host::-webkit-scrollbar-thumb {
  background: var(--accent-1);
  border: 2px solid var(--surface-2);
  border-radius: var(--r-pill);
}

.table-host::-webkit-scrollbar-thumb:hover {
  background: var(--accent-2);
}

.table-host :deep(.el-table__body-wrapper),
.table-host :deep(.el-table__header-wrapper) {
  scrollbar-gutter: stable;
}

.panel-hint {
  color: var(--text-dim);
  font-size: var(--fz-xs);
  font-weight: 400;
  white-space: nowrap;
}

.pool-name,
.event-summary {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  line-height: 1.35;
}

.event-detail pre {
  margin: var(--sp-1) 0 0;
  padding: var(--sp-2);
  background: var(--bg-secondary, #f5f7fa);
  border-radius: var(--r-md, 8px);
  font-size: 12px;
  max-height: 240px;
  overflow: auto;
}

.gates {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-1);
}

.error-code {
  font-size: 11px;
  color: var(--down);
}

.account-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-3);
}

.account-content {
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  overflow: auto;
  scrollbar-width: thin;
}

.account-section {
  margin-top: var(--sp-3);
  min-width: 0;
}

.account-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-2);
  margin-bottom: var(--sp-1);
  color: var(--text-secondary);
  font-size: var(--fz-xs);
  font-weight: 600;
}

.account-detail-table {
  min-width: 820px;
}

.orders-detail-table {
  min-width: 620px;
}

.account-empty {
  padding: var(--sp-3) 0;
  color: var(--text-dim);
  font-size: var(--fz-xs);
  text-align: center;
  border: 1px dashed var(--hairline-strong);
  border-radius: var(--r-sm);
}

.account-item .label {
  display: block;
  color: var(--text-secondary, #909399);
  font-size: 12px;
}

.account-item .value {
  font-size: 15px;
  font-weight: 600;
}

.account-after {
  margin-top: var(--sp-2);
  padding-top: var(--sp-2);
  border-top: 1px dashed var(--border-color, #dcdfe6);
  font-size: 12px;
  color: var(--text-secondary, #909399);
}

.account-after.positive { color: var(--up); }
.account-after.negative { color: var(--down); }

.account-meta {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-2);
}

.account-grid--finance {
  grid-template-columns: repeat(3, 1fr);
}

.account-tabs {
  margin-top: var(--sp-3);
  min-width: 0;
}

.account-tabs :deep(.el-tabs__header) {
  margin-bottom: var(--sp-2);
}

.account-status-filter {
  width: 140px;
}

.account-expand {
  padding: var(--sp-2) var(--sp-3);
  color: var(--text-secondary, #909399);
  font-size: 12px;
  line-height: 1.8;
}

.pnl-trades-table {
  margin-bottom: var(--sp-1);
}

.detail-chart {
  width: 100%;
  height: 420px;
}

.signal-table {
  margin-top: var(--sp-3);
}

.up {
  color: var(--up);
}

.down {
  color: var(--down);
}

/* 三列布局在窄屏下会让中间事件流失去可读宽度，改为纵向堆叠。 */
@media (max-width: 1100px) {
  .watch-monitor {
    height: auto;
    min-width: 0;
    min-height: 100%;
  }

  .status-row {
    min-width: 0;
  }

  .status-item.grow {
    flex-basis: 100%;
    min-width: 0;
    order: 10;
  }

  .main-grid {
    display: flex;
    flex-direction: column;
    min-width: 0;
    overflow: hidden;
  }

  .universe-panel,
  .events-panel {
    min-height: 420px;
  }

  .right-column {
    display: flex;
    flex-direction: column;
    min-height: 680px;
  }

  .submits-panel {
    min-height: 260px;
  }

  .account-panel {
    min-height: 420px;
  }

  .filters {
    width: 100%;
    justify-content: flex-start;
  }

  .pool-header .pool-add {
    width: 100%;
    min-width: 0;
    flex-basis: 100%;
  }
}
</style>
