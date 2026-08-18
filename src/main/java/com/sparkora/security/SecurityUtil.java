package com.sparkora.security;

import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 从 SecurityContext 取当前登录用户。
 */
public class SecurityUtil {

    public static CurrentUser current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CurrentUser cu)) {
            return null;
        }
        return cu;
    }

    public static CurrentUser require() {
        CurrentUser cu = current();
        if (cu == null) {
            throw new IllegalStateException("未登录");
        }
        return cu;
    }
}
