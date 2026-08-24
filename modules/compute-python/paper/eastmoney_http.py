"""东方财富公开行情 HTTP：进程内复用 Session，统一浏览器态 headers。"""
from __future__ import annotations

from typing import Any

import requests

EASTMONEY_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Referer": "https://quote.eastmoney.com/",
    "Accept": "*/*",
    "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
    "Connection": "keep-alive",
}

_EASTMONEY_PROXIES = {"http": None, "https": None}

_session: requests.Session | None = None


def get_eastmoney_session() -> requests.Session:
    """懒创建并复用 Session，降低反复握手与 Connection reset 概率。"""
    global _session
    if _session is None:
        session = requests.Session()
        session.headers.update(EASTMONEY_HEADERS)
        session.proxies.update(_EASTMONEY_PROXIES)
        _session = session
    return _session


def reset_eastmoney_session() -> None:
    """测试或故障恢复时丢弃旧连接池。"""
    global _session
    if _session is not None:
        try:
            _session.close()
        except Exception:
            pass
        _session = None


def eastmoney_get(
    url: str,
    *,
    params: dict[str, Any] | None = None,
    timeout: float | tuple[float, float] | None = None,
    headers: dict[str, str] | None = None,
    proxies: dict[str, str | None] | None = None,
    **kwargs: Any,
) -> requests.Response:
    """带连接复用的 GET；proxies 默认禁用系统代理（与历史行为一致）。"""
    session = get_eastmoney_session()
    return session.get(
        url,
        params=params,
        timeout=timeout,
        headers=headers,
        proxies=_EASTMONEY_PROXIES if proxies is None else proxies,
        **kwargs,
    )
