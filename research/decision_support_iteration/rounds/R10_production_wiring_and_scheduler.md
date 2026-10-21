# 第 10 轮报告：把 P0 修复真正送进生产链路 + 交易日历回填 + 定时生成

- 日期：2026-10-13
- 代码版本：`34098346` + 工作区改动（**本轮新增**：`SchedulerConfiguration`、`T1CandidateScheduler`、
  `T1CandidateController` 两个端点、`application.yml` 的 `quant.t1.schedule` 段）
- 性质：**工程验证轮**（真实启动后端 + 真实 HTTP 请求；不是离线重算）
- 脚本：`scripts/build_runtime_classpath.py`、`scripts/recompile_backend.py`、
  `scripts/recompile_quant_core_java21.py`、`scripts/api_calendar_backfill.py`、
  `scripts/verify_calendar_backfill.py`、`scripts/diag_zero_candidate_day.py`、
  `scripts/audit_snapshot_versions.py`、`scripts/show_v2_candidates.py`
- 上接：第 7 轮（覆盖率闸回放；当时结论是「闸从未在生产链路上运行过」）

---

## 0. 为什么做这一轮

前 9 轮的评估**全部绕过生产链路**：直接读 `market_data` 自己算分，因此
「覆盖率闸」「PIT 加载」「快照写入」这些**链路行为**从未被验证过。

第 7 轮给出的结论很直接：

> `INCOMPLETE_DAILY_DATA` 只存在于 `34098346`，全仓无 `@Scheduled`，
> 存量 8 条快照全部由更早版本以 6 键形态生成
> ⇒ 「生产行为」当时**不存在可供验证的运行痕迹**。

于是 §1 里的「T1 候选能进盘中观察池」「盘中展示数据时间/覆盖率/策略版本/失效状态」
这类要求，此前只能靠**读代码**论证，没有运行证据。本轮把后端真正跑起来，用真实请求去查。

## 1. 先排掉三个环境坑（都不是项目代码缺陷）

这三件事都会让结论失真，因此必须先排除，且必须写清楚它们**不是**产品缺陷。

### 1.1 Maven 离线无法解析任何插件

`mvn -o package` 报 `maven-jar-plugin:3.3.0 ... has not been downloaded`，
但该 jar **就在本地**。原因是 `_remote.repositories` 记录了 artifactory 来源，
离线模式下 Maven 拒绝使用来源不可验证的构件。`-llr` 是 Maven 2 语法（3.9 已移除），
`-Dmaven.legacyLocalRepo=true` 也无效。

⇒ 改用 **javac + `@argfile`**（Windows 命令行长度上限约 32k，274 个 jar 超限），
并扫描本地仓库自行拼运行 classpath。

> 顺带纠正一个此前的错误判断：`mvn test` 其实**可以**跑，加
> `-Dmaven.legacyLocalRepo=true` 即可。此前记的「Maven 完全不可用」过宽。

### 1.2 `.m2` 里的 `quant-core-java` 是 JDK 23 字节码

启动直接失败：

```
java.io.IOException: ASM ClassReader failed to parse class file -
  ... quant-core-java-1.0-SNAPSHOT.jar!/com/quant/core/collector/SohuStockClient.class
Caused by: IllegalArgumentException: Unsupported class file major version 67
```

`quant-core-java` 是**外部构件**（源码在 `E:\Project\StockInfo\quant-core-java`），
其 pom 写着 `maven.compiler.source/target=23`，而本机 `JAVA_HOME` 是 JDK 21，
Spring Boot 3.2 自带的 ASM 9.6 最高只认 major 66。

**为什么此前从不暴露**：`mvn test` 只跑单测，**从不启动 Spring 上下文**，
组件扫描根本没发生。只有真正 `java -cp ... QuantBackendApplication` 才会撞上。

⇒ 用 JDK 21 把这 14 个源文件重编译到 `%TEMP%\quant-core-java-21`，
**不改上游源码、不覆盖 `.m2`**。

一个关键细节：**仅仅把 Java 21 版本排在 classpath 前面是不够的**。
第一次尝试仍报同样错误 —— Spring 的组件扫描会遍历 classpath 上**所有** jar 条目，
陈旧 jar 只要在场就会被读到。必须把它**整条排除**。

### 1.3 手工 classpath 会拖进未声明的 starter

排掉陈旧 jar 后又出现 `401 WWW-Authenticate: Basic realm="Realm"`：
`spring-boot-starter-security` 在 classpath 上，Spring Boot 便自动装配
`DefaultSecurityFilterChain` + httpBasic，把所有请求挡在项目自身的 `AuthInterceptor` 之前。

