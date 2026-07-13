package com.topo.controller;

import com.topo.model.entity.Topology;
import com.topo.model.vo.TopologyJson;
import com.topo.result.Result;
import com.topo.service.TopoXmlParser;
import com.topo.service.TopologyService;
import com.topo.service.ZipProjectParser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 拓扑接口
 *
 * CRUD + .topo 文件导入。
 * userId 由 JwtInterceptor 注入到 request.setAttribute。
 *
 * topologyJson 是拓扑的核心数据，格式：
 *   { devices: [{name, model, type, interfaces, x, y}], connections: [{fromDevice, fromInterface, toDevice, toInterface}] }
 */
@RestController
@RequestMapping("/api/topology")
public class TopologyController {

    private final TopologyService topologyService;
    private final TopoXmlParser topoXmlParser;
    private final ZipProjectParser zipProjectParser;
    private final ObjectMapper objectMapper;

    public TopologyController(TopologyService topologyService,
                               TopoXmlParser topoXmlParser,
                               ZipProjectParser zipProjectParser,
                               ObjectMapper objectMapper) {
        this.topologyService = topologyService;
        this.topoXmlParser = topoXmlParser;
        this.zipProjectParser = zipProjectParser;
        this.objectMapper = objectMapper;
    }

    /** 手动创建拓扑（空画布） */
    @PostMapping
    public Result<Topology> create(@RequestBody Map<String, String> body,
                                    HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Topology topo = topologyService.create(
                userId,
                body.get("name"),
                body.getOrDefault("topologyJson", "{}"),
                body.getOrDefault("sourceType", "manual")
        );
        return Result.success(topo);
    }

    /** 更新拓扑名称或画布 JSON */
    @PutMapping("/{id}")
    public Result<Topology> update(@PathVariable Long id,
                                    @RequestBody Map<String, String> body,
                                    HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Topology topo = topologyService.update(
                id, userId, body.get("name"), body.get("topologyJson"));
        return Result.success(topo);
    }

    /** 逻辑删除 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        topologyService.delete(id, userId);
        return Result.success("已删除", null);
    }

    /** 查看拓扑详情（含完整 JSON） */
    @GetMapping("/{id}")
    public Result<Topology> getById(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        Topology topo = topologyService.getById(id, userId);
        return Result.success(topo);
    }

    /** 我的拓扑列表，按更新时间倒序 */
    @GetMapping("/list")
    public Result<List<Topology>> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        List<Topology> list = topologyService.listByUser(userId);
        return Result.success(list);
    }

    /**
     * 上传 .topo 文件，自动解析并创建拓扑
     *
     * 流程：
     *   MultipartFile → TopoXmlParser.parse() → TopologyJson →
     *   序列化 JSON → 存 MySQL → 返回完整的 Topology 对象
     *
     * 拓扑名称默认取上传文件名（去掉 .topo 后缀）
     */
    @PostMapping("/import/topo")
    public Result<Topology> importTopo(@RequestParam("file") MultipartFile file,
                                        HttpServletRequest request) throws Exception {
        Long userId = (Long) request.getAttribute("userId");

        // 解析 .topo 文件的 XML → 设备 + 连线列表
        TopologyJson topoJson = topoXmlParser.parse(file.getBytes());

        // 文件名去掉 .topo 后缀作为拓扑名称
        String name = file.getOriginalFilename();
        if (name != null && name.endsWith(".topo")) {
            name = name.substring(0, name.length() - 5);
        }

        // 对象序列化为 JSON 字符串存库
        Topology topo = topologyService.create(
                userId, name,
                objectMapper.writeValueAsString(topoJson),
                "ensp_topo_file"
        );
        return Result.success(topo);
    }

    /**
     * 上传 eNSP 工程 zip，解析拓扑 + 设备配置
     */
    @PostMapping("/import/zip")
    public Result<Topology> importZip(@RequestParam("file") MultipartFile file,
                                       HttpServletRequest request) throws Exception {
        Long userId = (Long) request.getAttribute("userId");

        ZipProjectParser.ProjectResult result = zipProjectParser.parse(file.getBytes());
        TopologyJson topoJson = result.topology;
        topoJson.setDeviceConfigs(result.deviceConfigs);

        String name = file.getOriginalFilename();
        if (name != null && name.toLowerCase().endsWith(".zip")) {
            name = name.substring(0, name.length() - 4);
        }

        Topology topo = topologyService.create(
                userId, name,
                objectMapper.writeValueAsString(topoJson),
                "ensp_zip"
        );
        return Result.success(topo);
    }
}
