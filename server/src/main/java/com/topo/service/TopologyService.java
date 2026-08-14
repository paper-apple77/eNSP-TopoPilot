package com.topo.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.topo.mapper.TopologyMapper;
import com.topo.model.dto.TopologySaveRequest;
import com.topo.model.entity.Topology;
import com.topo.model.vo.TopologyVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 拓扑持久化服务
 *
 * 画布拓扑（设备+连线）保存到 MySQL tb_topology：
 * - 每用户多拓扑，按更新时间倒序
 * - 所有操作校验归属，防止越权访问他人拓扑
 * - 删除走 @TableLogic 逻辑删除
 */
@Service
@RequiredArgsConstructor
public class TopologyService {

    private final TopologyMapper topologyMapper;
    private final ObjectMapper objectMapper;

    /** 我的拓扑列表（不含完整 JSON，只给概览字段） */
    public List<TopologyVO> list(Long userId) {
        LambdaQueryWrapper<Topology> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Topology::getUserId, userId)
               .orderByDesc(Topology::getUpdatedAt);
        List<TopologyVO> result = new ArrayList<>();
        for (Topology t : topologyMapper.selectList(wrapper)) {
            TopologyVO vo = new TopologyVO();
            vo.setId(t.getId());
            vo.setName(t.getName());
            vo.setSourceType(t.getSourceType());
            vo.setUpdatedAt(t.getUpdatedAt());
            vo.setDeviceCount(countDevices(t.getTopologyJson()));
            result.add(vo);
        }
        return result;
    }

    /** 拓扑详情（含完整 JSON，加载画布用） */
    public TopologyVO get(Long userId, Long id) {
        Topology t = requireOwned(userId, id);
        TopologyVO vo = new TopologyVO();
        vo.setId(t.getId());
        vo.setName(t.getName());
        vo.setSourceType(t.getSourceType());
        vo.setUpdatedAt(t.getUpdatedAt());
        vo.setDeviceCount(countDevices(t.getTopologyJson()));
        vo.setTopologyJson(t.getTopologyJson());
        return vo;
    }

    /** 保存：id 为空新建，否则覆盖本人已有拓扑 */
    public Long save(Long userId, TopologySaveRequest req) {
        String source = req.getSourceType() != null && !req.getSourceType().isBlank()
            ? req.getSourceType() : "manual";

        if (req.getId() == null) {
            Topology t = new Topology();
            t.setUserId(userId);
            t.setName(req.getName());
            t.setTopologyJson(req.getTopologyJson());
            t.setSourceType(source);
            topologyMapper.insert(t);
            return t.getId();
        }

        Topology t = requireOwned(userId, req.getId());
        t.setName(req.getName());
        t.setTopologyJson(req.getTopologyJson());
        t.setSourceType(source);
        topologyMapper.updateById(t);
        return t.getId();
    }

    /** 删除（逻辑删除，仅限本人） */
    public void delete(Long userId, Long id) {
        requireOwned(userId, id);
        topologyMapper.deleteById(id);
    }

    /** 查本人拓扑，不存在/非本人统一抛"不存在"（不泄露他人拓扑存在性） */
    private Topology requireOwned(Long userId, Long id) {
        LambdaQueryWrapper<Topology> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Topology::getId, id).eq(Topology::getUserId, userId);
        Topology t = topologyMapper.selectOne(wrapper);
        if (t == null) throw new RuntimeException("拓扑不存在或无权访问");
        return t;
    }

    /** 从拓扑 JSON 数设备数量（解析失败返回 0） */
    private int countDevices(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode devices = node.get("devices");
            return devices != null ? devices.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
