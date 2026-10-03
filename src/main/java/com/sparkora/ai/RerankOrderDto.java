package com.sparkora.ai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

import java.util.List;

/**
 * LLM 重排响应 DTO（10-03-a-rerank，design §3.1）：schema 由本类型单一派生，
 * 模型只需回吐「候选序号的相关性降序」；顺序的合法性/完备性由
 * {@code LlmReranker.applyOrder} 做后验校验并补齐（越界/重复丢弃、缺项按原序补尾）。
 *
 * <p>序号为 0 起（对应输入候选列表下标）；重排**不改分数**，仅改顺序。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RerankOrderDto {

    @JsonPropertyDescription("候选序号(0 起,对应输入列表)按相关性从高到低的排列;须包含全部序号且不重复")
    private List<Integer> order;
}
