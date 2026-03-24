package com.quant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
            Path frontendDir;
            Path backendDir;
            if (Files.isDirectory(cwd.resolve("frontend")) && Files.exists(cwd.resolve("frontend").resolve("package.json"))) {
                frontendDir = cwd.resolve("frontend");
                backendDir = Files.isDirectory(cwd.resolve("backend")) ? cwd.resolve("backend") : cwd;
            } else if (Files.isDirectory(cwd.getParent().resolve("frontend"))) {
                frontendDir = cwd.getParent().resolve("frontend");
                backendDir = cwd;
            } else {
                return;
            }
            if (!Files.exists(frontendDir.resolve("package.json"))) return;
            System.out.println("[Quant] 正在构建前端...");
            ProcessBuilder pb = new ProcessBuilder();
            pb.directory(frontendDir.toFile());
            pb.inheritIO();
            // npm install
            pb.command("npm", "install", "--prefer-offline", "--no-audit", "--no-fund");
            if (pb.start().waitFor() != 0) {
                System.err.println("[Quant] npm install 失败，将使用已有前端资源");
                return;
            }
            // npm run build
            pb.command("npm", "run", "build");
            if (pb.start().waitFor() != 0) {
                System.err.println("[Quant] npm run build 失败，将使用已有前端资源");
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
}
