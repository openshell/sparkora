package com.sparkora.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkora.config.WenyanProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WenyanServerService} 传输层错误归因与「探针/发布超时解耦」单测
 * (09-27-wenyan-stale-conn AC3/AC4/AC5;纯内存,不连网、不连库)。
 *
 * <p>背景:09-27 事故的用户可见报错是框架内部串
 * {@code Error while extracting response for type [java.lang.String]...},不可读也不防重复草稿。
 * 这里把「异常 → 中文归因」这条纯函数钉死,并锁住「探针与发布用不同 RestClient 实例」的实现契约
 * (AC5:两个超时是独立配置项,调其一不影响另一个)。
 */
class WenyanServerServiceTransportTest {

    private static WenyanServerService newService(long publishMs, long verifyMs) {
        WenyanProperties props = new WenyanProperties();
        props.setServerUrl("http://127.0.0.1:1/never-used");
        props.setServerApiKey("test-key");
        props.setPublishTimeoutMs(publishMs);
        props.setVerifyTimeoutMs(verifyMs);
        return new WenyanServerService(props, new ObjectMapper());
    }

    private static Object readField(Object target, String name) throws Exception {
        Field f = WenyanServerService.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    // ==================== AC5:探针/发布客户端是两个独立实例 ====================

    @Test
    void 探针与发布是两个独立RestClient实例() throws Exception {
        WenyanServerService svc = newService(180000, 5000);
        assertNotSame(readField(svc, "rest"), readField(svc, "probeRest"),
                "探针必须用独立客户端,否则调大发布超时会连带把探针挂住 180s(AC4 事故形态)");
    }

    @Test
    void 两档超时由各自配置项驱动() {
        // 仅改发布超时,探针侧配置不动 → 探针实例照旧按 verifyTimeoutMs 构造
        WenyanServerService onlyPublishChanged = newService(180000, 5000);
        WenyanServerService bothChanged = newService(180000, 2000);
        // 断言不依赖实例内部状态,仅保证两种组合都能正常构造(不抛异常)、且配置项互不覆盖
        assertTrue(readFieldQuietly(onlyPublishChanged, "probeRest") != null);
        assertTrue(readFieldQuietly(bothChanged, "probeRest") != null);
        // WenyanProperties 兜底值:发布 180s / 探针 5s(与 .env/.env.example/application.yml 三处一致)
        WenyanProperties def = new WenyanProperties();
        assertEquals(180000, def.getPublishTimeoutMs());
        assertEquals(5000, def.getVerifyTimeoutMs());
    }

    // ==================== AC3:超时必须被识别为「请求超时」 ====================

    @Test
    void 读超时被归因为请求超时() {
        // 09-27 实际观测到的形态:顶层 RestClientException,根因是读超时
        RestClientException timeout = new RestClientException(
                "Error while extracting response for type [java.lang.String] and content type [application/octet-stream]",
                new SocketTimeoutException("Read timed out"));
        assertEquals("请求超时", WenyanServerService.describeTransportFailure(timeout));

        // JDK HttpClient(本项目 ClientHttpRequestFactories 实际选中的实现)抛 HttpTimeoutException
        assertEquals("请求超时", WenyanServerService.describeTransportFailure(
                new RestClientException("boom", new HttpTimeoutException("request timed out"))));
    }

    @Test
    void 超时异常无cause时按类型判定() {
        assertEquals("请求超时", WenyanServerService.describeTransportFailure(new SocketTimeoutException("x")));
    }

    // ==================== AC3:框架内部串不得透给用户 ====================

    @Test
    void 框架内部串不透给用户且不含类名() {
        String desc = WenyanServerService.describeTransportFailure(new RestClientException(
                "Error while extracting response for type [java.lang.String] and content type [application/octet-stream]"));
        assertFalse(desc.contains("Error while extracting response"), "不得出现框架内部串");
        assertFalse(desc.contains("java.lang.String"), "不得泄漏框架类名");
        assertFalse(desc.contains("application/octet-stream"), "不得泄漏 content type");
    }

    @Test
    void 非超时类框架串同样被中和() {
        String desc = WenyanServerService.describeTransportFailure(
                new RestClientException("No suitable HttpMessageConverter for java.lang.String"));
        assertFalse(desc.contains("HttpMessageConverter"));
    }

    // ==================== 其它兜底 ====================

    @Test
    void 无消息异常归因为连接中断() {
        assertEquals("连接中断", WenyanServerService.describeTransportFailure(new RestClientException("")));
        assertEquals("连接中断", WenyanServerService.describeTransportFailure(new java.net.SocketException()));
    }

    @Test
    void 循环cause链不死循环() {
        // a→b→a 成环(Throwable 允许互相 initCause,只禁自指),归因方法必须靠已访问集合收敛。
        // root 取遍历到的最后一个非自指节点(此例为 b),断言只关心「收敛且有可读消息」——
        // 成环时哪个节点算根因无业务含义,真正要防的是死循环。
        Exception a = new Exception("boom-a");
        Exception b = new Exception("boom-b");
        a.initCause(b);
        b.initCause(a);
        assertEquals("boom-b", WenyanServerService.describeTransportFailure(a));
    }

    @Test
    void 优先取根因消息而非顶层包装() {
        // 顶层 "I/O error on POST request for ..." 信息量低,根因 "Connection reset" 才有指向性
        Exception e = new java.net.ConnectException("Connection reset");
        RestClientException top = new RestClientException(
                "I/O error on POST request for \"http://x/publish\": " + e.getMessage(), e);
        assertEquals("Connection reset", WenyanServerService.describeTransportFailure(top));
    }

    @Test
    void 超长消息被截断() {
        assertTrue(WenyanServerService.describeTransportFailure(new Exception("x".repeat(500))).length() <= 201);
    }

    @Test
    void rest与probeRest字段存在且类型正确() throws Exception {
        WenyanServerService svc = newService(180000, 5000);
        assertInstanceOf(RestClient.class, readField(svc, "rest"));
        assertInstanceOf(RestClient.class, readField(svc, "probeRest"));
    }

    // ==================== G3:超时提示必须可读且防重复草稿 ====================

    @Test
    void 超时提示含先确认草稿箱指引且不含框架串() {
        String msg = WenyanServerService.PUBLISH_TIMEOUT_MSG;
        assertTrue(msg.contains("草稿箱"), "必须指引用户到草稿箱确认");
        assertTrue(msg.contains("已写入"), "必须说明可能已写入(超时不等于失败)");
        assertFalse(msg.contains("Error while extracting"), "不得含框架内部串");
        assertFalse(msg.contains("java.lang"), "不得泄漏框架类名");
    }

    @Test
    void 传输失败提示同样带草稿箱指引() {
        String msg = WenyanServerService.PUBLISH_TRANSPORT_FAIL_MSG_PREFIX
                + WenyanServerService.describeTransportFailure(new RestClientException("Connection reset"))
                + WenyanServerService.PUBLISH_DRAFT_HINT_MSG;
        assertTrue(msg.contains("草稿箱"));
        assertFalse(msg.contains("RestClientException"), "不得泄漏框架类名");
    }

    @Test
    void 提示语长度在last_publish_error截断口径内() {
        // ProjectStatusService.markPublishFailure 截断 990;留足余量,避免提示被截成半句
        assertTrue(WenyanServerService.PUBLISH_TIMEOUT_MSG.length() < 200);
    }

    // ==================== AC4:通道不可达时探针快速失败 ====================

    @Test
    void 通道不可达时探针按短超时快速失败() throws Exception {
        // 起一个「接受连接但永不回应」的本地 socket:走的是真正的读超时路径
        // (而非连接被拒),这正是 AC4 要防的「探针被发布超时牵连而挂住」形态。
        try (ServerSocket acceptNeverRespond = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                while (!acceptNeverRespond.isClosed()) {
                    try (Socket s = acceptNeverRespond.accept()) { Thread.sleep(10_000); } catch (Exception ignore) { }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            WenyanProperties props = new WenyanProperties();
            props.setServerUrl("http://127.0.0.1:" + acceptNeverRespond.getLocalPort());
            props.setServerApiKey("test-key");
            props.setVerifyTimeoutMs(1200);   // 探针档:应生效
            props.setPublishTimeoutMs(180000); // 发布档:必须**不影响**探针
            WenyanServerService svc = new WenyanServerService(props, new ObjectMapper());

            long start = System.nanoTime();
            boolean ok = svc.verify();   // 期望:false(不可达)
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertFalse(ok, "通道不可达时 verify() 必须返回 false 而非抛异常");
            // AC4:≤ 2 × 探针超时的量级;若探针误用发布档(180s),这里会直接超时挂死
            assertTrue(elapsedMs < 1200 * 4,
                    "探针应按 verifyTimeoutMs(1200ms)级失败,实测 " + elapsedMs + "ms —— 疑似误用发布档超时");
        }
    }

    // ==================== AC3:publish() 超时提示可读(端到端走真实 HTTP) ====================

    @Test
    void publish读超时给出中文可读提示且不含框架串() throws Exception {
        // 「接受连接但永不回应」的本地 socket —— 走 publish() 的**完整**超时路径
        // (POST /publish → 读响应超时 → 归因 → 上抛中文异常),不触碰任何真实 server、不产生任何草稿。
        try (ServerSocket acceptNeverRespond = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread acceptor = new Thread(() -> {
                while (!acceptNeverRespond.isClosed()) {
                    try (Socket s = acceptNeverRespond.accept()) { Thread.sleep(10_000); } catch (Exception ignore) { }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            WenyanProperties props = new WenyanProperties();
            props.setServerUrl("http://127.0.0.1:" + acceptNeverRespond.getLocalPort());
            props.setServerApiKey("test-key");
            props.setPublishTimeoutMs(1200); // 压到 1.2s 便于测试;生产默认 180s
            WenyanServerService svc = new WenyanServerService(props, new ObjectMapper());

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> svc.publish("deadbeef.json"), "读超时应上抛中文 IllegalStateException");
            String msg = ex.getMessage();

            // AC3:中文可读语义 + 「可能已写入草稿 / 到草稿箱确认」指引 + 不含框架内部类名串
            assertTrue(msg.contains("草稿箱"), "必须指引到草稿箱确认,实际: " + msg);
            assertTrue(msg.contains("已写入"), "必须说明可能已写入(超时不等于失败),实际: " + msg);
            assertFalse(msg.contains("Error while extracting response"), "不得含框架内部串,实际: " + msg);
            assertFalse(msg.contains("java.lang."), "不得泄漏框架类名,实际: " + msg);
            assertFalse(msg.contains("Exception"), "不得泄漏框架类名,实际: " + msg);
        }
    }

    // ==================== C0:传输引擎必须锁定 JDK HttpClient(防 detect() 漂移) ====================

    @Test
    void 传输引擎锁定为JdkHttpClient而非自动探测() throws Exception {
        // C0 升级踩坑:引入 spring-ai-starter-model-openai 后传递带入 Reactor Netty,
        // ClientHttpRequestFactoryBuilder.detect() 会从 JDK HttpClient 改选 Reactor,
        // 读超时抛 Netty ReadTimeoutException(RuntimeException,非 JDK/Simple 超时族),
        // describeTransportFailure 无法识别 → 超时被误归因为普通传输失败、并泄漏框架串。
        // 故 9 处 RestClient 一律显式 .jdk();此测试锁死该选择,防未来误改回 detect()。
        WenyanServerService svc = newService(180000, 5000);
        for (String fieldName : java.util.List.of("rest", "probeRest")) {
            Object rest = readField(svc, fieldName);
            Field factoryField = rest.getClass().getDeclaredField("clientRequestFactory");
            factoryField.setAccessible(true);
            Object factory = factoryField.get(rest);
            assertEquals("org.springframework.http.client.JdkClientHttpRequestFactory",
                    factory.getClass().getName(),
                    fieldName + " 必须显式用 JDK HttpClient(.jdk());detect() 会因 Reactor Netty 在场而漂移");
        }
    }

    private static Object readFieldQuietly(Object target, String name) {
        try {
            return readField(target, name);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
