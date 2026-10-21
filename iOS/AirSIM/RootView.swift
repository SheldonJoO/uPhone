import SwiftUI
import UIKit

enum SystemCommunicationDestination: Equatable {
    case call(String)
    case message(String)
}

enum SystemCommunicationURL {
    static func destination(for url: URL) -> SystemCommunicationDestination? {
        guard let scheme = url.scheme?.lowercased(), scheme == "tel" || scheme == "im" else {
            return nil
        }
        let prefixLength = scheme.count + 1
        guard url.absoluteString.count > prefixLength else { return nil }
        var target = String(url.absoluteString.dropFirst(prefixLength))
        while target.hasPrefix("//") { target.removeFirst(2) }
        target = String(target.split(separator: "?", maxSplits: 1).first ?? "")
        target = target.removingPercentEncoding ?? target
        target = target.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty else { return nil }
        return scheme == "tel" ? .call(target) : .message(target)
    }
}

enum PhoneTab: String, CaseIterable, Identifiable {
    case dial = "拨号"
    case recents = "最近通话"
    case messages = "短信"
    case contacts = "通讯录"
    case settings = "设置"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .dial: return "circle.grid.3x3"
        case .recents: return "clock"
        case .messages: return "message"
        case .contacts: return "person.crop.circle"
        case .settings: return "gearshape"
        }
    }

    var tabTitle: String {
        switch self {
        case .recents: return "最近"
        case .contacts: return "联系人"
        default: return rawValue
        }
    }
}

/// 五个主要目的地使用系统 TabView；iOS 26 原生标签栏负责 Liquid Glass、
/// 长按放大与选中态交互，避免分页手势和自绘按钮争抢横向滑动。
struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var settings: AppSettings
    @AppStorage("airsim.selected-tab") private var selectedTabRawValue = PhoneTab.dial.rawValue
    @State private var pendingSMSRecipient: String?
    @State private var pendingSMSConversation: String?
    @AppStorage("airsim.first-connection-complete") private var firstConnectionComplete = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            TabView(selection: selectedTabBinding) {
                DialPadView()
                    .tag(PhoneTab.dial)
                    .tabItem { Label(L10n.t(PhoneTab.dial.tabTitle), systemImage: PhoneTab.dial.icon) }

                RecentsView(onCall: dial, onMessage: composeMessage)
                    .tag(PhoneTab.recents)
                    .tabItem { Label(L10n.t(PhoneTab.recents.tabTitle), systemImage: PhoneTab.recents.icon) }

                MessagesView(
                    pendingRecipient: $pendingSMSRecipient,
                    pendingConversation: $pendingSMSConversation
                )
                    .tag(PhoneTab.messages)
                    .tabItem { Label(L10n.t(PhoneTab.messages.tabTitle), systemImage: PhoneTab.messages.icon) }

                ContactsView(onCall: dial, onMessage: composeMessage)
                    .tag(PhoneTab.contacts)
                    .tabItem { Label(L10n.t(PhoneTab.contacts.tabTitle), systemImage: PhoneTab.contacts.icon) }

                AirSIMSettingsView()
                    .tag(PhoneTab.settings)
                    .tabItem { Label(L10n.t(PhoneTab.settings.tabTitle), systemImage: PhoneTab.settings.icon) }
            }
            .phoneTabBarMinimizeOnScroll()
            .phoneTabSelectionFeedback(selectedTabRawValue)
            .tint(.blue)

            if let call = model.inAppPresentedCall {
                ActiveCallView(call: call)
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
            }
            recordingSavedToast
        }
        .background(PhoneBackdrop())
        .animation(.easeInOut(duration: 0.2), value: model.inAppPresentedCall?.id)
        .preferredColorScheme(settings.appearance.colorScheme)
        .fullScreenCover(isPresented: Binding(
            get: { !firstConnectionComplete },
            set: { if !$0 { firstConnectionComplete = true } }
        )) {
            AirSIMFirstConnectionView()
                .environmentObject(model)
        }
        .onOpenURL(perform: handleSystemCommunicationURL)
        .onAppear {
            if let sender = IncomingSMSPresentationRequest.consume() {
                openSMSConversation(sender)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .airsimRemoteSMSReceived)) { _ in
            model.reloadMessagesFromLocalStore()
        }
        .onReceive(NotificationCenter.default.publisher(for: .airsimOpenSMSConversation)) { notification in
            let sender = IncomingSMSPresentationRequest.consume()
                ?? notification.userInfo?["sender"] as? String
            guard let sender else {
                selectedTab = .messages
                return
            }
            openSMSConversation(sender)
        }
        .onReceive(NotificationCenter.default.publisher(for: .airsimOpenIncomingCall)) { _ in
            model.presentCurrentCallInApp()
        }
        .onReceive(NotificationCenter.default.publisher(for: .uPhoneCallRecordingFinished)) { notification in
            model.isRecording = false
            model.recordingStartDate = nil
            if let url = notification.userInfo?["url"] as? URL {
                model.recordingShareURL = url
                model.recordingSavedNotice = L10n.t("通话录音已保存到本机 · 在录音列表可转发")
            }
        }
        .alert(L10n.t("通话录音"), isPresented: $model.showRecordingComplianceAlert) {
            Button(L10n.t("开始录音")) { model.confirmRecordingAndStart() }
            Button(L10n.t("取消"), role: .cancel) {}
        } message: {
            Text(L10n.t("录音将保存在本机（左声道是你，右声道是对方），可分享。请遵守当地法律法规，必要时告知对方正在录音。"))
        }
        // 录音结束/停止只静默落盘，用轻提示告知，不再自动弹系统分享框
        //（真正转发入口在录音列表）。提示 2.5s 后自动消失。
        .onChange(of: model.recordingSavedNotice) { notice in
            if notice != nil {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                    if model.recordingSavedNotice == notice { model.recordingSavedNotice = nil }
                }
            }
        }
        .alert(L10n.t("错误"), isPresented: errorBinding) {
            Button("OK") { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "")
        }
        .alert("麦克风权限", isPresented: $model.showMicrophoneSettingsAlert) {
            Button("前往系统设置") {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            }
            Button(L10n.t("取消"), role: .cancel) {}
        } message: {
            Text("云通话需要使用麦克风。请在系统设置中允许 AirSIM 访问麦克风，再重新拨打或接听。")
        }
    }

    /// 录音已保存的轻提示气泡（顶部）。
    private var recordingSavedToast: some View {
        Group {
            if let notice = model.recordingSavedNotice {
                Text(notice)
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 14)
                    .padding(.vertical, 9)
                    .background(.thinMaterial, in: Capsule())
                    .foregroundStyle(.primary)
                    .padding(.top, 10)
            }
        }
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { model.errorMessage?.isEmpty == false },
            set: { if !$0 { model.errorMessage = nil } }
        )
    }

    private var selectedTabBinding: Binding<PhoneTab> {
        Binding(
            get: { PhoneTab(rawValue: selectedTabRawValue) ?? .dial },
            set: { newTab in
                guard newTab.rawValue != selectedTabRawValue else { return }
                selectedTabRawValue = newTab.rawValue
            }
        )
    }

    private var selectedTab: PhoneTab {
        get { PhoneTab(rawValue: selectedTabRawValue) ?? .dial }
        nonmutating set { selectedTabRawValue = newValue.rawValue }
    }

    private func dial(_ number: String) {
        model.numberInput = number
        selectedTab = .dial
        Task { await model.dial() }
    }

    private func composeMessage(_ number: String) {
        pendingSMSRecipient = number
        selectedTab = .messages
    }

    private func openSMSConversation(_ sender: String) {
        model.reloadMessagesFromLocalStore()
        pendingSMSConversation = sender
        selectedTab = .messages
    }

    private func handleSystemCommunicationURL(_ url: URL) {
        switch SystemCommunicationURL.destination(for: url) {
        case let .call(number):
            dial(number)
        case let .message(recipient):
            composeMessage(recipient)
        case nil:
            break
        }
    }
}

