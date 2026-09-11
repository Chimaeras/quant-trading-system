"""盯盘唯一执行通道路由；业务循环只依赖统一 Adapter。"""
from __future__ import annotations

from dataclasses import dataclass


class PreSendRejected(Exception):
    """发送前确定性拒单：委托请求从未离开本进程。

    与「提交结果未知」必须严格区分：unknown 意味着服务端可能已受理，需要冻结 live
    并走 orders 对账；而本异常发生在任何 I/O 之前，服务端必然未受理，对账永远匹配
    不到。因此 `WatchLoop._submit` 把它映射为 REJECTED（不置 `_live_halted`、
    不写入 `unknown_submits`）。
    """

    code = "CLIENT_REJECTED"

    def __init__(self, message: str, *, code: str | None = None):
        super().__init__(message)
        self.message = message
        if code:
            self.code = code


@dataclass(frozen=True)
class ExecutionAdapter:
    execution_mode: str
    source_name: str
    supports_external_execution: bool
    broker: object | None

    def submit_order(self, intent, *, run_id: str, local_order=None):
        raise NotImplementedError

    def cancel_order(self, order_id: str, code: str, *, run_id: str):
        if self.broker is None:
            raise RuntimeError(f"{self.execution_mode} 不支持撤单")
        return self.broker.cancel_order(order_id, code, run_id=run_id)


class ShadowExecutionAdapter(ExecutionAdapter):
    def submit_order(self, intent, *, run_id: str, local_order=None):
        raise RuntimeError("SHADOW 禁止产生订单副作用")


class InternalPaperExecutionAdapter(ExecutionAdapter):
    def submit_order(self, intent, *, run_id: str, local_order=None):
        submit = getattr(self.broker, "submit_local_order", None)
        if local_order is None or not callable(submit):
            raise RuntimeError("INTERNAL_PAPER 必须先创建本地 OMS 订单")
        return submit(local_order)


class ExternalPaperExecutionAdapter(ExecutionAdapter):
    def submit_order(self, intent, *, run_id: str, local_order=None):
        try:
            return self.broker.trade(
                intent.stock_code, intent.side, intent.qty,
                use_market_price=False, price=intent.limit_price, run_id=run_id,
            )
        except (ValueError, TypeError) as exc:
            # MxHttpClient.post 把网络/解析异常一律转成 MxResult 返回、从不抛出，
            # 所以这里捕获到的 ValueError/TypeError 只可能来自 trade() 发送前的入参
            # 校验（qty 非正的整百、代码非 6 位、限价非正等）——委托未送达服务端。
            raise PreSendRejected(f"{type(exc).__name__}: {exc}") from exc


def create_execution_adapter(execution_mode: str, broker) -> ExecutionAdapter:
    mode = str(execution_mode).strip().upper()
    if mode == "SHADOW":
        return ShadowExecutionAdapter(mode, "SHADOW", False, None)
    if mode == "INTERNAL_PAPER":
        return InternalPaperExecutionAdapter(mode, "LOCAL_SIMULATION", False, broker)
    if mode == "EXTERNAL_PAPER":
        if broker is None or not callable(getattr(broker, "trade", None)):
            raise ValueError("EXTERNAL_PAPER 必须配置妙想 BrokerClient")
        if callable(getattr(broker, "submit_local_order", None)):
            raise ValueError("EXTERNAL_PAPER 禁止使用 InternalPaperBroker")
        return ExternalPaperExecutionAdapter(mode, "MX_MIRROR", True, broker)
    raise ValueError(f"未知 execution_mode: {mode}")
