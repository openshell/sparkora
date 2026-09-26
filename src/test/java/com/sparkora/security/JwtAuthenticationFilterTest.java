package com.sparkora.security;

import com.sparkora.config.JwtProperties;
import com.sparkora.domain.entity.UserEntity;
import com.sparkora.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JwtAuthenticationFilter 查库校验单测（09-27-p0-hardening R3；Mockito 不连库）。
 *
 * 覆盖：enabled 用户放行且 role 以库为准 / 禁用用户拒绝 / 用户不存在拒绝 / 查库异常 fail-closed /
 * 本地缓存 60s TTL 内不重复查库。
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private UserMapper userMapper;
    private JwtUtil jwtUtil;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setExpireMinutes(1440);
        jwtUtil = new JwtUtil(props);
        userMapper = mock(UserMapper.class);
        filter = new JwtAuthenticationFilter(jwtUtil, userMapper);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest request(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    private static UserEntity user(String username, String role, boolean enabled) {
        UserEntity u = new UserEntity();
        u.setId(7L);
        u.setUsername(username);
        u.setRole(role);
        u.setEnabled(enabled);
        return u;
    }

    @Test
    void enabled用户放行且role以库为准() throws Exception {
        // token 内 role=VIEWER,库中已改为 EDITOR → 鉴权用库值
        String token = jwtUtil.generate(7L, "alice", "VIEWER");
        when(userMapper.selectById(7L)).thenReturn(user("alice", "EDITOR", true));

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("EDITOR", ((CurrentUser) auth.getPrincipal()).getRole(), "role 取库中当前值,不信任 token");
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_EDITOR".equals(a.getAuthority())), "权限走库中 role");
    }

    @Test
    void 禁用用户不注入认证() throws Exception {
        String token = jwtUtil.generate(7L, "alice", "ADMIN");
        when(userMapper.selectById(7L)).thenReturn(user("alice", "ADMIN", false));

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication(), "enabled=false 视为未认证");
    }

    @Test
    void 用户不存在不注入认证() throws Exception {
        String token = jwtUtil.generate(7L, "ghost", "ADMIN");
        when(userMapper.selectById(7L)).thenReturn(null);

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication(), "用户不存在视为未认证");
    }

    @Test
    void 查库异常failClosed不注入认证() throws Exception {
        String token = jwtUtil.generate(7L, "alice", "ADMIN");
        when(userMapper.selectById(7L)).thenThrow(new RuntimeException("DB 抖动"));

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication(), "查库失败 fail-closed");
    }

    @Test
    void 缓存TTL内不重复查库() throws Exception {
        String token = jwtUtil.generate(7L, "alice", "ADMIN");
        when(userMapper.selectById(7L)).thenReturn(user("alice", "ADMIN", true));

        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());
        SecurityContextHolder.clearContext();
        filter.doFilter(request(token), new MockHttpServletResponse(), new MockFilterChain());

        verify(userMapper, times(1)).selectById(anyLong());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated(), "第二次走缓存仍放行");
    }
}
