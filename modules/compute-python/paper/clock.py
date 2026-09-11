"""盯盘链路统一时钟：锚定北京时间（Asia/Shanghai），并保持 naive datetime 契约。

宿主或容器的 TZ 不是 CST 时，`datetime.now()` / `datetime.fromtimestamp()` 取到的
是宿主本地墙钟：交易时段判定（`in_trading_window`）、报价新鲜度、委托 TTL、默认业务日
都会整体平移，且没有任何 fail-closed 信号。这里统一在取时后抹掉 tzinfo，返回
naive 北京时间——CST 宿主上的行为与改动前完全一致，非 CST 宿主则回到正确口径。

只做「时钟来源统一」，不做格式转换：调用方仍然拿 naive datetime 做比较与 isoformat。
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

# 中国标准时间固定 UTC+8（无夏令时），不依赖 tzdata 是否安装。
CN_TZ = timezone(timedelta(hours=8), name="Asia/Shanghai")


def now() -> datetime:
    """当前北京时间（naive，去掉 tzinfo 以维持既有调用契约）。"""
    return datetime.now(CN_TZ).replace(tzinfo=None)


def from_timestamp(timestamp: float) -> datetime:
    """把秒级 Unix 时间戳（如东财 f124）转为北京时间 naive datetime。"""
    return datetime.fromtimestamp(float(timestamp), tz=CN_TZ).replace(tzinfo=None)
