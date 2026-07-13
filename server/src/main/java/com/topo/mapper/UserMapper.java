package com.topo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.topo.model.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
