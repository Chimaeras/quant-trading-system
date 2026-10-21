"""离线构造 Spring Boot 运行 classpath。

背景：Maven 在离线模式下连插件都无法解析（`_remote.repositories` 记录了
artifactory 来源，离线时被拒），但本地仓库里的依赖 jar 是齐全的（测试能跑）。
这里直接扫描本地仓库拼 classpath，绕过 Maven 的插件解析。

产出：`modules/backend-java/cp.txt`（Windows 分号分隔）。
"""
from __future__ import annotations

import os
import pathlib
import re
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

REPO = pathlib.Path.home() / ".m2" / "repository"
MOD = pathlib.Path(__file__).resolve().parents[3] / "modules" / "backend-java"
CLASSES = MOD / "target" / "classes"

# 主版本目录（同 artifact 多版本时取最高），跳过测试/源码/文档与构建插件
SKIP_NAME = re.compile(r"(sources|javadoc|tests)\.jar$", re.I)
SKIP_PATH = re.compile(r"[\\/](plugins|plugin)[\\/]", re.I)


def ver_key(v: str):
    return [int(x) if x.isdigit() else x for x in re.split(r"[.\-]", v)]


def main() -> int:
    jars: dict[str, tuple[tuple, pathlib.Path]] = {}
    for jar in REPO.rglob("*.jar"):
        s = str(jar)
        if SKIP_NAME.search(jar.name) or SKIP_PATH.search(s):
            continue
        group_artifact = str(jar.parent.parent.relative_to(REPO))
        version = jar.parent.name
        try:
            k = tuple(ver_key(version))
        except TypeError:
            k = ((0,),)
        prev = jars.get(group_artifact)
        if prev is None or k > prev[0]:
            jars[group_artifact] = (k, jar)

    ordered = [str(v[1]) for _, v in sorted(jars.items())]
    # quant-core-java 的 .m2 快照是 JDK 23 字节码（major 67），Spring Boot 3.2 自带的
    # ASM 9.6 最高只认 major 66。注意：仅仅把它排后面**不够** —— Spring 的组件扫描会
    # 遍历 classpath 上**所有** jar 的条目，只要这个 jar 在场就会被读到并炸掉。
    # 因此必须整条排除，改用重编译出的 Java 21 版本。
    STALE_CORE = "com\\quant\\quant-core-java"
    # 手工拼 classpath 会把**共享 .m2 里存在但本项目并未声明**的 starter 一起拖进来。
    # `spring-boot-starter-security` 就是典型：本项目只声明了 `spring-security-crypto`
    # （用于 BCrypt 口令哈希），但 starter-security 一旦在 classpath 上，Spring Boot
    # 就会自动装配 `DefaultSecurityFilterChain` + httpBasic，把**所有**请求挡成
    # `401 WWW-Authenticate: Basic realm="Realm"`，与项目自身的 AuthInterceptor 冲突。
    # 真实 Maven 构建不会包含它，故此处必须排除（保留 spring-security-crypto）。
    LEAKED = (
        "spring-boot-starter-security",
        "spring-security-config",
        "spring-security-web",
        "spring-security-test",
    )
    core21 = pathlib.Path(os.environ["TEMP"]) / "quant-core-java-21"
    kept = [u for u in ordered if STALE_CORE not in u and not any(x in u for x in LEAKED)]
    dropped = len(ordered) - len(kept)
    cp = [str(CLASSES)]
    if core21.is_dir():
        cp.append(str(core21))
        print(f"[core] 排除 .m2 陈旧 jar；使用 Java21 重编译版本 {core21}")
    else:
        print("[warn] 未找到 Java21 版 quant-core-java，后端可能启动失败")
    print(f"[deps] 排除 {dropped} 个不应在 classpath 上的 jar（陈旧 core + 泄漏的 security starter）")
    cp += kept
    out = MOD / "cp.txt"
    out.write_text(";".join(cp), encoding="utf-8")
    print(f"artifacts={len(ordered)}  classes={CLASSES.exists()}")
    print(f"wrote {out} ({out.stat().st_size} bytes)")
    for key in ("org.springframework.boot/spring-boot",
                "com.mysql/mysql-connector-j",
                "org.flywaydb/flyway-core",
                "com.h2database/h2"):
        hit = [k for k in jars if key.split("/")[1] in k]
        print(f"  {key:44} {'OK' if hit else 'MISSING'} {hit[:2]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
