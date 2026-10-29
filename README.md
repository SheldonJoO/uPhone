# uKit

个人定制版 AirSIM iPhone App（侧载用），基于 [kai-wu-cortex/AirSIM](https://github.com/kai-wu-cortex/AirSIM) 的 iOS 源码定制。

## 定制内容

- App 名：**uKit**，图标换成个人头像
- Bundle ID：`com.sheldonjoo.ukit`（及 `.watchkitapp` / `.liveactivity` 后缀）
- 「配对三星手机」改名为「值守配对」
- 拨号盘：设备状态卡从拨号区上方移到**屏幕底部紧凑条**，不再挤占拨号盘
- **双卡拨号**：底部状态条可点选 SIM 1 / SIM 2（显示运营商），拨号命令携带 `subscription_id`，Android 端通过 `PhoneAccountHandle` 指定 SIM 呼出
- **双卡短信**：发送短信可指定 SIM（撰写框显示将用哪张卡发送）；收到的短信显示来源 SIM 标签（SIM 1 / SIM 2）
- **验证码一键复制**：短信详情里自动提取 4-8 位验证码，点一下复制到剪贴板
- **短信接收 bug 修复**：原版的 `IncomingSMSReceiver` 用 `goAsync() + 静态单线程池` 异步处理，进程被杀或线程池异常时短信直接丢失；改为 `onReceive` 内同步处理（PDU 解析 <100ms），并透传 `subscription_id` / `slot_index`

## 构建（未签名 IPA，侧载用）

推送到 `main` 分支后，GitHub Actions 自动在 macOS runner 上构建未签名 IPA，
在 Actions 页面的 Artifacts 里下载 `uPhone-unsigned-ipa`，用 Sideloadly / AltStore
以自己的 Apple ID 签名安装。

本地构建：

```bash
cd iOS
xcodebuild -project uKit.xcodeproj -scheme uKit -configuration Release \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO build
```

## 说明

- 云端 Relay 模式依赖 APNs 推送，免费 Apple ID 侧载无推送权限，只能用局域网 VoWLAN 模式；完整云端功能需要付费 Apple Developer 账号自行签名。
- 本项目仅限个人非商业用途，遵循上游 PolyForm Noncommercial 1.0.0 许可证。
- Android 端（双卡拨号/短信）改动在 `android/` 目录，需自行构建 APK。

<!-- build trigger 20261011020639 -->
