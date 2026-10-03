package com.sparkora.car.service;

import java.util.List;

/**
 * 检索重排抽象（10-03-a-rerank，design §3.1）。
 *
 * <p>在向量召回候选合并之后、锚点加权/配额之前插入一个**可选**重排阶段：把候选按相关性重排，
 * **只改顺序、不改分数**，从而不影响既有分数门槛（min/reject）与四态判定。
 *
 * <p>实现必须是「best-effort」：任何失败（异常/超时/返回不可解析/空 order）都应返回**原序**
 * （identity），绝不抛出、绝不阻断生成。
 */
public interface Reranker {

    /**
     * 对候选重排。
     *
     * @param query     查询文本（主查询）
     * @param candidates 候选命中（合并去重后）
     * @param keepTopN  参与重排的候选数上限（按**原始分数降序**取前 N；其余保持原序追加），控制 token 预算
     * @return 与 {@code candidates} 元素一一对应、仅顺序可能变化的列表；失败时原序返回
     */
    List<CarRagService.UnifiedHit> rerank(String query, List<CarRagService.UnifiedHit> candidates, int keepTopN);
}
