package com.sparkora.security;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前登录用户信息（从 JWT 解析）。
 */
@Data
@AllArgsConstructor
public class CurrentUser {
    private Long userId;
    private String username;
    private String role;  // ADMIN / EDITOR / VIEWER

    public boolean hasRole(String role) {
        return role != null && role.equalsIgnoreCase(this.role);
    }

    public boolean isEditorOrAbove() {
        return "ADMIN".equalsIgnoreCase(role) || "EDITOR".equalsIgnoreCase(role);
    }
}
