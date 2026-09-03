package com.sparkora.storage;

/**
 * 图床存储抽象（S6 图库完全依赖图床）。业务代码只依赖本接口，不依赖具体供应商。
 *
 * 当前实现：{@link QiniuImageStorage}（七牛）。切换供应商时新增一个实现类 + 改配置即可，业务代码零改动。
 *
 * 语义：图片入库即直接转存图床（不再懒转存），本地不留文件。
 *  - upload(bytes, ext)：上传字节，返回图床 key（服务端生成，无用户可控成分）；
 *  - publicUrl(key)：由 key 拼公网 URL（浏览器预览可见 + wenyan-server 可拉）；
 *  - delete(key)：删除对象（非阻塞，失败仅告警）。
 */
public interface ImageStorage {

    /** 图床是否可用（配置齐备）。 */
    boolean configured();

    /** 上传字节到图床，返回图床 key。失败抛 RuntimeException（中文原因）。 */
    String upload(byte[] bytes, String ext);

    /** 由 key 拼公网 URL。 */
    String publicUrl(String key);

    /** 由 key 下载图床对象字节（图生图参考图等场景）。失败抛 RuntimeException（中文原因）。 */
    byte[] download(String key);

    /** 删除图床对象（非阻塞：失败仅告警）。 */
    void delete(String key);
}
