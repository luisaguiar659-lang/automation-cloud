package com.automationcloud.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.text.method.PasswordTransformationMethod;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String PANEL_LOGIN =
            "https://panel.xtream.cloud/#/login";

    private static final String PANEL_DEVICES =
            "https://panel-v2.xtream.cloud/dashboard/devices";

    private static final String PANEL_DEVICES_VIEW =
            "https://panel.xtream.cloud/dashboard/devices";

    private static final String PANEL_SUBRESELLERS =
            "https://panel.xtream.cloud/dashboard/subresellers";

    private static final String PANEL_BRANDING =
            "https://panel.xtream.cloud/dashboard/branding/tv-app";

    private static final String PANEL_SETTINGS =
            "https://panel.xtream.cloud/dashboard/settings";

    private static final String PANEL_DASHBOARD =
            "https://panel.xtream.cloud/dashboard";

    private static final String PLAYLIST_BASE =
            "https://xtream.cloud/custom-playlist";

    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "panel.xtream.cloud",
            "panel-v2.xtream.cloud",
            "xtream.cloud"
    ));

    private enum Flow {
        ACTIVATE,
        RESET,
        DELETE
    }

    private enum AutomationStage {
        IDLE,
        DEVICE_SEARCHING,
        DEVICE_ADDING,
        DEVICE_VERIFYING,
        DEVICE_DEACTIVATING,
        DEVICE_ACTIVATING,
        DEVICE_ACTIVE_VERIFYING,
        DEVICE_DELETING,
        DELETE_VERIFYING,
        PLAYLIST_OPENING,
        PLAYLIST_SAVING,
        PLAYLIST_VERIFYING,
        COMPLETED,
        FAILED
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WebView webView;
    private static final int FILE_CHOOSER_REQUEST_CODE = 9101;
    private ValueCallback<Uri[]> fileChooserCallback;
    private ScrollView consoleView;
    private LinearLayout progressList;

    private ScrollView loginScreen;
    private LinearLayout appScreen;
    private SwipeRefreshLayout refreshLayout;
    private TextView loginStatus;
    private TextView connectionStatus;
    private AlertDialog operationDialog;
    private TextView operationIcon;
    private TextView operationTitle;
    private TextView operationDevice;
    private TextView operationStep;
    private TextView operationHint;
    private ProgressBar operationProgress;

    private EditText emailInput;
    private EditText passwordInput;
    private EditText deviceInput;
    private EditText playlistInput;
    private Spinner flowSpinner;

    private Button executeButton;
    private Button toggleBrowserButton;
    private Button loginButton;
    private Button logoutButton;
    private Button detailsButton;
    private Button togglePlaylistVisibilityButton;

    private Flow activeFlow = Flow.ACTIVATE;

    private boolean loginInjected = false;
    private boolean deviceInjected = false;
    private boolean deleteInjected = false;
    private boolean playlistInjected = false;
    private boolean resetCycleCompleted = false;
    private boolean running = false;
    private boolean loginOnlyMode = false;
    private boolean authenticated = false;
    private boolean detailedLogs = false;
    private int loginRetryCount = 0;
    private int automationRetryCount = 0;
    private boolean manualRefreshing = false;
    private String currentOperationId = "";
    private AutomationStage automationStage = AutomationStage.IDLE;

    private String currentDevice = "";
    private String currentPlaylist = "";

    private SharedPreferences securePrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        securePrefs = createSecurePreferences();
        buildUi();
        setupWebView();
        loadSavedCredentials();
        showLoginScreen();

        // As credenciais continuam salvas/preenchidas,
        // mas o login só acontece quando o usuário toca em ENTRAR.
        if (!emailInput.getText().toString().trim().isEmpty()
                && !passwordInput.getText().toString().isEmpty()) {
            loginStatus.setText("Credenciais salvas. Toque em ENTRAR.");
        }
    }

    private SharedPreferences createSecurePreferences() {
        try {
            MasterKey key = new MasterKey.Builder(this)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();

            return EncryptedSharedPreferences.create(
                    this,
                    "automation_secure",
                    key,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception error) {
            throw new RuntimeException("Falha ao inicializar armazenamento seguro.", error);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setUserAgentString(
                settings.getUserAgentString() + " AutomationCloud/1.0"
        );

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams
            ) {
                // Cancela qualquer seletor anterior que ainda esteja pendente.
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                }
                fileChooserCallback = filePathCallback;

                try {
                    Intent intent = fileChooserParams.createIntent();

                    // O painel usa este fluxo principalmente para logos/imagens.
                    // Quando o HTML não informa um tipo, mostramos imagens por padrão.
                    String[] acceptTypes = fileChooserParams.getAcceptTypes();
                    boolean hasAcceptType = false;
                    if (acceptTypes != null) {
                        for (String type : acceptTypes) {
                            if (type != null && !type.trim().isEmpty()) {
                                hasAcceptType = true;
                                break;
                            }
                        }
                    }
                    if (!hasAcceptType) {
                        intent.setType("image/*");
                    }

                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(
                            Intent.createChooser(intent, "Selecionar imagem"),
                            FILE_CHOOSER_REQUEST_CODE
                    );
                    return true;
                } catch (ActivityNotFoundException error) {
                    fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback = null;
                    Toast.makeText(
                            MainActivity.this,
                            "Nenhum aplicativo de galeria/arquivos disponível.",
                            Toast.LENGTH_LONG
                    ).show();
                    return true;
                } catch (Exception error) {
                    fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback = null;
                    Toast.makeText(
                            MainActivity.this,
                            "Não foi possível abrir a galeria.",
                            Toast.LENGTH_LONG
                    ).show();
                    return true;
                }
            }
        });
        webView.addJavascriptInterface(new AutomationBridge(), "AutomationBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                String host = request.getUrl().getHost();
                String scheme = request.getUrl().getScheme();

                if (!"https".equalsIgnoreCase(scheme)
                        || host == null
                        || !ALLOWED_HOSTS.contains(host)) {
                    addProgress(
                            "BLOQUEADO",
                            "Navegação externa bloqueada: " + host,
                            Color.rgb(255, 107, 107)
                    );
                    return true;
                }

                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                handlePageFinished(url);
            }
        });

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
    }


    private void buildUi() {
        float density =
                getResources()
                        .getDisplayMetrics()
                        .density;

        int screenWidthDp =
                Math.round(
                        getResources()
                                .getDisplayMetrics()
                                .widthPixels
                                / density
                );

        int screenHeightDp =
                Math.round(
                        getResources()
                                .getDisplayMetrics()
                                .heightPixels
                                / density
                );

        boolean veryCompactUi =
                screenWidthDp < 340
                        || screenHeightDp < 560;

        boolean compactUi =
                screenWidthDp < 380
                        || screenHeightDp < 700;

        boolean wideUi =
                screenWidthDp >= 600;

        boolean tabletUi =
                screenWidthDp >= 840;

        int pad =
                dp(
                        veryCompactUi
                                ? 8
                                : (compactUi ? 10 : 16)
                );

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(7, 9, 11));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(
                pad,
                dp(veryCompactUi ? 6 : (compactUi ? 8 : 14)),
                pad,
                dp(veryCompactUi ? 4 : (compactUi ? 6 : 10))
        );

        TextView title = new TextView(this);
        title.setText(
                veryCompactUi
                        ? "XCLOUD"
                        : "MASTER XCLOUD"
        );
        title.setTextColor(Color.WHITE);
        title.setTextSize(
                veryCompactUi
                        ? 15
                        : (compactUi ? 18 : 21)
        );
        title.setTypeface(null, 1);
        title.setLetterSpacing(0.02f);

        topBar.addView(
                title,
                new LinearLayout.LayoutParams(
                        0,
                        dp(veryCompactUi ? 40 : (compactUi ? 44 : 48)),
                        1f
                )
        );

        toggleBrowserButton = new Button(this);
        toggleBrowserButton.setText(
                compactUi
                        ? "PAINEL"
                        : "PAINEL"
        );
        toggleBrowserButton.setTextSize(
                veryCompactUi
                        ? 9
                        : (compactUi ? 10 : 11)
        );
        toggleBrowserButton.setMinWidth(0);
        toggleBrowserButton.setMinimumWidth(0);
        toggleBrowserButton.setPadding(
                dp(veryCompactUi ? 5 : (compactUi ? 7 : 10)),
                0,
                dp(veryCompactUi ? 5 : (compactUi ? 7 : 10)),
                0
        );
        toggleBrowserButton.setTextColor(Color.WHITE);
        toggleBrowserButton.setBackgroundResource(getDrawableId("bg_secondary_button", "drawable"));
        toggleBrowserButton.setVisibility(View.GONE);
        toggleBrowserButton.setOnClickListener(v -> toggleBrowser());
        // Botão PAINEL removido da barra superior na v1.9.0.

        detailsButton = new Button(this);
        detailsButton.setText(
                compactUi
                        ? "INFO"
                        : "RESUMO"
        );
        detailsButton.setTextSize(
                veryCompactUi
                        ? 9
                        : (compactUi ? 10 : 11)
        );
        detailsButton.setMinWidth(0);
        detailsButton.setMinimumWidth(0);
        detailsButton.setPadding(
                dp(veryCompactUi ? 5 : (compactUi ? 7 : 10)),
                0,
                dp(veryCompactUi ? 5 : (compactUi ? 7 : 10)),
                0
        );
        detailsButton.setTextColor(Color.WHITE);
        detailsButton.setBackgroundResource(getDrawableId("bg_secondary_button", "drawable"));
        detailsButton.setVisibility(View.GONE);
        detailsButton.setOnClickListener(v -> showSummary());
        topBar.addView(detailsButton);

        logoutButton = new Button(this);
        logoutButton.setText(
                compactUi
                        ? "SAIR"
                        : "SAIR"
        );
        logoutButton.setTextSize(
                veryCompactUi
                        ? 9
                        : (compactUi ? 10 : 11)
        );
        logoutButton.setMinWidth(0);
        logoutButton.setMinimumWidth(0);
        logoutButton.setPadding(
                dp(veryCompactUi ? 5 : (compactUi ? 7 : 10)),
                0,
                dp(veryCompactUi ? 5 : (compactUi ? 7 : 10)),
                0
        );
        logoutButton.setTextColor(Color.WHITE);
        logoutButton.setBackgroundResource(getDrawableId("bg_secondary_button", "drawable"));
        logoutButton.setVisibility(View.GONE);
        logoutButton.setOnClickListener(v -> logout());
        topBar.addView(logoutButton);

        root.addView(topBar);

        View glowLine = new View(this);
        glowLine.setBackgroundResource(getDrawableId("bg_glow_line", "drawable"));
        root.addView(glowLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3)
        ));

        // ---------- LOGIN SCREEN RESPONSIVA ----------
        boolean compactLogin =
                compactUi;

        boolean wideLogin =
                wideUi;

        int loginHorizontalPadding;

        if (wideLogin) {
            // Em tablets/telas grandes, mantém o formulário com largura
            // confortável em vez de esticá-lo por toda a tela.
            loginHorizontalPadding =
                    Math.max(
                            28,
                            (screenWidthDp - 480) / 2
                    );

        } else if (screenWidthDp < 360) {
            loginHorizontalPadding =
                    16;

        } else {
            loginHorizontalPadding =
                    22;
        }

        int loginTopPadding =
                compactLogin
                        ? 12
                        : 26;

        int loginBottomPadding =
                compactLogin
                        ? 24
                        : 40;

        int logoSize =
                compactLogin
                        ? 108
                        : (wideLogin ? 172 : 148);

        int titleSize =
                compactLogin
                        ? 23
                        : 28;

        int subtitleSize =
                compactLogin
                        ? 12
                        : 14;

        int subtitleBottom =
                compactLogin
                        ? 16
                        : 28;

        loginScreen =
                new ScrollView(
                        this
                );

        loginScreen.setFillViewport(
                true
        );

        loginScreen.setClipToPadding(
                false
        );

        loginScreen.setOverScrollMode(
                View.OVER_SCROLL_IF_CONTENT_SCROLLS
        );

        LinearLayout loginContent =
                new LinearLayout(
                        this
                );

        loginContent.setOrientation(
                LinearLayout.VERTICAL
        );

        loginContent.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        loginContent.setPadding(
                dp(loginHorizontalPadding),
                dp(loginTopPadding),
                dp(loginHorizontalPadding),
                dp(loginBottomPadding)
        );

        ImageView appLogo =
                new ImageView(
                        this
                );

        appLogo.setImageResource(
                getDrawableId(
                        "logo_master_xcloud",
                        "drawable"
                )
        );

        appLogo.setAdjustViewBounds(
                true
        );

        appLogo.setScaleType(
                ImageView.ScaleType.CENTER_INSIDE
        );

        LinearLayout.LayoutParams logoParams =
                new LinearLayout.LayoutParams(
                        dp(logoSize),
                        dp(logoSize)
                );

        logoParams.gravity =
                Gravity.CENTER_HORIZONTAL;

        appLogo.setLayoutParams(
                logoParams
        );

        loginContent.addView(
                appLogo
        );

        loginContent.addView(
                verticalSpace(
                        compactLogin
                                ? 4
                                : 10
                )
        );

        TextView loginTitle =
                new TextView(
                        this
                );

        loginTitle.setText(
                "MASTER XCLOUD"
        );

        loginTitle.setTextColor(
                Color.WHITE
        );

        loginTitle.setTextSize(
                titleSize
        );

        loginTitle.setTypeface(
                null,
                1
        );

        loginTitle.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        loginContent.addView(
                loginTitle,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        TextView loginSubtitle =
                new TextView(
                        this
                );

        loginSubtitle.setText(
                "Faça login para acessar a automação."
        );

        loginSubtitle.setTextColor(
                Color.rgb(
                        168,
                        179,
                        188
                )
        );

        loginSubtitle.setTextSize(
                subtitleSize
        );

        loginSubtitle.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        loginSubtitle.setPadding(
                0,
                dp(compactLogin ? 4 : 8),
                0,
                dp(subtitleBottom)
        );

        loginContent.addView(
                loginSubtitle,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        emailInput =
                field(
                        "Email",
                        false
                );

        passwordInput =
                field(
                        "Senha",
                        true
                );

        emailInput.setSingleLine(
                true
        );

        passwordInput.setSingleLine(
                true
        );

        emailInput.setImeOptions(
                android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
        );

        passwordInput.setImeOptions(
                android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        );

        loginContent.addView(
                emailInput,
                matchHeight(
                        dp(compactLogin ? 50 : 54)
                )
        );

        loginContent.addView(
                verticalSpace(
                        compactLogin
                                ? 8
                                : 12
                )
        );

        loginContent.addView(
                passwordInput,
                matchHeight(
                        dp(compactLogin ? 50 : 54)
                )
        );

        loginContent.addView(
                verticalSpace(
                        compactLogin
                                ? 10
                                : 12
                )
        );

        loginButton =
                new Button(
                        this
                );

        loginButton.setText(
                "ENTRAR"
        );

        loginButton.setTextSize(
                compactLogin
                        ? 14
                        : 15
        );

        loginButton.setTypeface(
                null,
                1
        );

        loginButton.setTextColor(
                Color.WHITE
        );

        loginButton.setBackgroundResource(
                getDrawableId(
                        "bg_login_gradient",
                        "drawable"
                )
        );

        loginButton.setOnClickListener(
                v -> authenticateOnly()
        );

        loginContent.addView(
                loginButton,
                matchHeight(
                        dp(compactLogin ? 52 : 56)
                )
        );

        loginStatus =
                new TextView(
                        this
                );

        loginStatus.setText(
                ""
        );

        loginStatus.setTextColor(
                Color.rgb(
                        0,
                        184,
                        255
                )
        );

        loginStatus.setTextSize(
                compactLogin
                        ? 12
                        : 13
        );

        loginStatus.setGravity(
                Gravity.CENTER_HORIZONTAL
        );

        loginStatus.setPadding(
                0,
                dp(compactLogin ? 12 : 18),
                0,
                0
        );

        loginContent.addView(
                loginStatus,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                )
        );

        loginScreen.addView(
                loginContent,
                new ScrollView.LayoutParams(
                        ScrollView.LayoutParams.MATCH_PARENT,
                        ScrollView.LayoutParams.WRAP_CONTENT
                )
        );

        root.addView(
                loginScreen,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        // ---------- MAIN APP SCREEN ----------
        appScreen = new LinearLayout(this);
        appScreen.setOrientation(LinearLayout.VERTICAL);
        appScreen.setVisibility(View.GONE);

        consoleView = new ScrollView(this);
        consoleView.setFillViewport(true);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);

        int formHorizontalPadding;

        if (tabletUi) {
            formHorizontalPadding =
                    Math.max(
                            32,
                            (screenWidthDp - 720) / 2
                    );
        } else if (wideUi) {
            formHorizontalPadding =
                    Math.max(
                            24,
                            (screenWidthDp - 560) / 2
                    );
        } else if (veryCompactUi) {
            formHorizontalPadding =
                    10;
        } else if (compactUi) {
            formHorizontalPadding =
                    14;
        } else {
            formHorizontalPadding =
                    18;
        }

        form.setPadding(
                dp(formHorizontalPadding),
                dp(veryCompactUi ? 10 : (compactUi ? 14 : 20)),
                dp(formHorizontalPadding),
                dp(veryCompactUi ? 22 : (compactUi ? 28 : 40))
        );

        LinearLayout brandCard = new LinearLayout(this);
        brandCard.setOrientation(LinearLayout.HORIZONTAL);
        brandCard.setGravity(Gravity.CENTER_VERTICAL);
        brandCard.setPadding(
                dp(veryCompactUi ? 9 : (compactUi ? 11 : 14)),
                dp(veryCompactUi ? 8 : (compactUi ? 10 : 12)),
                dp(veryCompactUi ? 9 : (compactUi ? 11 : 14)),
                dp(veryCompactUi ? 8 : (compactUi ? 10 : 12))
        );
        brandCard.setBackgroundResource(getDrawableId("bg_card", "drawable"));

        ImageView miniLogo = new ImageView(this);
        miniLogo.setImageResource(getDrawableId("logo_master_xcloud", "drawable"));
        int miniLogoSize =
                veryCompactUi
                        ? 44
                        : (compactUi ? 52 : (wideUi ? 72 : 64));

        brandCard.addView(
                miniLogo,
                new LinearLayout.LayoutParams(
                        dp(miniLogoSize),
                        dp(miniLogoSize)
                )
        );

        LinearLayout brandText = new LinearLayout(this);
        brandText.setOrientation(LinearLayout.VERTICAL);
        brandText.setPadding(
                dp(veryCompactUi ? 8 : 12),
                0,
                0,
                0
        );

        TextView brandName = new TextView(this);
        brandName.setText("MASTER XCLOUD");
        brandName.setTextColor(Color.WHITE);
        brandName.setTextSize(
                veryCompactUi
                        ? 15
                        : (compactUi ? 17 : 19)
        );
        brandName.setTypeface(null, 1);

        connectionStatus = new TextView(this);
        connectionStatus.setText("● Conectado");
        connectionStatus.setTextColor(Color.rgb(0, 184, 255));
        connectionStatus.setTextSize(
                veryCompactUi
                        ? 10
                        : (compactUi ? 11 : 12)
        );

        brandText.addView(brandName);
        brandText.addView(connectionStatus);
        brandCard.addView(brandText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        form.addView(brandCard);
        form.addView(sectionGap());

        // ---------- ACESSO RÁPIDO ÀS FUNÇÕES DO XCLOUD ----------
        form.addView(sectionTitle("ACESSO RÁPIDO"));

        Button quickActivate = quickActionButton("⚡  ATIVAR DISPOSITIVO");
        quickActivate.setOnClickListener(v -> {
            if (running) {
                toast("Aguarde a automação terminar.");
                return;
            }
            flowSpinner.setSelection(0);
            deviceInput.requestFocus();
            consoleView.post(() -> consoleView.smoothScrollTo(0, Math.max(0, deviceInput.getTop() - dp(80))));
        });
        form.addView(quickActivate, matchHeight(dp(52)));
        form.addView(verticalSpace(8));

        Button quickDashboard = quickActionButton("▦  PAINEL DE CONTROLE");
        quickDashboard.setOnClickListener(v -> openPanelUrl(PANEL_DASHBOARD));
        form.addView(quickDashboard, matchHeight(dp(52)));
        form.addView(verticalSpace(8));

        Button quickDevices = quickActionButton("▣  DISPOSITIVOS");
        quickDevices.setOnClickListener(v -> openPanelUrl(PANEL_DEVICES_VIEW));
        form.addView(quickDevices, matchHeight(dp(52)));
        form.addView(verticalSpace(8));

        Button quickResellers = quickActionButton("♙  SUB-REVENDEDORES");
        quickResellers.setOnClickListener(v -> openPanelUrl(PANEL_SUBRESELLERS));
        form.addView(quickResellers, matchHeight(dp(52)));
        form.addView(verticalSpace(8));

        Button quickBrand = quickActionButton("◉  MARCA");
        quickBrand.setOnClickListener(v -> openPanelUrl(PANEL_BRANDING));
        form.addView(quickBrand, matchHeight(dp(52)));
        form.addView(verticalSpace(8));

        Button quickSettings = quickActionButton("⚙  CONFIGURAÇÕES");
        quickSettings.setOnClickListener(v -> openPanelUrl(PANEL_SETTINGS));
        form.addView(quickSettings, matchHeight(dp(52)));

        form.addView(sectionGap());
        form.addView(sectionTitle("PAINEL DE AUTOMAÇÃO"));

        flowSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{
                        "Ativar MAC + DNS",
                        "Editar (Reset) + DNS",
                        "Excluir MAC"
                }
        );
        flowSpinner.setAdapter(adapter);
        flowSpinner.setBackgroundResource(getDrawableId("bg_input", "drawable"));
        form.addView(
                flowSpinner,
                matchHeight(
                        dp(veryCompactUi ? 46 : (compactUi ? 50 : 54))
                )
        );

        form.addView(space());

        deviceInput = field("Device Key / MAC", false);
        deviceInput.setMinHeight(
                dp(veryCompactUi ? 46 : (compactUi ? 50 : 54))
        );
        form.addView(deviceInput);

        form.addView(space());

        playlistInput = field("M3U / DNS", false);
        // Oculta por padrão, mas permite mostrar temporariamente para corrigir
        // erros de digitação como https:// em vez de http://.
        playlistInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        playlistInput.setMinLines(
                veryCompactUi
                        ? 1
                        : 2
        );
        playlistInput.setMaxLines(
                compactUi
                        ? 3
                        : 5
        );
        playlistInput.setGravity(Gravity.TOP);
        form.addView(playlistInput);

        togglePlaylistVisibilityButton = new Button(this);
        togglePlaylistVisibilityButton.setText("MOSTRAR M3U");
        togglePlaylistVisibilityButton.setTextSize(veryCompactUi ? 11 : 12);
        togglePlaylistVisibilityButton.setAllCaps(false);
        togglePlaylistVisibilityButton.setOnClickListener(v -> {
            int cursor = Math.max(0, playlistInput.getSelectionStart());
            boolean hidden = playlistInput.getTransformationMethod() != null;
            if (hidden) {
                playlistInput.setTransformationMethod(null);
                togglePlaylistVisibilityButton.setText("OCULTAR M3U");
            } else {
                playlistInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
                togglePlaylistVisibilityButton.setText("MOSTRAR M3U");
            }
            playlistInput.setSelection(Math.min(cursor, playlistInput.length()));
            playlistInput.requestFocus();
        });
        form.addView(togglePlaylistVisibilityButton, matchHeight(dp(veryCompactUi ? 38 : 42)));

        form.addView(space());

        executeButton = new Button(this);
        executeButton.setText("EXECUTAR");
        executeButton.setTextSize(
                veryCompactUi
                        ? 13
                        : (compactUi ? 14 : 15)
        );
        executeButton.setTypeface(null, 1);
        executeButton.setTextColor(Color.WHITE);
        executeButton.setBackgroundResource(getDrawableId("bg_action_gradient", "drawable"));
        executeButton.setOnClickListener(v -> startFlow());
        form.addView(
                executeButton,
                matchHeight(
                        dp(veryCompactUi ? 48 : (compactUi ? 52 : 56))
                )
        );

        form.addView(sectionGap());
        form.addView(sectionTitle("STATUS DA AUTOMAÇÃO"));

        LinearLayout statusCard = new LinearLayout(this);
        statusCard.setOrientation(LinearLayout.VERTICAL);
        statusCard.setPadding(
                dp(veryCompactUi ? 9 : (compactUi ? 11 : 14)),
                dp(veryCompactUi ? 7 : 10),
                dp(veryCompactUi ? 9 : (compactUi ? 11 : 14)),
                dp(veryCompactUi ? 7 : 10)
        );
        statusCard.setBackgroundResource(getDrawableId("bg_card", "drawable"));

        progressList = new LinearLayout(this);
        progressList.setOrientation(LinearLayout.VERTICAL);
        statusCard.addView(progressList);

        form.addView(statusCard);

        addProgress(
                "PRONTO",
                "Pronto para iniciar a automação.",
                Color.rgb(136, 148, 139)
        );

        consoleView.addView(form);

        appScreen.addView(
                consoleView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        webView = new WebView(this);
        webView.setVisibility(View.GONE);

        appScreen.addView(
                webView,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        refreshLayout = new SwipeRefreshLayout(this);
        refreshLayout.setColorSchemeColors(
                Color.rgb(255, 145, 0),
                Color.rgb(0, 184, 255)
        );

        refreshLayout.setOnChildScrollUpCallback((parent, child) -> {
            View target = webView.getVisibility() == View.VISIBLE
                    ? webView
                    : consoleView;

            return target != null && target.canScrollVertically(-1);
        });

        refreshLayout.setOnRefreshListener(this::refreshCurrentScreen);

        refreshLayout.addView(
                appScreen,
                new SwipeRefreshLayout.LayoutParams(
                        SwipeRefreshLayout.LayoutParams.MATCH_PARENT,
                        SwipeRefreshLayout.LayoutParams.MATCH_PARENT
                )
        );

        root.addView(
                refreshLayout,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                )
        );

        setContentView(root);
    }

    private Button quickActionButton(String text) {
        boolean compact = currentWidthDp() < 380 || currentHeightDp() < 700;
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(compact ? 13 : 14);
        button.setTypeface(null, 1);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setAllCaps(false);
        button.setPadding(dp(compact ? 14 : 18), 0, dp(12), 0);
        button.setBackgroundResource(getDrawableId("bg_secondary_button", "drawable"));
        return button;
    }

    private void openPanelUrl(String url) {
        if (running) {
            toast("Aguarde a automação terminar.");
            return;
        }
        String separator = url.contains("?") ? "&" : "?";
        webView.loadUrl(url + separator + "t=" + System.currentTimeMillis());
        showBrowser(true);
    }

    private TextView sectionTitle(String text) {
        boolean compact =
                currentWidthDp() < 380
                        || currentHeightDp() < 700;

        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.rgb(255, 156, 0));
        view.setTextSize(compact ? 11 : 12);
        view.setTypeface(null, 1);
        view.setLetterSpacing(compact ? 0.05f : 0.08f);
        view.setPadding(
                0,
                0,
                0,
                dp(compact ? 8 : 12)
        );
        return view;
    }

    private EditText field(String hint, boolean password) {
        boolean veryCompact =
                currentWidthDp() < 340
                        || currentHeightDp() < 560;

        boolean compact =
                currentWidthDp() < 380
                        || currentHeightDp() < 700;

        EditText view = new EditText(this);
        view.setHint(hint);
        view.setHintTextColor(Color.rgb(120, 132, 142));
        view.setTextColor(Color.WHITE);
        view.setTextSize(
                veryCompact
                        ? 13
                        : (compact ? 14 : 15)
        );
        view.setPadding(
                dp(veryCompact ? 10 : (compact ? 12 : 16)),
                dp(veryCompact ? 9 : (compact ? 11 : 14)),
                dp(veryCompact ? 10 : (compact ? 12 : 16)),
                dp(veryCompact ? 9 : (compact ? 11 : 14))
        );
        view.setBackgroundResource(getDrawableId("bg_input", "drawable"));

        if (password) {
            view.setInputType(
                    InputType.TYPE_CLASS_TEXT |
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
            );
        }

        return view;
    }

    private View verticalSpace(
            int heightDp
    ) {
        View view =
                new View(
                        this
                );

        view.setLayoutParams(
                new LinearLayout.LayoutParams(
                        1,
                        dp(heightDp)
                )
        );

        return view;
    }

    private View space() {
        boolean compact =
                currentWidthDp() < 380
                        || currentHeightDp() < 700;

        View v = new View(this);
        v.setLayoutParams(
                new LinearLayout.LayoutParams(
                        1,
                        dp(compact ? 8 : 12)
                )
        );
        return v;
    }

    private View sectionGap() {
        boolean compact =
                currentWidthDp() < 380
                        || currentHeightDp() < 700;

        View v = new View(this);
        v.setLayoutParams(
                new LinearLayout.LayoutParams(
                        1,
                        dp(compact ? 18 : 28)
                )
        );
        return v;
    }

    private LinearLayout.LayoutParams matchHeight(int height) {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        );
    }


    private void showLoginScreen() {
        authenticated = false;
        loginScreen.setVisibility(View.VISIBLE);

        if (refreshLayout != null) {
            refreshLayout.setVisibility(View.GONE);
            refreshLayout.setRefreshing(false);
        }

        appScreen.setVisibility(View.GONE);
        toggleBrowserButton.setVisibility(View.GONE);
        detailsButton.setVisibility(View.GONE);
        logoutButton.setVisibility(View.GONE);
    }

    private void showAppScreen() {
        authenticated = true;
        loginOnlyMode = false;
        running = false;

        loginScreen.setVisibility(View.GONE);

        if (refreshLayout != null) {
            refreshLayout.setVisibility(View.VISIBLE);
            refreshLayout.setRefreshing(false);
        }

        appScreen.setVisibility(View.VISIBLE);
        consoleView.setVisibility(View.VISIBLE);
        webView.setVisibility(View.GONE);

        toggleBrowserButton.setVisibility(View.VISIBLE);
        detailsButton.setVisibility(View.VISIBLE);
        logoutButton.setVisibility(View.VISIBLE);

        loginRetryCount = 0;
        if (connectionStatus != null) {
            connectionStatus.setText("● Conectado");
            connectionStatus.setTextColor(Color.rgb(0, 184, 255));
        }
        loginStatus.setText("");
        loginButton.setEnabled(true);
        loginButton.setText("ENTRAR");
    }

    private void refreshCurrentScreen() {
        if (!authenticated) {
            refreshLayout.setRefreshing(false);
            return;
        }

        if (running) {
            refreshLayout.setRefreshing(false);
            toast("Aguarde a automação terminar.");
            return;
        }

        manualRefreshing = true;

        if (webView.getVisibility() == View.VISIBLE) {
            // Dentro do PAINEL: atualiza a página que está visível.
            webView.reload();
        } else {
            // Tela principal: atualiza a sessão/painel em segundo plano.
            addProgress(
                    "ATUALIZANDO",
                    "Atualizando...",
                    Color.rgb(255, 166, 0)
            );

            webView.loadUrl(
                    PANEL_DASHBOARD
                            + "?refresh="
                            + System.currentTimeMillis()
            );
        }

        handler.postDelayed(() -> {
            if (!manualRefreshing) return;

            manualRefreshing = false;

            if (refreshLayout != null) {
                refreshLayout.setRefreshing(false);
            }

            if (consoleView.getVisibility() == View.VISIBLE) {
                addProgress(
                        "PRONTO",
                        "Atualização concluída. Pronto para iniciar.",
                        Color.rgb(136, 148, 139)
                );
            }
        }, 10000);
    }

    private void authenticateOnly() {
        if (loginOnlyMode) return;

        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            toast("Informe email e senha.");
            return;
        }

        saveCredentials();

        loginOnlyMode = true;
        running = true;
        authenticated = false;
        loginInjected = false;
        loginRetryCount = 0;

        loginButton.setEnabled(false);
        loginButton.setText("ENTRANDO...");
        loginStatus.setText("Verificando sessão salva...");

        webView.loadUrl(
                PANEL_DEVICES + "?t=" + System.currentTimeMillis()
        );
    }

    private void logout() {
        dismissOperationScreen();
        running = false;
        authenticated = false;
        loginOnlyMode = false;

        securePrefs.edit()
                .remove("email")
                .remove("password")
                .apply();
        emailInput.setText("");
        passwordInput.setText("");

        webView.clearHistory();
        webView.clearCache(true);

        android.webkit.CookieManager.getInstance().removeAllCookies(null);
        android.webkit.CookieManager.getInstance().flush();

        detailedLogs = false;
        if (detailsButton != null) detailsButton.setText("RESUMO");
        if (connectionStatus != null) {
            connectionStatus.setText("● Desconectado");
            connectionStatus.setTextColor(Color.rgb(136, 148, 139));
        }
        showLoginScreen();
        loginStatus.setText("Sessão encerrada.");
    }


    private void showSummary() {
        try {
            JSONArray history = new JSONArray(
                    securePrefs.getString("operation_history", "[]")
            );

            Calendar start = Calendar.getInstance();
            start.set(Calendar.HOUR_OF_DAY, 0);
            start.set(Calendar.MINUTE, 0);
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);
            long startToday = start.getTimeInMillis();

            int today = 0;
            int success = 0;
            int failed = 0;

            for (int i = 0; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item == null) continue;

                long ts = item.optLong("ts", 0L);
                if (ts >= startToday) {
                    today++;
                    if ("Sucesso".equals(item.optString("result"))) {
                        success++;
                    } else {
                        failed++;
                    }
                }
            }

            StringBuilder text = new StringBuilder();
            text.append("Sessão: Conectado\n");
            text.append("Versão: ").append(getInstalledVersionName()).append("\n\n");
            text.append("Hoje\n");
            text.append("• Operações: ").append(today).append("\n");
            text.append("• Sucessos: ").append(success).append("\n");
            text.append("• Falhas: ").append(failed).append("\n\n");
            text.append("Últimas operações\n");

            if (history.length() == 0) {
                text.append("Nenhuma operação registrada.");
            } else {
                SimpleDateFormat timeFormat =
                        new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault());

                int shown = 0;
                for (int i = history.length() - 1; i >= 0 && shown < 10; i--) {
                    JSONObject item = history.optJSONObject(i);
                    if (item == null) continue;

                    String result = item.optString("result", "-");
                    String symbol = "Sucesso".equals(result) ? "✓" : "!";
                    String mac = item.optString("device", "-");
                    String flow = item.optString("flow", "-");
                    long ts = item.optLong("ts", 0L);

                    text.append("\n")
                            .append(symbol).append(" ")
                            .append(timeFormat.format(new Date(ts)))
                            .append(" • ")
                            .append(mac)
                            .append("\n   ")
                            .append(flow)
                            .append(" • ")
                            .append(result);

                    shown++;
                }
            }

            new AlertDialog.Builder(this)
                    .setTitle("RESUMO • MASTER XCLOUD")
                    .setMessage(text.toString())
                    .setPositiveButton("FECHAR", null)
                    .setNeutralButton(
                            "LIMPAR HISTÓRICO",
                            (dialog, which) -> {
                                securePrefs.edit()
                                        .remove("operation_history")
                                        .apply();
                                toast("Histórico limpo.");
                            }
                    )
                    .show();

        } catch (Exception error) {
            toast("Não foi possível abrir o resumo.");
        }
    }

    private void recordHistory(
            Flow flow,
            String device,
            String result,
            String detail
    ) {
        if (device == null || device.trim().isEmpty()) return;

        try {
            JSONArray current = new JSONArray(
                    securePrefs.getString("operation_history", "[]")
            );

            JSONObject item = new JSONObject();
            item.put("ts", System.currentTimeMillis());
            item.put("device", device.trim().toUpperCase());
            item.put("flow", flowLabel(flow));
            item.put("result", result);

            // Não registra senha, M3U/DNS ou URL completa.
            if (detail != null && !detail.isEmpty()) {
                item.put("detail", detail);
            }

            current.put(item);

            JSONArray trimmed = new JSONArray();
            int start = Math.max(0, current.length() - 200);

            for (int i = start; i < current.length(); i++) {
                trimmed.put(current.get(i));
            }

            securePrefs.edit()
                    .putString("operation_history", trimmed.toString())
                    .apply();

        } catch (Exception ignored) {
        }
    }

    private boolean isRecoverableAutomationError(String code) {
        return "ADD_DEVICE_BUTTON_NOT_FOUND".equals(code)
                || "DEVICE_DIALOG_TIMEOUT".equals(code)
                || "DEVICE_KEY_INPUT_NOT_FOUND".equals(code)
                || "DEVICE_SAVE_NOT_FOUND".equals(code)
                || "SEARCH_INPUT_NOT_FOUND".equals(code)
                || "DEVICE_MENU_NOT_FOUND".equals(code)
                || "SECOND_MENU_NOT_FOUND".equals(code)
                || "DELETE_ACTION_NOT_FOUND".equals(code)
                || "PLAYLIST_URL_INPUT_NOT_FOUND".equals(code)
                || "PLAYLIST_SAVE_NOT_FOUND".equals(code)
                || "DEVICE_ADD_NOT_CONFIRMED".equals(code)
                || "DELETE_NOT_CONFIRMED".equals(code)
                || "DEVICE_NOT_VISIBLE_AFTER_DEACTIVATE".equals(code)
                || "RESET_DEACTIVATE_NOT_FOUND".equals(code)
                || "RESET_DEACTIVATE_CONFIRM_NOT_FOUND".equals(code)
                || "RESET_ACTIVATE_NOT_FOUND".equals(code)
                || "RESET_REACTIVATE_NOT_CONFIRMED".equals(code)
                || "PLAYLIST_NOT_CONFIRMED".equals(code)
                || "PANEL_NOT_READY".equals(code);
    }

    private void handleAutomationError(String code, String message) {
        if (isRecoverableAutomationError(code) && automationRetryCount < 3) {
            automationRetryCount++;

            addProgress(
                    "RETRY",
                    "Tentativa automática "
                            + automationRetryCount
                            + "/3...",
                    Color.rgb(255, 166, 0)
            );

            handler.postDelayed(() -> {
                if (!running) return;

                if (code.startsWith("PLAYLIST_")) {
                    playlistInjected = false;
                    webView.loadUrl(
                            PLAYLIST_BASE
                                    + "?device_key="
                                    + android.net.Uri.encode(currentDevice)
                                    + "&type=xtream&mode=add&t="
                                    + System.currentTimeMillis()
                    );
                    return;
                }

                if (activeFlow == Flow.DELETE
                        || (activeFlow == Flow.RESET
                        && !resetCycleCompleted)) {
                    deleteInjected = false;
                } else {
                    deviceInjected = false;
                }

                webView.loadUrl(
                        PANEL_DEVICES + "?t=" + System.currentTimeMillis()
                );
            }, 900);

            return;
        }

        automationStage = AutomationStage.FAILED;
        notifyOperationError();

        recordHistory(
                activeFlow,
                currentDevice,
                "Falha",
                message + " [" + code + " / " + automationStage + "]"
        );

        addProgress(
                "ERRO",
                message,
                Color.rgb(255, 92, 92)
        );
        updateOperationScreen("ERRO", message, false, true);
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(this::dismissOperationScreen, 3200);

        stopBusyOnly();
    }

    private String getInstalledVersionName() {
        try {
            return getPackageManager()
                    .getPackageInfo(getPackageName(), 0)
                    .versionName;
        } catch (Exception error) {
            return BuildConfig.VERSION_NAME;
        }
    }

    private int getDrawableId(String name, String type) {
        return getResources().getIdentifier(name, type, getPackageName());
    }

    private void loadSavedCredentials() {
        emailInput.setText(securePrefs.getString("email", ""));
        passwordInput.setText(securePrefs.getString("password", ""));
    }

    private void saveCredentials() {
        securePrefs.edit()
                .putString("email", emailInput.getText().toString().trim())
                .putString("password", passwordInput.getText().toString())
                .apply();
    }

    private void startFlow() {
        if (running) return;

        if (!authenticated) {
            showLoginScreen();
            toast("Faça login primeiro.");
            return;
        }

        String device = deviceInput.getText().toString().trim().toUpperCase();
        String playlist = playlistInput.getText().toString().trim();

        if (!device.matches("^[A-Z0-9_-]{4,32}$")) {
            toast("Device Key inválida.");
            return;
        }

        int selected = flowSpinner.getSelectedItemPosition();
        activeFlow = selected == 1
                ? Flow.RESET
                : selected == 2
                    ? Flow.DELETE
                    : Flow.ACTIVATE;

        if (activeFlow != Flow.DELETE) {
            try {
                android.net.Uri playlistUri = android.net.Uri.parse(playlist);
                if (!"http".equalsIgnoreCase(playlistUri.getScheme())
                        || playlistUri.getHost() == null
                        || playlistUri.getHost().trim().isEmpty()) {
                    toast("A M3U do XCloud deve começar com http://.");
                    return;
                }
            } catch (Exception error) {
                toast("A M3U do XCloud deve começar com http://.");
                return;
            }
        }

        if (activeFlow == Flow.RESET || activeFlow == Flow.DELETE) {
            String title = activeFlow == Flow.RESET
                    ? "Confirmar reposição"
                    : "Confirmar exclusão";

            String message = activeFlow == Flow.RESET
                    ? "Confirma resetar o dispositivo "
                    + device
                    + "? O app vai excluir, ativar novamente e aplicar a M3U."
                    : "Confirma excluir o dispositivo "
                    + device
                    + "?";

            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setNegativeButton("CANCELAR", null)
                    .setPositiveButton(
                            "CONFIRMAR",
                            (dialog, which) -> beginFlow(device, playlist)
                    )
                    .show();

            return;
        }

        beginFlow(device, playlist);
    }

    private void beginFlow(String device, String playlist) {
        handler.removeCallbacksAndMessages(null);
        currentOperationId = UUID.randomUUID().toString();
        automationStage = AutomationStage.DEVICE_SEARCHING;
        currentDevice = device;
        currentPlaylist = playlist;

        loginInjected = false;
        deviceInjected = false;
        deleteInjected = false;
        playlistInjected = false;
        resetCycleCompleted = false;
        automationRetryCount = 0;
        running = true;

        progressList.removeAllViews();

        executeButton.setEnabled(false);
        executeButton.setText("PROCESSANDO...");

        addProgress(
                "INÍCIO",
                flowLabel(activeFlow),
                Color.rgb(255, 193, 7)
        );

        showOperationScreen();
        updateOperationScreen("INÍCIO", flowLabel(activeFlow), false, false);

        webView.loadUrl(
                PANEL_DEVICES + "?t=" + System.currentTimeMillis()
        );
    }

    private void handlePageFinished(String url) {
        if (url == null) return;

        String lower = url.toLowerCase();

        if (manualRefreshing) {
            manualRefreshing = false;

            if (refreshLayout != null) {
                refreshLayout.setRefreshing(false);
            }

            // Se a sessão tiver expirado, volta para a tela de login.
            // Mantém a regra nova: o login só acontece ao tocar em ENTRAR.
            if (lower.contains("/login") || lower.contains("#/login")) {
                showLoginScreen();
                loginStatus.setText(
                        "Sessão expirada. Toque em ENTRAR para reconectar."
                );
                return;
            }

            if (consoleView.getVisibility() == View.VISIBLE) {
                addProgress(
                        "PRONTO",
                        "Atualizado. Pronto para iniciar.",
                        Color.rgb(136, 148, 139)
                );
            }

            if (!running) return;
        }

        // Navegação manual pelo botão PAINEL não inicia automação.
        if (!running) return;

        if (!loginOnlyMode && (lower.contains("/login") || lower.contains("#/login"))) {
            automationStage = AutomationStage.FAILED;
            notifyOperationError();
            recordHistory(activeFlow, currentDevice, "Falha", "Sessão expirada durante a automação.");
            addProgress("SESSION_EXPIRED", "Sessão expirada. Entre novamente no painel.", Color.rgb(255, 92, 92));
            stopBusyOnly();
            showLoginScreen();
            loginStatus.setText("Sessão expirada. Toque em ENTRAR para reconectar.");
            return;
        }

        if (loginOnlyMode) {
            if ((lower.contains("/login") || lower.contains("#/login"))
                    && !loginInjected) {
                loginInjected = true;

                String script = JsScripts.login(
                        emailInput.getText().toString().trim(),
                        passwordInput.getText().toString()
                );

                webView.evaluateJavascript(script, null);
                loginStatus.setText("Autenticando...");

                handler.postDelayed(() -> {
                    if (loginOnlyMode && running) {
                        String currentUrl = webView.getUrl();
                        if (currentUrl != null
                                && !currentUrl.toLowerCase().contains("/login")
                                && !currentUrl.toLowerCase().contains("#/login")) {
                            showAppScreen();
                        }
                    }
                }, 4500);

                return;
            }

            // O painel principal indica que o login foi aceito.
            if (lower.contains("/dashboard")
                    && !lower.contains("/login")
                    && !lower.contains("#/login")) {
                showAppScreen();
                return;
            }
        }

        if (!loginOnlyMode) {
            addProgress(
                    "PÁGINA",
                    url,
                    Color.rgb(136, 148, 139)
            );
        }

        if (lower.contains("panel-v2.xtream.cloud") &&
                lower.contains("/dashboard/devices")) {
            handleDevicesPage();
            return;
        }

        if (lower.contains("xtream.cloud/custom-playlist") &&
                !playlistInjected) {
            playlistInjected = true;

            webView.evaluateJavascript(
                    JsScripts.addPlaylist(
                            currentDevice,
                            currentPlaylist,
                            currentOperationId
                    ),
                    null
            );
        }
    }

    private void handleDevicesPage() {
        // Depois de salvar a M3U, a prova final vem da própria tabela de dispositivos.
        // Isso evita falso erro quando o XCloud salva corretamente mas não exibe toast.
        if (automationStage == AutomationStage.PLAYLIST_VERIFYING) {
            webView.evaluateJavascript(
                    JsScripts.verifyPlaylistInDevices(
                            currentDevice,
                            currentPlaylist,
                            currentOperationId
                    ),
                    null
            );
            return;
        }

        if (activeFlow == Flow.DELETE) {
            if (deleteInjected) return;

            deleteInjected = true;
            webView.evaluateJavascript(
                    JsScripts.deleteDevice(currentDevice, currentOperationId, false),
                    null
            );
            return;
        }

        if (activeFlow == Flow.RESET && !resetCycleCompleted) {
            // RESET reutiliza exatamente o mesmo fluxo de EXCLUIR que já foi validado.
            // Só depois de DEVICE_DELETED ele passa para o mesmo fluxo de ATIVAR + M3U.
            if (deleteInjected) return;

            deleteInjected = true;
            webView.evaluateJavascript(
                    JsScripts.deleteDevice(currentDevice, currentOperationId, false),
                    null
            );
            return;
        }

        if (!deviceInjected) {
            deviceInjected = true;
            webView.evaluateJavascript(
                    JsScripts.addDevice(currentDevice, currentOperationId),
                    null
            );
        }
    }

    private void onAutomationMessage(String raw) {
        runOnUiThread(() -> {
            try {
                JSONObject obj = new JSONObject(raw);

                String type = obj.optString("type", "progress");
                String code = obj.optString("code", "EVENT");
                String message = obj.optString("message", "");
                String operationId = obj.optString("operationId", "");

                if (!loginOnlyMode) {
                    String currentUrl = webView.getUrl();
                    android.net.Uri currentUri = currentUrl == null ? null : android.net.Uri.parse(currentUrl);
                    String host = currentUri == null ? null : currentUri.getHost();
                    String scheme = currentUri == null ? null : currentUri.getScheme();
                    if (!running
                            || !currentOperationId.equals(operationId)
                            || !"https".equalsIgnoreCase(scheme)
                            || host == null
                            || !ALLOWED_HOSTS.contains(host)) {
                        return;
                    }
                }

                int color = Color.rgb(255, 193, 7);

                if ("success".equals(type)) {
                    color = Color.rgb(183, 255, 60);
                } else if ("error".equals(type)) {
                    color = Color.rgb(255, 107, 107);
                }

                if (loginOnlyMode) {
                    if ("LOGIN_FIELDS_NOT_FOUND".equals(code) && loginRetryCount < 2) {
                        loginRetryCount++;
                        loginInjected = false;
                        loginStatus.setText("Reconectando ao painel...");

                        webView.loadUrl(
                                "https://panel.xtream.cloud/login?t="
                                        + System.currentTimeMillis()
                        );
                        return;
                    }

                    if ("error".equals(type)) {
                        loginOnlyMode = false;
                        running = false;
                        loginButton.setEnabled(true);
                        loginButton.setText("ENTRAR");
                        loginStatus.setText(message);
                    } else {
                        loginStatus.setText(message);
                    }
                    return;
                }

                if ("DELETE_SEARCH".equals(code) || "RESET_SEARCH".equals(code)) automationStage = AutomationStage.DEVICE_SEARCHING;
                else if ("DEACTIVATING".equals(code) || "RESET_DEACTIVATING".equals(code) || "RESET_CONFIRM_DEACTIVATE".equals(code)) automationStage = AutomationStage.DEVICE_DEACTIVATING;
                else if ("RESET_ACTIVATING".equals(code)) automationStage = AutomationStage.DEVICE_ACTIVATING;
                else if ("RESET_VERIFYING".equals(code)) automationStage = AutomationStage.DEVICE_ACTIVE_VERIFYING;
                else if ("DELETING".equals(code)) automationStage = AutomationStage.DEVICE_DELETING;
                else if ("DELETE_VERIFYING".equals(code)) automationStage = AutomationStage.DELETE_VERIFYING;
                else if ("OPEN_DEVICE_DIALOG".equals(code) || "DEVICE_DIALOG_OPEN".equals(code) || "DEVICE_KEY_FILLED".equals(code)) automationStage = AutomationStage.DEVICE_ADDING;
                else if ("DEVICE_VERIFYING".equals(code)) automationStage = AutomationStage.DEVICE_VERIFYING;
                else if ("PLAYLIST_FILL".equals(code)) automationStage = AutomationStage.PLAYLIST_SAVING;
                else if ("PLAYLIST_VERIFYING".equals(code) || "PLAYLIST_VERIFY_ON_DEVICES".equals(code)) automationStage = AutomationStage.PLAYLIST_VERIFYING;

                addProgress(code, message, color);
                updateOperationScreen(code, message, "success".equals(type), "error".equals(type));

                if ("PLAYLIST_VERIFY_ON_DEVICES".equals(code) && activeFlow == Flow.RESET) {
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        if (!running || activeFlow != Flow.RESET) return;
                        automationStage = AutomationStage.COMPLETED;
                        String resetDone = "Reset + DNS concluído com sucesso.";
                        addProgress("CONCLUÍDO", resetDone, Color.rgb(72, 220, 120));
                        updateOperationScreen("CONCLUÍDO", resetDone, true, false);
                        notifyOperationSuccess();
                        running = false;
                        stopBusyOnly();
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            dismissOperationScreen();
                            showBrowser(false);
                        }, 1200);
                    }, 4500);
                }

                if ("PLAYLIST_VERIFY_ON_DEVICES".equals(code)) {
                    webView.loadUrl(PANEL_DEVICES + "?t=" + System.currentTimeMillis());
                    return;
                }

                if ("DEVICE_ADDED".equals(code)) {
                    webView.loadUrl(
                            PLAYLIST_BASE
                                    + "?device_key=" + android.net.Uri.encode(currentDevice)
                                    + "&type=xtream&mode=add"
                    );
                    return;
                }

                if ("DEVICE_DELETED".equals(code) && activeFlow == Flow.DELETE) {
                    finishFlow("Dispositivo removido.");
                    return;
                }

                if ("DEVICE_DELETED".equals(code) && activeFlow == Flow.RESET) {
                    // Primeira metade do RESET concluída. A partir daqui usamos
                    // exatamente o mesmo cadastro + M3U do fluxo ATIVAR.
                    resetCycleCompleted = true;
                    deleteInjected = false;
                    deviceInjected = false;
                    playlistInjected = false;
                    automationRetryCount = 0;
                    automationStage = AutomationStage.DEVICE_ADDING;

                    addProgress(
                            "RESET_RECREATE",
                            "Dispositivo excluído. Ativando novamente...",
                            Color.rgb(183, 255, 60)
                    );

                    webView.loadUrl(
                            PANEL_DEVICES + "?t=" + System.currentTimeMillis()
                    );
                    return;
                }

                if ("PLAYLIST_ADDED".equals(code)) {
                    finishFlow(
                            activeFlow == Flow.RESET
                                    ? "Editar (Reset) + DNS concluído."
                                    : "Ativar MAC + DNS concluído."
                    );
                    return;
                }

                if ("error".equals(type)) {
                    handleAutomationError(code, message);
                    return;
                }

            } catch (Exception error) {
                addProgress(
                        "ERRO",
                        "Mensagem inválida da WebView.",
                        Color.rgb(255, 107, 107)
                );
            }
        });
    }

    private void finishFlow(String message) {
        recordHistory(
                activeFlow,
                currentDevice,
                "Sucesso",
                message
        );

        addProgress(
                "CONCLUÍDO",
                message + " Pronto para o próximo dispositivo.",
                Color.rgb(0, 184, 255)
        );

        automationStage = AutomationStage.COMPLETED;
        updateOperationScreen("CONCLUÍDO", message, true, false);
        notifyOperationSuccess();
        running = false;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            dismissOperationScreen();
            showBrowser(false);
        }, 1400);
        automationRetryCount = 0;
        handler.removeCallbacksAndMessages(null);
        currentOperationId = "";

        deviceInput.setText("");
        playlistInput.setText("");
        playlistInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        if (togglePlaylistVisibilityButton != null) {
            togglePlaylistVisibilityButton.setText("MOSTRAR M3U");
        }
        currentDevice = "";
        currentPlaylist = "";

        executeButton.setEnabled(true);
        executeButton.setText("EXECUTAR");

        deviceInput.requestFocus();
    }

    private void notifyOperationSuccess() {
        playOperationTone(ToneGenerator.TONE_PROP_ACK, 180);
        vibrateOperation(new long[]{0, 80});
    }

    private void notifyOperationError() {
        playOperationTone(ToneGenerator.TONE_PROP_NACK, 320);
        vibrateOperation(new long[]{0, 120, 90, 120});
    }

    private void playOperationTone(int toneType, int durationMs) {
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85);
            tone.startTone(toneType, durationMs);
            handler.postDelayed(() -> {
                try {
                    tone.release();
                } catch (Exception ignored) {
                }
            }, durationMs + 120L);
        } catch (Exception ignored) {
            // O aviso sonoro nunca deve interferir na automação.
        }
    }

    @SuppressWarnings("deprecation")
    private void vibrateOperation(long[] pattern) {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } catch (Exception ignored) {
            // A vibração é complementar e não pode afetar o fluxo principal.
        }
    }

    private void stopBusyOnly() {
        running = false;
        executeButton.setEnabled(true);
        executeButton.setText("EXECUTAR");
    }

    private void toggleBrowser() {
        boolean openingPanel = webView.getVisibility() != View.VISIBLE;

        if (openingPanel) {
            // O botão PAINEL sempre abre o dashboard principal,
            // independentemente da última página usada pela automação.
            webView.loadUrl(
                    PANEL_DASHBOARD + "?t=" + System.currentTimeMillis()
            );
            showBrowser(true);
        } else {
            showBrowser(false);
        }
    }

    private void showBrowser(boolean show) {
        webView.setVisibility(show ? View.VISIBLE : View.GONE);
        consoleView.setVisibility(show ? View.GONE : View.VISIBLE);
        toggleBrowserButton.setText(show ? "VOLTAR" : "PAINEL");
    }

    private void showOperationScreen() {
        dismissOperationScreen();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(24), dp(24), dp(20));
        box.setBackgroundColor(Color.rgb(15, 18, 22));

        operationIcon = new TextView(this);
        operationIcon.setText(operationIconForFlow());
        operationIcon.setTextSize(36);
        operationIcon.setGravity(Gravity.CENTER);
        operationIcon.setPadding(0, 0, 0, dp(6));
        box.addView(operationIcon, matchHeight(dp(54)));

        operationTitle = new TextView(this);
        operationTitle.setText(operationTitleForFlow());
        operationTitle.setTextColor(Color.WHITE);
        operationTitle.setTextSize(21);
        operationTitle.setTypeface(null, 1);
        operationTitle.setGravity(Gravity.CENTER);
        box.addView(operationTitle);

        operationDevice = new TextView(this);
        operationDevice.setText(currentDevice);
        operationDevice.setTextColor(Color.rgb(0, 184, 255));
        operationDevice.setTextSize(16);
        operationDevice.setTypeface(null, 1);
        operationDevice.setGravity(Gravity.CENTER);
        operationDevice.setPadding(0, dp(8), 0, dp(18));
        box.addView(operationDevice);

        operationProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        operationProgress.setIndeterminate(false);
        operationProgress.setMax(100);
        operationProgress.setProgress(8);
        box.addView(operationProgress, matchHeight(dp(8)));

        operationStep = new TextView(this);
        operationStep.setText("Preparando operação...");
        operationStep.setTextColor(Color.WHITE);
        operationStep.setTextSize(17);
        operationStep.setTypeface(null, 1);
        operationStep.setGravity(Gravity.CENTER);
        operationStep.setPadding(0, dp(20), 0, dp(6));
        box.addView(operationStep);

        operationHint = new TextView(this);
        operationHint.setText("Não feche o aplicativo enquanto o processo estiver em andamento.");
        operationHint.setTextColor(Color.rgb(145, 155, 165));
        operationHint.setTextSize(12);
        operationHint.setGravity(Gravity.CENTER);
        operationHint.setPadding(dp(4), 0, dp(4), dp(4));
        box.addView(operationHint);

        operationDialog = new AlertDialog.Builder(this)
                .setView(box)
                .setCancelable(false)
                .create();
        operationDialog.setCanceledOnTouchOutside(false);
        operationDialog.show();

        if (operationDialog.getWindow() != null) {
            operationDialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            operationDialog.getWindow().setDimAmount(0.82f);
            operationDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
    }

    private void updateOperationScreen(String code, String message, boolean success, boolean error) {
        if (operationDialog == null || !operationDialog.isShowing()) return;

        int progress = operationProgressFor(code);
        if (operationProgress != null) operationProgress.setProgress(progress);
        if (operationStep != null) operationStep.setText(operationFriendlyStep(code, message));

        if (success && ("PLAYLIST_ADDED".equals(code)
                || ("DEVICE_DELETED".equals(code) && activeFlow == Flow.DELETE)
                || "CONCLUÍDO".equals(code))) {
            if (operationIcon != null) operationIcon.setText("✓");
            if (operationIcon != null) operationIcon.setTextColor(Color.rgb(183, 255, 60));
            if (operationTitle != null) operationTitle.setText("OPERAÇÃO CONCLUÍDA");
            if (operationHint != null) operationHint.setText("Tudo certo. Voltando ao dashboard...");
            if (operationProgress != null) operationProgress.setProgress(100);
        } else if (error || "ERRO".equals(code)) {
            if (operationIcon != null) operationIcon.setText("!");
            if (operationIcon != null) operationIcon.setTextColor(Color.rgb(255, 92, 92));
            if (operationTitle != null) operationTitle.setText("NÃO FOI POSSÍVEL CONCLUIR");
            if (operationHint != null) operationHint.setText("Confira os dados e tente novamente.");
        }
    }

    private String operationIconForFlow() {
        if (activeFlow == Flow.RESET) return "↻";
        if (activeFlow == Flow.DELETE) return "×";
        return "⚡";
    }

    private String operationTitleForFlow() {
        if (activeFlow == Flow.RESET) return "RESETANDO DISPOSITIVO";
        if (activeFlow == Flow.DELETE) return "EXCLUINDO DISPOSITIVO";
        return "ATIVANDO DISPOSITIVO";
    }

    private int operationProgressFor(String code) {
        if (code == null) return 8;
        if ("INÍCIO".equals(code)) return 8;
        if (code.contains("SEARCH")) return 18;
        if (code.contains("DIALOG") || code.contains("KEY_FILLED")) return 32;
        if (code.contains("SAVING") || code.contains("DEACTIV")) return 46;
        if (code.contains("DEVICE_ADDED") || code.contains("ACTIVAT")) return 62;
        if (code.contains("PLAYLIST_FILL")) return 74;
        if (code.contains("VERIFY")) return 88;
        if (code.contains("DELET")) return 78;
        if (code.contains("ADDED") || code.contains("CONCLUÍDO")) return 100;
        if (code.contains("ERROR") || code.contains("NOT_FOUND") || code.contains("TIMEOUT") || "ERRO".equals(code)) return 100;
        return 38;
    }

    private String operationFriendlyStep(String code, String message) {
        if (code == null) return message;
        if ("INÍCIO".equals(code)) return "Preparando operação...";
        if (code.contains("SEARCH")) return "Localizando dispositivo no XCloud...";
        if (code.contains("DIALOG") || code.contains("KEY_FILLED")) return "Preparando dados do dispositivo...";
        if (code.contains("DEACTIV")) return "Desativando dispositivo com segurança...";
        if (code.contains("ACTIVAT")) return "Ativando dispositivo...";
        if (code.contains("DELET")) return "Removendo dispositivo...";
        if (code.contains("DEVICE_SAVING")) return "Salvando dispositivo...";
        if (code.contains("DEVICE_ADDED")) return "Dispositivo confirmado. Preparando DNS...";
        if (code.contains("PLAYLIST_FILL")) return "Aplicando M3U / DNS...";
        if (code.contains("VERIFY")) return "Confirmando alterações no painel...";
        if (code.contains("ADDED") || "CONCLUÍDO".equals(code)) return "Operação concluída com sucesso.";
        if (code.contains("ERROR") || code.contains("NOT_FOUND") || code.contains("TIMEOUT") || "ERRO".equals(code)) return message;
        return friendlyStatus(code, message);
    }

    private void dismissOperationScreen() {
        try {
            if (operationDialog != null && operationDialog.isShowing()) {
                operationDialog.dismiss();
            }
        } catch (Exception ignored) {
        }
        operationDialog = null;
        operationIcon = null;
        operationTitle = null;
        operationDevice = null;
        operationStep = null;
        operationHint = null;
        operationProgress = null;
    }

    private String friendlyStatus(String code, String message) {
        if ("PRONTO".equals(code)) {
            return "Pronto para iniciar.";
        }

        if ("INÍCIO".equals(code)) {
            switch (activeFlow) {
                case RESET:
                    return "Repondo DNS...";
                case DELETE:
                    return "Excluindo MAC...";
                default:
                    return "Ativando MAC...";
            }
        }

        if ("OPEN_DEVICE_DIALOG".equals(code)
                || "DEVICE_DIALOG_OPEN".equals(code)
                || "DEVICE_KEY_FILLED".equals(code)
                || "DEVICE_SAVING".equals(code)
                || "DEVICE_ADDED".equals(code)) {
            return "Ativando MAC...";
        }

        if ("PLAYLIST_FILL".equals(code)
                || "PLAYLIST_ADDED".equals(code)) {
            return activeFlow == Flow.RESET
                    ? "Repondo DNS..."
                    : "Subindo M3U...";
        }

        if ("RESET_SEARCH".equals(code)
                || "RESET_DEACTIVATING".equals(code)
                || "RESET_CONFIRM_DEACTIVATE".equals(code)) {
            return "Desativando MAC...";
        }

        if ("RESET_ACTIVATING".equals(code)
                || "RESET_VERIFYING".equals(code)
                || "RESET_REACTIVATED".equals(code)) {
            return "Ativando MAC novamente...";
        }

        if ("DELETE_SEARCH".equals(code)
                || "DEACTIVATING".equals(code)
                || "DELETING".equals(code)
                || "DEVICE_DELETED".equals(code)) {
            return "Excluindo MAC...";
        }

        if ("RESET_CONTINUE".equals(code)) {
            return "Repondo DNS...";
        }

        if ("CONCLUÍDO".equals(code)) {
            return message;
        }

        return message;
    }

    private void addProgress(String code, String message, int color) {
        if (!detailedLogs && "PÁGINA".equals(code)) {
            return;
        }

        if (!detailedLogs) {
            progressList.removeAllViews();
        } else {
            while (progressList.getChildCount() >= 12) {
                progressList.removeViewAt(0);
            }
        }

        TextView line = new TextView(this);

        String displayText = friendlyStatus(code, message);
        if (detailedLogs) {
            displayText = displayText + "\n" + code + " • " + automationStage;
        }

        int displayColor = color;

        if ("CONCLUÍDO".equals(code)
                || code.contains("ADDED")
                || code.contains("DELETED")
                || code.contains("CONTINUE")) {
            displayColor = Color.rgb(0, 184, 255);
        } else if (code.contains("ERROR")
                || code.contains("NOT_FOUND")
                || code.contains("TIMEOUT")
                || code.contains("BLOCKED")) {
            displayColor = Color.rgb(255, 92, 92);
        } else if (!"PRONTO".equals(code)) {
            displayColor = Color.rgb(255, 166, 0);
        }

        line.setText("● " + displayText);
        line.setTextColor(displayColor);
        line.setTextSize(16);
        line.setTypeface(null, 1);
        line.setPadding(0, dp(18), 0, dp(18));
        line.setGravity(Gravity.CENTER_VERTICAL);

        progressList.addView(line);
    }

    private String flowLabel(Flow flow) {
        switch (flow) {
            case RESET:
                return "Editar (Reset) + DNS";
            case DELETE:
                return "Excluir MAC";
            default:
                return "Ativar MAC + DNS";
        }
    }

    private int currentWidthDp() {
        float density =
                getResources()
                        .getDisplayMetrics()
                        .density;

        return Math.round(
                getResources()
                        .getDisplayMetrics()
                        .widthPixels
                        / density
        );
    }

    private int currentHeightDp() {
        float density =
                getResources()
                        .getDisplayMetrics()
                        .density;

        return Math.round(
                getResources()
                        .getDisplayMetrics()
                        .heightPixels
                        / density
        );
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != FILE_CHOOSER_REQUEST_CODE) {
            return;
        }

        if (fileChooserCallback == null) {
            return;
        }

        Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        fileChooserCallback.onReceiveValue(results);
        fileChooserCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView.getVisibility() == View.VISIBLE) {
            showBrowser(false);
            return;
        }

        super.onBackPressed();
    }

    public class AutomationBridge {
        @JavascriptInterface
        public void postMessage(String message) {
            onAutomationMessage(message);
        }
    }

    private static class JsScripts {

        private static String quote(String value) {
            return JSONObject.quote(value);
        }

        static String login(String email, String password) {
            return "(function(){"
                    + "const EMAIL=" + quote(email) + ";"
                    + "const PASSWORD=" + quote(password) + ";"
                    + helperFunctions()
                    + "let attempts=0;"
                    + "const timer=setInterval(()=>{"
                    + "attempts++;"
                    + "const inputs=[...document.querySelectorAll('input')];"
                    + "const emailInput=inputs.find(i=>i.type==='email')"
                    + "||inputs.find(i=>/email|e-mail|user|usuario/i.test(i.name||''))"
                    + "||inputs.find(i=>/email|e-mail|usuario|usuário/i.test(i.placeholder||''))"
                    + "||inputs.find(i=>/username|email/i.test(i.autocomplete||''))"
                    + "||inputs.find(i=>i.type==='text');"
                    + "const passInput=inputs.find(i=>i.type==='password')"
                    + "||inputs.find(i=>/password|senha/i.test(i.name||''))"
                    + "||inputs.find(i=>/password|senha/i.test(i.placeholder||''))"
                    + "||inputs.find(i=>/current-password|password/i.test(i.autocomplete||''));"
                    + "if(!emailInput||!passInput){"
                    + "if(attempts>=60){clearInterval(timer);"
                    + "post('error','LOGIN_FIELDS_NOT_FOUND','Campos de login não encontrados.');}"
                    + "return;}"
                    + "clearInterval(timer);"
                    + "post('progress','LOGIN_FILL','Autenticando no painel...');"
                    + "setReactInput(emailInput,EMAIL);"
                    + "setReactInput(passInput,PASSWORD);"
                    + "const form=passInput.closest('form')||emailInput.closest('form');"
                    + "const submit=(form&&form.querySelector('button[type=\"submit\"]'))"
                    + "||[...document.querySelectorAll('button')].find(b=>"
                    + "/login|entrar|sign in/i.test((b.textContent||'').trim()));"
                    + "if(form&&typeof form.requestSubmit==='function'){form.requestSubmit();}"
                    + "else if(submit){clickReal(submit);}"
                    + "else if(form){form.submit();}"
                    + "post('progress','LOGIN_SUBMITTED','Login enviado.');"
                    + "},500);"
                    + "})();true;";
        }

        static String addDevice(String deviceKey, String operationId) {
            return "(function(){"
                    + "const MAC=" + quote(deviceKey) + ";"
                    + "const OP=" + quote(operationId) + ";"
                    + helperFunctions()
                    + "function findAdd(){"
                    + "const buttons=[...document.querySelectorAll('button')];"
                    + "let btn=buttons.find(b=>{"
                    + "const c=String(b.className||'');"
                    + "const t=(b.textContent||'').toLowerCase().trim();"
                    + "return c.includes('bg-primary')&&c.includes('w-full')"
                    + "&&(t.includes('add')||t.includes('novo'));});"
                    + "if(btn)return btn;"
                    + "btn=buttons.find(b=>{const c=String(b.className||'');"
                    + "return c.includes('bg-primary')&&c.includes('w-full');});"
                    + "if(btn)return btn;"
                    + "const plus=document.querySelector('svg.lucide-plus');"
                    + "if(plus)return plus.closest('button');"
                    + "return buttons.find(b=>{"
                    + "const t=(b.textContent||'').toLowerCase().trim();"
                    + "return (t.includes('add')&&t.includes('device'))"
                    + "||t==='add'||t.includes('adicionar dispositivo');})||null;}"
                    + "const add=findAdd();"
                    + "if(!add){post('error','ADD_DEVICE_BUTTON_NOT_FOUND','Botão Add Device não encontrado.');return;}"
                    + "post('progress','OPEN_DEVICE_DIALOG','Abrindo cadastro do dispositivo...');"
                    + "clickReal(add);"
                    + "let attempts=0;"
                    + "const timer=setInterval(()=>{"
                    + "attempts++;"
                    + "const dialog=document.querySelector('[role=\"dialog\"]');"
                    + "if(!dialog&&(attempts===4||attempts===8)){const r=findAdd();if(r)clickReal(r);}"
                    + "if(!dialog){if(attempts>=20){clearInterval(timer);"
                    + "post('error','DEVICE_DIALOG_TIMEOUT','O modal de cadastro não abriu.');}return;}"
                    + "clearInterval(timer);"
                    + "post('progress','DEVICE_DIALOG_OPEN','Cadastro aberto.');"
                    + "const inputs=[...dialog.querySelectorAll('input')];"
                    + "let keyInput=inputs.find(i=>{"
                    + "if(['radio','checkbox','hidden'].includes(i.type))return false;"
                    + "const p=(i.placeholder||'').toLowerCase();"
                    + "return p.includes('device')||p.includes('key')||p.includes('mac')"
                    + "||p.includes('codigo')||p.includes('código');});"
                    + "keyInput=keyInput||inputs.find(i=>!['radio','checkbox','hidden'].includes(i.type));"
                    + "if(!keyInput){post('error','DEVICE_KEY_INPUT_NOT_FOUND','Campo Device Key não encontrado.');return;}"
                    + "setReactInput(keyInput,MAC);keyInput.blur();"
                    + "post('progress','DEVICE_KEY_FILLED','Device Key preenchida.');"
                    + "setTimeout(()=>{"
                    + "const form=dialog.querySelector('form');"
                    + "let submit=form&&form.querySelector('button[type=\"submit\"]');"
                    + "if(!submit){submit=[...dialog.querySelectorAll('button')].reverse().find(b=>{"
                    + "const t=(b.textContent||'').toLowerCase().trim();"
                    + "return t&&!t.includes('cancel')&&!t.includes('voltar');});}"
                    + "post('progress','DEVICE_SAVING','Salvando dispositivo...');"
                    + "if(form&&typeof form.requestSubmit==='function'){form.requestSubmit();}"
                    + "else if(submit){clickReal(submit);}"
                    + "else if(form){form.submit();}"
                    + "else{post('error','DEVICE_SAVE_NOT_FOUND','Botão de salvar não encontrado.');return;}"
                    + "let wait=0;let verifyingShown=false;"
                    + "const close=setInterval(()=>{wait++;"
                    + "const rows=[...document.querySelectorAll('tr')];"
                    + "const found=rows.some(r=>(r.innerText||'').toUpperCase().includes(MAC.toUpperCase()));"
                    + "if(found){clearInterval(close);post('success','DEVICE_ADDED','Dispositivo cadastrado e confirmado no painel.');return;}"
                    + "const open=document.querySelector('[role=\"dialog\"]');"
                    + "if(!open&&!verifyingShown){verifyingShown=true;post('progress','DEVICE_VERIFYING','Confirmando cadastro no painel...');}"
                    + "if(wait>=40){clearInterval(close);post('error','DEVICE_ADD_NOT_CONFIRMED','O dispositivo não apareceu no painel após o cadastro.');}"
                    + "},500);"
                    + "},1200);"
                    + "},500);"
                    + "})();true;";
        }

        static String addPlaylist(String deviceKey, String playlistUrl, String operationId) {
            return "(function(){"
                    + "const MAC=" + quote(deviceKey) + ";"
                    + "const URL_LISTA=" + quote(playlistUrl) + ";"
                    + "const OP=" + quote(operationId) + ";"
                    + helperFunctions()
                    + "let attempts=0;"
                    + "const timer=setInterval(()=>{attempts++;"
                    + "const inputs=[...document.querySelectorAll('input')];"
                    + "const urlInput=inputs.find(i=>{"
                    + "const p=(i.placeholder||'').toLowerCase();"
                    + "const a=i.getAttribute('aria-label')||'';"
                    + "return p.includes('http')||p.includes('url')||p.includes('link')"
                    + "||p.includes('playlist')||/url|playlist/i.test(a);});"
                    + "if(!urlInput){if(attempts>=30){clearInterval(timer);"
                    + "post('error','PLAYLIST_URL_INPUT_NOT_FOUND','Campo da URL não encontrado.');}"
                    + "return;}"
                    + "clearInterval(timer);"
                    + "post('progress','PLAYLIST_FILL','Enviando lista...');"
                    + "urlInput.focus();setReactInput(urlInput,URL_LISTA);urlInput.blur();"
                    + "setTimeout(()=>{"
                    + "const form=urlInput.closest('form');"
                    + "const buttons=[...document.querySelectorAll('button')];"
                    + "const save=(form&&form.querySelector('button[type=\"submit\"]'))"
                    + "||buttons.find(b=>{const t=(b.textContent||'').toLowerCase();"
                    + "return t.includes('save')||t.includes('add')||t.includes('salvar');});"
                    + "if(form&&typeof form.requestSubmit==='function'){form.requestSubmit();}"
                    + "else if(save){clickReal(save);}"
                    + "else if(form){form.submit();}"
                    + "else{post('error','PLAYLIST_SAVE_NOT_FOUND','Botão Save não encontrado.');return;}"
                    + "post('progress','PLAYLIST_VERIFYING','Confirmando lista no painel...');"
                    + "let check=0;const verify=setInterval(()=>{check++;"
                    + "const notices=[...document.querySelectorAll('[role=\"alert\"],[role=\"status\"],.toast,.Toastify__toast,[class*=\"toast\"],[class*=\"alert\"]')];"
                    + "const txt=notices.map(n=>(n.innerText||n.textContent||'').toLowerCase()).join(' ');"
                    + "const bad=/invalid|failed|error|required|erro|inválid|falhou/.test(txt);"
                    + "const good=/success|saved|added|created|sucesso|salv|adicion/.test(txt);"
                    + "if(bad){clearInterval(verify);post('error','PLAYLIST_VISIBLE_ERROR','O painel informou erro ao salvar a lista.');}"
                    + "else if(good){clearInterval(verify);post('success','PLAYLIST_ADDED','Lista adicionada e confirmada.');}"
                    + "else if(check>=12){clearInterval(verify);post('progress','PLAYLIST_VERIFY_ON_DEVICES','Verificando a M3U na lista de dispositivos...');}"
                    + "},500);"
                    + "},900);"
                    + "},500);"
                    + "})();true;";
        }

        static String verifyPlaylistInDevices(String deviceKey, String playlistUrl, String operationId) {
            return "(function(){"
                    + "const MAC=" + quote(deviceKey) + ";"
                    + "const URL_LISTA=" + quote(playlistUrl) + ";"
                    + "const OP=" + quote(operationId) + ";"
                    + helperFunctions()
                    + "function rowPayload(row){let out=(row.innerText||row.textContent||'');"
                    + "row.querySelectorAll('*').forEach(el=>{['title','value','href','aria-label','data-original-title'].forEach(a=>{const v=el.getAttribute&&el.getAttribute(a);if(v)out+=' '+v;});});return out;}"
                    + "let tries=0;const verify=setInterval(()=>{tries++;"
                    + "const rows=[...document.querySelectorAll('tr')];"
                    + "const row=rows.find(r=>rowPayload(r).toUpperCase().includes(MAC.toUpperCase()));"
                    + "if(row){const hay=rowPayload(row);"
                    + "let expectedHost='';try{expectedHost=(new URL(URL_LISTA)).host.toLowerCase();}catch(e){}"
                    + "const low=hay.toLowerCase();"
                    + "const exact=hay.includes(URL_LISTA);"
                    + "const hostMatch=expectedHost&&low.includes(expectedHost);"
                    + "const hasHttp=/http:\\/\\//i.test(hay);"
                    + "if(exact||hostMatch||hasHttp){clearInterval(verify);post('success','PLAYLIST_ADDED','M3U salva e confirmada na tabela de dispositivos.');return;}"
                    + "}"
                    + "if(tries>=40){clearInterval(verify);post('error','PLAYLIST_NOT_CONFIRMED','A M3U não apareceu na tabela de dispositivos.');}"
                    + "},500);"
                    + "})();true;";
        }

        static String resetDevice(String deviceKey, String operationId) {
            return "(function(){"
                    + "const MAC=" + quote(deviceKey) + ";"
                    + "const OP=" + quote(operationId) + ";"
                    + helperFunctions()
                    + "function findRow(){return [...document.querySelectorAll('tr')].find(r=>"
                    + "(r.innerText||'').toUpperCase().includes(MAC.toUpperCase()));}"
                    + "function menuButton(row){"
                    + "const icon=row.querySelector('svg.lucide-ellipsis')"
                    + "||row.querySelector('svg[class*=\"ellipsis\"]');"
                    + "return icon?icon.closest('button'):null;}"
                    + "function action(words){"
                    + "const els=[...document.querySelectorAll('[role=\"menuitem\"]'),...document.querySelectorAll('button')];"
                    + "return els.find(e=>{const t=(e.textContent||'').trim().toLowerCase();"
                    + "return t&&t.length<80&&words.some(w=>t===w||t.includes(w));})||null;}"
                    + "function searchInput(){const inputs=[...document.querySelectorAll('input')];"
                    + "return inputs.find(i=>i.type==='search')||inputs.find(i=>/search|busca|filtr/i.test(i.placeholder||''))"
                    + "||inputs.find(i=>i.offsetParent!==null&&(i.type==='text'||!i.type));}"
                    + "function reopenMenu(done,fail){let n=0;const wait=setInterval(()=>{n++;const row=findRow();"
                    + "if(row){const m=menuButton(row);if(m){clearInterval(wait);clickReal(m);setTimeout(done,700);return;}}"
                    + "if(n>=40){clearInterval(wait);fail();}},500);}"
                    + "function confirmDeactivate(){let n=0;const wait=setInterval(()=>{n++;"
                    + "const dialogs=[...document.querySelectorAll('[role=\"dialog\"]')];const d=dialogs[dialogs.length-1];"
                    + "if(d){const b=[...d.querySelectorAll('button')].find(x=>{const t=(x.textContent||'').trim().toLowerCase();"
                    + "return t.includes('desativ')||t.includes('deactiv')||t.includes('disable');});"
                    + "if(b){clearInterval(wait);post('progress','RESET_CONFIRM_DEACTIVATE','Confirmando Desativar...');clickReal(b);"
                    + "setTimeout(waitForActivateAction,900);return;}}"
                    + "if(n>=20){clearInterval(wait);post('error','RESET_DEACTIVATE_CONFIRM_NOT_FOUND','Botão de confirmação Desativar não encontrado.');}},400);}"
                    + "function waitForActivateAction(){reopenMenu(()=>{"
                    + "const act=action(['activate','ativar','enable']);"
                    + "if(!act){post('error','RESET_ACTIVATE_NOT_FOUND','Ação Ativar não encontrada após a desativação.');return;}"
                    + "post('progress','RESET_ACTIVATING','Ativando novamente...');clickReal(act);setTimeout(verifyActive,900);"
                    + "},()=>post('error','SECOND_MENU_NOT_FOUND','Não foi possível localizar o dispositivo após desativar.'));}"
                    + "function verifyActive(){post('progress','RESET_VERIFYING','Confirmando que o dispositivo voltou a ficar ativo...');"
                    + "let n=0;const v=setInterval(()=>{n++;const row=findRow();if(row){const m=menuButton(row);if(m){"
                    + "clickReal(m);setTimeout(()=>{const deact=action(['deactivate','desativar','disable','inactiv']);"
                    + "if(deact){clearInterval(v);try{clickReal(m);}catch(e){}post('success','RESET_REACTIVATED','Dispositivo desativado e ativado novamente com sucesso.');}},450);}}"
                    + "if(n>=40){clearInterval(v);post('error','RESET_REACTIVATE_NOT_CONFIRMED','O painel não confirmou que o dispositivo voltou a ficar ativo.');}},500);}"
                    + "post('progress','RESET_SEARCH','Procurando dispositivo para reset...');"
                    + "const sb=[...document.querySelectorAll('button')].find(b=>{const t=(b.textContent||'').trim().toLowerCase();return t==='search'||t==='pesquisar';});if(sb)clickReal(sb);"
                    + "let attempts=0;const timer=setInterval(()=>{attempts++;const search=searchInput();"
                    + "if(!search){if(attempts>=20){clearInterval(timer);post('error','SEARCH_INPUT_NOT_FOUND','Campo de pesquisa não encontrado.');}return;}"
                    + "clearInterval(timer);setReactInput(search,MAC);setTimeout(()=>{const row=findRow();"
                    + "if(!row){post('error','DEVICE_NOT_FOUND','Dispositivo não encontrado.');return;}const menu=menuButton(row);"
                    + "if(!menu){post('error','DEVICE_MENU_NOT_FOUND','Menu não encontrado.');return;}clickReal(menu);setTimeout(()=>{"
                    + "const deact=action(['deactivate','desativar','disable','inactiv']);"
                    + "if(!deact){post('error','RESET_DEACTIVATE_NOT_FOUND','Ação Desativar não encontrada.');return;}"
                    + "post('progress','RESET_DEACTIVATING','Clicando em Desativar...');clickReal(deact);setTimeout(confirmDeactivate,500);"
                    + "},700);},700);},500);"
                    + "})();true;";
        }

        static String deleteDevice(String deviceKey, String operationId, boolean preferDirectDelete) {
            return "(function(){"
                    + "const MAC=" + quote(deviceKey) + ";"
                    + "const OP=" + quote(operationId) + ";"
                    + "const DIRECT=" + (preferDirectDelete ? "true" : "false") + ";"
                    + helperFunctions()
                    + "function findRow(){return [...document.querySelectorAll('tr')].find(r=>"
                    + "(r.innerText||'').toUpperCase().includes(MAC.toUpperCase()));}"
                    + "function menuButton(row){"
                    + "const icon=row.querySelector('svg.lucide-ellipsis')"
                    + "||row.querySelector('svg[class*=\"ellipsis\"]');"
                    + "return icon?icon.closest('button'):null;}"
                    + "function action(words){"
                    + "const els=[...document.querySelectorAll('[role=\"menuitem\"]'),"
                    + "...document.querySelectorAll('button')];"
                    + "return els.find(e=>{const t=(e.textContent||'').trim().toLowerCase();"
                    + "return t&&t.length<80&&words.some(w=>t.includes(w));})||null;}"
                    + "function verifyDeleted(){post('progress','DELETE_VERIFYING','Confirmando exclusão...');let n=0;const v=setInterval(()=>{n++;const row=findRow();if(!row){clearInterval(v);post('success','DEVICE_DELETED','Dispositivo removido e confirmado.');}else if(n>=12){clearInterval(v);post('error','DELETE_NOT_CONFIRMED','O painel não confirmou a exclusão do dispositivo.');}},500);}"
                    + "function confirmDialog(){"
                    + "const ds=[...document.querySelectorAll('[role=\"dialog\"]')];"
                    + "const d=ds[ds.length-1];if(!d)return false;"
                    + "const b=[...d.querySelectorAll('button')].find(x=>{"
                    + "const t=(x.textContent||'').trim().toLowerCase();"
                    + "return t&&!t.includes('cancel')&&!t.includes('não')"
                    + "&&!t.includes('nao')&&t!=='no';});"
                    + "if(!b)return false;clickReal(b);return true;}"
                    + "post('progress','DELETE_SEARCH','Procurando dispositivo...');"
                    + "const sb=[...document.querySelectorAll('button')].find(b=>{"
                    + "const t=(b.textContent||'').trim().toLowerCase();"
                    + "return t==='search'||t==='pesquisar';});"
                    + "if(sb)clickReal(sb);"
                    + "let attempts=0;"
                    + "const timer=setInterval(()=>{attempts++;"
                    + "const inputs=[...document.querySelectorAll('input')];"
                    + "const search=inputs.find(i=>i.type==='search')"
                    + "||inputs.find(i=>/search|busca|filtr/i.test(i.placeholder||''))"
                    + "||inputs.find(i=>i.offsetParent!==null&&(i.type==='text'||!i.type));"
                    + "if(!search){if(attempts>=20){clearInterval(timer);"
                    + "post('error','SEARCH_INPUT_NOT_FOUND','Campo de pesquisa não encontrado.');}"
                    + "return;}"
                    + "clearInterval(timer);setReactInput(search,MAC);"
                    + "setTimeout(()=>{"
                    + "const row=findRow();"
                    + "if(!row){post('error','DEVICE_NOT_FOUND','Dispositivo não encontrado.');return;}"
                    + "const menu=menuButton(row);"
                    + "if(!menu){post('error','DEVICE_MENU_NOT_FOUND','Menu não encontrado.');return;}"
                    + "clickReal(menu);"
                    + "setTimeout(()=>{"
                    + "const deact=action(['deactivate','desativar','disable','inactiv']);"
                    + "const del0=action(['delete','excluir','deletar','remove','apagar','trash','lixeira']);"
                    + "if(DIRECT&&del0){"
                    + "post('progress','DELETING','Removendo dispositivo para o reset...');"
                    + "clickReal(del0);"
                    + "setTimeout(()=>{confirmDialog();"
                    + "setTimeout(()=>verifyDeleted(),900);"
                    + "},900);"
                    + "return;"
                    + "}"
                    + "if(deact){"
                    + "post('progress','DEACTIVATING','Desativando no painel...');"
                    + "clickReal(deact);"
                    + "setTimeout(()=>{confirmDialog();"
                    + "setTimeout(()=>{"
                    + "const row2=findRow();"
                    + "if(!row2){post('error','DEVICE_NOT_VISIBLE_AFTER_DEACTIVATE','O dispositivo saiu da lista após desativar, mas a exclusão ainda não foi confirmada.');return;}"
                    + "const menu2=menuButton(row2);"
                    + "if(!menu2){post('error','SECOND_MENU_NOT_FOUND','Não foi possível reabrir o menu.');return;}"
                    + "clickReal(menu2);"
                    + "setTimeout(()=>{"
                    + "const del=action(['delete','excluir','deletar','remove','apagar','trash','lixeira']);"
                    + "if(!del){post('error','DELETE_ACTION_NOT_FOUND','Ação Delete não encontrada.');return;}"
                    + "post('progress','DELETING','Removendo dispositivo...');"
                    + "clickReal(del);"
                    + "setTimeout(()=>{confirmDialog();"
                    + "setTimeout(()=>verifyDeleted(),900);"
                    + "},900);"
                    + "},1400);"
                    + "},2400);"
                    + "},900);"
                    + "return;"
                    + "}"
                    + "if(del0){"
                    + "post('progress','DELETING','Removendo dispositivo...');"
                    + "clickReal(del0);"
                    + "setTimeout(()=>{confirmDialog();"
                    + "setTimeout(()=>verifyDeleted(),900);"
                    + "},900);"
                    + "return;"
                    + "}"
                    + "post('error','DELETE_ACTION_NOT_FOUND','Deactivate/Delete não encontrado.');"
                    + "},1200);"
                    + "},800);"
                    + "},500);"
                    + "})();true;";
        }

        private static String helperFunctions() {
            return ""
                    + "function post(type,code,message){"
                    + "AutomationBridge.postMessage(JSON.stringify({type,code,message,operationId:(typeof OP==='undefined'?'':OP)}));}"
                    + "function clickReal(button){"
                    + "try{button.dispatchEvent(new PointerEvent('pointerdown',{bubbles:true,cancelable:true}));"
                    + "button.dispatchEvent(new PointerEvent('pointerup',{bubbles:true,cancelable:true}));}"
                    + "catch(e){}button.click();}"
                    + "function setReactInput(input,value){"
                    + "const own=Object.getOwnPropertyDescriptor(input,'value');"
                    + "const proto=Object.getPrototypeOf(input);"
                    + "const pd=Object.getOwnPropertyDescriptor(proto,'value');"
                    + "if(pd&&pd.set&&(!own||own.set!==pd.set)){pd.set.call(input,value);}"
                    + "else if(own&&own.set){own.set.call(input,value);}"
                    + "else{input.value=value;}"
                    + "input.dispatchEvent(new Event('input',{bubbles:true}));"
                    + "input.dispatchEvent(new Event('change',{bubbles:true}));"
                    + "if(input._valueTracker){input._valueTracker.setValue('');"
                    + "input.dispatchEvent(new Event('input',{bubbles:true}));}"
                    + "}";
        }
    }
}
