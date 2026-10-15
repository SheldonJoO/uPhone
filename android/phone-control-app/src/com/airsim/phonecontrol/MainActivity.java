package com.airsim.phonecontrol;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int ROLE_REQUEST = 100;
    private static final int DEFAULT_APPS_REQUEST = 102;

    // Neumorphism 浅色主题（试改）：底 #E0E5EC，双阴影塑造凸起/凹陷
    private static final int BG = Color.rgb(224, 229, 236);
    private static final int SURFACE = Color.rgb(224, 229, 236);
    private static final int SURFACE_HIGH = Color.rgb(224, 229, 236);
    private static final int BORDER = Color.rgb(163, 177, 198);
    private static final int PRIMARY = Color.rgb(24, 154, 118);
    private static final int CYAN = Color.rgb(14, 124, 134);
    private static final int BLUE = Color.rgb(47, 128, 237);
    private static final int TEXT = Color.rgb(46, 58, 77);
    private static final int MUTED = Color.rgb(107, 120, 137);
    private static final int WARNING = Color.rgb(178, 106, 0);
    private static final int DANGER = Color.rgb(214, 69, 69);

    // 一键值守：主路径只剩「值守」一页；通话 / 设置 / 诊断收进「更多」里的次级分段，
    // 不再占用底部导航，避免值守机每次打开都要在四个页签里找开关。
    private enum Page { DUTY, MORE }
    private enum MoreSection { CALLS, SETTINGS, DEBUG }
    private enum Feature { VOWLAN, AGENT, PCM, PUSH, SHIZUKU }

    private FrameLayout contentHost;
    private FrameLayout moreHost;
    private LinearLayout bottomBar;
    private final List<TextView> navigationItems = new ArrayList<>();
    private final List<TextView> moreSectionItems = new ArrayList<>();
    private Page currentPage = Page.DUTY;
    private MoreSection currentMore = MoreSection.CALLS;

    private EditText endpoint;
    private EditText token;
    private EditText number;
    private String selectedMode;
    private TextView status;
    private TextView shizukuStatus;
	private TextView installerStatus;
    private TextView pairingStatus;
    private TextView pairingCodeView;
    private ImageView pairingQrView;
    private TextView pairingCountdownView;
    private LinearLayout debugRows;
    private String lastRenderedDebugLog;
    private String lastRenderedDebugFilter;
    private TextView agentRefreshResult;
    private String agentRefreshMessage = "点击刷新以查看 Agent 的实际返回结果";
    private int agentRefreshColor = MUTED;
    private int agentRefreshGeneration;
    private String activeDebugFilter = "全部";
    private boolean realtimeLogging = true;
    private boolean agentOnline;
    private String agentDetail = "待检测";
    private boolean pushConfigured;
    private String pushDetail = "尚未检查";
    private String pushDiagnostic = "尚未读取 Agent 的 Push 状态";
	private boolean cloudEnabled;
	private boolean cloudRelayConfigured;
	private String cloudLastError = "";
	private boolean avfStartupPromptShown;

    private PairingServer pairingServer;
    private CountDownTimer pairingCountdown;
    private String pairingCode = "";
    private long pairingExpiresAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable debugRefresh = new Runnable() {
        @Override public void run() {
            if (currentPage == Page.MORE && currentMore == MoreSection.DEBUG && realtimeLogging) {
                refreshDebugLog();
                handler.postDelayed(this, 2_000);
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        configureWindow();
        setContentView(buildShell());
        showPage(Page.DUTY);
        Uri data = getIntent().getData();
        if (Intent.ACTION_DIAL.equals(getIntent().getAction()) && data != null) {
            openMore(MoreSection.CALLS);
            if (number != null) number.setText(data.getSchemeSpecificPart());
        }
        refreshStatus();
        refreshAgent();
		String[] missingPermissions = RuntimePermissionPolicy.missingPermissions(
				Build.VERSION.SDK_INT,
				checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED,
				checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED,
				checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
				checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
				checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED,
                checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED,
				Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
						== PackageManager.PERMISSION_GRANTED);
		if (missingPermissions.length > 0) {
            requestPermissions(missingPermissions, 101);
		} else {
			continueStartupSetup();
		}
    }

	@Override public void onRequestPermissionsResult(
			int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == 101) continueStartupSetup();
	}

    private void configureWindow() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        // 浅色主题：状态栏图标切深色
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(false);

        contentHost = new FrameLayout(this);
        root.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        bottomBar = buildBottomBar();
        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(78)));

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(0, bars.top, 0, bars.bottom);
            } else {
                view.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                        insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        return root;
    }

    private LinearLayout buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(10), dp(6), dp(10), dp(8));
        bar.setBackground(neuDrawable("neu_bar_bg"));
        navigationItems.clear();
        bar.addView(navigationItem("值守", android.R.drawable.presence_online, Page.DUTY), weighted());
        bar.addView(navigationItem("更多", android.R.drawable.ic_menu_preferences, Page.MORE), weighted());
        return bar;
    }

    private TextView navigationItem(String title, int icon, Page page) {
        TextView item = label(title, 12, MUTED, Gravity.CENTER);
        item.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        item.setCompoundDrawableTintList(ColorStateList.valueOf(MUTED));
        item.setCompoundDrawablePadding(dp(3));
        item.setPadding(dp(4), dp(4), dp(4), dp(4));
        item.setOnClickListener(ignored -> showPage(page));
        item.setTag(page);
        navigationItems.add(item);
        return item;
    }

    private void showPage(Page page) {
        currentPage = page;
        handler.removeCallbacks(debugRefresh);
        bottomBar.setVisibility(View.VISIBLE);
        moreSectionItems.clear();
        contentHost.removeAllViews();
        View content = page == Page.MORE ? morePage() : dutyPage();
        contentHost.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        updateNavigation();
        if (page == Page.MORE) showMoreSection(currentMore);
    }

    /** 「更多」页内的次级分段：通话 / 设置 / 诊断，共用同一个容器，不新增底部页签。 */
    private void showMoreSection(MoreSection section) {
        currentMore = section;
        if (moreHost == null) return;
        handler.removeCallbacks(debugRefresh);
        moreHost.removeAllViews();
        View content;
        switch (section) {
        case SETTINGS: content = settingsPage(); break;
        case DEBUG: content = debugPage(); break;
        case CALLS:
        default: content = callsPage(); break;
        }
        moreHost.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (TextView item : moreSectionItems) {
            boolean selected = item.getTag() == section;
            item.setTextColor(selected ? PRIMARY : MUTED);
            item.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (section == MoreSection.DEBUG && realtimeLogging) handler.post(debugRefresh);
    }

    private void openMore(MoreSection section) {
        currentMore = section;
        showPage(Page.MORE);
    }

    private void updateNavigation() {
        for (TextView item : navigationItems) {
            boolean selected = item.getTag() == currentPage;
            int color = selected ? PRIMARY : MUTED;
            item.setTextColor(color);
            item.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            item.setCompoundDrawableTintList(ColorStateList.valueOf(color));
        }
    }

    /** 一键值守台：值守机上日常要操作的，只有最上面那个开关。 */
    private View dutyPage() {
        LinearLayout root = pageRoot();
        boolean dutyOn = DutyStore.isDutyOn(this);
        boolean paired = VoWLANPairingStore.configured(this);

        root.addView(pageTitle("余奉电话助理", dutyOn ? "值守中" : "待命"));

        LinearLayout hero = card(SURFACE_HIGH, 26);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(20), dp(22), dp(20), dp(22));

        LinearLayout headRow = row();
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = icon(android.R.drawable.presence_online, dutyOn ? PRIMARY : MUTED, 44);
        mark.setBackground(neuInsetDrawable(24));
        mark.setPadding(dp(11), dp(11), dp(11), dp(11));
        headRow.addView(mark, new LinearLayout.LayoutParams(dp(50), dp(50)));
        LinearLayout headCopy = column(dp(12));
        TextView headTitle = label(dutyOn ? "值守中" : "尚未开始值守", 22,
                dutyOn ? PRIMARY : TEXT, Gravity.START);
        headTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headCopy.addView(headTitle);
        headCopy.addView(label(dutySummary(dutyOn, paired), 13, MUTED, Gravity.START));
        headRow.addView(headCopy, weighted());
        hero.addView(headRow);

        TextView toggle = dutyOn ? dangerButton("停止值守") : primaryButton("一键开始值守", PRIMARY);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setTextSize(17);
        toggle.setOnClickListener(ignored -> toggleDuty());
        hero.addView(toggle, matchWithTop(18));
        root.addView(hero, matchWithTop(16));

        root.addView(buildDutySimCard(), matchWithTop(14));

        String connectionMode = MainScreenPresentation.connectionModeTitle(
                vowlanReady(), cloudEnabled, cloudRelayConfigured, cloudLastError);
        int connectionColor = vowlanReady() ? CYAN
                : cloudEnabled && cloudRelayConfigured && cloudLastError.isEmpty() ? BLUE : WARNING;
        LinearLayout connectionCard = card(SURFACE, 18);
        connectionCard.setOrientation(LinearLayout.VERTICAL);
        connectionCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        connectionCard.addView(overline("当前连接模式"));
        TextView connectionTitle = label(connectionMode, 17, connectionColor, Gravity.START);
        connectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        connectionCard.addView(connectionTitle, matchWithTop(5));
        root.addView(connectionCard, matchWithTop(12));

        TextView pairing = primaryButton(paired ? "重新配对 iPhone" : "配对 iPhone", BLUE);
        pairing.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_input_add, 0, 0, 0);
        pairing.setCompoundDrawableTintList(ColorStateList.valueOf(Color.WHITE));
        pairing.setCompoundDrawablePadding(dp(8));
        pairing.setOnClickListener(ignored -> openPairingPage());
        root.addView(pairing, matchWithTop(14));

        root.addView(sectionTitle("最近活动"), matchWithTop(22));
        LinearLayout recent = card(SURFACE, 18);
        recent.setPadding(dp(16), dp(14), dp(16), dp(14));
        recent.setOrientation(LinearLayout.HORIZONTAL);
        recent.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(solidPill(PRIMARY, 7));
        recent.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView activity = label(latestActivity(), 14, TEXT, Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams activityParams = weighted();
        activityParams.leftMargin = dp(12);
        recent.addView(activity, activityParams);
        recent.setOnClickListener(ignored -> openMore(MoreSection.DEBUG));
        root.addView(recent, matchWithTop(10));

        status = label("", 12, MUTED, Gravity.START);
        status.setVisibility(View.GONE);
        root.addView(status);
        return scroll(root);
    }

    private String dutySummary(boolean dutyOn, boolean paired) {
        if (!dutyOn) {
            return paired ? "已配对 iPhone，点下方按钮开始值守" : "点下方按钮开始值守，再配对 iPhone";
        }
        if (!paired) return "值守已开启，等待配对 iPhone";
        return MainScreenPresentation.connectionModeDetail(
                vowlanReady(), cloudEnabled, cloudRelayConfigured, cloudLastError);
    }

    /**
     * 值守卡选择。双卡是这个应用的命门：这里既让用户选卡，也把每张卡能否被系统
     * 准确反查摊开显示——反查失败就意味着选卡在这台机器上会退化。
     */
    private View buildDutySimCard() {
        LinearLayout cardView = card(SURFACE, 20);
        cardView.setOrientation(LinearLayout.VERTICAL);
        cardView.setPadding(dp(16), dp(15), dp(16), dp(15));
        cardView.addView(overline("值守卡（拨出与接听默认使用）"));

        List<SimCards.Card> cards = SimCards.activeCards(this);
        int selected = DutyStore.getDutySlot(this);
        if (cards.isEmpty()) {
            cardView.addView(description("未检测到 SIM 卡：请确认已授予电话权限且卡槽已插卡。"), matchWithTop(8));
        } else {
            for (SimCards.Card sim : cards) {
                cardView.addView(simOptionRow(sim, selected), matchWithTop(8));
            }
        }
        cardView.addView(simOptionRow(null, selected), matchWithTop(8));

        TextView health = description(SimCards.describe(this));
        health.setTextIsSelectable(true);
        cardView.addView(health, matchWithTop(10));
        return cardView;
    }

    private View simOptionRow(SimCards.Card sim, int selectedSlot) {
        boolean follow = sim == null;
        int slot = follow ? DutyStore.SLOT_FOLLOW_SYSTEM : sim.slot;
        boolean selected = selectedSlot == slot;

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), dp(11), dp(12), dp(11));
        item.setBackground(selected ? neuInsetDrawable(14) : neuRaisedDrawable(14));

        LinearLayout copy = column(0);
        TextView title = label(follow ? "跟随系统默认卡" : sim.title(), 15,
                selected ? PRIMARY : TEXT, Gravity.START);
        if (selected) title.setTypeface(Typeface.DEFAULT_BOLD);
        copy.addView(title);
        String detail = follow ? "由系统决定用哪张卡拨出"
                : (sim.reverseLookupOk ? "可被系统准确识别" : "识别受限：无法反查该卡");
        copy.addView(description(detail), matchWithTop(2));
        item.addView(copy, weighted());
        if (selected) item.addView(label("当前", 12, PRIMARY, Gravity.CENTER));

        item.setClickable(true);
        item.setOnClickListener(ignored -> {
            DutyStore.setDutySlot(this, slot);
            BridgeLog.info("duty_sim_selected slot=" + slot);
            showPage(Page.DUTY);
            toast(slot == DutyStore.SLOT_FOLLOW_SYSTEM ? "已跟随系统默认卡" : "值守卡已设为卡" + (slot + 1));
        });
        return item;
    }

    private TextView dangerButton(String title) {
        TextView button = label(title, 16, Color.WHITE, Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(54));
        button.setPadding(dp(16), dp(13), dp(16), dp(13));
        button.setBackground(solidPill(DANGER, 27));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    /** 值守总开关：开即常驻转发，关即真正停服。 */
    private void toggleDuty() {
        boolean next = !DutyStore.isDutyOn(this);
        if (!next) {
            DutyStore.setDutyOn(this, false);
            StandaloneAgentService.stop(this);
            BridgeLog.info("duty_stopped");
            toast("已停止值守");
            showPage(Page.DUTY);
            return;
        }
        String[] missing = RuntimePermissionPolicy.missingPermissions(
                Build.VERSION.SDK_INT,
                checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED,
                checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED,
                checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED,
                checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED,
                checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED,
                checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED,
                Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED);
        DutyStore.setDutyOn(this, true);
        if (missing.length > 0) {
            requestPermissions(missing, 101);
            toast("已开启值守，请先授予剩余权限");
            showPage(Page.DUTY);
            return;
        }
        StandaloneAgentService.start(this);
        refreshStatus();
        if (!dialerRoleHeld()) requestDialerRole();
        BridgeLog.info("duty_started");
        toast("已开始值守");
        showPage(Page.DUTY);
    }

    /** 「更多」：通话测试 / 设置 / 诊断收进次级分段，不再占用底部导航。 */
    private View morePage() {
        LinearLayout root = pageRoot();
        root.addView(pageTitle("更多", null));

        LinearLayout segments = row();
        segments.setGravity(Gravity.CENTER);
        segments.setPadding(dp(4), dp(4), dp(4), dp(4));
        segments.setBackground(neuDrawable("neu_bar_bg"));
        moreSectionItems.clear();
        segments.addView(moreSegmentItem("通话", MoreSection.CALLS), weighted());
        segments.addView(moreSegmentItem("设置", MoreSection.SETTINGS), weighted());
        segments.addView(moreSegmentItem("诊断", MoreSection.DEBUG), weighted());
        root.addView(segments, matchWithTop(12));

        moreHost = new FrameLayout(this);
        root.addView(moreHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private TextView moreSegmentItem(String title, MoreSection section) {
        TextView item = label(title, 14, MUTED, Gravity.CENTER);
        item.setPadding(dp(10), dp(9), dp(10), dp(9));
        item.setClickable(true);
        item.setTag(section);
        item.setOnClickListener(ignored -> showMoreSection(section));
        moreSectionItems.add(item);
        return item;
    }

    private View statusPage() {
        LinearLayout root = pageRoot();
        root.addView(pageTitle("余奉电话助理", "运行中"));

        boolean paired = VoWLANPairingStore.configured(this);
        LinearLayout hero = card(SURFACE_HIGH, 24);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(18), dp(20), dp(18), dp(20));
        ImageView wifi = icon(android.R.drawable.ic_menu_share, CYAN, 54);
        wifi.setBackground(neuInsetDrawable(28));
        wifi.setPadding(dp(14), dp(14), dp(14), dp(14));
        hero.addView(wifi, new LinearLayout.LayoutParams(dp(58), dp(58)));
        LinearLayout heroCopy = column(dp(14));
        TextView heroTitle = label(
                paired ? (vowlanReady() ? "VoWLAN 已就绪" : "VoWLAN 准备中") : "VoWLAN 尚未配对",
                25, vowlanReady() ? CYAN : WARNING, Gravity.START);
        heroTitle.setTypeface(Typeface.DEFAULT_BOLD);
        heroCopy.addView(heroTitle);
        heroCopy.addView(label(vowlanNetworkLine(), 14, MUTED, Gravity.START));
        hero.addView(heroCopy, weighted());
        hero.setClickable(true);
        hero.setOnClickListener(ignored -> showFeatureDetails(Feature.VOWLAN));
        root.addView(hero, matchWithTop(16));

        String connectionMode = MainScreenPresentation.connectionModeTitle(
                vowlanReady(), cloudEnabled, cloudRelayConfigured, cloudLastError);
        String connectionDetail = MainScreenPresentation.connectionModeDetail(
                vowlanReady(), cloudEnabled, cloudRelayConfigured, cloudLastError);
        int connectionColor = vowlanReady() ? CYAN
                : cloudEnabled && cloudRelayConfigured && cloudLastError.isEmpty() ? BLUE : WARNING;
        LinearLayout connectionCard = card(SURFACE, 18);
        connectionCard.setOrientation(LinearLayout.VERTICAL);
        connectionCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        connectionCard.addView(overline("当前连接模式"));
        TextView connectionTitle = label(connectionMode, 18, connectionColor, Gravity.START);
        connectionTitle.setTypeface(Typeface.DEFAULT_BOLD);
        connectionCard.addView(connectionTitle, matchWithTop(5));
        connectionCard.addView(label(connectionDetail, 13, MUTED, Gravity.START), matchWithTop(5));
        root.addView(connectionCard, matchWithTop(12));

        LinearLayout firstHealthRow = row();
        LinearLayout agentTile = healthTile(
                RuntimeMode.agentLabel(this), agentOnline ? "在线" : agentDetail,
                android.R.drawable.ic_menu_manage, agentOnline);
        agentTile.setOnClickListener(ignored -> showFeatureDetails(Feature.AGENT));
        firstHealthRow.addView(agentTile, weightedWithEnd(5));
        LinearLayout pcmTile = healthTile(
                "PCM 音频桥", pcmReady() ? "就绪" : "待启动",
                android.R.drawable.ic_media_play, pcmReady());
        pcmTile.setOnClickListener(ignored -> showFeatureDetails(Feature.PCM));
        firstHealthRow.addView(pcmTile, weightedWithStart(5));
        root.addView(firstHealthRow, matchWithTop(12));

        LinearLayout secondHealthRow = row();
        LinearLayout pushTile = healthTile(
                "Push / Relay", pushDetail,
                android.R.drawable.stat_notify_sync, pushConfigured);
        pushTile.setOnClickListener(ignored -> showFeatureDetails(Feature.PUSH));
        secondHealthRow.addView(pushTile, weightedWithEnd(5));
        LinearLayout shizukuTile = healthTile(
                "Shizuku", shizukuReady() ? "已授权" : "待授权",
                android.R.drawable.ic_lock_idle_lock, shizukuReady());
        shizukuTile.setOnClickListener(ignored -> showFeatureDetails(Feature.SHIZUKU));
        secondHealthRow.addView(shizukuTile, weightedWithStart(5));
        root.addView(secondHealthRow, matchWithTop(10));

        root.addView(modeSelector(), matchWithTop(14));

        TextView pairing = primaryButton(paired ? "重新配对 iPhone" : "配对新 iPhone", BLUE);
        pairing.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_input_add, 0, 0, 0);
        pairing.setCompoundDrawableTintList(ColorStateList.valueOf(Color.WHITE));
        pairing.setCompoundDrawablePadding(dp(8));
        pairing.setOnClickListener(ignored -> openPairingPage());
        root.addView(pairing, matchWithTop(14));

        root.addView(sectionTitle("最近活动"), matchWithTop(22));
        LinearLayout recent = card(SURFACE, 18);
        recent.setPadding(dp(16), dp(14), dp(16), dp(14));
        recent.setOrientation(LinearLayout.HORIZONTAL);
        recent.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        dot.setBackground(solidPill(PRIMARY, 7));
        recent.addView(dot, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView activity = label(latestActivity(), 14, TEXT, Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams activityParams = weighted();
        activityParams.leftMargin = dp(12);
        recent.addView(activity, activityParams);
        recent.setOnClickListener(ignored -> openMore(MoreSection.DEBUG));
        root.addView(recent, matchWithTop(10));

        status = label("", 12, MUTED, Gravity.START);
        status.setVisibility(View.GONE);
        root.addView(status);
        return scroll(root);
    }

    private View callsPage() {
        LinearLayout root = pageRoot();
        root.addView(pageTitle("通话控制", null));
        root.addView(description("本机 Telecom 本机控制；iPhone 通话优先使用 VoWLAN，局域网不可用时由云端中继。"), matchWithTop(8));

        LinearLayout dialCard = card(SURFACE, 24);
        dialCard.setOrientation(LinearLayout.VERTICAL);
        dialCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        dialCard.addView(overline("拨号号码"));
        number = input("输入电话号码", InputType.TYPE_CLASS_PHONE);
        dialCard.addView(number, matchWithTop(8));
        TextView call = primaryButton("通过系统 Telecom 拨号", PRIMARY);
        call.setTextColor(BG);
        call.setOnClickListener(ignored -> placeSystemCall());
        dialCard.addView(call, matchWithTop(14));
        root.addView(dialCard, matchWithTop(18));

        root.addView(sectionTitle("当前通话"), matchWithTop(22));
        LinearLayout actions = row();
        TextView answer = secondaryButton("本机接听");
        answer.setOnClickListener(ignored -> localCall("answer"));
        TextView hangup = secondaryButton("挂断通话");
        hangup.setTextColor(DANGER);
        hangup.setOnClickListener(ignored -> localCall("end"));
        actions.addView(answer, weightedWithEnd(5));
        actions.addView(hangup, weightedWithStart(5));
        root.addView(actions, matchWithTop(10));

        LinearLayout roleCard = card(SURFACE, 20);
        roleCard.setOrientation(LinearLayout.VERTICAL);
        roleCard.setPadding(dp(16), dp(14), dp(16), dp(14));
        roleCard.addView(label(roleText(), 15, TEXT, Gravity.START));
        roleCard.addView(description("紧急呼叫始终由系统预装拨号器处理。"), matchWithTop(4));
        roleCard.setOnClickListener(ignored -> requestDialerRole());
        root.addView(roleCard, matchWithTop(18));
        return scroll(root);
    }

    private View debugPage() {
        LinearLayout root = pageRoot();
        root.addView(pageTitle("Debug 诊断", null));
        root.addView(debugFilters(), matchWithTop(14));

        LinearLayout logCard = card(SURFACE, 22);
        logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(dp(12), dp(4), dp(12), dp(4));
        debugRows = column(0);
        lastRenderedDebugLog = null;
        lastRenderedDebugFilter = null;
        logCard.addView(debugRows);
        root.addView(logCard, matchWithTop(14));

        LinearLayout actions = row();
        TextView copy = secondaryButton("复制日志");
        copy.setOnClickListener(ignored -> copyDebugLog());
        TextView export = secondaryButton("导出诊断");
        export.setOnClickListener(ignored -> exportDebugLog());
        actions.addView(copy, weightedWithEnd(5));
        actions.addView(export, weightedWithStart(5));
        root.addView(actions, matchWithTop(12));

        LinearLayout live = card(SURFACE, 20);
        live.setOrientation(LinearLayout.HORIZONTAL);
        live.setGravity(Gravity.CENTER_VERTICAL);
        live.setPadding(dp(16), dp(14), dp(12), dp(14));
        LinearLayout liveCopy = column(0);
        liveCopy.addView(label("实时记录", 15, TEXT, Gravity.START));
        liveCopy.addView(label("自动刷新最新的日志信息", 12, MUTED, Gravity.START));
        live.addView(liveCopy, weighted());
        Switch toggle = new Switch(this);
        toggle.setChecked(realtimeLogging);
        toggle.setThumbTintList(ColorStateList.valueOf(PRIMARY));
        toggle.setOnCheckedChangeListener((ignored, enabled) -> {
            realtimeLogging = enabled;
            handler.removeCallbacks(debugRefresh);
            if (enabled) handler.post(debugRefresh);
        });
        live.addView(toggle);
        root.addView(live, matchWithTop(12));

        LinearLayout destructive = row();
        TextView refresh = secondaryButton("立即刷新");
        refresh.setOnClickListener(ignored -> refreshDebugLog());
        TextView clear = secondaryButton("清空日志");
        clear.setTextColor(DANGER);
        clear.setOnClickListener(ignored -> { BridgeLog.clear(); refreshDebugLog(); });
        destructive.addView(refresh, weightedWithEnd(5));
        destructive.addView(clear, weightedWithStart(5));
        root.addView(destructive, matchWithTop(10));
        refreshDebugLog();
        return scroll(root);
    }

    private View settingsPage() {
        LinearLayout root = pageRoot();
        root.addView(pageTitle("设置", null));

        boolean standaloneRuntime = RuntimeMode.isStandalone(this);
        root.addView(sectionTitle(RuntimeMode.agentLabel(this)), matchWithTop(18));
        LinearLayout agentCard = card(SURFACE, 22);
        agentCard.setOrientation(LinearLayout.VERTICAL);
        agentCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        if (standaloneRuntime) {
            agentCard.addView(label("Agent 运行在本 APK 的前台服务中，不使用 AVF、Linux Terminal、8575 或 8576。",
                    14, TEXT, Gravity.START));
        } else {
            endpoint = input("Agent 地址", InputType.TYPE_CLASS_TEXT);
            endpoint.setText(AppConfig.endpoint(this));
            agentCard.addView(endpoint);
            token = input("控制令牌", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            token.setText(AppConfig.token(this));
            agentCard.addView(token, matchWithTop(10));
        }
        selectedMode = AppConfig.mode(this);
        agentCard.addView(modeSelector(), matchWithTop(12));
        TextView save = primaryButton(standaloneRuntime ? "保存模式并启动内置 Agent" : "保存配置并启动守护", BLUE);
        save.setOnClickListener(ignored -> saveConfiguration());
        agentCard.addView(save, matchWithTop(12));
        root.addView(agentCard, matchWithTop(10));

        // 双卡设置：手动配置 SIM 1 / SIM 2 号码（自动检测失败时的兜底）
        root.addView(sectionTitle("双卡设置"), matchWithTop(22));
        LinearLayout simCard = card(SURFACE, 22);
        simCard.setOrientation(LinearLayout.VERTICAL);
        simCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        simCard.addView(label("自动检测不到双卡时，手动填写号码，iPhone 端会显示两张卡。",
                13, MUTED, Gravity.START));
        EditText sim1Input = input("SIM 1 号码", InputType.TYPE_CLASS_PHONE);
        sim1Input.setText(AppConfig.sim1Number(this));
        simCard.addView(sim1Input, matchWithTop(10));
        EditText sim2Input = input("SIM 2 号码", InputType.TYPE_CLASS_PHONE);
        sim2Input.setText(AppConfig.sim2Number(this));
        simCard.addView(sim2Input, matchWithTop(10));
        TextView simSave = primaryButton("保存号码", BLUE);
        TextView simStatus = label("", 13, MUTED, Gravity.START);
        Runnable refreshSimStatus = () -> {
            String s1 = AppConfig.sim1Number(this);
            String s2 = AppConfig.sim2Number(this);
            String json;
            try {
                json = AndroidAgentIdentity.simCards(this).toString();
            } catch (Exception e) {
                json = "[]";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("当前已保存：");
            sb.append("SIM 1=").append(s1.isEmpty() ? "（未填）" : s1);
            sb.append("  SIM 2=").append(s2.isEmpty() ? "（未填）" : s2);
            sb.append("\n上报 JSON：").append(json);
            try {
                org.json.JSONObject r = new StandaloneAgentStore(this).registration();
                String did = r.optString("device_id", "");
                sb.append("\n设备ID：").append(did.isEmpty() ? "（未配对）" : did);
            } catch (Exception e) {
                sb.append("\n设备ID：（读取失败）");
            }
            simStatus.setText(sb.toString());
        };
        simSave.setOnClickListener(ignored -> {
            AppConfig.setSim1Number(this, sim1Input.getText().toString());
            AppConfig.setSim2Number(this, sim2Input.getText().toString());
            BridgeLog.info("sim_numbers_saved");
            refreshSimStatus.run();
            android.widget.Toast.makeText(this, "已保存，iPhone 端 30 秒内刷新", android.widget.Toast.LENGTH_SHORT).show();
            // 触发心跳上报，让 iPhone 尽快拿到新号码
            AgentWatchdogService.start(this);
        });
        simCard.addView(simSave, matchWithTop(12));
        simCard.addView(simStatus, matchWithTop(8));
        refreshSimStatus.run();
        root.addView(simCard, matchWithTop(10));

        // 云端 Relay 自定义地址
        root.addView(sectionTitle("云端 Relay"), matchWithTop(22));
        LinearLayout relayCard = card(SURFACE, 22);
        relayCard.setOrientation(LinearLayout.VERTICAL);
        relayCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        relayCard.addView(label("自定义 Relay 服务器地址（为空则使用配对时的地址）。修改后需重启 Agent 生效。",
                13, MUTED, Gravity.START));
        EditText relayInput = input("Relay 地址 (https://...)", InputType.TYPE_CLASS_TEXT);
        relayInput.setText(AppConfig.customRelayUrl(this));
        relayCard.addView(relayInput, matchWithTop(10));
        TextView relaySave = primaryButton("保存 Relay 地址", BLUE);
        relaySave.setOnClickListener(ignored -> {
            AppConfig.setCustomRelayUrl(this, relayInput.getText().toString());
            BridgeLog.info("custom_relay_url_saved");
            AgentWatchdogService.start(this);
        });
        relayCard.addView(relaySave, matchWithTop(12));
        root.addView(relayCard, matchWithTop(10));

		if (!standaloneRuntime) {
		root.addView(sectionTitle("AVF Linux 首次安装"), matchWithTop(22));
		LinearLayout bootstrapCard = card(SURFACE, 22);
		bootstrapCard.setOrientation(LinearLayout.VERTICAL);
		bootstrapCard.setPadding(dp(16), dp(16), dp(16), dp(16));
		AVFEnvironmentDetector.Snapshot avf = AVFEnvironmentDetector.inspect(this);
		bootstrapCard.addView(label(avfEnvironmentSummary(avf), 14, TEXT, Gravity.START));
		TextView installCommand = label(AVFStartupPolicy.installCommand(), 12, MUTED, Gravity.START);
		installCommand.setTextIsSelectable(true);
		installCommand.setPadding(0, dp(10), 0, dp(4));
		bootstrapCard.addView(installCommand);
		LinearLayout bootstrapActions = row();
		TextView copyInstall = secondaryButton("复制一键安装命令");
		copyInstall.setOnClickListener(ignored -> copyAVFInstallCommand());
		TextView openLinux = secondaryButton(avf.state() == AVFStartupPolicy.State.RUNNING
				? "打开 Linux Terminal" : "启动 Linux Terminal");
		openLinux.setOnClickListener(ignored -> openAVFTerminalOrSettings());
		bootstrapActions.addView(copyInstall, weightedWithEnd(5));
		bootstrapActions.addView(openLinux, weightedWithStart(5));
		bootstrapCard.addView(bootstrapActions, matchWithTop(10));
		TextView developerOptions = secondaryButton("打开开发者选项");
		developerOptions.setOnClickListener(ignored -> AVFEnvironmentActions.openDeveloperOptions(this));
		bootstrapCard.addView(developerOptions, matchWithTop(10));
		root.addView(bootstrapCard, matchWithTop(10));

		root.addView(sectionTitle("AVF Agent 管理"), matchWithTop(22));
		LinearLayout installerCard = card(SURFACE, 22);
		installerCard.setOrientation(LinearLayout.VERTICAL);
		installerCard.setPadding(dp(16), dp(16), dp(16), dp(16));
		installerStatus = label("正在读取 installerd 状态…", 13, MUTED, Gravity.START);
		installerCard.addView(installerStatus);
		TextView updateAgent = primaryButton("检查并安装最新 Agent", BLUE);
		updateAgent.setOnClickListener(ignored -> runAgentMaintenance("update"));
		installerCard.addView(updateAgent, matchWithTop(12));
		LinearLayout installerActions = row();
		TextView repairAgent = secondaryButton("修复当前版本");
		repairAgent.setOnClickListener(ignored -> runAgentMaintenance("repair"));
		TextView rollbackAgent = secondaryButton("回滚上一版本");
		rollbackAgent.setTextColor(WARNING);
		rollbackAgent.setOnClickListener(ignored -> runAgentMaintenance("rollback"));
		installerActions.addView(repairAgent, weightedWithEnd(5));
		installerActions.addView(rollbackAgent, weightedWithStart(5));
		installerCard.addView(installerActions, matchWithTop(10));
		root.addView(installerCard, matchWithTop(10));
		refreshInstallerStatus();
		}

        root.addView(sectionTitle("Shizuku 权限与 PCM 桥"), matchWithTop(22));
        LinearLayout shizukuCard = card(SURFACE, 22);
        shizukuCard.setOrientation(LinearLayout.VERTICAL);
        shizukuCard.setPadding(dp(16), dp(16), dp(16), dp(16));
        shizukuStatus = label("", 14, TEXT, Gravity.START);
        shizukuCard.addView(shizukuStatus);
        shizukuCard.addView(description("首次通过无线调试启动并授权；之后 余奉电话助理 会自动恢复 PCM 桥。"), matchWithTop(6));
        LinearLayout shizukuActions = row();
        TextView authorize = secondaryButton("授权并启动");
        authorize.setOnClickListener(ignored -> {
            ShizukuBridgeManager.get(this).requestPermission();
            refreshShizukuStatus();
        });
        TextView open = secondaryButton("打开 Shizuku");
        open.setOnClickListener(ignored -> {
            ShizukuBridgeManager.get(this).openShizuku();
            refreshShizukuStatus();
        });
        shizukuActions.addView(authorize, weightedWithEnd(5));
        shizukuActions.addView(open, weightedWithStart(5));
        shizukuCard.addView(shizukuActions, matchWithTop(12));
        root.addView(shizukuCard, matchWithTop(10));

        root.addView(sectionTitle("电话角色与诊断"), matchWithTop(22));
        TextView dialer = secondaryButton("设为默认电话应用");
        dialer.setOnClickListener(ignored -> requestDialerRole());
        root.addView(dialer, matchWithTop(10));
        TextView agent = secondaryButton("刷新 Agent 状态");
        agent.setOnClickListener(ignored -> refreshAgent(true));
        root.addView(agent, matchWithTop(10));
        agentRefreshResult = label(agentRefreshMessage, 12, agentRefreshColor, Gravity.START);
        agentRefreshResult.setPadding(dp(12), dp(8), dp(12), dp(8));
        agentRefreshResult.setBackground(neuInsetDrawable(12));
        root.addView(agentRefreshResult, matchWithTop(6));

        // 双卡选路的关键权限：授予后系统才会在拨号早期把去电重定向到指定 SIM。
        // 国产 ROM（TCL 等）会忽略 placeCall 的指定 SIM，必须靠这个角色才能稳定双卡。
        TextView redir = secondaryButton("授予双卡通话重定向权限");
        redir.setOnClickListener(ignored -> {
            boolean ok = CallRedirectionPolicy.requestRole(this);
            toast(ok
                    ? (CallRedirectionPolicy.isRoleHeld(this) ? "已获得通话重定向权限" : "已发起授权，请在系统弹窗中确认")
                    : "无法发起授权：该角色在当前设备可能不可用，请到 系统设置 → 应用 → 默认应用 → 通话重定向 中手动授予 余奉电话助理");
        });
        root.addView(redir, matchWithTop(10));
        root.addView(description(CallRedirectionPolicy.isRoleHeld(this)
                ? "双卡通话重定向：已授予"
                : "双卡通话重定向：未授予（双卡选路可能走默认卡，建议授予）"), matchWithTop(6));

        CheckBox debugEnabled = new CheckBox(this);
        debugEnabled.setText("启用 Debug 模式");
        debugEnabled.setTextColor(TEXT);
        debugEnabled.setButtonTintList(ColorStateList.valueOf(PRIMARY));
        debugEnabled.setChecked(AppConfig.debugEnabled(this));
        debugEnabled.setOnCheckedChangeListener((ignored, enabled) -> {
            BridgeLog.setDebugEnabled(this, enabled);
            toast(enabled ? "Debug 日志已开启" : "Debug 日志已关闭");
        });
        root.addView(debugEnabled, matchWithTop(14));
        refreshShizukuStatus();
        return scroll(root);
    }

    private void openPairingPage() {
        handler.removeCallbacks(debugRefresh);
        bottomBar.setVisibility(View.GONE);
        contentHost.removeAllViews();
        contentHost.addView(pairingPage(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        startPairing();
    }

    private View pairingPage() {
        LinearLayout root = pageRoot();
        TextView back = label("‹", 40, TEXT, Gravity.START);
        back.setContentDescription("返回");
        back.setOnClickListener(ignored -> {
            stopPairingServer();
            showPage(Page.DUTY);
        });
        root.addView(back, new LinearLayout.LayoutParams(dp(56), dp(52)));

        TextView title = label("一次性配对", 30, TEXT, Gravity.START);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, matchWithTop(8));
        root.addView(description("让 iPhone 与本机连接同一 Wi‑Fi 或手机热点"), matchWithTop(6));

        LinearLayout pairingCard = card(SURFACE, 28);
        pairingCard.setOrientation(LinearLayout.VERTICAL);
        pairingCard.setGravity(Gravity.CENTER_HORIZONTAL);
        pairingCard.setPadding(dp(20), dp(24), dp(20), dp(24));
        pairingCard.addView(label("配对码（一次性）", 14, MUTED, Gravity.CENTER));
        pairingCodeView = label(
                MainScreenPresentation.formatPairingCode(pairingCode), 42, TEXT, Gravity.CENTER);
        pairingCodeView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        pairingCodeView.setLetterSpacing(0.05f);
        pairingCodeView.setBackground(neuInsetDrawable(18));
        pairingCodeView.setPadding(dp(18), dp(18), dp(18), dp(18));
        pairingCard.addView(pairingCodeView, matchWithTop(18));

        pairingQrView = new ImageView(this);
        pairingQrView.setContentDescription("扫码配对二维码");
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(200), dp(200));
        qrParams.topMargin = dp(16);
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        pairingCard.addView(pairingQrView, qrParams);
        pairingCard.addView(label("iPhone 用 uPhone 扫码配对（或手动输入上方配对码）", 13, MUTED, Gravity.CENTER),
                matchWithTop(8));

        pairingCountdownView = label("正在生成安全密钥…", 14, BLUE, Gravity.CENTER);
        pairingCard.addView(pairingCountdownView, matchWithTop(16));

        ImageView link = icon(android.R.drawable.ic_menu_share, CYAN, 92);
        link.setPadding(dp(22), dp(22), dp(22), dp(22));
        pairingCard.addView(link, new LinearLayout.LayoutParams(dp(112), dp(112)));

        pairingStatus = label("正在检查局域网并生成临时密钥…", 15, TEXT, Gravity.CENTER);
        pairingStatus.setMaxLines(4);
        pairingCard.addView(pairingStatus, matchWithTop(10));
        root.addView(pairingCard, matchWithTop(20));

        TextView cancel = secondaryButton("取消配对");
        cancel.setTextColor(DANGER);
        cancel.setOnClickListener(ignored -> {
            stopPairingServer();
            showPage(Page.DUTY);
        });
        root.addView(cancel, matchWithTop(18));

        TextView note = label("临时密钥 · 仅可使用一次", 13, MUTED, Gravity.CENTER);
        note.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_lock_idle_lock, 0, 0, 0);
        note.setCompoundDrawableTintList(ColorStateList.valueOf(MUTED));
        note.setCompoundDrawablePadding(dp(8));
        root.addView(note, matchWithTop(20));
        return scroll(root);
    }

    private LinearLayout debugFilters() {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout filterRow = row();
        String[] filters = {"全部", "Agent", "VoWLAN", "PCM", "Telecom"};
        for (String filter : filters) {
            boolean selected = filter.equals(activeDebugFilter);
            TextView chip = label(filter, 13, selected ? Color.WHITE : TEXT, Gravity.CENTER);
            chip.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            chip.setPadding(dp(16), dp(9), dp(16), dp(9));
            chip.setBackground(selected
                    ? neuDrawable("neu_accent_green")
                    : neuRaisedDrawable(18));
            LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipParams.rightMargin = dp(8);
            filterRow.addView(chip, chipParams);
            chip.setOnClickListener(ignored -> {
                activeDebugFilter = filter;
                openMore(MoreSection.DEBUG);
            });
        }
        scroll.addView(filterRow);
        LinearLayout holder = column(0);
        holder.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return holder;
    }

    private View modeSelector() {
        selectedMode = selectedMode == null ? AppConfig.mode(this) : selectedMode;
        LinearLayout segment = row();
        segment.setPadding(dp(3), dp(3), dp(3), dp(3));
        segment.setBackground(neuInsetDrawable(24));
        segment.addView(segmentItem("远端静默", AppConfig.MODE_REMOTE_SILENT), weighted());
        segment.addView(segmentItem("本机显示", AppConfig.MODE_LOCAL_AND_PUSH), weighted());
        return segment;
    }

    private TextView segmentItem(String title, String mode) {
        boolean selected = mode.equals(selectedMode);
        TextView item = label(title, 14, selected ? PRIMARY : MUTED, Gravity.CENTER);
        item.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        item.setPadding(dp(8), dp(12), dp(8), dp(12));
        item.setBackground(selected ? neuRaisedDrawable(20) : solidPill(Color.TRANSPARENT, 20));
        item.setOnClickListener(ignored -> {
            selectedMode = mode;
            if (currentPage == Page.DUTY
                    || (currentPage == Page.MORE && currentMore == MoreSection.SETTINGS)) {
                showPage(currentPage);
            }
        });
        return item;
    }

    private LinearLayout healthTile(String title, String value, int iconResource, boolean healthy) {
        LinearLayout tile = card(SURFACE, 18);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(12), dp(15), dp(12), dp(15));
        ImageView icon = icon(iconResource, healthy ? PRIMARY : WARNING, 42);
        icon.setBackground(neuInsetDrawable(21));
        icon.setPadding(dp(10), dp(10), dp(10), dp(10));
        tile.addView(icon, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout copy = column(dp(10));
        TextView heading = label(title, 13, TEXT, Gravity.START);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(heading);
        TextView state = label(value, 13, healthy ? PRIMARY : WARNING, Gravity.START);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        state.setSingleLine(true);
        state.setEllipsize(TextUtils.TruncateAt.END);
        copy.addView(state, matchWithTop(4));
        tile.addView(copy, weighted());
        return tile;
    }

    private LinearLayout pageTitle(String title, String badge) {
        LinearLayout block = column(0);
        TextView heading = label(title, 30, TEXT, Gravity.START);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        block.addView(heading);
        if (badge != null) {
            TextView chip = label(badge, 13, PRIMARY, Gravity.CENTER);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(dp(12), dp(5), dp(12), dp(5));
            chip.setBackground(neuRaisedDrawable(16));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(6);
            block.addView(chip, params);
        }
        return block;
    }

    private void placeSystemCall() {
        String value = number == null ? "" : number.getText().toString().trim();
        if (value.isEmpty()) { toast("请输入号码"); return; }
        if (!dialerRoleHeld()) {
            toast("请先将 余奉电话助理 设为默认电话应用");
            requestDialerRole();
            return;
        }
        getSystemService(TelecomManager.class).placeCall(Uri.parse("tel:" + value), null);
    }

    private void continueStartupSetup() {
        ShizukuBridgeManager shizuku = ShizukuBridgeManager.get(this);
        switch (DialerRoleSetupPolicy.initial(
                dialerRoleHeld(), shizuku.dialerRoleAutomationAuthorized())) {
            case COMPLETE -> checkAVFEnvironmentAtStartup();
            case SHIZUKU -> attemptPrivilegedDialerRole(false);
            case SYSTEM_ROLE -> requestDialerRole();
            case SETTINGS -> openDefaultAppsSettings();
        }
    }

    private void requestDialerRole() {
        RoleManager manager = getSystemService(RoleManager.class);
        if (manager == null || !manager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            toast("设备不支持默认拨号器角色");
            continueAfterSystemRoleRequest();
            return;
        }
        try {
            startActivityForResult(manager.createRequestRoleIntent(RoleManager.ROLE_DIALER), ROLE_REQUEST);
        } catch (RuntimeException error) {
            BridgeLog.error("dialer_role_system_request_failed", error);
            continueAfterSystemRoleRequest();
        }
    }

    private void continueAfterSystemRoleRequest() {
        ShizukuBridgeManager shizuku = ShizukuBridgeManager.get(this);
        switch (DialerRoleSetupPolicy.afterSystemRequest(
                dialerRoleHeld(), shizuku.dialerRoleAutomationAvailable())) {
            case COMPLETE -> checkAVFEnvironmentAtStartup();
            case SHIZUKU -> attemptPrivilegedDialerRole(true);
            case SETTINGS -> openDefaultAppsSettings();
            case SYSTEM_ROLE -> requestDialerRole();
        }
    }

    private void attemptPrivilegedDialerRole(boolean requestPermission) {
        ShizukuBridgeManager.get(this).ensureDialerRole(requestPermission, (success, detail) -> {
            if (isFinishing() || isDestroyed()) return;
            refreshStatus();
            DialerRoleSetupPolicy.Action next = DialerRoleSetupPolicy.afterPrivilegedAttempt(
                    success && dialerRoleHeld());
            if (next == DialerRoleSetupPolicy.Action.COMPLETE) {
                toast("默认电话角色已由系统启用");
                checkAVFEnvironmentAtStartup();
            } else {
                toast(detail + "，请在默认应用中选择 余奉电话助理");
                openDefaultAppsSettings();
            }
        });
    }

    private void openDefaultAppsSettings() {
        Intent settings = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
        try {
            startActivityForResult(settings, DEFAULT_APPS_REQUEST);
        } catch (RuntimeException error) {
            BridgeLog.error("default_apps_settings_open_failed", error);
            Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(details, DEFAULT_APPS_REQUEST);
        }
    }

    private void localCall(String action) {
        if (!dialerRoleHeld()) {
            toast("当前通话属于其他默认电话应用；请先选择 余奉电话助理");
            requestDialerRole();
            return;
        }
        String callId = CallRepository.firstId();
        if (callId.isEmpty()) { toast("当前没有可控制的通话"); return; }
        Executors.newSingleThreadExecutor().execute(() -> {
            CallRepository.ActionResult result = CallRepository.execute(
                    MainActivity.this,
                    new AgentCommand(UUID.randomUUID().toString(), action, callId, ""),
                    getSystemService(TelecomManager.class));
            runOnUiThread(() -> toast(result.success ? "Telecom 已确认" : result.error));
        });
    }

    private void refreshAgent() {
        refreshAgent(false);
    }

    private void refreshAgent(boolean showResult) {
        final int generation = ++agentRefreshGeneration;
        agentDetail = "检测中";
        if (showResult) setAgentRefreshMessage("正在请求 " + AppConfig.endpoint(this)
                + " 的 Agent 状态…", MUTED);
        updateVisibleStatus();
        Executors.newSingleThreadExecutor().execute(() -> {
            String address = AppConfig.endpoint(this);
            String path = AppConfig.configured(this) ? "/api/android/status" : "/api/health";
            try {
                boolean paired = AppConfig.configured(this);
                AgentClient client = new AgentClient(this);
                String payload = paired ? client.status() : client.health();
                JSONObject result = new JSONObject(payload);
                boolean healthy = result.optBoolean("ok", false);
                String profile = result.optString("runtime_profile", "未知");
                String detail = paired
                        ? "接口：GET " + path + " · HTTP 200\nAgent：" + address
                                + "\n运行模式：" + profile
                                + " · 待执行命令：" + result.optInt("pending_commands", 0)
                                + "\n最近活动：" + result.optString("last_seen", "未记录")
                        : "接口：GET " + path + " · HTTP 200\nAgent：" + address
                                + "\n版本：" + result.optString("version", "未知")
                                + " · 运行模式：" + profile
                                + "\n控制令牌：尚未配置";
                BridgeLog.info("agent_status_refresh path=" + path + " ok=" + healthy
                        + " profile=" + profile + " endpoint=" + address);
                runOnUiThread(() -> {
                    if (generation != agentRefreshGeneration) return;
                    agentOnline = paired && healthy;
                    agentDetail = healthy
                            ? (paired ? "在线" : "可达 · 尚未配对控制令牌")
                            : "可达 · Agent 报告未就绪";
                    if (showResult) setAgentRefreshMessage(
                            (healthy ? "刷新成功" : "Agent 未就绪") + "\n" + detail,
                            healthy ? PRIMARY : WARNING);
                    updateVisibleStatus();
                });
                try {
                    JSONObject pushStatus = new JSONObject(client.pushStatus());
                    runOnUiThread(() -> {
                        if (generation != agentRefreshGeneration) return;
                        updatePushStatus(pushStatus);
                        updateVisibleStatus();
                    });
                } catch (Exception error) {
                    BridgeLog.error("push_status_refresh_failed endpoint=" + address, error);
                    String pushError = error.getClass().getSimpleName() + " · " + error.getMessage();
                    runOnUiThread(() -> {
                        if (generation != agentRefreshGeneration) return;
                        pushConfigured = false;
                        pushDetail = "状态不可读";
                        cloudRelayConfigured = false;
                        cloudLastError = DebugRedactor.sanitize(pushError);
                        pushDiagnostic = "读取 /api/push/status 失败："
                                + DebugRedactor.sanitize(pushError);
                        updateVisibleStatus();
                    });
                }
            } catch (Exception error) {
                BridgeLog.error("agent_status_refresh_failed path=" + path + " endpoint=" + address, error);
                runOnUiThread(() -> {
                    if (generation != agentRefreshGeneration) return;
                    agentOnline = false;
                    agentDetail = "离线 · " + error.getClass().getSimpleName();
                    pushConfigured = false;
                    pushDetail = "Agent 不可达";
                    cloudRelayConfigured = false;
                    cloudLastError = RuntimeMode.agentLabel(this) + "不可达";
                    pushDiagnostic = "先恢复" + RuntimeMode.agentLabel(this)
                            + "；当前无法查询 /api/push/status。";
                    if (showResult) setAgentRefreshMessage("刷新失败 · GET " + path
                            + "\nAgent：" + address + "\n原因："
                            + DebugRedactor.sanitize(error.getClass().getSimpleName() + " · "
                                    + String.valueOf(error.getMessage())), DANGER);
                    updateVisibleStatus();
                });
            }
        });
    }

    private void setAgentRefreshMessage(String message, int color) {
        agentRefreshMessage = message;
        agentRefreshColor = color;
        if (agentRefreshResult != null) {
            agentRefreshResult.setText(message);
            agentRefreshResult.setTextColor(color);
        }
    }

    private void updatePushStatus(JSONObject result) {
        boolean enabled = result.optBoolean("cloud_enabled", false);
        boolean calls = result.optBoolean("call_push_ready", false);
        boolean messages = result.optBoolean("message_push_ready", false);
        String relay = result.optString("relay_url", "");
        String lastError = result.optString("last_error", "");
        cloudEnabled = enabled;
        cloudRelayConfigured = enabled && result.optBoolean("configured", false) && !relay.isEmpty();
        cloudLastError = DebugRedactor.sanitize(lastError);
        pushConfigured = enabled && calls && messages;
        pushDetail = !enabled ? "云端已关闭" : pushConfigured ? "推送已配置" : "配置不完整";
        pushDiagnostic = "云端开关：" + (enabled ? "开启" : "关闭")
                + "\n来电 Push：" + (calls ? "已配置" : "未就绪")
                + " · 短信 Push：" + (messages ? "已配置" : "未就绪")
                + "\nRelay：" + (relay.isEmpty() ? "未配置" : relay)
                + "\nAPNs 环境：" + result.optString("environment", "未知")
                + " · WAN：" + result.optString("wan_interface", "未检测到")
                + "\n" + MainScreenPresentation.heartbeatLine(
                        result.optLong("last_heartbeat", 0), result.optInt("heartbeat_failures", 0))
                + (lastError.isEmpty() ? "" : "\n最近错误：" + DebugRedactor.sanitize(lastError))
                + "\n注意：已配置不等于公网 Relay 已验证可达。";
    }

    private void showFeatureDetails(Feature feature) {
        // 不用系统 AlertDialog：部分定制 ROM 上渲染为空白，改用 App 自有样式的自定义弹窗。
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(neuRaisedDrawable(18));
        root.setPadding(dp(20), dp(18), dp(20), dp(14));

        TextView titleView = label(featureTitle(feature), 18, TEXT, Gravity.START);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(titleView);

        TextView messageView = label(featureDetails(feature, ""), 13, TEXT, Gravity.START);
        messageView.setLineSpacing(dp(3), 1.0f);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(messageView,
                new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300));
        scrollParams.topMargin = dp(10);
        root.addView(scroll, scrollParams);

        LinearLayout buttons = row();
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(12), 0, 0);
        android.widget.Button recheck = new android.widget.Button(this);
        recheck.setText("重新检查");
        recheck.setOnClickListener(ignored -> {
            dialog.dismiss();
            showFeatureDetails(feature);
        });
        android.widget.Button close = new android.widget.Button(this);
        close.setText("关闭");
        close.setOnClickListener(ignored -> dialog.dismiss());
        buttons.addView(recheck);
        buttons.addView(close);
        root.addView(buttons);

        dialog.setContentView(root);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.92),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        }
        final TextView liveMessageView = messageView;
        final android.app.Dialog liveDialog = dialog;
        dialog.show();
        if (feature != Feature.AGENT && feature != Feature.PUSH) return;
        Executors.newSingleThreadExecutor().execute(() -> {
            String live;
            try {
                AgentClient client = new AgentClient(this);
                JSONObject result = new JSONObject(feature == Feature.PUSH
                        ? client.pushStatus()
                        : AppConfig.configured(this) ? client.status() : client.health());
                if (feature == Feature.PUSH) {
                    String cloudLogs;
                    try {
                        cloudLogs = cloudAgentLogs(new JSONObject(client.debugSnapshot()));
                    } catch (Exception error) {
                        cloudLogs = "Agent 云端事件读取失败：" + DebugRedactor.sanitize(
                                error.getClass().getSimpleName() + " · " + error.getMessage());
                    }
                    String liveCloudLogs = cloudLogs;
                    runOnUiThread(() -> {
                        updatePushStatus(result);
                        updateVisibleStatus();
                        if (liveDialog.isShowing()) liveMessageView.setText(featureDetails(feature,
                                "实时查询：/api/push/status · HTTP 200\n\nAgent 云端事件（新到旧）\n"
                                        + liveCloudLogs));
                    });
                    return;
                }
                live = "实时查询：" + (AppConfig.configured(this) ? "/api/android/status" : "/api/health")
                        + " · HTTP 200\n返回：ok=" + result.optBoolean("ok", false)
                        + " · runtime_profile=" + result.optString("runtime_profile", "未知")
                        + (AppConfig.configured(this)
                            ? " · pending_commands=" + result.optInt("pending_commands", 0)
                            : " · version=" + result.optString("version", "未知"));
            } catch (Exception error) {
                live = "实时查询失败：" + DebugRedactor.sanitize(
                        error.getClass().getSimpleName() + " · " + error.getMessage());
                BridgeLog.error("feature_diagnostic_failed feature=" + feature, error);
            }
            String outcome = live;
            runOnUiThread(() -> {
                if (liveDialog.isShowing()) liveMessageView.setText(featureDetails(feature, outcome));
            });
        });
    }

    private String cloudAgentLogs(JSONObject snapshot) {
        JSONArray events = snapshot.optJSONArray("events");
        if (events == null) return "Agent 未返回事件列表";
        StringBuilder output = new StringBuilder();
        int count = 0;
        for (int index = events.length() - 1; index >= 0 && count < 10; index--) {
            JSONObject event = events.optJSONObject(index);
            if (event == null) continue;
            String category = event.optString("category", "");
            if (!category.equals("push") && !category.startsWith("cloud")) continue;
            if (output.length() > 0) output.append('\n');
            output.append(DebugRedactor.sanitize(event.optString("timestamp", "")))
                    .append(" · ").append(category)
                    .append(" · ").append(DebugRedactor.sanitize(event.optString("summary", "")));
            String payload = event.optString("payload", "");
            if (!payload.isEmpty()) output.append("\n  ").append(
                    payload.contains("://") ? "[可能包含媒体凭据，链接详情已省略]"
                            : DebugRedactor.sanitize(payload));
            count++;
        }
        return output.length() == 0 ? "暂无 Push / Cloud 事件" : output.toString();
    }

    private String featureTitle(Feature feature) {
        return switch (feature) {
            case VOWLAN -> "VoWLAN 连接详情";
            case AGENT -> RuntimeMode.agentLabel(this) + " 详情";
            case PCM -> "PCM 音频桥详情";
            case PUSH -> "Push / Relay 详情";
            case SHIZUKU -> "Shizuku 详情";
        };
    }

    private String featureDetails(Feature feature, String live) {
        String log = BridgeLog.read();
        String latestVoWLAN = MainScreenPresentation.latestContaining(log, "vowlan_state");
        String currentVoWLAN = VoWLANGatewayService.currentDiagnosticState();
        String details;
        String[] keywords;
        switch (feature) {
            case VOWLAN -> {
                boolean paired = VoWLANPairingStore.configured(this);
                String address = localVoWLANAddress();
                String pcmBackend = RuntimeMode.isStandalone(this) ? "本机 PCM 端口 7580" : "AVF PCM 端口 7580";
                String reason = !paired ? "尚未与 iPhone 配对"
                        : address.isEmpty() ? "没有可用的同网 Wi-Fi 或手机热点地址"
                        : currentVoWLAN.contains("avf=missing") ? "AVF 虚拟网卡未出现"
                        : currentVoWLAN.contains("agent_ready=false") ? "Agent 未通过 VoWLAN 就绪检查"
                        : currentVoWLAN.contains("pcm_ready=false") ? pcmBackend + "未就绪"
                        : !vowlanReady() ? "VoWLAN 服务尚未完成当前检查：" + currentVoWLAN
                        : "当前检查项满足；仍需实际通话验证";
                details = "配对：" + (paired ? "已保存密钥" : "未配对")
                        + "\n本机局域网：" + (address.isEmpty() ? "未发现" : address)
                        + "\n状态原因：" + reason
                        + "\n当前检查：" + currentVoWLAN
                        + "\n最近检查：" + (latestVoWLAN.isEmpty() ? "无" : latestVoWLAN);
                keywords = new String[]{"vowlan", "pairing"};
            }
            case AGENT -> {
                boolean standalone = RuntimeMode.isStandalone(this);
                details = "状态：" + agentDetail
                        + (standalone ? "\n运行位置：本 APK 前台服务"
                                : "\n地址：" + AppConfig.endpoint(this)
                                + "\n控制令牌：" + (AppConfig.configured(this) ? "已配置" : "未配置"))
                        + "\n原因：" + (agentOnline ? "最近一次状态检查通过"
                            : standalone ? "检查前台服务、通知权限与系统省电限制"
                            : "检查 AVF Linux 是否运行、地址是否为 :8575，以及控制令牌是否匹配")
                        + "\n" + agentRefreshMessage;
                keywords = new String[]{"agent", "watchdog", "http_request", "avf"};
            }
            case PCM -> {
                String shizuku = ShizukuBridgeManager.get(this).status();
                String pcmBackend = RuntimeMode.isStandalone(this) ? "本机 PCM 端口 7580" : "AVF PCM 端口 7580";
                String reason = !shizukuReady() ? "Shizuku 未连接或未授权，PCM 用户服务不可用"
                        : currentVoWLAN.contains("pcm_ready=false") ? pcmBackend + "未响应"
                        : !pcmReady() ? "VoWLAN PCM 服务尚未完成当前检查：" + currentVoWLAN
                        : "当前检查项满足；仍需实际通话验证双向音频";
                details = "Shizuku：" + shizuku + "\n桥状态：" + (pcmReady() ? "就绪" : "待启动")
                        + "\n状态原因：" + reason
                        + "\n当前 VoWLAN 检查：" + currentVoWLAN
                        + "\n最近 VoWLAN 检查：" + (latestVoWLAN.isEmpty() ? "无" : latestVoWLAN);
                keywords = new String[]{"pcm", "shizuku", "vowlan_state"};
            }
            case PUSH -> {
                details = "状态：" + pushDetail + "\n" + pushDiagnostic
                        + "\n原因：" + (agentOnline ? "请核对开关、Relay 地址、APNs token 与最近错误"
                            : "Agent 不可达时无法确认云端注册或心跳");
                keywords = new String[]{"push", "relay", "pairing"};
            }
            case SHIZUKU -> {
                String shizuku = ShizukuBridgeManager.get(this).status();
                details = "状态：" + shizuku + "\n原因：" + (shizukuReady()
                        ? "Binder 已连接；是否有声音仍需检查 PCM 路由"
                        : "检查 Shizuku 是否运行、余奉电话助理 授权及 PCM 用户服务是否成功绑定");
                keywords = new String[]{"shizuku", "local_output"};
            }
            default -> throw new IllegalStateException("未知功能");
        }
        String recent = MainScreenPresentation.recentMatchingLogLines(log, 12, keywords);
        return details + (live.isEmpty() ? "" : "\n\n" + live)
                + "\n\n相关日志（新到旧）\n" + recent;
    }

	private void checkAVFEnvironmentAtStartup() {
		if (RuntimeMode.isStandalone(this)) {
			StandaloneAgentService.start(this);
			return;
		}
		if (avfStartupPromptShown) return;
		AVFEnvironmentDetector.Snapshot snapshot = AVFEnvironmentDetector.inspect(this);
		boolean agentConfigured = AppConfig.configured(this);
		if (!AVFStartupPolicy.shouldPrompt(snapshot.state(), agentConfigured)) return;
		avfStartupPromptShown = true;
		boolean needsInstall = snapshot.state() == AVFStartupPolicy.State.RUNNING && !agentConfigured;
		boolean canLaunch = snapshot.state() == AVFStartupPolicy.State.READY_TO_START;
		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(needsInstall ? "配置 余奉电话助理 AVF Agent" : "需要启动 AVF Linux 环境")
				.setMessage(avfEnvironmentSummary(snapshot)
						+ (needsInstall
						? "\n\n若尚未安装，复制首次安装命令到 Linux Terminal；" +
								"若 Agent 已运行，先执行 sudo airsim-avf-pair 配对并检查 installerd。"
						: "\n\n启动 Linux 后，在 Terminal 中粘贴 余奉电话助理 一键安装命令。"))
				.setPositiveButton(needsInstall ? "复制首次安装命令"
						: canLaunch ? "启动 Linux Terminal" : "打开开发者选项",
						(ignored, which) -> {
							if (needsInstall) copyAVFInstallCommand();
							else if (canLaunch) openAVFTerminalOrSettings();
							else AVFEnvironmentActions.openDeveloperOptions(this);
						})
				.setNeutralButton(needsInstall ? "打开 Linux Terminal" : "复制安装命令",
						(ignored, which) -> {
							if (needsInstall) openAVFTerminalOrSettings();
							else copyAVFInstallCommand();
						})
				.setNegativeButton("稍后", null)
				.create();
		dialog.show();
	}

	private String avfEnvironmentSummary(AVFEnvironmentDetector.Snapshot snapshot) {
		return switch (snapshot.state()) {
			case RUNNING -> "AVF Linux 已启动，可连接 Agent。";
			case READY_TO_START -> "系统已提供 AVF 和 Linux Terminal，但虚拟 Linux 尚未启动。";
			case TERMINAL_DISABLED -> "系统包含 Linux Terminal，但当前被开发者选项停用。";
			case TERMINAL_MISSING -> snapshot.restrictedChinaOEM()
					? "当前国产 ROM 未提供 AOSP Linux Terminal 组件；余奉电话助理 无法用普通应用权限强制补装或开启。"
					: "系统声明支持 AVF，但未安装 AOSP Linux Terminal 组件。";
			case AVF_UNSUPPORTED -> snapshot.restrictedChinaOEM()
					? "当前国产 ROM 没有向第三方应用公开 AVF Linux 能力，无法由余奉电话助理强制开启。"
					: "当前系统没有公开 AVF 能力，请检查系统版本和厂商支持。";
		};
	}

	private void openAVFTerminalOrSettings() {
		if (!AVFEnvironmentActions.openTerminal(this)) {
			AVFEnvironmentActions.openDeveloperOptions(this);
			toast("未找到可启动的 Linux Terminal，请在开发者选项中启用");
		}
	}

	private void copyAVFInstallCommand() {
		getSystemService(ClipboardManager.class).setPrimaryClip(
				ClipData.newPlainText("余奉电话助理 AVF 一键安装", AVFStartupPolicy.installCommand()));
		toast("AVF 一键安装命令已复制");
	}

	private void refreshInstallerStatus() {
		if (installerStatus == null) return;
		if (!AppConfig.configured(this)) {
			installerStatus.setText("尚未配对控制令牌。先在 AVF Linux Terminal 完成首次签名安装，" +
					"再运行 sudo airsim-avf-pair，将令牌保存到上方配置。" +
					"已有 Agent 但安装服务不可用时，先在 Terminal 检查 airsim-installerd 服务，不要清除 Linux 数据。");
			return;
		}
		Executors.newSingleThreadExecutor().execute(() -> {
			try {
				String value = new AgentReleaseManager(this).status();
				runOnUiThread(() -> {
					if (installerStatus != null) installerStatus.setText("installerd 在线\n" + value);
				});
			} catch (Exception error) {
				runOnUiThread(() -> {
					if (installerStatus != null) installerStatus.setText("installerd 不可用 · " +
							error.getClass().getSimpleName() +
							"。请在 Linux Terminal 检查 sudo systemctl status airsim-installerd；" +
							"不要清除 Linux 数据。");
				});
			}
		});
	}

	private void runAgentMaintenance(String action) {
		if (!AppConfig.configured(this)) {
			if (installerStatus != null) installerStatus.setText(
					"尚未配对。先完成 AVF Linux 首次安装并运行 sudo airsim-avf-pair，" +
					"保存控制令牌后再更新、修复或回滚。");
			return;
		}
		if (installerStatus != null) installerStatus.setText("Agent 操作进行中 · " + action);
		Executors.newSingleThreadExecutor().execute(() -> {
			try {
				AgentReleaseManager manager = new AgentReleaseManager(this);
				String result = switch (action) {
					case "repair" -> manager.repairLatest();
					case "rollback" -> manager.rollback();
					default -> manager.installLatest();
				};
				runOnUiThread(() -> {
					if (installerStatus != null) installerStatus.setText("Agent 操作完成\n" + result);
					toast("AVF Agent 操作完成");
					refreshAgent();
				});
			} catch (Exception error) {
				BridgeLog.error("avf_agent_maintenance_failed action=" + action, error);
				runOnUiThread(() -> {
					if (installerStatus != null) installerStatus.setText("Agent 操作失败 · " + error.getMessage());
					toast("AVF Agent 操作失败");
				});
			}
		});
	}

    private void updateVisibleStatus() {
        if (status != null) status.setText(roleText() + "\nAgent：" + agentDetail);
        if (currentPage == Page.DUTY && contentHost != null
                && bottomBar.getVisibility() == View.VISIBLE) showPage(Page.DUTY);
    }

    private void startPairing() {
        stopPairingServer();
        if (pairingStatus != null) pairingStatus.setText("正在检查局域网并生成临时密钥…");
        pairingServer = new PairingServer(this, new PairingServer.Listener() {
            @Override public void onStarted(String code, String address, long expiresAtMillis, String qrPayload) {
                BridgeLog.info("pairing_started address=" + address + " expires_at=" + expiresAtMillis);
                pairingCode = code;
                pairingExpiresAt = expiresAtMillis;
                runOnUiThread(() -> {
                    if (pairingCodeView != null) pairingCodeView.setText(
                            MainScreenPresentation.formatPairingCode(code));
                    if (pairingQrView != null) {
                        android.graphics.Bitmap qr = QrCodeBitmap.encode(qrPayload, dp(200));
                        if (qr != null) pairingQrView.setImageBitmap(qr);
                    }
                    if (pairingStatus != null) pairingStatus.setText("等待 iPhone 连接…\n" + address);
                    startPairingCountdown();
                });
            }

            @Override public void onSucceeded(String agentResponse) {
                BridgeLog.info("pairing_completed");
                boolean localMode = false;
                try {
                    localMode = new JSONObject(agentResponse).optBoolean("local_mode", false);
                } catch (Exception ignored) {
                    // Older Agent responses do not carry a mode marker.
                }
                boolean pairedLocally = localMode;
                runOnUiThread(() -> {
                    cancelPairingCountdown();
                    if (pairingCodeView != null) {
                        pairingCodeView.setText("配对成功");
                        pairingCodeView.setTextSize(28);
                    }
                    if (pairingCountdownView != null) pairingCountdownView.setText("VoWLAN 密钥已安全保存");
                    if (pairingStatus != null) pairingStatus.setText(pairedLocally
                            ? "本地模式已启用，不使用 Relay 或 Push"
                            : "iOS Push 身份已写入 Android Agent");
                    refreshAgent();
                });
            }

            @Override public void onFailed(String message) {
                BridgeLog.error("pairing_failed", new IllegalStateException(message));
                runOnUiThread(() -> {
                    cancelPairingCountdown();
                    if (pairingCountdownView != null) pairingCountdownView.setText("配对窗口已关闭");
                    if (pairingStatus != null) pairingStatus.setText("配对失败：" + message);
                });
            }
        });
        pairingServer.start();
    }

    private void startPairingCountdown() {
        cancelPairingCountdown();
        long remaining = Math.max(0, pairingExpiresAt - System.currentTimeMillis());
        pairingCountdown = new CountDownTimer(remaining, 1_000) {
            @Override public void onTick(long millisUntilFinished) {
                long seconds = millisUntilFinished / 1_000;
                if (pairingCountdownView != null) pairingCountdownView.setText(
                        String.format(java.util.Locale.CHINA, "%02d:%02d 后失效", seconds / 60, seconds % 60));
            }

            @Override public void onFinish() {
                if (pairingCountdownView != null) pairingCountdownView.setText("配对窗口已失效");
            }
        }.start();
    }

    private void cancelPairingCountdown() {
        if (pairingCountdown != null) pairingCountdown.cancel();
        pairingCountdown = null;
    }

    private void stopPairingServer() {
        cancelPairingCountdown();
        PairingServer active = pairingServer;
        pairingServer = null;
        if (active != null) {
            try { active.close(); }
            catch (Exception error) { BridgeLog.error("pairing_close", error); }
        }
    }

    private void refreshStatus() {
        refreshShizukuStatus();
    }

    private void refreshShizukuStatus() {
        if (shizukuStatus != null) shizukuStatus.setText(ShizukuBridgeManager.get(this).status());
    }

    private void refreshDebugLog() {
        if (debugRows == null) return;
        String log = BridgeLog.read();
        if (log.equals(lastRenderedDebugLog) && activeDebugFilter.equals(lastRenderedDebugFilter)) return;
        lastRenderedDebugLog = log;
        lastRenderedDebugFilter = activeDebugFilter;
        debugRows.removeAllViews();
        String visible = MainScreenPresentation.recentLogLines(
                log, activeDebugFilter, 60);
        if (visible.startsWith("暂无") || visible.startsWith("此分类")) {
            TextView empty = description(visible);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(28), dp(8), dp(28));
            debugRows.addView(empty);
            return;
        }
        String[] lines = visible.split("\\R");
        for (int index = 0; index < lines.length; index++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(dp(2), dp(6), dp(2), dp(6));

            View dot = new View(this);
            dot.setBackground(solidPill(MainScreenPresentation.isErrorLog(lines[index]) ? DANGER : PRIMARY, 4));
            LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7));
            dotParams.topMargin = dp(5);
            row.addView(dot, dotParams);

            LinearLayout copy = column(dp(9));
            TextView metadata = label(MainScreenPresentation.logMetadata(lines[index]),
                    10, MUTED, Gravity.START);
            copy.addView(metadata);
            TextView title = label(MainScreenPresentation.activitySummary(lines[index]),
                    13, TEXT, Gravity.START);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            copy.addView(title, matchWithTop(1));
            TextView detail = label(MainScreenPresentation.eventBody(lines[index]),
                    11, MUTED, Gravity.START);
            detail.setTypeface(Typeface.MONOSPACE);
            copy.addView(detail, matchWithTop(1));
            row.addView(copy, weighted());
            debugRows.addView(row);

            if (index < lines.length - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(BORDER);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
                dividerParams.leftMargin = dp(18);
                debugRows.addView(divider, dividerParams);
            }
        }
    }

    private void copyDebugLog() {
        String value = BridgeLog.read();
        getSystemService(ClipboardManager.class).setPrimaryClip(
                ClipData.newPlainText("余奉电话助理 Debug Log", value));
        toast("日志已复制");
    }

    private void exportDebugLog() {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "余奉电话助理 Android 诊断");
        share.putExtra(Intent.EXTRA_TEXT, BridgeLog.read());
        startActivity(Intent.createChooser(share, "导出诊断"));
    }

    private void saveConfiguration() {
        try {
            if (RuntimeMode.isStandalone(this)) {
                AppConfig.save(this, "", "", selectedMode);
            } else {
                AppConfig.save(this, endpoint.getText().toString(), token.getText().toString(), selectedMode);
            }
            AgentWatchdogService.start(this);
            toast(RuntimeMode.isStandalone(this)
                    ? "模式已保存，内置 Agent 正在启动" : "配置已保存，守护服务正在启动");
            refreshAgent();
        } catch (IllegalArgumentException error) {
            toast(error.getMessage());
        }
    }

    private String roleText() {
        return "默认电话角色：" + (dialerRoleHeld() ? "已启用" : "未启用");
    }

    private boolean dialerRoleHeld() {
        RoleManager manager = getSystemService(RoleManager.class);
        return manager != null && manager.isRoleAvailable(RoleManager.ROLE_DIALER)
                && manager.isRoleHeld(RoleManager.ROLE_DIALER);
    }

    private String vowlanNetworkLine() {
        String host = localVoWLANAddress();
        return host.isEmpty() ? "同一 Wi‑Fi / 手机热点" : "同一 Wi‑Fi · " + host;
    }

    private boolean vowlanReady() {
        String state = VoWLANGatewayService.currentDiagnosticState();
        return dialerRoleHeld() && VoWLANPairingStore.configured(this) && !localVoWLANAddress().isEmpty()
                && state.contains("paired=true") && state.contains("agent_ready=true")
                && state.contains("pcm_ready=true") && state.contains("dialer_role=true")
                && !state.contains("avf=missing");
    }

    private String localVoWLANAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface item = interfaces.nextElement();
                if (!item.isUp() || item.isLoopback()) continue;
                Enumeration<InetAddress> addresses = item.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address
                            && VoWLANNetworkPolicy.isVoWLANInterface(item.getName(), address.getHostAddress())) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private boolean shizukuReady() {
        String value = ShizukuBridgeManager.get(this).status();
        return value.contains("Shizuku 已连接");
    }

    private boolean pcmReady() {
        return shizukuReady() && VoWLANGatewayService.currentDiagnosticState().contains("pcm_ready=true");
    }

    private String latestActivity() {
        String line = MainScreenPresentation.recentLogLines(BridgeLog.read(), "", 1);
        return "暂无 Debug 日志".equals(line)
                ? "等待连接活动"
                : "刚刚 · " + MainScreenPresentation.activitySummary(line);
    }

    private LinearLayout pageRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(20), dp(18), dp(30));
        root.setBackgroundColor(BG);
        return root;
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private LinearLayout card(int color, int radius) {
        LinearLayout card = new LinearLayout(this);
        card.setBackground(neuDrawable("neu_card_bg"));
        return card;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout column(int startPadding) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        if (startPadding > 0) column.setPadding(startPadding, 0, 0, 0);
        return column;
    }

    private TextView sectionTitle(String value) {
        TextView title = label(value, 18, TEXT, Gravity.START);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        return title;
    }

    private TextView overline(String value) {
        TextView text = label(value, 12, MUTED, Gravity.START);
        text.setLetterSpacing(0.04f);
        return text;
    }

    private TextView description(String value) {
        TextView text = label(value, 13, MUTED, Gravity.START);
        text.setLineSpacing(dp(2), 1f);
        return text;
    }

    private TextView label(String value, int sp, int color, int gravity) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(gravity);
        view.setFontFeatureSettings("kern");
        return view;
    }

    private EditText input(String hint, int type) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setHintTextColor(MUTED);
        view.setTextColor(TEXT);
        view.setTextSize(15);
        view.setInputType(type);
        view.setSingleLine(true);
        view.setPadding(dp(14), dp(11), dp(14), dp(11));
        view.setBackground(neuDrawable("neu_input_bg"));
        return view;
    }

    private TextView primaryButton(String title, int color) {
        TextView button = label(title, 16, Color.WHITE, Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(54));
        button.setPadding(dp(16), dp(13), dp(16), dp(13));
        button.setBackground(color == BLUE
                ? neuDrawable("neu_accent_blue") : neuDrawable("neu_accent_green"));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView secondaryButton(String title) {
        TextView button = label(title, 14, TEXT, Gravity.CENTER);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setMinHeight(dp(50));
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setBackground(neuDrawable("neu_btn_bg"));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private ImageView icon(int resource, int tint, int contentSize) {
        ImageView view = new ImageView(this);
        view.setImageResource(resource);
        view.setImageTintList(ColorStateList.valueOf(tint));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setMinimumWidth(dp(contentSize));
        view.setMinimumHeight(dp(contentSize));
        return view;
    }

    /**
     * Neumorphism 双阴影 layer：inset=false 为凸起（左上浅阴影、右下深阴影），
     * inset=true 则阴影反转呈现凹陷。底色统一用 BG（#E0E5EC）。
     */
    private Drawable neuLayer(boolean inset, int radiusDp, int baseColor) {
        int r = dp(radiusDp);
        int shadow = dp(6);
        int pad = dp(3);
        GradientDrawable dark = new GradientDrawable();
        dark.setColor(Color.rgb(163, 177, 198));
        dark.setCornerRadius(r);
        GradientDrawable light = new GradientDrawable();
        light.setColor(Color.WHITE);
        light.setCornerRadius(r);
        GradientDrawable base = new GradientDrawable();
        base.setColor(baseColor);
        base.setCornerRadius(Math.max(dp(1), r - pad));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{dark, light, base});
        if (inset) {
            layers.setLayerInset(0, 0, 0, shadow, shadow);
            layers.setLayerInset(1, shadow, shadow, 0, 0);
        } else {
            layers.setLayerInset(0, shadow, shadow, 0, 0);
            layers.setLayerInset(1, 0, 0, shadow, shadow);
        }
        layers.setLayerInset(2, pad, pad, pad, pad);
        return layers;
    }

    private Drawable neuRaisedDrawable(int radiusDp) {
        return neuLayer(false, radiusDp, BG);
    }

    private Drawable neuInsetDrawable(int radiusDp) {
        return neuLayer(true, radiusDp, BG);
    }


    /** 取 app 自带 drawable（本工程不生成 R 类，用 getIdentifier 动态解析）。 */
    private Drawable neuDrawable(String name) {
        int id = getResources().getIdentifier(name, "drawable", getPackageName());
        if (id != 0) {
            Drawable d = getDrawable(id);
            if (d != null) return d;
        }
        // 兜底：纯色圆角
        GradientDrawable fallback = new GradientDrawable();
        fallback.setColor(BG);
        fallback.setCornerRadius(dp(14));
        return fallback;
    }

    /** 纯色小圆点/小胶囊（不带阴影），用于状态点。 */
    private Drawable solidPill(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
    }

    private LinearLayout.LayoutParams weightedWithStart(int start) {
        LinearLayout.LayoutParams params = weighted();
        params.leftMargin = dp(start);
        return params;
    }

    private LinearLayout.LayoutParams weightedWithEnd(int end) {
        LinearLayout.LayoutParams params = weighted();
        params.rightMargin = dp(end);
        return params;
    }

    private LinearLayout.LayoutParams matchWithTop(int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        refreshStatus();
        if (request == ROLE_REQUEST) {
            continueAfterSystemRoleRequest();
        } else if (request == DEFAULT_APPS_REQUEST) {
            if (dialerRoleHeld()) checkAVFEnvironmentAtStartup();
            else toast("余奉电话助理仍未获得默认电话角色");
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshShizukuStatus();
        // 一键值守：开关决定 Agent 是否常驻。关闭值守即真正停止，不再被前台唤醒偷偷拉起。
        if (RuntimeMode.isStandalone(this) && DutyStore.isDutyOn(this)) {
            StandaloneAgentService.start(this);
        }
    }

    @Override public void onBackPressed() {
        if (bottomBar != null && bottomBar.getVisibility() == View.GONE) {
            stopPairingServer();
            showPage(Page.DUTY);
            return;
        }
        if (currentPage != Page.DUTY) {
            showPage(Page.DUTY);
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(debugRefresh);
        stopPairingServer();
        super.onDestroy();
    }
}
