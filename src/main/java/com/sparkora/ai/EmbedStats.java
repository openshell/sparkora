package com.sparkora.ai;

/**
 * 向量化结果（可观测）：成功/失败条目计数，供接口返回与日志输出「部分块未向量化」（09-27 知识域写入侧统一）。
 *
 * <p>CAR / KB / NEWS / IMAGE 四域写入路径共用同一定义（替代原先 KB/IMAGE 各一份的同形 record）。
 */
public record EmbedStats(int total, int success, int failed) {
}
