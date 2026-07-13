package com.topo.service;

import com.topo.model.dto.LoginRequest;
import com.topo.model.dto.RegisterRequest;

/**
 * 用户服务接口
 */
public interface UserService {
    /** 注册，返回 JWT token */
    String register(RegisterRequest request);

    /** 登录，返回 JWT token */
    String login(LoginRequest request);

    /** 登出，token 加 Redis 黑名单 */
    void logout(String token);
}
