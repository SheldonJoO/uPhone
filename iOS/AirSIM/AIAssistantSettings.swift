import SwiftUI

// MARK: - AI 电话助理（预留接口）
//
// 设计目标：未来接入 Muse / 豆包 / Grok 等 AI 助手，用自然语言管理电话与短信，
// 例如"帮我给张三回个电话，用 SIM 2 打""把今天的验证码找出来"。
//
// 集成点约定（后续实现时遵循）：
//  1. AIAssistantConnector 是唯一入口，所有 AI 能力经它调用，不直调 AppModel。
//  2. 敏感动作（拨号、发短信、删记录）必须走用户确认：connector 返回的是"提议"，
//     由 UI 弹出确认后才会执行。AI 永远不能静默外呼。
//  3. 电话号码与短信内容不出本机：connector 只拿脱敏后的摘要做语义理解。

/// AI 助手连接器协议。Muse / 豆包 / Grok 各自实现一个 connector 即可接入。
protocol AIAssistantConnector: Sendable {
    /// 连接器标识，如 "muse"、"doubao"、"grok"。
    var identifier: String { get }
    /// 展示名，如 "Muse"。
    var displayName: String { get }
    /// 是否已配置可用（API Key / 授权）。
    var isConfigured: Bool { get }
}

/// 已知的连接器（占位：实现接入时在这里注册）。
enum AIAssistantRegistry {
    static var connectors: [any AIAssistantConnector] { [] }
}

/// AI 能力的作用域：将来做权限申请时按 scope 逐项授权。
enum AIAssistantScope: String, CaseIterable {
    case readCalls = "读取通话记录"
    case readSMS = "读取短信（含验证码）"
    case placeCall = "发起通话（需每次确认）"
    case sendSMS = "发送短信（需每次确认）"
    case manageBlocklist = "管理骚扰拦截名单"
}

struct AIAssistantSettingsView: View {
    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Label("AI 电话助理", systemImage: "sparkles")
                        .font(.headline)
                    Text("把值守机的电话和短信交给 AI 助手打理：查验证码、回电话、整理骚扰拦截，都可以用一句话完成。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    HStack {
                        Text("状态")
                        Spacer()
                        Text("即将推出")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(.orange)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .background(Color.orange.opacity(0.12), in: Capsule())
                    }
                    .font(.subheadline)
                }
                .padding(.vertical, 6)
            } header: {
                Text("余奉电话助理 · AI 扩展")
            }

            Section("规划中的能力") {
                ForEach(AIAssistantScope.allCases, id: \.self) { scope in
                    LabeledContent(scope.rawValue, value: "未接入")
                }
            } footer: {
                Text("安全原则：AI 只能提议、不能直接外呼或发短信；敏感操作每次都需要你点确认；号码与短信内容不出本机。")
            }

            Section("连接器") {
                if AIAssistantRegistry.connectors.isEmpty {
                    Text("还没有可用的 AI 连接器。Muse、豆包、Grok 的接入正在开发中。")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle("AI 电话助理")
        .navigationBarTitleDisplayMode(.inline)
    }
}
