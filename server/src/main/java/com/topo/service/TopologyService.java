package com.topo.service;

import com.topo.model.entity.Topology;
import java.util.List;

/**
 * 拓扑服务接口
 *
 * 所有方法都要求传入 userId 做权限校验，保证用户只能操作自己的拓扑。
 */
public interface TopologyService {
    Topology create(Long userId, String name, String topologyJson, String sourceType);
    Topology update(Long id, Long userId, String name, String topologyJson);
    void delete(Long id, Long userId);
    Topology getById(Long id, Long userId);
    List<Topology> listByUser(Long userId);
}