/// 浅色模式使用系统电话式纯白底；深色模式才绘制深蓝黑底部环境光。
struct PhoneBackdrop: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        ZStack {
            if colorScheme == .dark {
                Color(red: 0.008, green: 0.016, blue: 0.035)
                LinearGradient(
                    colors: [
                        Color.clear,
                        Color(red: 0.018, green: 0.075, blue: 0.16).opacity(0.72),
                        Color(red: 0.025, green: 0.20, blue: 0.48).opacity(0.62),
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
            } else {
                // Neumorphism 浅灰底，与安卓端统一
                NeuColors.background
            }
        }
        .ignoresSafeArea()
    }
}

/// 关键内容表面：浅色模式用 Neumorphism 凸起卡片，深色模式用 Liquid Glass/系统材质。
struct PhoneCard: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    @ViewBuilder
    func body(content: Content) -> some View {
        if colorScheme == .light {
            content
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .neuCard()
        } else {
#if compiler(>=6.2)
            if #available(iOS 26.0, *) {
                content
                    .padding(16)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .glassEffect(
                        .regular,
                        in: RoundedRectangle(cornerRadius: 18, style: .continuous)
                    )
            } else {
                phoneCardDarkSurface(content)
            }
#else
            phoneCardDarkSurface(content)
#endif
        }
    }
}

@ViewBuilder
private func phoneCardDarkSurface<C: View>(_ content: C) -> some View {
    content
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
        )
}

struct PhoneGlassSurface: ViewModifier {
    let cornerRadius: CGFloat
    let tint: Color?
    let interactive: Bool

    @ViewBuilder
    func body(content: Content) -> some View {
#if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            let glass = interactive
                ? Glass.regular.tint(tint).interactive()
                : Glass.regular.tint(tint)
            content.glassEffect(
                glass,
                in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
            )
        } else {
#endif
            content
                .background(
                    .regularMaterial,
                    in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                )
                .overlay {
                    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                        .strokeBorder(.primary.opacity(0.08))
                }
#if compiler(>=6.2)
        }
#endif
    }
}

