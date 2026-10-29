# uKit 重建 + App Store 上架方案（基于 AirSIM 版 repo2）

> 决策已定：非中国区上架、保留 CallKit；先出方案再动手。
> 本文件是计划，未改动任何代码。

---

## 0. 核心结论

- `~/Projects/uPhone/uPhone-cleanroom` 是从零重写的失败版本，已废弃。
- `~/Projects/uPhone/repo2` 就是 `SheldonJoO/uPhone` 工作副本，基于 `kai-wu-cortex/AirSIM` 定制的 uKit。
  README 已写明 App 名 uKit、双卡拨号/短信等定制，git 历史有完整音频/通话修复。
- 干净房间那两个 bug 在 repo2 里**不复现**，理由：
  - 无声：repo2 早已用 `reportOutgoingCall(with:connectedAt:)`（CallKitController.swift:189/352/640/654），
    干净房间当初在 iOS 18.5 SDK 编译挂掉的旧 API 它从没用过；且历史有 `f908708`「修复接听后无声」等修复。
  - 挂不断：repo2 的 Android 走正规 `TelecomManager` + `AirSIMInCallService`(InCallService) + `ShizukuBridgeManager` 兜底
    （`android/.../AirSIMInCallService.java`、`StandaloneAgentGateway.java:194`、`CallRepository.execute(...TelecomManager)`），
    与干净房间"只靠 Shizuku"的脆弱路径不同。
  - `call_id` 链路贯穿 relay/iOS/Android 一致（relay 全程用 `call_id`；iOS `IPhoneCloudCallSession` 下发 `"call_id": call.callID`）。
- 所以"重建"= 回到 repo2 继续，而不是重写。

### 0.1 干净房间（uPhone-cleanroom）处置结论

- **能否合并：不能。**
  - 语言不同：干净房间 Android 是 **Kotlin**（`dev.uphone.agent`，6108 行），repo2 Android 是 **Java**（`com.airsim.phonecontrol`）。
  - 架构/命名空间不同：iOS 一个叫 `uPhone`、一个叫 `AirSIM`；无共同 git 祖先（干净房间本就是"洁净室"从零重写，不是 repo2 的 fork），`git merge` 无合并基、无意义且会编译爆炸。
  - 干净房间的修复针对的是它**自己架构上的 bug**，repo2 根本不是这套代码。
- **借鉴意义：代码层几乎为零（已用 grep 证实）。**
  - 干净房间头号根因（只认 `MODE_IN_CALL`、漏 `MODE_IN_COMMUNICATION`）→ **repo2 的 `LocalCallAudioController.java:130` 已同时处理两者**，修复冗余。
  - 干净房间 `MediaCodecOpusCodec` 的 `INFO_OUTPUT_FORMAT_CHANGED` 解码 bug → repo2 grep 不到任何 MediaCodec/Opus 解码代码（音频走独立 `phone-audio-bridge` 架构），该 bug 不适用。
  - 干净房间有 Kotlin 单测，但 **repo2 本身已有测试**（`AirSIMTests.swift`、`BridgeUnitTests.java`、`CoreTests.java`）；付费墙 StoreKit 逻辑 repo2 也已有。
- **仅有的非代码价值（建议保留，不合并）：**
  - `BUG_FIX_PLAN.md` / `ACCEPTANCE.md` 的**分析方法论**（可观测性优先：记录真实 audio mode、解码错独立槽、首失败包 hex）值得作为参考沉淀。
  - 定价决策（6/99）是产品问题，repo2 也需对齐，与代码合并无关。
- **处置建议：** 保留 `uPhone-cleanroom` 仓库作为历史参考、停止维护；不合并代码；把上述两份分析文档并入 repo2 作参考；继续推进 Phase 1。

---

## 1. 现状核对（已查证，带证据）

| 项 | 现状 | 上架缺口 |
|---|---|---|
| Bundle ID | pbxproj 仍是 `com.example.airsim`，`DEVELOPMENT_TEAM = ""` | 必须改 `com.sheldonjoo.ukit` + 填真实 Team ID |
| 显示名/scheme | scheme 仍叫 `AirSIM` | 建议改名 `uPhone`，`CFBundleDisplayName = uKit` |
| 版本 | `MARKETING_VERSION = 0.9.7`，`CURRENT_PROJECT_VERSION = 89`，`SWIFT_VERSION = 5.0` | 商店首版建议 `1.0.0`，版本号递增 |
| 后台模式 | Info.plist 已含 `audio` / `voip` / `remote-notification` | 已满足，无需改 |
| Entitlements | 仅 `aps-environment = $(APS_ENVIRONMENT)` | 商店构建传 `production`；VoIP 走证书+后台模式，无需额外 entitlement key |
| 中继地址 | 构建变量 `AIRSIM_PUSH_RELAY_URL`（CI 传 `https://airsim-push.dntmove.asia`）；代码默认 `https://airsim-push.remotepilot.site` | 生产须指向稳定可用的 `airsim-push.dntmove.asia` |
| APNs/VoIP | relay worker 已实现 `sendAPNs`，发 `pushType:"voip"`、topic `${bundle_id}.voip`（index.mjs:1199-1217） | worker 的 wrangler env 需配 APNs auth key/keyId/teamId；证书需为 `com.sheldonjoo.ukit` 的 VoIP Services |
| 签名流水线 | 仅 `build-ipa.yml` 出**未签名** IPA（侧载用） | 缺 App Store 签名/导出流水线（无 ExportOptions.plist / fastlane / xcconfig） |
| Watch/LiveActivity | 含 Watch App + LiveActivity 扩展 + Widget | 首版建议只交 iOS 主 App + LiveActivity，Watch 后续更版再加，降低审核面 |

