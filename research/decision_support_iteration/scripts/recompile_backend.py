"""用 javac 把 backend-java 源码增量重编译进 target/classes。

为什么不用 Maven：离线模式下 Maven 连 **插件** 都无法解析
（`_remote.repositories` 记录 artifactory 来源，离线被拒），
`mvn package` / `mvn dependency:build-classpath` 全部失败。
但依赖 jar 与 javac 都在，直接用 `@argfile` 编译即可。

注意：`target/classes` 可能停留在**改动之前**的快照——必须先重编译，
否则「源码已修」≠「生产链路在跑修复」。
"""
from __future__ import annotations

import os
import pathlib
import re
import subprocess
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = pathlib.Path(__file__).resolve().parents[3]
BACKEND = ROOT / "modules" / "backend-java"
SRC = BACKEND / "src" / "main" / "java"
OUT = BACKEND / "target" / "classes"
JDK = pathlib.Path(r"C:\Program Files\Microsoft\jdk-21.0.10.7-hotspot\bin\javac.exe")
RES = BACKEND / "src" / "main" / "resources"

SKIP_PATH = re.compile(r"[\\/](plugins|plugin)[\\/]", re.I)
LEAKED = ("spring-boot-starter-security", "spring-security-config",
          "spring-security-web", "spring-security-test")


def cp_jars() -> list[str]:
    repo = pathlib.Path.home() / ".m2" / "repository"
    out = []
    for j in repo.rglob("*.jar"):
        s = str(j)
        if SKIP_PATH.search(s) or re.search(r"(sources|javadoc)\.jar$", j.name, re.I):
            continue
        if "com\\quant\\quant-core-java" in s:
            continue  # JDK23 字节码，用重编译版本替代
        if any(x in s for x in LEAKED):
            continue
        out.append(s)
    core21 = pathlib.Path(os.environ["TEMP"]) / "quant-core-java-21"
    if core21.is_dir():
        out.append(str(core21))
    return out


def main() -> int:
    srcs = sorted(str(p) for p in SRC.rglob("*.java"))
    if not srcs:
        print("no sources")
        return 1
    OUT.mkdir(parents=True, exist_ok=True)
    cp = ";".join([str(OUT)] + cp_jars()).replace("\\", "/")

    argfile = pathlib.Path(os.environ["TEMP"]) / "backend-javac-args.txt"
    lines = ["-nowarn", "--release", "21", "-encoding", "UTF-8",
             "-parameters", "-d", str(OUT).replace("\\", "/"),
             "-cp", '"' + cp + '"']
    argfile.write_text("\n".join(lines) + "\n" +
                       "\n".join(s.replace("\\", "/") for s in srcs) + "\n",
                       encoding="utf-8")
    print(f"[javac] {len(srcs)} sources -> {OUT}")
    proc = subprocess.run([str(JDK), "@" + str(argfile)], capture_output=True,
                          text=True, encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        print("[javac] FAILED")
        print(proc.stdout[-4000:])
        print(proc.stderr[-4000:])
        return 1
    n = sum(1 for _ in OUT.rglob("*.class"))
    print(f"[javac] OK, {n} class files")

    # 把资源（application.yml、db/migration 等）同步进 classes
    import shutil
    copied = 0
    for p in RES.rglob("*"):
        if p.is_file():
            dst = OUT / p.relative_to(RES)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(p, dst)
            copied += 1
    print(f"[res] 同步 {copied} 个资源文件")
    return 0


if __name__ == "__main__":
    sys.exit(main())
