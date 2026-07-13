package com.topo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.topo.model.entity.Topology;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface TopologyMapper extends BaseMapper<Topology> {

    @Select("SELECT * FROM tb_topology WHERE user_id = #{userId} AND deleted = 0 ORDER BY updated_at DESC")
    List<Topology> selectByUserId(Long userId);
}
