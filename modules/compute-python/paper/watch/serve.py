"""watch 监控面板只读 API（标准库实现，无第三方依赖）。

默认只绑定 127.0.0.1，只读本地 logs/paper/watch/** 产物，不下单不联网。

端点：
  GET /api/watch/days                          可用交易日列表
  GET /api/watch/{date}/events?after_seq=&code=&type=&limit=   事件增量
  GET /api/watch/{date}/state                  当日汇总（state.json + 最近 CYCLE）
  GET /api/watch/{date}/ticks                  当日每轮 tick 摘要列表
"""
from __future__ import annotations

import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from .events import read_events

DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def _json_response(handler: BaseHTTPRequestHandler, payload, status: int = 200) -> None:
    body = json.dumps(payload, ensure_ascii=False, default=str).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json; charset=utf-8")
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.end_headers()
    handler.wfile.write(body)


def _days(log_base: Path) -> list[str]:
    watch_dir = log_base / "watch"
    if not watch_dir.is_dir():
        return []
    return sorted(
        (item.name for item in watch_dir.iterdir() if item.is_dir() and DATE_RE.match(item.name)),
        reverse=True,
    )


def _state(log_base: Path, trade_date: str) -> dict:
    state_path = log_base / "watch" / trade_date / "state.json"
    state = {}
    if state_path.exists():
        try:
            state = json.loads(state_path.read_text(encoding="utf-8"))
        except ValueError:
            state = {}
    # 最近一轮 CYCLE 概要
    last_cycle = {}
    for event in reversed(read_events(log_base, trade_date, limit=100000)):
        if event.get("type") == "CYCLE" and event.get("detail", {}).get("phase") == "end":
            last_cycle = event
            break
    return {"state": state, "last_cycle": last_cycle}


def _ticks(log_base: Path, trade_date: str) -> list[dict]:
    day_dir = log_base / "watch" / trade_date
    ticks: list[dict] = []
    if not day_dir.is_dir():
        return ticks
    for run_dir in sorted(item for item in day_dir.iterdir() if item.is_dir()):
        tick_path = run_dir / "tick.json"
        if not tick_path.exists():
            continue
        try:
            tick = json.loads(tick_path.read_text(encoding="utf-8"))
        except ValueError:
            continue
        ticks.append({
            "run_id": tick.get("run_id", run_dir.name),
            "started_at": tick.get("started_at", ""),
            "finished_at": tick.get("finished_at", ""),
            "live": tick.get("live", False),
            "status": tick.get("status", ""),
            "bars": len(tick.get("new_closed_bars", [])),
            "signals": len(tick.get("signals", [])),
            "submits": len(tick.get("submits", [])),
            "errors": len(tick.get("errors", [])),
            "account": tick.get("account", {}),
        })
    return ticks


def make_handler(log_base: Path):
    class WatchServeHandler(BaseHTTPRequestHandler):
        # 静默默认访问日志，避免刷屏；错误照常返回 4xx/5xx
        def log_message(self, fmt, *args):  # noqa: N802
            pass

        def do_GET(self):  # noqa: N802
            parsed = urlparse(self.path)
            parts = [part for part in parsed.path.split("/") if part]

            if parsed.path == "/api/watch/health":
                return _json_response(self, {"ok": True, "service": "watch-serve"})

            if parsed.path == "/api/watch/days":
                return _json_response(self, {"days": _days(log_base)})

            if len(parts) == 3 and parts[0] == "api" and parts[1] == "watch" and DATE_RE.match(parts[2]):
                trade_date = parts[2]
                if trade_date not in _days(log_base):
                    return _json_response(self, {"error": "date_not_found", "date": trade_date}, 404)
                # /api/watch/{date} 直接给当日汇总
                return _json_response(self, _state(log_base, trade_date))

            if len(parts) == 4 and parts[0] == "api" and parts[1] == "watch" and DATE_RE.match(parts[2]):
                trade_date, resource = parts[2], parts[3]
                query = parse_qs(parsed.query)
                if trade_date not in _days(log_base):
                    return _json_response(self, {"error": "date_not_found", "date": trade_date}, 404)
                if resource == "events":
                    events = read_events(
                        log_base,
                        trade_date,
                        after_seq=int(query.get("after_seq", ["0"])[0] or 0),
                        code=query.get("code", [""])[0],
                        event_type=query.get("type", [""])[0],
                        limit=min(int(query.get("limit", ["2000"])[0] or 2000), 10000),
                    )
                    return _json_response(self, {"date": trade_date, "events": events})
                if resource == "state":
                    return _json_response(self, _state(log_base, trade_date))
                if resource == "ticks":
                    return _json_response(self, {"date": trade_date, "ticks": _ticks(log_base, trade_date)})
                return _json_response(self, {"error": "unknown_resource"}, 404)

            return _json_response(self, {"error": "not_found"}, 404)

    return WatchServeHandler


def serve(log_base: Path, host: str = "127.0.0.1", port: int = 8790) -> None:
    httpd = ThreadingHTTPServer((host, port), make_handler(Path(log_base)))
    print(f"watch-serve 只读 API: http://{host}:{port}/api/watch/days (log_base={log_base})")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
