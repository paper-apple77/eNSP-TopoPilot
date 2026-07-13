package com.topo.controller;

import com.topo.model.dto.LoginRequest;
import com.topo.model.dto.RegisterRequest;
import com.topo.result.Result;
import com.topo.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 用户接口
 *
 * /api/user/register  注册  - 无需登录
 * /api/user/login     登录  - 无需登录
 * /api/user/logout    登出  - 需要登录
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 注册：@Valid 自动校验邮箱格式、密码长度
     * 成功后直接返回 token，前端存 localStorage
     */
    @PostMapping("/register")
    public Result<String> register(@Valid @RequestBody RegisterRequest request) {
        String token = userService.register(request);
        return Result.success("注册成功", token);
    }

    /**
     * 登录：返回 token，前端存 localStorage，后续请求带在 Authorization 头
     */
    @PostMapping("/login")
    public Result<String> login(@Valid @RequestBody LoginRequest request) {
        String token = userService.login(request);
        return Result.success("登录成功", token);
    }

    /**
     * 登出：token 加入 Redis 黑名单
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authHeader) {
        String token = authHeader.substring(7); // 去掉 "Bearer " 前缀
        userService.logout(token);
        return Result.success("已登出", null);
    }
}
