package com.topo.controller;

import com.topo.model.dto.TopologySaveRequest;
import com.topo.model.vo.TopologyVO;
import com.topo.result.Result;
import com.topo.service.TopologyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 拓扑管理接口
 *
 * 画布拓扑的 MySQL 持久化：保存 / 列表 / 详情 / 删除。
 * userId 由 JwtInterceptor 写入 request attribute，前端不传（防伪造）。
 */
@Tag(name = "拓扑管理", description = "画布拓扑的保存 / 加载 / 删除（MySQL 持久化）")
@RestController
@RequestMapping("/api/topology")
@RequiredArgsConstructor
public class TopologyController {

    private final TopologyService topologyService;

    @Operation(summary = "我的拓扑列表", description = "按更新时间倒序，不含完整 JSON")
    @GetMapping("/list")
    public Result<List<TopologyVO>> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(topologyService.list(userId));
    }

    @Operation(summary = "拓扑详情", description = "返回完整 topologyJson，用于恢复画布")
    @GetMapping("/{id}")
    public Result<TopologyVO> get(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success(topologyService.get(userId, id));
    }

    @Operation(summary = "保存拓扑", description = "id 为空新建，否则覆盖保存本人拓扑")
    @PostMapping("/save")
    public Result<Long> save(@Valid @RequestBody TopologySaveRequest req, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return Result.success("保存成功", topologyService.save(userId, req));
    }

    @Operation(summary = "删除拓扑", description = "逻辑删除，仅限本人")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        topologyService.delete(userId, id);
        return Result.success("删除成功", null);
    }
}
