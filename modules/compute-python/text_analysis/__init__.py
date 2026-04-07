"""
金融 / 通用文本情感分析（FinBERT 等）。

首次使用会从 Hugging Face 下载模型，需联网；之后可离线推理。
"""
from typing import TYPE_CHECKING, Any

__all__ = [
    "FinancialSentimentAnalyzer",
    "SentimentResult",
    "analyze_text",
    "get_analyzer",
]

if TYPE_CHECKING:
    from .financial_sentiment import (
        FinancialSentimentAnalyzer as FinancialSentimentAnalyzer,
        SentimentResult as SentimentResult,
        analyze_text as analyze_text,
        get_analyzer as get_analyzer,
    )


def __getattr__(name: str) -> Any:
    if name in __all__:
        from . import financial_sentiment as _fs

        return getattr(_fs, name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")
