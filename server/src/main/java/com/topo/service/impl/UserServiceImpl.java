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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * 密码使用 BCrypt 加盐哈希存储。
 * 平滑迁移：老账号（MD5 存储）登录校验通过后自动升级为 BCrypt。
 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    /** 登录限流：同邮箱连续失败 5 次锁 10 分钟 */
    private static final int MAX_LOGIN_FAILS = 5;
    private static final long LOCK_SECONDS = 600;

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * 注册：校验邮箱唯一 → BCrypt 加密密码 → 入库 → 签发 token
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
        user.setPassword(encoder.encode(request.getPassword()));
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
     * 登录：查邮箱 → 验密码（BCrypt，兼容老 MD5）→ 签发 token
     *
     * 防爆破：Redis 计数器，同邮箱连续失败 5 次锁 10 分钟；
     * Redis 不可用时自动降级为不限流（try-catch 吞掉），不阻塞正常登录。
     */
    @Override
    public String login(LoginRequest request) {
        // 限流检查
        if (isLoginLocked(request.getEmail())) {
            throw new RuntimeException("失败次数过多，请10分钟后再试");
        }

        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getEmail, request.getEmail());
        User user = userMapper.selectOne(wrapper);

        if (user == null || !verifyPassword(request.getPassword(), user)) {
            recordLoginFail(request.getEmail());
            throw new RuntimeException("邮箱或密码错误");
        }

        clearLoginFails(request.getEmail());
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        redisTemplate.opsForValue().set(
                "token:" + user.getId(),
                token,
                jwtUtil.getExpiration(),
                TimeUnit.MILLISECONDS
        );
        return token;
    }

    // ===== 登录限流（Redis 计数器，故障自动降级） =====

    private boolean isLoginLocked(String email) {
        try {
            String v = redisTemplate.opsForValue().get("login-fail:" + email);
            return v != null && Integer.parseInt(v) >= MAX_LOGIN_FAILS;
        } catch (Exception e) {
            return false; // Redis 不可用 → 不限流
        }
    }

    private void recordLoginFail(String email) {
        try {
            Long n = redisTemplate.opsForValue().increment("login-fail:" + email);
            // 第一次失败时设置过期窗口，之后窗口内累加
            if (n != null && n == 1) {
                redisTemplate.expire("login-fail:" + email, LOCK_SECONDS, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
            // Redis 不可用 → 限流失效但不影响登录主流程
        }
    }

    private void clearLoginFails(String email) {
        try {
            redisTemplate.delete("login-fail:" + email);
        } catch (Exception ignored) {}
    }

    /**
     * 密码校验（平滑迁移）：
     * 1. 密文以 $2 开头 → BCrypt matches
     * 2. 老 MD5 密文 → MD5 比对，通过后自动升级为 BCrypt 并落库
     */
    private boolean verifyPassword(String raw, User user) {
        String stored = user.getPassword();
        if (stored != null && stored.startsWith("$2")) {
            return encoder.matches(raw, stored);
        }
        if (stored != null && stored.equals(md5(raw))) {
            user.setPassword(encoder.encode(raw));
            userMapper.updateById(user);
            log.info("[Auth] 老账号密码已从 MD5 平滑升级为 BCrypt: " + user.getEmail());
            return true;
        }
        return false;
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

    /** MD5 哈希 — 仅用于老账号密码的兼容比对，新密码一律走 BCrypt */
    private String md5(String raw) {
        return DigestUtils.md5DigestAsHex(raw.getBytes(StandardCharsets.UTF_8));
    }
}
