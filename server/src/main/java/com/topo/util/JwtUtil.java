package com.topo.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

/**
 * JWT 工具类
 *
 * 负责 Token 的生成、解析、过期校验。
 * 密钥和过期时间从 application.yml 读取。
 *
 * Token 结构：
 *   sub:  用户 ID
 *   email: 用户邮箱
 *   iat:  签发时间
 *   exp:  过期时间（默认 6 小时）
 */
@Component
public class JwtUtil {

    /** HMAC-SHA256 密钥，从 base64 编码的配置值解码 */
    private final SecretKey key;

    /** Token 有效期，毫秒 */
    private final long expiration;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration}") long expiration) {
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalArgumentException("jwt.secret 未配置或过短，至少 32 位，否则签名可被暴力破解");
        }
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expiration = expiration;
    }

    /**
     * 签发 Token
     * @param userId 用户 ID
     * @param email  用户邮箱
     * @return JWT 字符串
     */
    public String generateToken(Long userId, String email) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + expiration);
        return Jwts.builder()
                .subject(String.valueOf(userId))  // sub 存用户 ID
                .claim("email", email)             // 自定义字段存邮箱
                .issuedAt(now)
                .expiration(exp)
                .signWith(key)
                .compact();
    }

    /**
     * 从 Token 中提取用户 ID
     */
    public Long extractUserId(String token) {
        Claims claims = parseToken(token);
        return Long.parseLong(claims.getSubject());
    }

    /**
     * 获取 Token 剩余有效时间（毫秒）
     * 用于登出时设置黑名单 TTL
     */
    public long getRemainingTime(String token) {
        Claims claims = parseToken(token);
        return claims.getExpiration().getTime() - System.currentTimeMillis();
    }

    /**
     * 判断 Token 是否已过期
     * 解析失败也视为过期（签名不对、格式错误等）
     */
    public boolean isExpired(String token) {
        try {
            return parseToken(token).getExpiration().before(new Date());
        } catch (Exception e) {
            return true;
        }
    }

    public long getExpiration() {
        return expiration;
    }

    /**
     * 解析并校验 Token，返回 Claims
     */
    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