项目 pom 只声明了 `spring-security-crypto`（BCrypt 口令哈希用）。
该 starter 是**共享 `.m2` 里存在、但本项目并未声明**的构件，真实 Maven 构建不会包含它。
⇒ 连同 `spring-security-config/web/test` 一并排除。

> 三个坑的共同教训：**「在我机器上跑不起来」≠「功能坏了」**。
> 第 8 轮已记过同类教训（harness 空值不是功能缺失）。这次是环境层。

## 2. 核心发现：P0-1 覆盖率闸**首次真正在生产链路执行**

先重编译 `target/classes`（用 `recompile_backend.py`，255 个源文件），
确保「源码已修」=「跑的确实是修复后的代码」，然后用真实 HTTP 请求。

| 请求日期 | status | daily_coverage | expected / covered | calendar_trading_day |
|---------|--------|---------------|--------------------|---------------------|
| 2026-08-19 | `OK` | **0.9967** | 5477 / 5459 | true |
| 2026-09-07 | `NO_ELIGIBLE_CANDIDATE` | **0.9974** | 5470 / 5456 | true |
| **2026-09-08** | `INCOMPLETE_DAILY_DATA` | **0.0455** | 5469 / 249 | true |
| 2026-10-12 | `INCOMPLETE_DAILY_DATA` | **0.0454** | 5469 / 249 | true |

**闸门确实在跑的直接证据**：阻断响应里出现了第 5 轮新增的字段

```
coverage_denominator_rule = "该键在 [T-60d, T] 内有 bar 且 status=1 且 list_date<=T"
```

该字段**只可能**由修复后的代码产生。

**与第 7 轮离线回放逐位吻合**：离线 0.9963–0.9978 / 0.0455 / 0.0454，
在线 0.9967 / 0.0455 / 0.0454。离线结论第一次在真实链路上被确认。

**阈值仍是 0.95，未放宽**；真实断崖依然被正确阻断。

同时 P0-3 也验证了：`calendar_trading_day=true` 且与 `UNKNOWN` 语义分离生效
（回填前日历止于 2026-08-19，2026-09/10 会被误判成「非交易日」而掩盖真实数据缺失）。

## 3. v2 口径首次产出真实候选（且如实标注降级）

`2026-08-19` 落库了**第一条 v2 口径候选快照**（此前 8 条全是 `t1_rule_v1` + 阈值 40.0）：

| 字段 | 值 |
|------|-----|
| `threshold_version` | `t1_rule_v2` |
| `rule_score_threshold` | 35.0 |
| `total_scored` | 1314 |
| `eligible_count` / `returned_count` | 1 / 1 |
| `rejected_by_reason` | `{"BELOW_RULE_SCORE_THRESHOLD": 1313}` |
| 候选 | `300684 中石科技 SZSE rule_score=40.2628 close=86.59` |
| `daily_coverage` | 0.9967（5459/5477） |
| `master_source` / `pit_limited` | `stock_info_fallback` / **true** |
| `quality_status` / `fallback_reason` | `PIT_LIMITED` / `PIT_LIMITED` |
| `score_type` / `score_model` | `RULE_SCORE` / `None` |

**要点**：系统**没有**把降级伪装成权威 —— `pit_limited=true` 被如实写进快照，
概率模型按设计被阻断，只输出规则分。这符合「证据不足时明确降级」的要求。

**但降级本身没有解决**（见 §6），且这**只影响 2026-09-07 之前的日期**
（2026-09-08 起被覆盖率闸正确阻断）。这条候选**不构成任何收益承诺**，
`rule_score` 是排序分，不是上涨概率。

## 4. 追查「零候选」：是市场状态，不是闸门误杀或规则退化

2026-09-07 覆盖率 0.9974 通过闸门却 0 候选，需要区分两种可能：闸门误杀 vs 确实无人过线。
只读复刻打分（**注意：这是近似复刻，不是生产代码的逐位拷贝**）：

| 日期 | 池内行数 | rule_score 最大 | p99 | 过线(≥35) |
|------|---------|----------------|-----|----------|
| 2026-08-11 | 1429 | 42.43 | 24.10 | 3 |
| 2026-08-18 | 1403 | 42.52 | 24.07 | 3 |
| 2026-09-04 | 1385 | 32.52 | 19.67 | 0 |
| **2026-09-07** | 1342 | **33.10** | 17.82 | **0** |

