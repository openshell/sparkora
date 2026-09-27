package com.sparkora.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 向量 mapper SQL 契约单测（09-27 R5/AC9）。
 *
 * 纯反射读取注解 SQL，断言 4 条检索查询均含 {@code embedding_model} 过滤
 * （换模型后旧模型行不再参与检索，不静默混空间），以及写入/对账口径的模型参数。
 * 不需要连库。
 */
class EmbeddingMapperModelFilterTest {

    private static String selectSql(Class<?> mapper, String method, Class<?>... params) throws Exception {
        Method m = mapper.getMethod(method, params);
        Select s = m.getAnnotation(Select.class);
        return String.join(" ", s.value());
    }

    @Test
    void car检索_按模型过滤() throws Exception {
        String sql = selectSql(CarDocEmbeddingMapper.class, "searchTopK",
                Long.class, String.class, int.class, String.class);
        assertTrue(sql.contains("e.embedding_model = #{model}"), sql);
    }

    @Test
    void 统一检索三段_均按模型过滤() throws Exception {
        String sql = selectSql(CarDocEmbeddingMapper.class, "searchTopKUnified",
                String.class, int.class, String.class);
        // 三段（CAR/KB/NEWS）各一个过滤条件
        int count = sql.split("embedding_model = #\\{model\\}", -1).length - 1;
        assertTrue(count == 3, "统一检索 CAR/KB/NEWS 三段均须过滤,实际 " + count + ": " + sql);
    }

    @Test
    void kb检索_按模型过滤() throws Exception {
        String sql = selectSql(KbChunkEmbeddingMapper.class, "searchTopK",
                String.class, int.class, String.class);
        assertTrue(sql.contains("e.embedding_model = #{model}"), sql);
    }

    @Test
    void 图片检索_按模型过滤() throws Exception {
        String sql = selectSql(ImageEmbeddingMapper.class, "searchTopK",
                String.class, java.util.List.class, double.class, int.class, String.class);
        assertTrue(sql.contains("e.embedding_model = #{model}"), sql);
    }

    @Test
    void 车型对账_embeddedCount只计当前模型() throws Exception {
        String sql = selectSql(CarDocEmbeddingMapper.class, "countByModel", String.class);
        assertTrue(sql.contains("FILTER (WHERE e.embedding_model = #{model})"), sql);
    }

    @Test
    void 图片补缺失_JOIN按模型过滤() throws Exception {
        String sql = selectSql(ImageEmbeddingMapper.class, "findImageIdsWithoutEmbedding", String.class);
        assertTrue(sql.contains("e.embedding_model = #{model}"), sql);
    }

    @Test
    void 四个写入_insert_均带embedding_model列() throws Exception {
        String car = selectSqlInsert(CarDocEmbeddingMapper.class, "insert", Long.class, Long.class, String.class, String.class);
        String kb = selectSqlInsert(KbChunkEmbeddingMapper.class, "insert", Long.class, String.class, String.class);
        String news = selectSqlInsert(NewsDocEmbeddingMapper.class, "insert", Long.class, Long.class, String.class, String.class);
        String img = selectSqlInsert(ImageEmbeddingMapper.class, "insert", Long.class, String.class, String.class, String.class);
        assertTrue(car.contains("embedding_model"), car);
        assertTrue(kb.contains("embedding_model"), kb);
        assertTrue(news.contains("embedding_model"), news);
        assertTrue(img.contains("embedding_model"), img);
    }

    private static String selectSqlInsert(Class<?> mapper, String method, Class<?>... params) throws Exception {
        Method m = mapper.getMethod(method, params);
        org.apache.ibatis.annotations.Insert ins = m.getAnnotation(org.apache.ibatis.annotations.Insert.class);
        return String.join(" ", ins.value());
    }
}
