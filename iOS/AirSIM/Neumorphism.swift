import SwiftUI

/// Neumorphism（新拟态）主题：与安卓端统一的柔和"黏土"风。
/// 浅色模式下生效；深色模式保持原有深色样式。
enum NeuColors {
    /// 主背景 #E0E5EC
    static let background = Color(red: 0.878, green: 0.898, blue: 0.925)
    /// 深阴影（右下）
    static let shadowDark = Color(red: 0.639, green: 0.694, blue: 0.776)
    /// 浅阴影（左上）
    static let shadowLight = Color.white
    /// 按压态更深的阴影
    static let shadowDarkPressed = Color(red: 0.592, green: 0.651, blue: 0.741)
    /// 文字主色
    static let textPrimary = Color(red: 0.18, green: 0.227, blue: 0.302)
    /// 文字次色
    static let textSecondary = Color(red: 0.42, green: 0.471, blue: 0.537)
}

extension View {
    /// 凸起效果：左上白阴影 + 右下深阴影，经典新拟态。
    func neuRaised<S: Shape>(
        shape: S,
        cornerRadius: CGFloat = 16,
        shadowRadius: CGFloat = 8
    ) -> some View {
        self
            .background(shape.fill(NeuColors.background))
            .shadow(color: NeuColors.shadowLight.opacity(0.9), radius: shadowRadius, x: -shadowRadius / 2, y: -shadowRadius / 2)
            .shadow(color: NeuColors.shadowDark.opacity(0.55), radius: shadowRadius, x: shadowRadius / 2, y: shadowRadius / 2)
    }

    /// 凹陷效果（输入框、按压态）：内阴影模拟。
    func neuPressed<S: Shape>(
        shape: S,
        shadowRadius: CGFloat = 6
    ) -> some View {
        self
            .background(shape.fill(NeuColors.background))
            .overlay(
                shape
                    .stroke(NeuColors.shadowDark.opacity(0.35), lineWidth: 1.5)
                    .blur(radius: 1)
                    .offset(x: 1, y: 1)
                    .mask(shape)
            )
            .shadow(color: NeuColors.shadowLight.opacity(0.7), radius: shadowRadius, x: -2, y: -2)
    }

    /// 卡片：凸起圆角矩形。
    func neuCard(cornerRadius: CGFloat = 18) -> some View {
        neuRaised(shape: RoundedRectangle(cornerRadius: cornerRadius), cornerRadius: cornerRadius)
    }

    /// 圆形凸起（拨号键）。
    func neuCircleButton() -> some View {
        neuRaised(shape: Circle(), shadowRadius: 7)
    }
}