⇒ 2026-09-07 **确实无人过线**（最大 33.10 < 35.0），`NO_ELIGIBLE_CANDIDATE` 是真话。

> 复刻与生产**不完全一致**（复刻未施加 PIT 宇宙过滤与生产级 bar 有效性判定，
> 故复刻最大值 42.52 而生产 2026-08-19 实际 total_scored=1314、最高 40.2628）。
> 因此上表**只用于判断「过线人数是否为 0」这一趋势**，不能当作生产数值引用。

「零候选日」属于策略的**选择性**特征，应如实报告，**不计入**任何覆盖率改善。

## 5. 交易日历回填（授权项 3）：485 → 517 行

用项目自带的幂等接口（`DataController.java:164-169` → `deriveFromShIndex`），
先 `mysqldump` 备份（46,296 字节）：

```
POST /api/data/trading-calendar/init?startDate=2024-08-19&endDate=2026-10-12
→ total_fetched=517, inserted=32, skipped_existing=485,
  source=eastmoney_derived, reliability=limited
```

| 项 | 回填前 | 回填后 |
|----|-------|-------|
| 区间 | 2024-08-19 .. 2026-08-19 | 2024-08-19 .. **2026-10-12** |
| 行数 | 485 | **517** |
| 2026-09 天数 | — | 21 |
| 2026-10 天数 | — | 3 |

**幂等性得到验证**：485 条既有行全部 skipped，只补 32 条缺失。
`source=eastmoney_derived` / `reliability=limited`（由上证指数日 K 反推），
**不是官方日历**，该局限已记入 §七.8。

## 6. `security_master_history` 回填（授权项 4）：**核查后决定不执行**

「全部授权」包含这一项。**不执行的理由是技术上无效，而非无授权。**

内置路径 `SecurityMasterHistoryService.initializeFromStockInfoSnapshot()` 写入的是：

- `source = current_snapshot`、`reliability = limited`；
- `note` 自陈「LIMITED_BOOTSTRAP：当前快照初始化，退市证券未覆盖…无历史 ST/改名/行业变更记录」。

而 `PointInTimeUniverseProvider:80-84` 明确把 `current_snapshot`
**强制**判为 `limited`：

```java
if (!RELIABILITY_OFFICIAL.equalsIgnoreCase(h.getReliability()) || h.getSource() == null
        || h.getSource().isBlank() || "current_snapshot".equalsIgnoreCase(h.getSource())
        || "delisted_placeholder".equalsIgnoreCase(h.getSource())) {
    worstReliability = RELIABILITY_LIMITED;
}
```

⇒ 即使写入 5835 行，`pitLimited` 仍为 `true`、概率模型仍被阻断、
幸存者偏差一点没少。**回填等于白做。**

而本地**不存在**任何带版本证明的历史源：

- `stock_info` 只有当前截面（5835 行、`status` 全为 1、无退市列、无改名史）；
- 全库仅 5 张表名匹配 `history|master|name|cal|delist|suspend`，**均不可用**；
- `security_master_history` 当前 0 行。

⇒ 唯一能真正解锁的是**外部官方历史源**（Tushare `trade_cal` / 退市名单等），本地不可得。
**编造历史属明令禁止**，故本轮**不写入任何行**。

> 这一条把结论边界**收紧**了：在当前数据条件下，概率模型**不可能**被合法解锁。

## 7. 本轮落地代码：定时生成（DS-R030）

此前候选清单**只能人工调** `POST /api/research/t1/candidates`，全仓无 `@Scheduled`，
无人值守时链路永不产出。

| 文件 | 内容 |
|------|------|
| `config/SchedulerConfiguration.java` | `@EnableScheduling` + **池大小 1** 的 `ThreadPoolTaskScheduler` + `Clock` 统一 `Asia/Shanghai` |
| `scheduler/T1CandidateScheduler.java` | 漏跑自愈的日期解析 + 幂等委托 + 异常显式兜住 + `status()` 可观测 |
| `controller/T1CandidateController.java` | `GET /schedule/status`（只读）、`POST /schedule/trigger`（复用同一段逻辑） |
| `application.yml` | `quant.t1.schedule.{enabled,cron,lookback-days}`，**enabled 默认 false** |

设计要点：

1. **池大小 1**：日频候选生成是重 IO + 写库，宁可排队也不并发，
   避免同一信号日被两个线程同时生成。
