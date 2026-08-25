# SonicLink 手机附件协同

## 功能范围

当前版本打通 AI 缺陷助手草稿与 SonicLink 手机附件上传：

1. 用户在测试平台 AI 缺陷助手选择项目后点击“手机”。
2. 平台创建 5 分钟有效的一次性配对二维码和 2 小时有效的草稿会话。
3. SonicLink 在“缺陷协同”页面扫码，使用一次性 Token 换取 90 天设备 Token。
4. 设备 Token 由 Android Keystore 加密保存，服务端只保存 SHA-256。
5. 手机从图片、视频或文件页面上传后，网页每 2 秒拉取并加入当前草稿附件列表。
6. 提交或重置缺陷、切换项目时，网页停止旧草稿的附件同步。

聊天区域当前仅用于本机协同备注，不包含局域网 P2P 聊天或文件快传协议。

## 平台部署

测试平台需要执行数据库迁移：

```powershell
cd D:\new_works\xin_testing_platform\backend
python cli.py upgrade
```

新增表：

- `ai_bug_submit_mobile_device`
- `ai_bug_submit_mobile_session`
- `ai_bug_submit_mobile_attachment`

可通过环境变量限制附件大小，默认 512 MB：

```text
BUG_SUBMIT_MAX_ATTACHMENT_BYTES=536870912
```

## 局域网地址

二维码中的平台地址必须能被手机访问。网页使用 `localhost` 或 `127.0.0.1` 时，应在手机弹窗中改成电脑的局域网 IP，例如：

```text
http://192.168.1.20:5173
```

公网或跨网部署必须使用 HTTPS。HTTP 仅允许回环、链路本地或私有局域网地址。

## 安全边界

- 配对 Token 一次性使用，5 分钟过期。
- 草稿会话 2 小时过期，并绑定平台用户和项目。
- 移动上传必须同时匹配设备 ID、设备 Token、用户和草稿会话。
- 解除绑定会先撤销服务端设备 Token，再清理本机密文。
- Android 应用禁用系统备份，避免恢复无法解密或泄露的设备凭证。
