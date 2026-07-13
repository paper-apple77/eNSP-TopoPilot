package com.topo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.topo.mapper.UserMapper;
import com.topo.model.dto.LoginRequest;
import com.topo.model.dto.RegisterRequest;
import com.topo.model.entity.User;
import com.topo.service.UserService;
import com.topo.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 用户服务实现
 *
 * 注册/登录/登出 + JWT 双因子认证：
 *   - JWT 本身有过期时间（exp）
 *   - Redis 存一份 token → 用户 ID 映射，多一层服务端控制
 *   - 登出时 token 加入 Redis 黑名单，TTL = 剩余有效期
 *
 * 密码使用 MD5 加密（Spring 自带的 DigestUtils）
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 注册：校验邮箱唯一 → MD5 加密密码 → 入库 → 签发 token
     */
    @Override
    public String register(RegisterRequest request) {
        // 邮箱唯一性校验
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getEmail, request.getEmail());
        if (userMapper.selectCount(wrapper) > 0) {
            throw new RuntimeException("该邮箱已注册");
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPassword(md5(request.getPassword()));
        userMapper.insert(user);

        // 注册成功直接签发 token（无需再登录）
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        redisTemplate.opsForValue().set(
                "token:" + user.getId(),
                token,
                jwtUtil.getExpiration(),
                TimeUnit.MILLISECONDS
        );
        return token;
    }

    /**
     * 登录：查邮箱 → 验密码 MD5 → 签发 token
     */
    @Override
    public String login(LoginRequest request) {
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getEmail, request.getEmail());
        User user = userMapper.selectOne(wrapper);

        if (user == null || !user.getPassword().equals(md5(request.getPassword()))) {
            throw new RuntimeException("邮箱或密码错误");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        redisTemplate.opsForValue().set(
                "token:" + user.getId(),
                token,
                jwtUtil.getExpiration(),
                TimeUnit.MILLISECONDS
        );
        return token;
    }

    /**
     * 登出：
     * 1. 把当前 token 加入 Redis 黑名单，TTL = token 剩余有效时间
     *    （过了 TTL 自动清理，不用永久存）
     * 2. 删除用户 token 映射
     */
    @Override
    public void logout(String token) {
        Long userId = jwtUtil.extractUserId(token);
        long remaining = jwtUtil.getRemainingTime(token);
        if (remaining > 0) {
            redisTemplate.opsForValue().set(
                    "blacklist:" + token, "1",
                    remaining, TimeUnit.MILLISECONDS
            );
            redisTemplate.delete("token:" + userId);
        }
    }

    /**
     * MD5 加密
     * 简易实现，生产环境应使用 BCrypt
     */
    private String md5(String raw) {
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }
}
