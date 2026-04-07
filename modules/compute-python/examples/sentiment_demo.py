"""
FinBERT 文本情感分析示例。

运行方式（任选其一）：
  cd modules/compute-python
  python examples/sentiment_demo.py

  或：
  python e:/Project/quant-trading-system/modules/compute-python/examples/sentiment_demo.py

首次运行需联网下载模型；若访问 huggingface.co 失败，可先设置镜像：
  PowerShell: $env:HF_ENDPOINT = "https://hf-mirror.com"
"""
from __future__ import annotations

import sys
from pathlib import Path

# 保证从任意 cwd 运行都能找到 text_analysis
_ROOT = Path(__file__).resolve().parent.parent
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from text_analysis import FinancialSentimentAnalyzer  # noqa: E402


def main() -> None:
    samples = [
        "新华社华盛顿4月6日电 美国总统特朗普6日在白宫举行的新闻发布会上声称，该由美国、而非伊朗收取霍尔木兹海峡“通行费”。被问及如何重新开放霍尔木兹海峡时，特朗普说，重新开放霍尔木兹海峡必须成为结束冲突方案的一部分。“必须达成一份我能够接受的协议，而这份协议的内容之一就是要确保石油及各类物资的自由通行。”他再度声称美国已经在军事上击败伊朗，因此应该“战胜者拥有战利品”，并表示，该由美国而不是伊朗对通过海峡的船只征收通行费。“为什么我们不收费？我们是赢家。”伊朗武装部队哈塔姆安比亚中央总部发言人6日晚些时候发表声明说，特朗普充满“妄想”，其无礼且傲慢的言辞以及毫无根据的威胁，无法弥补美国在西亚地区遭受的“羞辱”。",
        "A major developer defaulted on debt, raising credit concerns.",
        "CPI rose 2.1% in October, in line with market expectations.",
    ]
    print("正在加载 FinBERT（首次会下载模型，请稍候）...")
    analyzer = FinancialSentimentAnalyzer()
    analyzer.load()
    print("加载完成。\n")

    for text in samples:
        r = analyzer.analyze(text)
        print("文本:", text)
        print(f"  情感: {r.label_zh} ({r.label_en})")
        print(f"  置信度: {r.confidence:.4f}")
        print(f"  概率: {r.probabilities}")
        print()


if __name__ == "__main__":
    main()
