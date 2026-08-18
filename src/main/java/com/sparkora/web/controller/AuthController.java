package com.sparkora.web.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.common.R;
import com.sparkora.domain.entity.UserEntity;
import com.sparkora.mapper.UserMapper;
import com.sparkora.security.CurrentUser;
import com.sparkora.security.JwtUtil;
import com.sparkora.security.LoginRequest;
import com.sparkora.security.SecurityUtil;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 登录/当前用户/登出。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthController(UserMapper userMapper, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@Valid @RequestBody LoginRequest req) {
        UserEntity user = userMapper.selectOne(
                new QueryWrapper<UserEntity>().eq("username", req.getUsername()));
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())
                || !passwordEncoder.matches(req.getPassword(), user.getPassword())) {
            return R.fail(401, "用户名或密码错误");
        }
        String token = jwtUtil.generate(user.getId(), user.getUsername(), user.getRole());
        return R.ok(Map.of(
                "token", token,
                "userId", user.getId(),
                "username", user.getUsername(),
                "displayName", user.getDisplayName() == null ? "" : user.getDisplayName(),
                "role", user.getRole()
        ));
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        // JWT 无状态：前端丢弃 token 即可
        return R.ok();
    }

    @GetMapping("/me")
    public R<CurrentUser> me() {
        return R.ok(SecurityUtil.require());
    }
}