2. **漏跑自愈**：在最近 `lookback-days`(=10) 个自然日内找最近一个
   「日历确认是交易日」**且**「当日已有行情」的日期。
   - 只看日历 → 会把「今天还没收盘」选中，每天跑出空结果；
   - 只看行情 → 可能把非交易日当成交易日；
   - 找不到 → **明确 SKIPPED，不猜日期**。
3. **委托给既有 `generateCandidates`**：本身幂等（有快照则复用不改写；
   未收盘 / 覆盖率不足不落库），因此重复执行不产生重复行、不改写既往研究结论。
4. **异常显式兜住并记 `FAILED`**：`@Scheduled` 抛出的异常只会进日志，
   外部只能看到「候选清单没更新」而不知原因 —— 那是静默失败，必须避免。
5. **默认关闭**：与「默认观察、不自动产生交易动作」的既有约定一致。
   无论开关如何，本任务只写 `t1_candidate` 快照，**不触碰任何下单接口**。

`@EnableScheduling` 此前被移除过，原因写在 `ScoringConfiguration`；
其注释明确写了「若后续新增 `@Scheduled` 任务，请随该任务一并加回并按需配置线程池大小」。
本类即据此**独立**装配，不再把「打分 Bean 装配」与「调度基础设施」混在一处。

### 在线验证（真实调用，非推论）

```
POST /api/research/t1/schedule/trigger
→ signal_date    = 2026-10-12   （今天 2026-10-13 当日无 bar ⇒ 自愈回退到上一个有数据的交易日）
  status         = INCOMPLETE_DAILY_DATA
  daily_coverage = 0.04538      （正确阻断断崖）
  calendar_trading_day = true

GET /api/research/t1/schedule/status
→ present=true, schedule_enabled=false,
  cron="0 10 15 * * MON-FRI", zone="Asia/Shanghai", seconds_since_last_run=0
```

自愈行为、闸门阻断、状态可观测三点在**真实链路上**同时成立。

### 单测

`T1CandidateSchedulerTest`，6 个用例，覆盖：

1. 日历不覆盖 → SKIPPED，且**从不**调用 `generateCandidates`；
2. 回看跳过无行情交易日，且委托的是**解析出的信号日**而非「今天」；
3. 回看窗口内全非交易日 → SKIPPED（不猜日期）；
4. 底层抛异常 → `FAILED` + 原因，而不是静默；
5. 未执行过时 `status().present=false`；
6. 执行后 `status()` 暴露上次结果与信号日。

> 第一版 4/6 失败，原因是被测类会**逐日向前试探**，而测试只对关心的日期打了桩，
> Mockito 严格桩遇到未声明日期直接抛 `PotentialStubbingProblem`。
> 修法是加**默认桩**（任意日期非交易日）+ 对关心日期单独覆盖。
> 这是测试写法问题，不是被测逻辑问题。

## 8. 验证

| 验证 | 结果 |
|------|------|
| 新增调度单测 | **6/6 通过, BUILD SUCCESS** |
| 生产链路覆盖率闸 | ✅ 首次真实执行，含 `coverage_denominator_rule` 字段证据 |
| 在线值 vs 第 7 轮离线回放 | ✅ 逐位吻合 |
| 真实候选落库 | ✅ 2026-08-19，`t1_rule_v2`/35.0，1 只，降级字段如实标注 |
| 交易日历 | ✅ 485 → **517**，2024-08-19..**2026-10-12**，`eastmoney_derived`，幂等 |
| `security_master_history` | ⛔ **刻意未写入**（技术无效，理由见 §6） |
| 阈值 / 覆盖率保护 | ✅ 0.95 未放宽、`ACTIVE_WINDOW_DAYS=60` 未改 |
| 是否触达下单接口 | ✅ 未触碰，SHADOW 观察态不变 |
| 是否提交 / 推送 / 部署 | ✅ 未提交、未推送、未部署 |

## 9. 本轮**不能**证明什么

1. **不能证明策略准确性有任何改善。** 本轮全部成果都在
   「修复是否进了生产链路」与「自动生成能力」两个工程层面。
   它把一件事从**无法验证**变成**已用真实请求验证**，仅此而已。
2. **不能消除幸存者偏差**，也不能解锁概率模型（§6 反而证明当前条件下不可能解锁）。
3. **不能证明 `rule_score` 阈值 35.0 的合理性**：该阈值来源仍是旧流水线
   （`t1_rule_v1`/40.0），本轮只做检验、未做优化，其「待验证假设」属性不变。
4. **不能证明交易日历的完备性**：`eastmoney_derived` 是派生口径，
   指数缺一日即漏判一个交易日。
