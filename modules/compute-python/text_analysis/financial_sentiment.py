"""
基于 Hugging Face Transformers 的金融文本情感分析（FinBERT）。

模型默认：ProsusAI/finbert（英文财经短语/新闻，三分类：positive / negative / neutral）。
标签顺序以模型 config.id2label 为准，避免与固定下标不一致。
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

import numpy as np
import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

# 英文标签 -> 中文展示（FinBERT 常用三类）
_LABEL_ZH: Dict[str, str] = {
    "positive": "正面(利好)",
    "negative": "负面(利空)",
    "neutral": "中性",
}


@dataclass
class SentimentResult:
    """单条文本的情感分析结果。"""

    text: str
    label_en: str
    label_zh: str
    confidence: float
    probabilities: Dict[str, float] = field(default_factory=dict)
    probabilities_zh: Dict[str, float] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "text": self.text,
            "label_en": self.label_en,
            "label_zh": self.label_zh,
            "confidence": self.confidence,
            "probabilities": self.probabilities,
            "probabilities_zh": self.probabilities_zh,
        }


def _normalize_id2label(id2label: Any) -> Dict[int, str]:
    if id2label is None:
        return {}
    if isinstance(id2label, dict):
        return {int(k): str(v) for k, v in id2label.items()}
    if isinstance(id2label, (list, tuple)):
        return {i: str(v) for i, v in enumerate(id2label)}
    return {}


class FinancialSentimentAnalyzer:
    """
    FinBERT 情感分析器。首次 ``analyze`` 时加载模型（可显式调用 ``load()`` 预加载）。
    """

    def __init__(
        self,
        model_name: str = "ProsusAI/finbert",
        device: Optional[str] = None,
        max_length: int = 128,
    ) -> None:
        self.model_name = model_name
        self.max_length = max_length
        if device:
            self.device = torch.device(device)
        else:
            self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self._tokenizer: Optional[Any] = None
        self._model: Optional[Any] = None
        self._id2label: Dict[int, str] = {}

    def load(self) -> None:
        if self._model is not None:
            return
        # 关闭 Transformers 在加载 pytorch_model.bin 后启动的后台「safetensors 转换」线程
        #（该线程会请求 Hugging Face API，与推理无关；网络不稳时会出现 Thread-auto_conversion 报错）
        os.environ.setdefault("DISABLE_SAFETENSORS_CONVERSION", "1")
        self._tokenizer = AutoTokenizer.from_pretrained(self.model_name)
        self._model = AutoModelForSequenceClassification.from_pretrained(self.model_name)
        self._model.to(self.device)
        self._model.eval()
        self._id2label = _normalize_id2label(getattr(self._model.config, "id2label", None))
        if not self._id2label:
            n = int(self._model.config.num_labels)
            self._id2label = {i: f"label_{i}" for i in range(n)}

    def _label_zh(self, label_en: str) -> str:
        key = label_en.strip().lower()
        return _LABEL_ZH.get(key, label_en)

    def analyze(self, text: str) -> SentimentResult:
        if not text or not str(text).strip():
            raise ValueError("text 不能为空")
        self.load()
        assert self._tokenizer is not None and self._model is not None

        inputs = self._tokenizer(
            text,
            max_length=self.max_length,
            padding="max_length",
            truncation=True,
            return_tensors="pt",
        )
        inputs = {k: v.to(self.device) for k, v in inputs.items()}

        with torch.no_grad():
            outputs = self._model(**inputs)
            logits = outputs.logits
            probs_t = torch.softmax(logits, dim=1).detach().cpu().numpy()[0]

        pred_idx = int(np.argmax(probs_t))
        label_en = self._id2label.get(pred_idx, f"label_{pred_idx}")
        confidence = float(np.max(probs_t))

        probs_by_en: Dict[str, float] = {}
        probs_by_zh: Dict[str, float] = {}
        for i, p in enumerate(probs_t):
            en = self._id2label.get(i, f"label_{i}")
            probs_by_en[en] = float(p)
            zh = self._label_zh(en)
            probs_by_zh[zh] = float(p)

        return SentimentResult(
            text=text,
            label_en=label_en,
            label_zh=self._label_zh(label_en),
            confidence=confidence,
            probabilities=probs_by_en,
            probabilities_zh=probs_by_zh,
        )

    def analyze_many(self, texts: List[str]) -> List[SentimentResult]:
        return [self.analyze(t) for t in texts]


_analyzer: Optional[FinancialSentimentAnalyzer] = None


def get_analyzer(
    model_name: str = "ProsusAI/finbert",
    device: Optional[str] = None,
) -> FinancialSentimentAnalyzer:
    """返回进程内单例分析器（便于在策略脚本里反复调用）。"""
    global _analyzer
    if _analyzer is None or _analyzer.model_name != model_name:
        _analyzer = FinancialSentimentAnalyzer(model_name=model_name, device=device)
    return _analyzer


def analyze_text(text: str, model_name: str = "ProsusAI/finbert") -> SentimentResult:
    """便捷函数：对单条文本做情感分析。"""
    return get_analyzer(model_name=model_name).analyze(text)


def run_demo() -> None:
    samples = [
        "The company reported a 45% YoY profit increase, beating expectations.",
        "A major developer defaulted on debt, raising credit concerns.",
        "CPI rose 2.1% in October, in line with market expectations.",
    ]
    print("正在加载 FinBERT 模型（首次运行会下载权重，请稍候）...")
    analyzer = FinancialSentimentAnalyzer()
    analyzer.load()
    print("模型就绪。\n")
    for news in samples:
        r = analyzer.analyze(news)
        print(f"文本: {news}")
        print(f"  情感: {r.label_zh} ({r.label_en}), 置信度: {r.confidence:.3f}")
        print(f"  概率: {r.probabilities}")
        print()


if __name__ == "__main__":
    run_demo()
