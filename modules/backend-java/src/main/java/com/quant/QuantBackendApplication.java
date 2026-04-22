package com.quant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

@SpringBootApplication
public class QuantBackendApplication {

    public static void main(String[] args) {
        // 启动前构建前端并复制到 static（可关闭：quant.properties 中 build-frontend-on-startup=false 或 -Dquant.skipFrontendBuild=true）
        boolean skip = "true".equalsIgnoreCase(System.getProperty("quant.skipFrontendBuild"));
        if (!skip) {
            try (InputStream is = QuantBackendApplication.class.getResourceAsStream("/quant.properties")) {
                if (is != null) {
                    Properties p = new Properties();
                    p.load(is);
                    skip = "false".equalsIgnoreCase(p.getProperty("build-frontend-on-startup", "true"));
                }
            } catch (Exception ignored) { }
        }
        if (!skip) {
            buildFrontendIfNeeded();
        } else {
            System.out.println("[Quant] 已跳过前端构建");
        }
        SpringApplication.run(QuantBackendApplication.class, args);
    }

    /** 在 Spring 启动前构建前端并复制到 static */
    private static void buildFrontendIfNeeded() {
        try {
            Path cwd = Path.of(System.getProperty("user.dir"));
            Path frontendDir = null;
            Path backendDir = null;

            // 新结构：repoRoot/modules/{web,backend-java}
            Path repoModulesWeb = cwd.resolve("modules").resolve("web");
            Path repoModulesBackend = cwd.resolve("modules").resolve("backend-java");
            if (Files.exists(repoModulesWeb.resolve("package.json")) && Files.isDirectory(repoModulesBackend)) {
                frontendDir = repoModulesWeb;
                backendDir = repoModulesBackend;
            }

            // 兼容：从 backend-java 目录启动（../web）
            if (frontendDir == null && Files.isDirectory(cwd.resolve("src")) && Files.exists(cwd.resolve("pom.xml"))) {
                Path siblingWeb = cwd.getParent().resolve("web");
                if (Files.exists(siblingWeb.resolve("package.json"))) {
                    frontendDir = siblingWeb;
                    backendDir = cwd;
                }
            }

            // 兼容旧结构：repoRoot/{frontend,backend}
            if (frontendDir == null) {
                Path oldFrontend = cwd.resolve("frontend");
                if (Files.exists(oldFrontend.resolve("package.json"))) {
                    frontendDir = oldFrontend;
                    backendDir = Files.isDirectory(cwd.resolve("backend")) ? cwd.resolve("backend") : cwd;
                }
            }
            if (frontendDir == null && cwd.getParent() != null) {
                Path oldFrontend = cwd.getParent().resolve("frontend");
                if (Files.exists(oldFrontend.resolve("package.json"))) {
                    frontendDir = oldFrontend;
                    backendDir = cwd;
                }
            }

            if (frontendDir == null || backendDir == null) return;
            System.out.println("[Quant] 正在构建前端...");
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(frontendDir.toFile());
            pb.inheritIO();

            List<List<String>> npmCandidates = npmCommands();
            boolean npmOk = false;
            for (List<String> npm : npmCandidates) {
                try {
                    // npm install
                    pb.command(join(npm, List.of("install", "--prefer-offline", "--no-audit", "--no-fund")));
                    int installCode = pb.start().waitFor();
                    if (installCode != 0) {
                        continue;
                    }
                    // npm run build
                    pb.command(join(npm, List.of("run", "build")));
                    int buildCode = pb.start().waitFor();
                    if (buildCode == 0) {
                        npmOk = true;
                        break;
                    }
                } catch (IOException e) {
                    // 当前候选无法启动（例如 PATH 中无 npm.cmd），尝试下一组命令
                }
            }
            if (!npmOk) {
                System.err.println("[Quant] npm 不可用或构建失败（将使用已有前端资源）。请安装 Node.js 并确保 npm 在 PATH 中，或设置环境变量 NODE_HOME；也可在 VM 选项加 -Dquant.skipFrontendBuild=true 并在 modules/web 下手动执行 npm run build。");
                return;
            }
            // 复制 dist 到 target/classes/static
            Path distDir = frontendDir.resolve("dist");
            if (!Files.isDirectory(distDir)) {
                System.err.println("[Quant] frontend/dist 不存在");
                return;
            }
            Path staticDir = backendDir.resolve("target").resolve("classes").resolve("static");
            if (Files.exists(staticDir)) {
                Files.walk(staticDir).sorted((a, b) -> -a.compareTo(b)).forEach(p -> { try { Files.delete(p); } catch (Exception ignored) {} });
            }
            Files.createDirectories(staticDir);
            copyDir(distDir, staticDir);
            System.out.println("[Quant] 前端构建完成");
        } catch (Exception e) {
            System.err.println("[Quant] 前端构建异常（将使用已有资源）: " + e.getMessage());
        }
    }

    private static void copyDir(Path src, Path dest) throws Exception {
        Files.walk(src).forEach(p -> {
            try {
                Path target = dest.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static List<List<String>> npmCommands() {
        List<List<String>> out = new ArrayList<>();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (isWindows) {
            String nodeHome = System.getenv("NODE_HOME");
            if (nodeHome != null && !nodeHome.isBlank()) {
                Path npmFromHome = Path.of(nodeHome.trim(), "npm.cmd");
                if (Files.isRegularFile(npmFromHome)) {
                    out.add(List.of(npmFromHome.toString()));
                }
            }
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                Path npmLocal = Path.of(localAppData, "Programs", "nodejs", "npm.cmd");
                if (Files.isRegularFile(npmLocal)) {
                    out.add(List.of(npmLocal.toString()));
                }
            }
            // cmd 会加载用户 PATH，部分环境下比直接 exec npm.cmd 更易找到 Node
            out.add(List.of("cmd", "/c", "npm"));
            out.add(List.of("cmd", "/c", "npm.cmd"));
            out.add(List.of("npm.cmd"));
            out.add(List.of("C:\\Program Files\\nodejs\\npm.cmd"));
            out.add(List.of("C:\\Program Files (x86)\\nodejs\\npm.cmd"));
        }
        out.add(List.of("npm"));
        return out;
    }

    private static List<String> join(List<String> prefix, List<String> suffix) {
        List<String> out = new ArrayList<>(prefix.size() + suffix.size());
        out.addAll(prefix);
        out.addAll(suffix);
        return out;
    }
}
