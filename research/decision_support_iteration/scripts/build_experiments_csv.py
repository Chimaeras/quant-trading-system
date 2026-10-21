"""生成 experiments.csv（保证每行字段数与表头一致）。

实验台账列：代码版本 / 数据范围与来源 / 股票池 / 特征 / 参数 /
训练-验证-测试区间 / 样本数 / 覆盖率 / 指标 / 成本口径 / 结果 / 限制 / 决策。
空值一律写空字符串，不用占位符，避免列错位。
"""
from __future__ import annotations

import csv
import pathlib

HEADER = [
    "experiment_id", "date", "code_version", "round", "purpose",
    "data_source", "data_range", "universe", "features", "params",
    "train_window", "valid_window", "test_window", "n_samples", "coverage",
    "primary_metric", "aux_metrics", "cost_model", "result", "limitations", "decision",
]

R = "2026-10-13"
ROWS = [
    dict(experiment_id="DS-R001", date=R, code_version="34098346", round="1",
         purpose="核对基线：34098346 修复是否进入生产链路",
         data_source="git log + git status",
         primary_metric="检出 P0-1/P0-2/P0-3 三个现存缺陷",
         result="HEAD=34098346；工作区干净；无未提交改动",
         cost_model="无（静态+只读DB核查）",
         limitations="未撤销任何可信度保护；未改代码",
         decision="ACCEPTED_AS_FINDING"),
    dict(experiment_id="DS-R002", date=R, code_version="34098346", round="1",
         purpose="实测本地数据资产（只读盘点）",
         data_source="MySQL quant_db 127.0.0.1:3306",
         data_range="market_data 2003-12-16..2026-10-12", universe="56 张表",
         params="只读 SELECT/SHOW",
         result="2460262 行 market_data；5835 stock_info；0 security_master_history；"
                "8 t1_candidate；0 model_registry；0 calibrator_registry",
         primary_metric="未计算收益指标", cost_model="只读；未写库",
         limitations="发现 PIT 主数据为空、概率轨无任何产物",
         decision="ACCEPTED_AS_FINDING"),
    dict(experiment_id="DS-R003", date=R, code_version="34098346", round="1",
         purpose="验证日频覆盖率闸是否可通过（P0-1）",
         data_source="MySQL quant_db",
         data_range="2026-03-01..2026-08-31 (121 交易日) + 全历史",
         universe="stock_info status=1 (5835)",
         params="MIN_DAILY_COVERAGE=0.95；expected=5835 → 需 5544 bar",
         n_samples="121",
         primary_metric="max 可达覆盖率 0.93865 (5477/5835)；正常期 mean 5463.6 bars/day",
         aux_metrics="全部 121 日 coverage < 0.95，缺口恒 67 bar",
         cost_model="无（覆盖率闸，非收益指标）",
         result="闸门对每个信号日恒不通过 → generateCandidates 恒返回 INCOMPLETE_DAILY_DATA，永不产出候选",
         limitations="分母含已退市/停牌/未交易标的，口径与 covered 不一致",
         decision="PENDING_USER_DECISION"),
    dict(experiment_id="DS-R004", date=R, code_version="34098346", round="1",
         purpose="核对生产候选快照（P0-1 佐证）",
         data_source="MySQL quant_db t1_candidate", universe="t1_candidate 8 行",
         n_samples="8",
         primary_metric="candidates_json 全为 [] (长度 2)",
         aux_metrics="ecology 阈值版本全为 t1_rule_v1/40.0，当前代码为 t1_rule_v2/35.0",
         result="8 行全部 0 候选；created_at ≤ 2026-08-24",
         limitations="无 generated_at 等新字段 → 34098346 之后的代码从未在生产运行",
         decision="ACCEPTED_AS_FINDING"),
    dict(experiment_id="DS-R005", date=R, code_version="34098346", round="1",
         purpose="核查生效日回退元数据（P0-3）",
         data_source="源码静态分析", features="T1CandidateService.java",
         primary_metric="actualDate 全文件无重新赋值 → data_date_fallback 恒 false（死代码）",
         aux_metrics="emptyDecision 亦硬编码 false；dailyBarClosed 不校验交易日历",
         cost_model="无",
         result="请求节假日返回 NO_DATA，无法区分非交易日与数据缺失",
         decision="ACCEPTED_AS_FINDING"),
    dict(experiment_id="DS-R006", date=R, code_version="34098346", round="1",
         purpose="核查 PIT 与概率轨可达性（P0-2）",
         data_source="MySQL + 源码",
         data_range="security_master_history / model_registry",
         primary_metric="security_master_history=0 行 → pitLimited=true → 概率模型拒绝加载；"
                        "model_registry=0；calibrator_registry=0",
         aux_metrics="生产只可能输出 RULE_SCORE；无任何已校准概率模型",
         cost_model="无",
         result="禁止报告 Brier/log loss 为已验证",
         limitations="PIT 回填属高影响操作，需用户确认",
         decision="ACCEPTED_AS_FINDING / BLOCKED_ON_DATA"),
    dict(experiment_id="DS-R007", date=R, code_version="34098346", round="1",
         purpose="核查 trading_calendar 覆盖", data_source="MySQL trading_calendar",
         n_samples="485",
         primary_metric="日历 2024-08-19..2026-08-19；market_data 到 2026-10-12",
         aux_metrics="晚覆盖 54 天 → 2026-08-19 后信号日无法核销",
         cost_model="无",
         result="findTradingDays 查不到次日 → no_next_day",
         limitations="补日历需写库，待确认", decision="PENDING"),
    dict(experiment_id="DS-R008", date=R, code_version="34098346", round="1",
         purpose="建立离线样本外评价（B0/B1/B2 对照）",
         data_source="MySQL quant_db market_data",
         data_range="2026-01..2026-08 正常期",
         universe="同池（as-of 可交易标的）",
         features="scoreT1 三项 / ret5 / 量比",
         primary_metric="未开始",
         limitations="需先确认成本参数与可成交口径落地细节", decision="PENDING"),
    dict(experiment_id="DS-R008b", date=R, code_version="34098346", round="1",
         purpose="独立审计链路 B（盘中观察）7 项",
         data_source="源码 + pytest", universe="paper/watch",
         primary_metric="4 项已修复（T1传参/SHADOW/无信号vs数据不足/预热收盘bar）；"
                        "2 项部分（展示字段/重启恢复）；1 项缺陷（提醒级去重缺失）",
         aux_metrics="另发现 D-2/D-4/D-5/D-6", cost_model="无",
         limitations="未启动 Java 服务、未接实时行情，部分结论标注 CANNOT-DETERMINE",
         decision="ACCEPTED_AS_FINDING"),
    dict(experiment_id="DS-R009", date=R, code_version="34098346", round="1",
         purpose="定位覆盖率闸分母被放大的具体构成",
         data_source="MySQL quant_db", data_range="全历史",
         universe="stock_info 5835 行",
         params="COUNT(DISTINCT code|exchange) vs COUNT(DISTINCT stock_code)",
         n_samples="5835",
         primary_metric="284 个 stock_code 同时有 BSE(920xxx) 与 SSE 两个 exchange_type 行",
         aux_metrics="两行在 market_data 均有 bar，故纯生命周期条件无法剔除",
         cost_model="无",
         result="确认根因：北交所代码迁移遗留双编码，非停牌或未上市",
         decision="ACCEPTED_AS_ROOT_CAUSE"),
    dict(experiment_id="DS-R010", date=R, code_version="34098346", round="1",
         purpose="标定覆盖率分母的活跃窗口长度 W（阈值 KEEP 0.95）",
         data_source="MySQL quant_db market_data",
         data_range="2026-01-05..2026-08-31 (155 交易日)",
         universe="stock_info status=1 ∩ 窗口内活跃",
         features="coverage = covered / expected",
         params="W ∈ {60 120 180 250}", n_samples="155",
         primary_metric="W60 min=.9596 med=.9958 pass 155/155；W120 pass 130/155；"
                        "W180 96/155；W250 47/155",
         aux_metrics="断崖检出 2026-09-08 bar 5456→249 → coverage 0.0455 → 正确 BLOCKED",
         cost_model="无",
         result="W=60 既 155/155 通过又保留断崖检出能力",
         limitations="标定样本为单一数据快照的 155 个交易日，未经样本外验证",
         decision="ACCEPTED（阈值未变，仅修分母口径）"),
    dict(experiment_id="DS-R011", date=R, code_version="working-tree", round="1",
         purpose="修复 P0-1：覆盖率闸分母改为回看 60 日活跃键",
         data_source="本地实测 + 单元测试", universe="同池",
         params="ACTIVE_WINDOW_DAYS=60；MIN_DAILY_COVERAGE 不变 0.95",
         n_samples="1 例新增",
         primary_metric="mvn -Dtest=T1CandidateServiceTest → BUILD SUCCESS 15/15；"
                        "新增 staleUniverseKeysAreExcludedFromCoverageDenominator",
         aux_metrics="未放宽任何可信度保护；未用 >T 数据（无前视）",
         cost_model="无",
         result="闸门恢复可通过，同时仍阻断真实断崖",
         limitations="单测只证明行为符合预期，不证明策略有效性",
         decision="ACCEPTED"),
    dict(experiment_id="DS-R012", date=R, code_version="working-tree", round="1",
         purpose="修复 D-1：T1 正式日历校验是死代码（运算符优先级）",
         data_source="源码 + 回归用例", universe="T1 观察池",
         params="calendar_is_official = available AND "
                "(trading_calendar_file OR source startswith file:)",
         n_samples="2 例新增",
         primary_metric="正向用例通过；反向对照（还原原始 loop.py）→ 2 failed",
         aux_metrics="修复陷阱：仅加括号不够，默认 TradingCalendarService 的 "
                     "_calendar_explicit 仍为 True 但行为是周一至周五回退",
         cost_model="无",
         result="节假日不再把上一交易日陈旧候选当观察池",
         decision="ACCEPTED"),
    dict(experiment_id="DS-R013", date=R, code_version="working-tree", round="1",
         purpose="修复 D-3：EXPIRED_BAR 审计被 warmup 分支整体替换抹除",
         data_source="源码 + 回归用例", universe="盘中信号",
         params="reason_codes 前缀追加；保持先过期后 warmup 的顺序",
         n_samples="1 例新增",
         primary_metric="反向对照 → failed；恢复修复版后 2 passed",
         aux_metrics="顺序不可颠倒：warmup 会把 action 压成 hold，令过期分支永不可达",
         cost_model="无",
         result="过期原因在审计中不再丢失", decision="ACCEPTED"),
    dict(experiment_id="DS-R014", date=R, code_version="working-tree", round="1",
         purpose="回归验证（与本轮风险相关的最小集）",
         data_source="pytest + maven", n_samples="350",
         primary_metric="python -m pytest -q → 350 passed（基线 347，新增 3）",
         aux_metrics="策略有效性不由单测证明", cost_model="无",
         result="代码行为符合预期",
         limitations="不构成任何准确性改善证据",
         decision="ACCEPTED_ONLY_ENGINEERING"),

    # ==================== 第 2 轮：离线样本外评价 ====================
    dict(experiment_id="DS-R015", date=R, code_version="34098346", round="2",
         purpose="建立离线样本外评价（B0/B1/B2 对照，DS-EC-1）",
         data_source="MySQL quant_db market_data（只读）",
         data_range="信号日 2026-01-05..2026-08-31；装载 2025-07-09..2026-08-31",
         universe="stock_info 当前截面（无 PIT → 幸存者偏差）",
         features="生产 scoreT1 三项忠重复刻（动量/量能/趋势）",
         params="阈值 35.0 冻结；TopN=10；流动性闸 30,000 万元",
         train_window="", valid_window="", test_window="",
         n_samples="248337 个 (code,date) 观测 / 155 个信号日",
         coverage="合格池约 1602 只/日；B0 有候选 109/155 日（70.3%）",
         primary_metric="B0 扣费后日均净收益 +1.530%（block CI [+0.771%, +2.216%]）",
         aux_metrics="B1 同池等权 +0.017%；B2a 纯ret5 Top10 +0.851%；B2b 纯量比 +0.292%；"
                     "B0−B1 配对差 +1.493%, CI_block [+0.840%, +2.176%]",
         cost_model="双边佣金3bp×2+印花税5bp+过户费0.1bp×2 = 11.2bp；T+1开盘买→T+1收盘卖",
         result="B0 显著优于同池等权；但相对最优简单对照未见区间不显著",
         limitations="绕过生产覆盖率闸与PIT；单区间；非walk-forward；无PIT",
         decision="ACCEPTED_AS_EVIDENCE（不足以替换生产参数）"),
    dict(experiment_id="DS-R016", date=R, code_version="34098346", round="2",
         purpose="解释「Rank IC≈0 但尾部收益高」的矛盾",
         data_source="同上（只读）", data_range="2026-01-05..2026-08-31",
         universe="同上", features="十分位分组 + 尾部价差（并列稳健）",
         params="按 date 分组做 qcut(10)",
         n_samples="155 个交易日",
         primary_metric="全池第9档−第0档 = +0.316%/日, CI_block [+0.102%, +0.534%]",
         aux_metrics="第9档 +0.276%/日；第0档 −0.040%/日；有限 Spearman IC 仅 +0.0041 "
                     "（CI 含 0）→ IC 对本规则无分辨力",
         cost_model="同上",
         result="scoreT1 在三项为负时被 clamp 成 0 → 大量并列值使秩相关失效；"
                "真实区分力只存在于最尾部，中段近似无效",
         limitations="未做 IC 的并列修正替代指标的系统比较",
         decision="ACCEPTED（修正了对 IC 的解读）"),
    dict(experiment_id="DS-R017", date=R, code_version="34098346", round="2",
         purpose="控制动量/量比/趋势与日期固定效应后，规则尾部是否仍有增量",
         data_source="同上（只读）", data_range="2026-01-05..2026-08-31", universe="同上",
         features="ret5 / volume_ratio / ma_pos（winsorize 1-99% 后标准化）+ 日期哑变量",
         params="双残差化 OLS（FWL）",
         n_samples="246939",
         primary_metric="rule_score>=35 增量系数 = +1.017%/日, se=0.227%, t=+4.47, "
                        "95%CI [+0.571%, +1.463%]",
         cost_model="同上",
         result="规则尾部不是 5 日动量/量比/趋势的简单代理",
         limitations="同期横截面回归；未处理横截面相关性带来的 t 值高估",
         decision="ACCEPTED_AS_EVIDENCE"),
    dict(experiment_id="DS-R018", date=R, code_version="34098346", round="2",
         purpose="时间切分：1-5 月定型 / 6-8 月（未见）验证",
         data_source="同上（只读）",
         data_range="train 2026-01-05..2026-05-22 (90日) / holdout 2026-06-01..2026-08-31 (65日)",
         universe="同上", params="阈值 35.0 不变（未在 holdout 上调参）",
         n_samples="train 143708 行 / holdout 104629 行",
         primary_metric="holdout: B0 = +1.966%/日, CI_block [+0.632%, +3.038%]；"
                        "B0−B1 = +2.042%, CI [+0.979%, +3.129%]",
         aux_metrics="train: B0 = +1.207%, B0−B1 = +1.085% CI [+0.358%, +1.906%]；"
                     "两个区间 B0−B2a 的 CI 均含 0",
         cost_model="同上",
         result="未见区间未推翻规则优势；但也无法区分 B0 与纯 ret5 Top10",
         limitations="单次切分，非多窗口 walk-forward",
         decision="ACCEPTED_AS_EVIDENCE"),
    dict(experiment_id="DS-R019", date=R, code_version="34098346", round="2",
         purpose="阈值迁移检验（在未见区间上读数，非调参）",
         data_source="同上（只读）", data_range="holdout 2026-06-01..2026-08-31（65 日）",
         universe="同上", params="阈值 ∈ {25,30,32,35,38,40,42,45}",
         n_samples="阈值35: 46 个有候选日 / 90 个选数",
         primary_metric="相邻阈值单调上升比例 0.71；阈值35 = +1.966% 处于平台中段而非孤立尖峰",
         aux_metrics="阈值30→+1.742%, 32→+2.217%, 38→+3.028%, 40→+4.021%(仅23日/29选)",
         cost_model="同上",
         result="阈值方向承载真实信息；但收益与覆盖率存在真实权衡（阈值越高选数越少）",
         limitations="在该区间读数是探索性；读数得到的最优阈值不得回填为生产参数",
         decision="ACCEPTED_AS_EVIDENCE"),
    dict(experiment_id="DS-R020", date=R, code_version="34098346", round="2",
         purpose="脆弱性检验：留一法剔除贡献最大的 k 只标的",
         data_source="同上（只读）", data_range="2026-01-05..2026-08-31",
         universe="候选 215 选数 / 171 只不同标的；单只最多 4 次；top10 标的占 13.5%",
         params="k ∈ {1,3,5,10,20}",
         n_samples="108 个有候选日",
         primary_metric="剔除 top-20 后日均 +0.606%, CI_block [−0.114%, +1.236%] → 显著性消失",
         aux_metrics="剔除 top-1 仅掉 0.12pp（非异常值污染）；剔除 top-10 仍 CI>0",
         cost_model="同上",
         result="收益薄且方差大，约 12% 的标的影响统计显著性",
         limitations="留一法是必要非充分条件；不排除共同因子暴露",
         decision="ACCEPTED_AS_LIMITATION（必须与主结果同读）"),
    dict(experiment_id="DS-R021", date=R, code_version="34098346", round="2",
         purpose="反事实对照：旧生产阈值 40.0(t1_rule_v1) vs 新 35.0(t1_rule_v2)",
         data_source="MySQL quant_db market_data（只读）",
         data_range="2026-01-05..2026-08-31；另单看 holdout 2026-06-01..2026-08-31",
         universe="同上（248,337 观测）",
         params="同池/同成交口径/同费用，TopN ∈ {10,20,50}",
         n_samples="thr35: 109 日/215 选数；thr40: 56 日/74 选数",
         primary_metric="配对差(新-旧) = −0.518%, CI_block [−1.242%, +0.349%]",
         aux_metrics="holdout 配对差 = −0.948%, CI_block [−1.910%, −0.251%]（不含 0，新阈值更差）；"
                     "thr40 日均 +2.169%(全区间) / +4.021%(holdout) 均高于 thr35",
         cost_model="同上",
         result="40.0→35.0 是选择性取舍（多交易、低单笔质量），不是准确性改善；"
                "未见区间的差异方向与「提升准确性」相反",
         limitations="旧公式含 is_limit_up+30 与 lianban 项（池内恒不触发）且满分 100，"
                     "与新公式(满分50)不可直接比 → 非单一变量的受控检验",
         decision="REJECT_IMPROVEMENT_CLAIM（不构成准确性改善证据）"),

    # ==================== 第 3 轮：多窗口 walk-forward + 持有期 ====================
    dict(experiment_id="DS-R022", date=R, code_version="34098346", round="3",
         purpose="多窗口 walk-forward：规则优势是否在每个滚动窗口成立",
         data_source="MySQL quant_db market_data（只读）",
         data_range="信号日 2026-01-05..2026-08-31；滚动训练/验证，验证段互不重叠",
         universe="同池（248,337 观测）",
         params="固定冻结阈值 35.0；配置 (60,20)/(80,20)/(60,40)；T+1 收盘退出",
         n_samples="9 折；池化并集 60~80 日、B0 有候选 44~62 日",
         coverage="并集仅覆盖 2026-04-08 起；1-2 月无样本外证据",
         primary_metric="9/9 折为正，折均值 +1.915%（sd 1.03%，min +0.432%，max +3.275%）",
         aux_metrics="池化 B0−B1 = +1.868%, CI_block [+1.105%, +2.796%]（不含 0）；"
                     "B0 胜出日占比 0.613~0.636",
         cost_model="11.2bp 双边（T+1 开盘买 → T+1 收盘卖）",
         result="第 2 轮单次切分结论经多窗口复核后成立",
         limitations="区间已被本会话反复读取（多重比较）；无 PIT；1-2 月未覆盖",
         decision="ACCEPTED_AS_EVIDENCE（仍不足以替换生产参数）"),
    dict(experiment_id="DS-R023", date=R, code_version="34098346", round="3",
         purpose="把 walk-forward 起点提前，缩小未覆盖缺口",
         data_source="同上（只读）", data_range="同上",
         universe="同池", params="配置 (40,20)/(50,20)",
         n_samples="各 5 折",
         primary_metric="40/20: 5/5 为正，最早验证段 2026-03-10，折均值 +1.593%；"
                        "50/20: 5/5 为正，最早 2026-03-24，折均值 +1.714%",
         cost_model="同上",
         result="起点提前到 3 月 10 日后仍全部为正",
         limitations="1-2 月仍无任何样本外证据",
         decision="ACCEPTED_AS_EVIDENCE"),
    dict(experiment_id="DS-R024", date=R, code_version="34098346", round="3",
         purpose="持有期敏感性：收益是否只是 T+1 单日噪声",
         data_source="同上（只读）",
         data_range="2026-01-05..2026-08-31",
         universe="同池",
         params="k ∈ {1,2,3,5,10}，T+1 开盘买 → T+k 收盘卖；费用含每多一日 0.1bp 过户",
         n_samples="k=1: B0 有候选 109 日",
         primary_metric="k=1 +1.530% CI[+0.771%,+2.216%]；k=2 +1.548% CI[+0.197%,+3.083%]；"
                        "k=3 +0.269% CI[−1.680%,+2.117%]（含 0）",
         aux_metrics="k=2 时 B0−B2a 的 CI 首次不含 0 [+0.247%,+2.659%]；"
                     "B0 在 k=1..10 点估计均高于 B1（方向一致）",
         cost_model="见上（k 越大费用略增）",
         result="优势集中在 T+1~T+2，k≥3 后不显著；与规则由 5 日动量+量能+20 日趋势构成的机理一致",
         limitations="未检验 T+1 开盘即卖等其它退出规则",
         decision="ACCEPTED_AS_LIMITATION（短周期效应，非持续漂移）"),
    dict(experiment_id="DS-R025", date=R, code_version="34098346", round="3",
         purpose="阈值搜索的样本外行为（可否用训练窗口稳定选出更好阈值）",
         data_source="同上（只读）", data_range="同上（每折训练窗口内择阈值）",
         universe="同池", params="网格 {25,30,32,35,38,40,42,45}，要求训练窗口内≥10 个有候选日",
         n_samples="9 折",
         primary_metric="挑中 38.0(6折)/42.0(2折)/35.0(1折)；挑中冻结阈值的比例 0.11；"
                        "fitted 折均值 +3.924%",
         aux_metrics="fitted 高于 fixed(+1.915%) 属选择偏差——择优倾向挑更极端、候选更少的阈值",
         cost_model="同上",
         result="冻结阈值 35.0 是保守的一端而非数据驱动最优；阈值的最优点在折间不稳定",
         limitations="网格较粗；未做嵌套交叉验证的完整校正",
         decision="REJECT_PARAM_OPTIMIZATION（不得据此类搜索回填生产参数）"),

    # ==================== 第 4 轮：D-2 盘中可解释性 ====================
    dict(experiment_id="DS-R026", date=R, code_version="working-tree", round="4",
         purpose="修复 D-2：盘中信号状态/持有期口径/失效条件的可解释性",
         data_source="源码 + 回归用例 + SFC 编译校验",
         data_range="paper/watch/loop.py；modules/web/src/views/watch/MonitorView.vue",
         universe="盘中信号链路",
         features="signal_status / holding_horizon / invalidation_conditions",
         params="actionable = action∈{buy,sell} 且 ready_before 且 status==OBSERVE",
         n_samples="2 例新增（WARMUP 去因 + 可执行口径）",
         primary_metric="pytest paper/tests/test_decision_audit.py → 9 passed；"
                        "全量 python -m pytest -q → 352 passed（基线 350）",
         aux_metrics="反向对照（还原原始 loop.py）→ 4 failed，恢复后字节一致；"
                     "Vue SFC parse/compileScript/TS/模板编译全部 OK",
         cost_model="无",
         result="signal_status 三态在监控页可见；不可执行信号必须给出去因；"
                "可执行信号持有期口径为 INTRADAY_EOD",
         limitations="未执行全量 vite build（高资源且会覆盖 dist）；"
                     "未在浏览器做端到端目视确认",
         decision="ACCEPTED_ONLY_ENGINEERING（可解释性修复，不构成准确性改善证据）"),

    # ==================== 第 5 轮：D-4 + P0-3 ====================
    dict(experiment_id="DS-R027", date=R, code_version="working-tree", round="5",
         purpose="修复 D-4：覆盖率「适用范围」与「不适用原因」的可解释性",
         data_source="源码 + 回归用例 + SFC 编译校验",
         data_range="paper/watch/loop.py；modules/web/src/views/watch/MonitorView.vue",
         universe="盘中 tick 指标 + 监控页状态栏",
         features="coverage_scope / market_wide_coverage / market_wide_coverage_na_reason",
         params="full_market 且池构建成功 → 数值；池内模式 → None + UNIVERSE_NOT_FULL_MARKET；"
                "池构建失败 → None + FULL_UNIVERSE_UNAVAILABLE",
         n_samples="2 例新增（池内模式 vs 全市场；全市场不可用）",
         primary_metric="pytest paper/tests/test_decision_audit.py → 11 passed；"
                        "全量 python -m pytest -q → 354 passed（基线 350）",
         aux_metrics="反向对照（还原原始 loop.py）→ 6 failed，恢复后字节一致；"
                     "Vue 双文件 SFC/TS/模板编译全部 OK",
         cost_model="无",
         result="「全市场覆盖」栏不再显示无信息量占位；口径不符与取数失败被区分；"
                "池内模式下真实覆盖率由 quote_coverage 承载",
         limitations="未执行全量 vite build；未在浏览器目视确认",
         decision="ACCEPTED_ONLY_ENGINEERING（可解释性修复，不构成准确性改善证据）"),

    dict(experiment_id="DS-R028", date=R, code_version="working-tree", round="5",
         purpose="修复 P0-3：区分「非交易日」与「交易日但数据缺失」；删除恒假元数据",
         data_source="源码 + 回归用例 + 本地只读 DB 盘点",
         data_range="T1CandidateService.java；TradingCalendarService.java；"
                    "web research.ts + T1Candidate.vue",
         universe="T+1 候选生成的空决策路径",
         features="status(NOT_A_TRADING_DAY/NO_DATA/UNKNOWN) / calendar_trading_day / "
                  "data_cutoff；删除 data_date_fallback",
         params="日历覆盖且交易日→NO_DATA；日历覆盖且非交易日→NOT_A_TRADING_DAY；"
                "日历不覆盖→UNKNOWN（不放宽、不回退、不落库）",
         n_samples="3 例新增 + 1 例改写（Java）",
         primary_metric="mvn -Dtest=T1CandidateServiceTest → 18/18；"
                        "mvn 全量 → 452 tests, 0 failures, BUILD SUCCESS",
         aux_metrics="反向对照（只回退 T1CandidateService，保留 coverageWindow 基础设施）"
                     "→ 4 例行为断言失败（expected NOT_A_TRADING_DAY/UNKNOWN, was NO_DATA），"
                     "恢复后字节一致",
         cost_model="无",
         result="休市与数据缺失不再混为一谈；日历不覆盖时给出 UNKNOWN 而非妄断；"
                "恒为 false 的 data_date_fallback 已从后端/类型/页面移除",
         limitations="未构建前端、未浏览器确认；交易日历仍只到 2026-08-19（写库需授权），"
                     "故 2026-09-08 之后只是如实报告 UNKNOWN，并未修好数据",
         decision="ACCEPTED_ONLY_ENGINEERING（如实性修复，不构成准确性改善证据）"),

    # ==================== 第 6 轮：D-5/D-6 反证 + 链路贯通 ====================
    dict(experiment_id="DS-R029", date=R, code_version="working-tree", round="6",
         purpose="核查 D-5：重启是否会重复播报同一根 bar（原判断为缺陷）",
         data_source="真实 SqliteWatchStore 构造重启场景 + 源码结构核对",
         data_range="closed_bar 主键；bar_store 跨桶关闭；loop 信号产生点",
         universe="单标的（600519）5 分钟 bar，两会话模拟重启",
         features="new_closed / closed_bar 唯一性 / strategy_state",
         params="会话1 seed 19 根推进 +100min；会话2 从 store 重建推进 +105min",
         n_samples="2 会话 × 2 轮 = 4 次 run_once",
         primary_metric="两会话信号交集 = 空（未观察到重复播报）",
         aux_metrics="closed_bar 重复主键行 = 0；strategy_state 确有写入；"
                     "新增回归用例 test_restart_does_not_rebroadcast_the_same_closed_bar",
         cost_model="无",
         result="原缺陷主张【不成立】。结构性原因：closed_bar 主键 "
                "(code,period_min,start_ts)+upsert 使同一根 bar 至多一行，"
                "且信号只由跨桶关闭产生的 new_closed 触发",
         limitations="会话2 的 store 仅含 1 根 bar（存量种子未落库），"
                     "多 bar 重建路径未覆盖；未量化提醒条数增长",
         decision="REJECT_ORIGINAL_CLAIM（推翻自家缺陷主张；不修生产逻辑，"
                  "避免与主键约束重叠并引入跨日状态）"),

    dict(experiment_id="DS-R030", date=R, code_version="working-tree", round="6",
         purpose="核查 D-6：是否对同一股票/规则无限重复提醒",
         data_source="源码结构核对（events.py emit 无去重；MonitorView 80 行上限）",
         data_range="loop.py:2239；events.py:195-221；MonitorView.vue 显示上限",
         universe="SIGNAL 事件流",
         features="emit 去重 / 提醒级节流 / 展示上限",
         params="未做参数改动",
         n_samples="0（结构性论证）",
         primary_metric="未观察到同一根 bar 的重复提醒",
         aux_metrics="上游输入侧重复已被结构性排除（同 DS-R029）",
         cost_model="无",
         result="原缺陷主张【不成立】。仍成立但属另一问题的部分："
                "提示条数可随时间线性增长（『条数多』而非『重复』）",
         limitations="未量化真实长跑的提醒条数；把展示上限升级为提醒节流"
                     "会改变用户可见信息量，属产品取舍，需使用者确认",
         decision="REJECT_ORIGINAL_CLAIM（无重复证据；节流属产品行为变更，暂不做）"),

    dict(experiment_id="DS-R031", date=R, code_version="working-tree", round="6",
         purpose="验证第 4/5 轮修复字段能否真正到达浏览器（生产链路贯通）",
         data_source="源码级 7 环节核对",
         data_range="loop.py:2622-2651 → _finish → mx_http.sanitize_payload → "
                    "events.emit → serve.py:1141-1158 → MonitorView.vue:472-478,506-524",
         universe="coverage_scope / market_wide_coverage_na_reason / signal_status",
         features="tick.metrics → CYCLE detail → 脱敏 → JSONL → HTTP → 前端",
         params="未做参数改动",
         n_samples="7 个环节逐一核对",
         primary_metric="链路贯通：新字段可到达前端读取处",
         aux_metrics="关键一环 mx_http.py:46-49 sanitize_payload 确认只遮蔽账户/密钥同义字段，"
                     "不会丢弃新字段",
         cost_model="无",
         result="字段可达浏览器；此前未验证的『脱敏是否会丢字段』风险已排除",
         limitations="仍**未**在浏览器目视确认；未执行 vite build",
         decision="ACCEPTED_AS_CHAIN_EVIDENCE（链路证据，非界面确认）"),

    # ============ 第 7 轮：覆盖率闸真实回放 + 闸从未在生产运行 ============
    dict(experiment_id="DS-R032", date=R, code_version="34098346+working-tree", round="7",
         purpose="在真实 quant_db 上回放覆盖率闸：旧分母 vs 修正分母的判定差异",
         data_source="本地 quant_db（只读 SELECT）market_data / stock_info",
         data_range="2026-03-31、2026-08-03/04/11/18/24、2026-09-07/08、2026-10-12",
         universe="stock_info status=1 且 list_date<=T 的键；修正口径再与 [T-60d,T] 有 bar 取交集",
         features="daily_coverage / daily_expected / daily_covered",
         params="ACTIVE_WINDOW_DAYS=60；MIN_DAILY_COVERAGE=0.95（未动）",
         n_samples="9 个交易日",
         primary_metric="OLD 覆盖率 0.9350-0.9371（9/9 BLOCK）；NEW 正常期 0.9963-0.9978（PASS）",
         aux_metrics="断崖日 2026-09-08 NEW=0.0455、2026-10-12 NEW=0.0454 仍 BLOCK；"
                     "与 Java 注释标定（155/155、min=0.9596、断崖 0.0455）独立吻合",
         cost_model="无",
         result="旧口径在真实数据上确认恒不通过（候选恒空机制复现）；"
                "修正口径恢复灵敏度（正常期放行）且不放宽标准（断崖仍阻断）",
         limitations="单日重算，未跑完整 155 日；未执行真实 Java 服务（离线等价实现）；"
                     "注释把「主表∖活跃」≈358 只归因于 284 个双 exchange 代码，归因不完整",
         decision="ACCEPTED_AS_GATE_EVIDENCE（证明修复有效；非准确性改善证据）"),

    dict(experiment_id="DS-R033", date=R, code_version="34098346+working-tree", round="7",
         purpose="核实覆盖率闸是否真正进入生产链路（任务书 §1 首项）",
         data_source="git 全历史扫描 + 本地 quant_db t1_candidate 落库快照 + 全仓 @Scheduled 扫描",
         data_range="T1CandidateService.java 全部 10 个提交；t1_candidate 8 条快照",
         universe="INCOMPLETE_DAILY_DATA 标记 / eligMeta 键集合 / 调度注解",
         features="闸引入提交、落库 eligibility 形态、触发方式",
         params="无",
         n_samples="10 个提交 × 4 个标记；8 条快照",
         primary_metric="INCOMPLETE_DAILY_DATA 仅存在于 34098346；全仓 @Scheduled = 0",
         aux_metrics="8 条快照的 eligibility 恰为 6 键（不含 daily_coverage），"
                     "对应 9f0863f0(08-13)/32ae86f6(08-09)/d70120b6(09-15) 形态；"
                     "唯一入口为手动 POST /api/research/t1/candidates",
         cost_model="无",
         result="闸【从未在生产链路运行过】：无调度触发，且无任何落库快照携带闸相关字段。"
                "8 条历史空快照由更早版本产生，不是覆盖率闸造成的",
         limitations="无法排除「曾部署过含闸的构建但未留下快照」——"
                     "但即使如此，也没有任何生产证据证明闸曾生效",
         decision="CONFIRMED_NOT_IN_PRODUCTION（修复未进入生产链路）"),

    dict(experiment_id="DS-R034", date=R, code_version="34098346+working-tree", round="7",
         purpose="界定幸存者偏差与前视 ST 过滤的可测性",
         data_source="本地 quant_db（只读 SELECT）+ offline_eval.py 源码核对",
         data_range="market_data 全量键；stock_info 全表；评价窗口 2026-01-01..2026-08-31",
         universe="5,839 行情键 vs 5,835 主表键；窗口内中途停止交易的 23 个代码",
         features="键覆盖 / status 分布 / 退市列存在性 / 当前名称 ST 标记",
         params="无",
         n_samples="5,839 键；23 个中途停牌代码",
         primary_metric="行情键中仅 4 个不在主表；status 全为 1；stock_info 无退市列",
         aux_metrics="23 个中途停牌代码中 20 个当前为 *ST/ST；"
                     "offline_eval.py:205 用当前 stock_name 过滤历史全窗口（前视，方向乐观）；"
                     "B0 total_unfillable=3/215（1.40%）",
         cost_model="无",
         result="L1 前视 ST 过滤【已确认存在】，可影响范围限于当前 ST 名单；"
                "L2 幸存者偏差【存在但不可量化】——退市标的行情整体不存在；"
                "L5 不可成交剔除影响 < 0.02%",
         limitations="无历史 ST 名单、无退市字段 ⇒ L1/L2 均无法在本地修复；"
                     "未重跑评价量化 L1（需重新生成 pool 缓存）",
         decision="DISCLOSED_AS_LIMITATION（如实披露，不做猜测性改动）"),

    dict(experiment_id="DS-R035", date=R, code_version="34098346+working-tree", round="8",
         purpose="用可执行的 DOM-free harness 复核 MonitorView 的 D-2/D-4 装配"
                 "（此前只有文本/AST 存在性证据，未证明运行结果）",
         data_source="本地 modules/web 真实源码 + 真实 node_modules"
                     "（vue / @vue/compiler-sfc / typescript）；"
                     "受控 fixture（非真实后端）",
         data_range="6 个 fixture 场景（A 正常全市场 / B 池内模式 / C 取数失败 / "
                    "D 字段缺失 / E 状态映射 / F WARMUP 缺省）",
         universe="MonitorView.vue 单一组件（真实 setup + 真实编译模板）",
         features="signal_status 标签 / holding_horizon / invalidation_conditions / "
                  "market_wide_coverage 文案 / coverage_scope 口径",
         params="无（未改生产代码）",
         n_samples="17 条断言",
         primary_metric="17/17 通过，退出码 0",
         aux_metrics="覆盖率四种口径互不串味：全市场 99.83% / 池内「不适用（池内模式）」/"
                     "取数失败「不可用（全市场池构建失败）」/缺字段「不适用（口径未标注）」；"
                     "WARMUP→预热不足、INVALIDATED→已失效、空值→观察中（缺省不谎报失效）；"
                     "WARMUP 持有期口径 UNVALIDATED",
         cost_model="无",
         result="D-2 与 D-4 的前端装配【执行级通过】："
                "失败被正确区分、不被静默伪装成一种正常口径；"
                "缺省状态不谎报为已失效",
         limitations="非界面目视确认（未做 vite build，未看真实排版/颜色/滚动）；"
                     "T1Candidate.vue 依赖较多未纳入，P0-3 标签仍只有文本级证据；"
                     "依赖 modules/web/node_modules 版本，升级后需重跑",
         decision="ACCEPTED_AS_ASSEMBLY_EVIDENCE（证明前端显示正确；非准确性改善证据）"),

    dict(experiment_id="DS-R036", date=R, code_version="34098346+working-tree", round="9",
         purpose="补「成交约束证据」：量化现有『一字板』成交约束的实际影响，"
                 "并检验已报告优势对入场滑点的依赖（此前假设零滑点且从未量化）",
         data_source="本地 quant_db（只读 SELECT）重建 B0 入选集合；"
                     "口径逐条照抄 offline_eval.py / offline_metrics.py",
         data_range="2026-01-05..2026-08-31（155 个信号日）",
         universe="B0 生产规则 TopN=10、阈值 35.0 的 215 个入选标的",
         features="T+1 开盘/涨停价比值、T+1 开盘跳空、一字板、入场滑点",
         params="入场滑点 s ∈ {0, 0.5%, 1%, 2%, 3%}；高开剔除阈值 {3%,5%,7%,9%}",
         n_samples="215 入选（可成交 212）",
         primary_metric="复现与冻结基线逐位一致：total_picks=215、rows_scored=248337、"
                        "组合日均净收益 0.015303550444946206（== 基线，<1e-12）",
         aux_metrics="一字板约束近无影响：unfillable=3/215(1.40%)、真一字板=1(0.47%)、"
                     "开盘价/涨停价最大仅 0.9934（无一够到涨停）；"
                     "入场滑点：0%→+1.530% CI[+0.767%,+2.196%]，0.5%→+1.025%，"
                     "1%→+0.524% CI[−0.231%,+1.182%]（含 0），2%→−0.463%，3%→−1.430%；"
                     "保本滑点≈1.51%；T+1 开盘跳空中位 −1.37%、均值 −1.95%（平均低开）；"
                     "剔除高开标的配对差均含 0（无显著收益）",
         cost_model="佣金 3bp×2 + 印花税 5bp + 过户费 0.1bp×2 = 11.2bp（与基线一致）",
         result="『一字板』约束对这套规则近乎无影响（第 7 轮结论被独立复现并加强）；"
                "真正的主要成交风险是【入场滑点】——保本点仅约 1.51%，"
                "即约 1% 的不利滑点就使优势与 0 不可区分，此前完全未披露；"
                "但 T+1 开盘平均低开 1.95%，故统一不利滑点对本策略偏保守；"
                "剔除高开标的无显著收益，不作为改进采纳",
         limitations="本地无盘中撮合/L2 数据 ⇒ 真实滑点【无法估计】（不是算出为 0，"
                     "而是本地没有可估的量）；滑点序列为机械恒等式，其配对 CI 退化是"
                     "结构性的，不可读成显著性；单一区间 2026-01..08，已被反复查看",
         decision="DISCLOSED_AS_FILL_CONSTRAINT_EVIDENCE"
                  "（补足 §1 成交约束证据；缩小而非扩大可声称的空间）"),

    dict(experiment_id="DS-R037", date=R, code_version="34098346+working-tree", round="10",
         purpose="把 P0-1/P0-3 修复真正送进生产链路并取得运行证据（此前 9 轮全部绕过链路、"
                 "第 7 轮结论是「闸从未在生产上运行过」）；同时补交易日历回填与自动生成能力",
         data_source="真实启动 Spring Boot 后端并发出真实 HTTP 请求；"
                     "本地 quant_db（calendar 回填走项目自带幂等接口，其余只读）",
         data_range="请求日 2026-08-19 / 2026-09-07 / 2026-09-08 / 2026-10-12",
         universe="生产链路实际加载的宇宙（stock_info_fallback，pit_limited=true）",
         features="覆盖率闸分母口径（活跃窗口 60 天）、日历交易日判定、快照落库字段",
         params="MIN_DAILY_COVERAGE=0.95（未改）；ACTIVE_WINDOW_DAYS=60（未改）；"
                "quant.t1.schedule.enabled=false（默认关）",
         n_samples="4 个请求日 + 1 条新落库快照（2026-08-19）",
         primary_metric="覆盖率闸首次在生产链路执行：2026-08-19 cov=0.9967 通过；"
                        "2026-09-08 cov=0.0455 阻断（5469/249）；2026-10-12 cov=0.0454 阻断",
         aux_metrics="阻断响应含第 5 轮新增字段 coverage_denominator_rule ⇒ 修复确实在跑；"
                     "在线值 vs 第 7 轮离线回放逐位吻合；P0-3 calendar_trading_day=true 生效；"
                     "v2 首次落库：t1_rule_v2/35.0、total_scored=1314、eligible=1、"
                     "候选 300684 中石科技 rule_score=40.2628；"
                     "快照如实标注 master_source=stock_info_fallback / pit_limited=true / "
                     "quality_status=PIT_LIMITED；每日 bar 数确认断崖恰在 2026-09-08（5456→249）",
         cost_model="11.2bp（沿用冻结合同；本轮未重估）",
         result="P0-1 覆盖率闸与 P0-3 日历语义【首次在生产链路上取得运行证据】，"
                "且与离线回放逐位一致；阈值 0.95 未放宽、真实断崖仍被正确阻断；"
                "零候选日（2026-09-07，池内最大分 33.10<35.0）确认是市场状态而非闸门误杀；"
                "security_master_history 回填【被证否】：内置 bootstrap 写 current_snapshot+limited，"
                "而 PointInTimeUniverseProvider:80-84 强制将其判为 limited ⇒ pitLimited 仍 true、"
                "概率模型仍被阻断，本地又无任何带版本证明的历史源 ⇒ 写入等于白做，故不执行",
         limitations="这是【工程验证轮】，不含任何策略准确性证据；本轮对生产库有写入"
                     "（新增 2026-08-19、2026-09-07 两条 t1_candidate 行，均为新增而非覆盖）；"
                     "交易日历为 eastmoney_derived 派生口径、reliability=limited，非官方；"
                     "幸存者偏差未消除且当前条件下概率模型不可能被合法解锁；"
                     "零候选日的分数分布来自【近似复刻】（未施加 PIT 过滤），只可用于判断趋势；"
                     "未产生任何新的独立时间区间",
         decision="PRODUCTION_WIRING_VERIFIED_AND_SCHEDULER_ADDED"
                  "（把「修复是否进生产」从读代码推进到运行证据；"
                  "并用证否 security_master_history 收紧结论边界）"),

    dict(experiment_id="DS-R038", date=R, code_version="34098346+working-tree", round="11",
         purpose="查明「2026-09-08 数据断崖」的根因：是行情源没数据，还是自家入库管线坏了。"
                 "（第 7 轮只把它当日期边界处理，未追问性质）",
         data_source="本地 quant_db 只读：market_data.created_at、data_update_task、"
                     "data_update_task_item；外加离线复算（口径同第 9 轮）",
         data_range="market_data 尾段 2026-08-25..2026-10-12；任务表全部 49 个任务",
         universe="全市场 5,484 只（MAIN_BOARD）",
         features="每日 bar 数、入库时间戳、交易所分布、集合重叠、任务级/标的级状态",
         params="阈值 35.0 / topn 10 / ACTIVE_WINDOW_DAYS=60（全部沿用冻结基线，未改）",
         n_samples="断崖后 19 个交易日、249 只 SZSE 标的；任务级 49 条、标的级 144,108 条",
         primary_metric="根因确证为【自家全市场入库管线部分失败】：最后全市场成功任务 id 47 "
                        "（2026-09-08 10:30:11, 5484/5484），其后 id 48/49 今天分别在 "
                        "252/153 只处中断；断崖后仅 SZSE 249 只（BSE/SSE 停在 09-08）",
         aux_metrics="断崖后集合是断崖前的【子集】（交集 249、仅断崖后 0）；000001 在窗口内"
                     "每日都有且仅有一根 bar（数据本身完整，只是覆盖面只剩 249 只）；"
                     "FAILED 来源为启动恢复（唯一会同时留下 finished_at=null 且 message=null 的路径）；"
                     "断崖窗口按生产规则实测 19 天、池内 64–69 只、过阈值 0、入选 0；"
                     "冻结基线复现 3/3 逐位一致（215 / 248337 / 0.015303550444946206）",
         cost_model="11.2bp（沿用；本轮无成交故未实际使用）",
         result="断崖【不是市场休市，而是自家管线部分失败】，且有任务表时间线 + 交易所指纹"
                "双重证据；据此确立前置依赖：日频选股当前时点不可用（数据不够而非规则不行），"
                "覆盖率闸的阻断是【正确的】——它拦住的正是「用 249 只冒充全市场」的静默失真；"
                "断崖窗口不可用于任何策略评价；更正第 10 轮把 09-08 之后零候选归因于"
                "「市场状态」的表述（该归因只对 09-07 成立）",
         limitations="无法确定中断的具体触发者（10:31–10:36 那个 JVM 的日志已不存在），"
                     "只能确证「中断后由启动恢复兜底改状态」；本轮未修复该管线、"
                     "未触发全市场回填（属需先确认的动作，且今天已失败两次）；"
                     "未产生任何策略收益证据；幸存者偏差/PIT 未解锁/单区间/阈值来源等旧局限不变",
         decision="ROOT_CAUSE_IDENTIFIED_OWN_PIPELINE_PARTIAL_FAILURE"
                  "（确立「必须先修复全市场回填才谈后续迭代」的前置依赖；"
                  "并新增登记启动恢复不记原因的诊断缺口）"),
]


def main() -> None:
    out = pathlib.Path("research/decision_support_iteration/experiments.csv")
    with out.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=HEADER, lineterminator="\n")
        w.writeheader()
        for row in ROWS:
            missing = set(HEADER) - set(row)
            unknown = set(row) - set(HEADER)
            assert not unknown, f"{row['experiment_id']} unknown cols: {unknown}"
            w.writerow({k: row.get(k, "") for k in HEADER})
    # 自检
    rows = list(csv.reader(out.open(encoding="utf-8", newline="")))
    widths = {len(r) for r in rows}
    print(f"wrote {out} rows={len(rows) - 1} distinct field counts={widths}")
    assert widths == {len(HEADER)}, f"field count mismatch: {widths}"
    print("CSV field-count check: OK")


if __name__ == "__main__":
    main()