---

## 2. 分阶段执行计划

### Phase 0 — 收口到 repo2（废弃干净房间）
- 确认 `repo2` 为唯一规范源，`uPhone-cleanroom` 不再维护。
- 建立分支策略：在 `main` 之外开 `appstore` 分支承载上架改动（不污染日常侧载构建）。
- 全量 `xcodebuild` 真编译验证（**不用 `swiftc -parse`**，它会漏错），确认当前 HEAD 能出包。
- 复核干净房间两个 bug 在 repo2 是否复现：结论——无需干净房间的 callId 补丁（Fix A–F 不适用）。

### Phase 1 — 上架工程化改造（代码侧）
- pbxproj：`PRODUCT_BUNDLE_IDENTIFIER` `com.example.airsim` → `com.sheldonjoo.ukit`
  （含 `.watchkitapp` / `.liveactivity` 后缀），`DEVELOPMENT_TEAM` 填真实 10 位 Team ID，
  `CODE_SIGN_STYLE = Automatic`，`MARKETING_VERSION = 1.0.0`，`CURRENT_PROJECT_VERSION` 从 89 递增。
- scheme 改名 `AirSIM` → `uPhone`；`CFBundleDisplayName = uKit`。
- `aps-environment`：商店构建设为 `production`（CI 传入，不写死）。
- 中继地址：默认/构建变量指向生产 `https://airsim-push.dntmove.asia`，确认已部署且稳定。
- VoIP Services 证书：在 ADC 为 `com.sheldonjoo.ukit` 创建 VoIP Services 证书；
  relay worker 的 wrangler env 注入 APNs auth key / keyId / teamId。
- 发_push 自检：确认 `sendAPNs` 对 `com.sheldonjoo.ukit.voip` topic 在生产 relay 上可送达。

### Phase 2 — 隐私与合规
- 隐私政策页：托管到 `dntmove.asia` 或 Cloudflare Pages，URL 写入 App Store Connect。
- App Privacy 营养标签：如实申报中继处理的元数据（设备 token、通话生命周期、是否存通话记录/音频）。
- 加密合规：通话若加密需走自分类豁免或 CCATS；Opus 编解码属通用，一般豁免。需先确认音频是否过 relay。
- CallKit 审核风险：准备说明——uKit 属"远程 SIM / 呼叫转移"合法用途，非伪造系统来电。

### Phase 3 — 签名 / 分发流水线
- 推荐 Xcode Cloud（Apple 原生，签名最简单）或 GitHub Actions + App Store Connect API Key + `ExportOptions.plist`（`method=app-store`）。
- 新建 `build-appstore.yml`：`archive` → `exportArchive`（App Store 签名）→ 上传 TestFlight（`App Store Connect API`）。
- ⚠️ 真实签名/上传需你提供：App Store 分发证书+描述文件，或 API Key（`authKey.p8`+keyId+issuerId）+ Team ID，注入 GitHub Secrets。
- 我先搭好流水线骨架与 `ExportOptions.plist`，签名那一步等你给密钥。

### Phase 4 — App Store Connect 与提审
- 建 App 记录：名称 uKit、Bundle `com.sheldonjoo.ukit`、类目 Utilities、年龄分级。
- 截图/预览（iPhone，视情况 Watch）、描述、关键词、隐私政策 URL。
- TestFlight 内部测试先跑真机（iPhone + 配对 Android）验证：无声 / 挂断 / 短信。
- 审核说明（Review Notes）：双设备中继原理、demo 视频、可测后端、审核用测试设备/账号、CallKit 合法用途。
- 提交**非中国区**（如美区）。

### Phase 5 — 审核与迭代
- 处理苹果反馈（常见于 CallKit 用途、隐私标签、功能可测性）。
- 通过后后续版本再加 Watch App / 更多能力。

---

## 3. 关键风险

- ⚠️ CallKit 在 cn 区禁售——已定非中国区，OK。
- ⚠️ 双设备产品审核难：审核员需能测。必须提供 demo 视频 + 可测后端 + 说明。
- ⚠️ VoIP 推送证书/密钥会过期，导致来电失效，需监控续期。
- ⚠️ 签名密钥需你提供，我无法凭空生成。
- ⚠️ Bundle 仍是 `com.example.airsim`，必须最先改。
- ⚠️ 无声/挂断需真机（iPhone+Android 配对）验证，本机跑不了。

---

## 4. 我这一轮能立刻动手的（等你确认）

可立即做（不依赖外部密钥）：
- 在 `appstore` 分支改 bundle/team/版本、scheme 改名、显示名、生产 relay 地址。
- 补 `ExportOptions.plist` + `build-appstore.yml` 骨架。
- 写隐私政策初稿。
- 全量 `xcodebuild` 编译验证当前 HEAD。

不能做（需你提供）：
- 真实 App Store 签名与上传（缺证书/API Key）。
- APNs 密钥注入生产 relay（需你给 auth key/keyId/teamId）。
- 真机功能测试（无声/挂断/短信需 iPhone+Android 配对）。

---

## 5. 待你确认 / 提供

1. 真实 Apple **Team ID**（10 位）与 `com.sheldonjoo.ukit` 是否已在 ADC 注册。
2. 签名方式偏好：Xcode Cloud 还是 GitHub Actions + API Key。
3. 上架目标区（建议美区）与首版是否含 Watch App。
4. APNs auth key（.p8）+ keyId + teamId（用于生产 relay 发 VoIP 推送）。
5. 中继 `airsim-push.dntmove.asia` 是否已是 repo2 worker 的生产部署且稳定运行。
