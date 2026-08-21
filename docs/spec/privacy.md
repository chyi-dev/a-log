# 隐私

## 禁止写入

- 密码、完整 token / Authorization
- 完整银行卡号、身份证号
- 精确 GPS（可写粗略城市级）
- 剪贴板内容

## 脱敏（PrivacyInterceptor）

- 大陆手机号 `1[3-9]\d{9}` → 保留前 3 后 4，中间 `****`
- `Bearer <token>` / `token=` 后的值 → `***`

业务仍应避免把隐私打进日志。落盘压缩不能替代「别打」。

## 上传

用户同意前不得上传。sample 用按钮表示主动同意。回捞指令执行后调用 `POST /logs/fetch-ack`。
