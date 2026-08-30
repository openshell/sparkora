# 图床接入(S4 预览/发布用)

> 密钥一律放 `.env`(不入库,本文件仅作接口说明)。

## 七牛云(当前采用)

- **配置**(Sparkora `.env`,规范名;兼容旧裸名 AK/SK):
  - `QINIU_ACCESS_KEY` / `QINIU_SECRET_KEY`(兼容 `${AK}`/`${SK}` 回退)
  - `QINIU_BUCKET=caiqz`(唯一绑定外链域名的空间)
  - `QINIU_UPLOAD_HOST=http://up-z2.qiniup.com`(z2 区域实测)
  - `QINIU_PUBLIC_DOMAIN=http://pic.caiqz.cn`
- **签名**:上传 token = `AK:<b64(hmac_sha1(SK, putPolicy_b64))>`:`<b64(putPolicy)>`;putPolicy `{"scope":"<bucket>:<key>","deadline":...}`(指定 key 的覆盖上传,天然幂等);管理类接口 `Authorization: QBox <AK>:<b64(hmac_sha1(SK, path + "\n" + body))>`
- **上传端点**:z2 区域为 `http://up-z2.qiniup.com/`,multipart 三字段 `token/key/file`
- **key 策略**:`sparkora/{imageId}.{ext}`(服务端生成,同图重传=覆盖,幂等)
- **管理端点**:`rs.qbox.me/delete/<b64urlsafe(bucket:key)>`(POST)

### 旧方案备忘(imgbb,已弃用)

- 外链域 `i.ibb.co` 在本网络与 wenyan-server 出口均被 TLS 重置,无法用于发布拉图与浏览器预览 → 弃用
- API 文档留档:`POST https://api.imgbb.com/1/upload?key=<KEY>&expiration=<sec>`,image 支持二进制/base64/URL(≤32MB),expiration 秒(60~15552000);**key 见 .env 或向管理员索取,不得写死在任何仓库文件**