# 4G 模块 Web 管理后台（大疆 / 移远）

`index.html` 是一个**自包含**的 Web 控制台（无依赖、无构建），Mac/NAS 上的 cellbridge 网关直接托管这个目录即可用。

## 打开

- 直接双击 `index.html` 在浏览器预览（演示模式，内置模拟数据）。
- 或由网关托管：
  ```bash
  cd module-dashboard && python3 -m http.server 8080
  # 访问 http://<mac/nas-ip>:8080
  ```

## 功能

| 页面 | 说明 |
| --- | --- |
| 总览 | 连接状态 / 信号（RSRP·RSRQ·SINR + 实时波形）/ 运营商 / SIP 注册 / 最近事件 |
| SIM 卡 | 双卡槽：ICCID、号码、运营商、信号、激活状态 |
| 通话 | 通话记录 + 测试拨号 |
| 短信 | 收发记录 + 发送框 |
| SIP 网关 | 服务器/端口/账号/编码/传输 + 注册状态 |
| AT 控制台 | 直接下发 AT 指令（ATI/CGMM/CCID/CSQ/COPS…），演示模式返回模拟响应 |
| 设置 | 厂商/型号/连接方式(USB·TCP)/AT 串口或主机端口/Relay 地址 |

顶部可在多个模块间切换：大疆 QDC507 / EG25-G、移远 EG25-G / EG912Y-IE。

## 接真实网关

`index.html` 顶部有完整 **API 契约**注释。把脚本里的 `const DEMO = true;` 改成 `false`，
由网关实现这些同源端点（与 cellbridge-gateway 对应）：

```
GET  /api/status      模块状态（信号/IMEI/ICCID/运营商/SIP…）
GET  /api/sim         双卡信息
GET  /api/calls       通话记录
GET  /api/sms         短信记录        POST /api/sms  发短信
POST /api/calls/dial  拨号
GET  /api/sip         SIP 配置        POST /api/sip  保存并注册
POST /api/at          {command} → {response}   AT 指令
GET/POST /api/settings 模块与连接设置
```

设置页字段与 iOS App「设置 → 硬件值守模块」一致，供两端共用同一份模块配置。
