package com.topo.service.impl;

import com.topo.mapper.TopologyMapper;
import com.topo.model.entity.Topology;
import com.topo.service.TopologyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 拓扑服务实现
 *
 * 拓扑的核心数据是 topologyJson 字段（MySQL JSON 类型），
 * 里面存设备列表 + 连线列表的完整结构。
 *
 * 每个接口都校验 userId，保证用户只能操作自己的拓扑。
 */
@Service
@RequiredArgsConstructor
public class TopologyServiceImpl implements TopologyService {

    private final TopologyMapper topologyMapper;

    @Override
    public Topology create(Long userId, String name, String topologyJson, String sourceType) {
        Topology topo = new Topology();
        topo.setUserId(userId);
        topo.setName(name);
        topo.setTopologyJson(topologyJson);
        topo.setSourceType(sourceType); // ensp_topo_file / screenshot / manual
        topologyMapper.insert(topo);
        return topo;
    }

    @Override
    public Topology update(Long id, Long userId, String name, String topologyJson) {
        Topology topo = topologyMapper.selectById(id);
        // 校验：拓扑存在 + 归属当前用户
        if (topo == null || !topo.getUserId().equals(userId)) {
            throw new RuntimeException("拓扑不存在或无权限");
        }
        topo.setName(name);
        topo.setTopologyJson(topologyJson);
        topologyMapper.updateById(topo);
        return topo;
    }

    @Override
    public void delete(Long id, Long userId) {
        Topology topo = topologyMapper.selectById(id);
        if (topo == null || !topo.getUserId().equals(userId)) {
            throw new RuntimeException("拓扑不存在或无权限");
        }
        // MyBatis-Plus 逻辑删除：实际执行 UPDATE SET deleted=1
        topologyMapper.deleteById(id);
    }

    @Override
    public Topology getById(Long id, Long userId) {
        Topology topo = topologyMapper.selectById(id);
        if (topo == null || !topo.getUserId().equals(userId)) {
            throw new RuntimeException("拓扑不存在或无权限");
        }
        return topo;
    }

    @Override
    public List<Topology> listByUser(Long userId) {
        return topologyMapper.selectByUserId(userId);
    }
}
