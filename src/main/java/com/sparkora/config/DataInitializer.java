package com.sparkora.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sparkora.domain.entity.UserEntity;
import com.sparkora.mapper.UserMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 启动时预置账号：admin(ADMIN)/editor(EDITOR)/viewer(VIEWER)，密码默认 <用户名>123。
 * BCrypt 哈希由 PasswordEncoder 生成；仅当不存在时插入，幂等。
 * 仅用于本地/内网开发验证角色矩阵，正式部署应改密码或关闭。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        seed("admin", "admin123", "默认管理员", "ADMIN");
        seed("editor", "editor123", "示例编辑", "EDITOR");
        seed("viewer", "viewer123", "示例只读", "VIEWER");
    }

    private void seed(String username, String password, String displayName, String role) {
        Long exists = userMapper.selectCount(new QueryWrapper<UserEntity>().eq("username", username));
        if (exists == null || exists == 0) {
            UserEntity u = new UserEntity();
            u.setUsername(username);
            u.setPassword(passwordEncoder.encode(password));
            u.setDisplayName(displayName);
            u.setRole(role);
            u.setEnabled(true);
            userMapper.insert(u);
        }
    }
}
