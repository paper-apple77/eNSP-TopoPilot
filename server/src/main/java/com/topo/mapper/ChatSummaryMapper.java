package com.topo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.topo.model.entity.ChatSummary;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ChatSummaryMapper extends BaseMapper<ChatSummary> {
    // 查/写都走 BaseMapper 的 selectOne/insert/updateById，
    // 唯一键 (user_id, topology_id, mode) 保证每用户每拓扑每模式一条摘要
}
