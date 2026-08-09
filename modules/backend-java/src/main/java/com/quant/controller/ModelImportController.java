package com.quant.controller;

import com.quant.dto.ApiResponse;
import com.quant.research.artifact.ModelImportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 模型 artifact 导入审核端点（V2-MP-025）。
 *
 * <ul>
 *   <li>{@code POST /api/research/models/import} — 审核导入（只到 DRAFT，幂等，拒绝路径引用）；</li>
 *   <li>{@code GET  /api/research/models/{modelVersion}} — 元数据视图（不含 artifact 本体）。</li>
 * </ul>
 * 模型注册表列表仍走 {@link ResearchController#models()}。
 */
@RestController
@RequestMapping("/api/research/models")
@RequiredArgsConstructor
public class ModelImportController {

    private final ModelImportService modelImportService;

    /** 导入模型到 DRAFT（待审）。请求体见 ModelImportService#importModel 契约。 */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importModel(@RequestBody Map<String, Object> body) {
        return ApiResponse.success(modelImportService.importModel(body));
    }

    /** 模型元数据（含 lineage / schema_hash / artifact_hash，不含大 artifact）。 */
    @GetMapping("/{modelVersion}")
    public ApiResponse<Map<String, Object>> metadata(@PathVariable String modelVersion) {
        return ApiResponse.success(modelImportService.metadata(modelVersion));
    }
}
