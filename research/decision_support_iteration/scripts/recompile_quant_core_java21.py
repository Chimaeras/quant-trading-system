"""用 javac 直接重编译 quant-core-java 到 Java 21 字节码。

背景
----
`.m2` 里的 `com.quant:quant-core-java:1.0-SNAPSHOT` 是 2026-02 用 **JDK 23**
（class file major 67）安装的，而本机 `JAVA_HOME` 是 JDK 21、Spring Boot 3.2 自带的
ASM 9.6 最高只认到 Java 22（major 66）。于是后端启动时 Spring 的组件扫描在解析
`com.quant.core.collector.SohuStockClient` 时抛：

    IllegalArgumentException: Unsupported class file major version 67

为什么此前没暴露：`mvn test` 只跑单测，**从不启动 Spring 上下文**，
ASM 扫描根本没发生。只有真正 `java -cp ... QuantBackendApplication` 才会撞上。

做法：不碰源码，只把 14 个 .java 文件按 Java 21 **重新编译**到独立目录
（`%TEMP%\\quant-core-java-21`），运行时把它排在旧 jar **之前**即可。
不修改 `E:\\Project\\StockInfo` 里的任何文件，也不覆盖 `.m2`。
"""
from __future__ import annotations

import os
import pathlib
import re
import shutil
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

CORE = pathlib.Path(r"E:\Project\StockInfo\quant-core-java")
REPO = pathlib.Path.home() / ".m2" / "repository"
OUT = pathlib.Path(os.environ["TEMP"]) / "quant-core-java-21"
JDK = pathlib.Path(r"C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\javac.exe")

SKIP_PATH = re.compile(r"[\\/](plugins|plugin)[\\/]", re.I)


def classpath() -> str:
    """宽松 classpath：全部非插件 jar（含 lombok / core-io）。"""
    jars = [str(j) for j in REPO.rglob("*.jar")
            if not SKIP_PATH.search(str(j)) and not re.search(r"(sources|javadoc)\.jar$", j.name, re.I)]
    return ";".join(jars)


def main() -> int:
    srcs = [str(p) for p in (CORE / "src").rglob("*.java")]
    if not srcs:
        print("no sources found")
        return 1
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True, exist_ok=True)

    # Windows 命令行长度上限约 32k，274 个 jar 的 classpath 会超。
    # 用 javac 的 @argfile（JDK 9+）承载参数。
    argfile = OUT.parent / "javac-args.txt"
    cp = classpath().replace("\\", "/")
    lines = ["-nowarn", "-proc:full", "--release", "21", "-encoding", "UTF-8",
             "-d", str(OUT).replace("\\", "/")]
    lines.append('-cp "' + cp + '"')
    argfile.write_text("\n".join(lines) + "\n" + "\n".join(
        s.replace("\\", "/") for s in srcs) + "\n", encoding="utf-8")

    cmd = [str(JDK), "@" + str(argfile)]
    print(f"[javac] {len(srcs)} sources -> {OUT} (--release 21); argfile={argfile}")
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if proc.stdout:
        print(proc.stdout[-3000:])
    if proc.returncode != 0:
        print("[javac] FAILED")
        print(proc.stderr[-4000:])
        return 1
    n = sum(1 for _ in OUT.rglob("*.class"))
    print(f"[javac] OK, {n} class files")
    target = OUT / "com" / "quant" / "core" / "collector" / "SohuStockClient.class"
    print(f"[check] SohuStockClient.class exists: {target.exists()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
