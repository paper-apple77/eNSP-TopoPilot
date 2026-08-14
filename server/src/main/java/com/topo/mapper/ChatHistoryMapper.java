package com.topo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.topo.model.entity.ChatHistory;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatHistoryMapper extends BaseMapper<ChatHistory> {

    /** 查某用户某拓扑某模式的最近 limit 条历史（按时间正序返回，供 AI 上下文使用） */
    @Select("SELECT * FROM tb_chat_history WHERE user_id=#{userId} AND topology_id=#{topologyId} " +
            "AND mode=#{mode} ORDER BY id DESC LIMIT #{limit}")
    List<ChatHistory> findRecent(@Param("userId") Long userId, @Param("topologyId") Long topologyId,
                                 @Param("mode") String mode, @Param("limit") int limit);

    /** 清理超出保留上限的最旧记录（物理删除，防止表无限膨胀） */
    @Delete("DELETE FROM tb_chat_history WHERE user_id=#{userId} AND topology_id=#{topologyId} " +
            "AND mode=#{mode} AND id NOT IN (" +
            "SELECT id FROM (SELECT id FROM tb_chat_history WHERE user_id=#{userId} " +
            "AND topology_id=#{topologyId} AND mode=#{mode} " +
            "ORDER BY id DESC LIMIT #{keep}) tmp)")
    int deleteOldest(@Param("userId") Long userId, @Param("topologyId") Long topologyId,
                     @Param("mode") String mode, @Param("keep") int keep);
}