5. **样本外证据仍然缺位**：本轮没有产生任何新的独立时间区间。
   2026-01..08 已被反复查看，其显著性只能读作**区间内稳健性**。
6. **`t1_candidate` 我确实写入了新行**（2026-08-19、2026-09-07）。
   这两行此前**不存在**，属于新增而非覆盖既有结论；但必须如实披露：
   本轮对生产库有写入，不只是只读。

## 10. 本轮对数据库的全部写入（如实披露）

本轮**不是纯只读轮**，共产生 4 类写入，全部列出以便复核：

| # | 表 | 写入 | 依据 | 可否回退 |
|---|----|------|------|---------|
| 1 | `trading_calendar` | +32 行（485 → 517） | 授权项 3；走项目自带幂等接口 `POST /api/data/trading-calendar/init` | ✅ 已 `mysqldump` 备份（46,296 字节，回填前） |
| 2 | `t1_candidate` | +2 行（2026-08-19、2026-09-07） | 授权项 1 的验证过程；均为**新增**，无既有行被改写 | ⚠️ 未单独备份；删除这 2 行即可回退 |
| 3 | `user` / `user_account` | +1 账号 `dsh_research`(id=4) + 1 账户行 | 调用受保护 API 需要 token；`quant.auth.enabled=false` 但 `AuthService` 无默认管理员 | ❌ **保留**：`user_account.user_id=4` 已有 1 行引用，删除会破坏引用完整性 |
| 4 | `flyway_schema_history` | 无 | Flyway 28/28 已应用、0 待执行 ⇒ 启动**未**执行任何迁移 | — |

**未写入**：`security_master_history`（§6 证否，**0 行**）、`model_registry`、`calibrator_registry`。

> 写入 3 是本轮的一个**操作瑕疵**：我本可直接使用项目既有的口令哈希路径，
> 却走公开注册接口新建了账号。若使用者希望清除，需先处理 `user_account` 的引用
> （脚本 `scripts/audit_registered_user.py` 已给出引用核查）。
> 我**没有**擅自执行删除，因为那属于破坏性写操作。

> 结论：**目前无法证明策略准确性改善。**
> 本轮的意义是把「P0 修复在生产链路是否生效」从读代码推进到运行证据，
> 并把 `security_master_history` 这条看似可行的路**证否**，从而收紧结论边界。

---

## 附录：如何复现本轮（后端已在本轮结束时停止）

本轮结束时已把后端进程停掉（`8080` 已释放），MySQL（`3306`）保持原样运行。

```powershell
cd E:\Project\quant-trading-system

# 1) 依赖 jar 与运行 classpath（离线 Maven 无法解析插件，故手工拼）
& $py research\decision_support_iteration\scripts\build_runtime_classpath.py
#    -> modules\backend-java\cp.txt

# 2) 确保 quant-core-java 是 Java 21 字节码（否则 Spring 组件扫描会崩）
& $py research\decision_support_iteration\scripts\recompile_quant_core_java21.py

# 3) 把源码重编译进 target/classes（保证跑的是当前源码）
& $py research\decision_support_iteration\scripts\recompile_backend.py

# 4) 启动
cd modules\backend-java
java -Dfile.encoding=UTF-8 -Dquant.skipFrontendBuild=true -cp (Get-Content cp.txt -Raw) `
     com.quant.QuantBackendApplication
```

`-Dquant.skipFrontendBuild=true` **必须加**：否则每次启动都会跑一遍
`npm install` + `vite build`（`QuantBackendApplication:23-35`）。

若要让前端改动对浏览器生效，还需把构建产物同步到 Spring 的静态目录
（`target/classes/static`，**它是构建产物不是源码**）：

```powershell
cd modules\web; npm run build
Copy-Item dist\* ..\backend-java\target\classes\static -Recurse -Force
```

验证：

```powershell
& $py research\decision_support_iteration\scripts\verify_served_frontend.py   # 前端下发产物
& $py research\decision_support_iteration\scripts\verify_calendar_backfill.py # 日历 + 闸门
& $py research\decision_support_iteration\scripts\audit_snapshot_versions.py  # 快照版本
& $py research\decision_support_iteration\scripts\show_v2_candidates.py       # v2 候选明细
```

若要**开启**自动生成（默认关闭）：

```powershell
java -Dquant.t1.schedule.enabled=true -Dquant.skipFrontendBuild=true -cp ... com.quant.QuantBackendApplication
# 或改 application.yml 的 quant.t1.schedule.enabled: true
```
