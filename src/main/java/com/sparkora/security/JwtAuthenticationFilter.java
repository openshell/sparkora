package com.sparkora.security;

import com.sparkora.domain.entity.UserEntity;
import com.sparkora.mapper.UserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 Authorization: Bearer &lt;token&gt; 解析 JWT，查库校验后注入 SecurityContext。
 *
 * <p>token 只证明「签发时」的身份；用户可能已被禁用或改角色。故解析成功后必须查库确认：
 * 用户存在且 {@code enabled=true} 才注入；role 以库为准（token 内 role 仅作参考，不参与鉴权）。
 * 查库失败（DB 抖动）fail-closed——拒绝认证而非信任陈旧 token（内部系统宁可 401 也不放行旧角色；
 * 登录接口不经过本 filter，仍可登录）。
 *
 * <p>缓存：{@link ConcurrentHashMap} userId → (enabled/username/role/查询时间戳)，TTL 60s，
 * 避免每请求查库；含负缓存（用户不存在也缓存 60s，防无效 token 打库）。代价是禁用/改角色最多 60s 生效。
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 本地缓存 TTL：禁用/角色变更最迟 60s 生效（内部系统折中，无外部缓存依赖）。 */
    private static final long CACHE_TTL_MS = 60_000L;

    private final JwtUtil jwtUtil;
    private final UserMapper userMapper;

    private final ConcurrentHashMap<Long, CachedUser> cache = new ConcurrentHashMap<>();

    public JwtAuthenticationFilter(JwtUtil jwtUtil, UserMapper userMapper) {
        this.jwtUtil = jwtUtil;
        this.userMapper = userMapper;
    }

    /** 用户校验快照；用户不存在时以 enabled=false 负缓存。 */
    private record CachedUser(boolean enabled, String username, String role, long fetchedAt) {}

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtUtil.parse(token);
                Long userId = Long.valueOf(claims.getSubject());
                String tokenUsername = claims.get("username", String.class);
                CachedUser cu = loadUser(userId);
                // 查库失败 / 用户不存在 / 已禁用 / 无有效角色 → 视为未认证（不注入，走 401）
                if (cu != null && cu.enabled() && cu.role() != null && !cu.role().isBlank()) {
                    if (tokenUsername != null && !tokenUsername.equals(cu.username())) {
                        // 改名后旧 token：仅告警不阻断（鉴权只认库中 role，username 不参与鉴权）
                        log.warn("JWT username 与库中不一致 userId={} token={} db={}", userId, tokenUsername, cu.username());
                    }
                    CurrentUser principal = new CurrentUser(userId, cu.username(), cu.role());
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + cu.role().toUpperCase())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) {
                // token 无效或异常，保持未认证
            }
        }
        chain.doFilter(req, resp);
    }

    /**
     * 查缓存或查库获取用户校验快照。
     * @return null 表示查库失败（fail-closed，调用方不得注入认证）；否则返回快照（含负缓存）
     */
    private CachedUser loadUser(Long userId) {
        long now = System.currentTimeMillis();
        CachedUser cached = cache.get(userId);
        if (cached != null && now - cached.fetchedAt() < CACHE_TTL_MS) return cached;
        try {
            UserEntity u = userMapper.selectById(userId);
            CachedUser fresh = u == null
                    ? new CachedUser(false, null, null, now)
                    : new CachedUser(Boolean.TRUE.equals(u.getEnabled()), u.getUsername(), u.getRole(), now);
            cache.put(userId, fresh);
            return fresh;
        } catch (RuntimeException ex) {
            // fail-closed：不放行陈旧角色；仅告警不抛出（避免 500，语义收敛为 401）
            log.warn("JWT 用户查库失败，拒绝认证 userId={}: {}", userId, ex.getMessage());
            return null;
        }
    }
}