/// 自定义工具栏材质仅用于隐藏了系统共享背景的 ToolbarItem，确保始终只有一层玻璃。
private struct PhoneToolbarGlassMorph: ViewModifier {
    let id: String
    @Namespace private var glassNamespace

    @ViewBuilder
    func body(content: Content) -> some View {
#if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            GlassEffectContainer(spacing: 14) {
                content
                    .buttonStyle(.plain)
                    .padding(.horizontal, 10)
                    .frame(minWidth: 40, minHeight: 40)
                    .contentShape(Capsule())
                    .glassEffect(.regular.interactive(), in: Capsule())
                    .glassEffectID(id, in: glassNamespace)
                    .glassEffectTransition(.materialize)
            }
            .transition(.opacity.combined(with: .scale(scale: 0.82)))
        } else {
#endif
            content
#if compiler(>=6.2)
        }
#endif
    }
}

/// iOS 26 隐藏导航栏自动生成的共享按钮背景，再使用上面的单层可变形玻璃；
/// 旧系统继续交给 ToolbarItem 使用平台默认样式。
@ToolbarContentBuilder
func phoneMorphingToolbarItem<Content: View>(
    placement: ToolbarItemPlacement,
    id: String,
    @ViewBuilder content: () -> Content
) -> some ToolbarContent {
#if compiler(>=6.2)
    if #available(iOS 26.0, *) {
        ToolbarItem(placement: placement) {
            content().phoneToolbarGlassMorph(id: id)
        }
        .sharedBackgroundVisibility(.hidden)
    } else {
#endif
        ToolbarItem(placement: placement, content: content)
#if compiler(>=6.2)
    }
#endif
}

extension View {
    func phoneCard() -> some View { modifier(PhoneCard()) }

    func phoneGlassSurface(
        cornerRadius: CGFloat = 18,
        tint: Color? = nil,
        interactive: Bool = false
    ) -> some View {
        modifier(PhoneGlassSurface(
            cornerRadius: cornerRadius,
            tint: tint,
            interactive: interactive
        ))
    }

    @ViewBuilder
    func phoneTabBarMinimizeOnScroll() -> some View {
#if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.tabBarMinimizeBehavior(.onScrollDown)
        } else {
#endif
            self
#if compiler(>=6.2)
        }
#endif
    }

    func phoneToolbarGlassMorph(id: String) -> some View {
        modifier(PhoneToolbarGlassMorph(id: id))
    }

    @ViewBuilder
    func phoneTabSelectionFeedback(_ trigger: String) -> some View {
        if #available(iOS 17.0, *) {
            sensoryFeedback(.selection, trigger: trigger)
        } else {
            self
        }
    }
}

private struct PhoneTabBarCompactStateActionKey: EnvironmentKey {
    static let defaultValue: (Bool) -> Void = { _ in }
}

extension EnvironmentValues {
    fileprivate var phoneTabBarCompactStateAction: (Bool) -> Void {
        get { self[PhoneTabBarCompactStateActionKey.self] }
        set { self[PhoneTabBarCompactStateActionKey.self] = newValue }
    }
}

private struct PhoneTabBarCompactScrollReporter: ViewModifier {
    @Environment(\.phoneTabBarCompactStateAction) private var reportCompactState

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onScrollGeometryChange(for: CGFloat.self) { geometry in
                max(0, geometry.contentOffset.y + geometry.contentInsets.top)
            } action: { oldOffset, newOffset in
                let delta = newOffset - oldOffset
                if newOffset < 2 {
                    reportCompactState(false)
                } else if delta > 2 {
                    reportCompactState(true)
                } else if delta < -2 {
                    reportCompactState(false)
                }
            }
        } else {
            content
        }
    }
}

extension View {
    func phoneReportsTabBarCompactState() -> some View {
        modifier(PhoneTabBarCompactScrollReporter())
    }
}

/// 系统分享面板：直接走 UIKit present，不经过 SwiftUI sheet 托管
/// UIActivityViewController（后者会导致白屏）。返回是否成功弹出。
enum SystemSharePresenter {
    @discardableResult
    static func present(url: URL) -> Bool {
        let activity = UIActivityViewController(activityItems: [url], applicationActivities: nil)
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        guard let scene = scenes.first(where: { $0.activationState == .foregroundActive }) ?? scenes.first,
              let window = scene.keyWindow ?? scene.windows.first,
              var top = window.rootViewController
        else { return false }
        while let presented = top.presentedViewController { top = presented }
        if top.presentedViewController is UIActivityViewController { return true }
        if let pop = activity.popoverPresentationController {
            pop.sourceView = top.view
            pop.sourceRect = CGRect(
                x: top.view.bounds.midX, y: top.view.bounds.midY, width: 1, height: 1)
            pop.permittedArrowDirections = []
        }
        top.present(activity, animated: true)
        return true
    }
}
