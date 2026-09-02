from pathlib import Path
import sys

if len(sys.argv) != 2:
    raise SystemExit("Uso: front_dashboard.py <raiz-do-projeto-android>")

root = Path(sys.argv[1])
build_file = root / "app" / "build.gradle"
main_file = root / "app" / "src" / "main" / "java" / "com" / "automationcloud" / "app" / "MainActivity.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Patch {label}: esperado 1 trecho, encontrado {count}.")
    return text.replace(old, new, 1)


# ---- versão ----
build = build_file.read_text(encoding="utf-8")
build = replace_once(build, "versionCode 17", "versionCode 18", "versionCode")
build = replace_once(build, "versionName '1.8.0'", "versionName '1.9.0'", "versionName")
build_file.write_text(build, encoding="utf-8")

src = main_file.read_text(encoding="utf-8")

# IMPORTANTE: preserva PANEL_DEVICES exatamente como na v1.8.0 funcional
# (panel-v2.xtream.cloud). Os fluxos ATIVAR / RESET / EXCLUIR dependem dessa rota
# e também da validação do host existente em handlePageFinished().
#
# Para os botões visuais da área ACESSO RÁPIDO usamos constantes separadas com
# as rotas oficiais informadas pelo usuário. Assim a navegação manual muda sem
# tocar no motor de automação já validado.
src = replace_once(
    src,
    '    private static final String PANEL_DEVICES =\n            "https://panel-v2.xtream.cloud/dashboard/devices";\n',
    '    private static final String PANEL_DEVICES =\n            "https://panel-v2.xtream.cloud/dashboard/devices";\n\n'
    '    private static final String PANEL_DEVICES_VIEW =\n            "https://panel.xtream.cloud/dashboard/devices";\n\n'
    '    private static final String PANEL_SUBRESELLERS =\n            "https://panel.xtream.cloud/dashboard/subresellers";\n\n'
    '    private static final String PANEL_BRANDING =\n            "https://panel.xtream.cloud/dashboard/branding/tv-app";\n\n'
    '    private static final String PANEL_SETTINGS =\n            "https://panel.xtream.cloud/dashboard/settings";\n',
    "rotas visuais do painel",
)

# O botão PAINEL do topo não é mais necessário: os acessos passam a ficar todos
# na seção ACESSO RÁPIDO. Mantemos a instância internamente porque showBrowser()
# ainda a usa para alternar o texto, mas ela não é adicionada à barra superior.
src = replace_once(
    src,
    '        topBar.addView(toggleBrowserButton);\n',
    '        // Botão PAINEL removido da barra superior na v1.9.0.\n',
    "remoção do botão Painel",
)

# Coloca as funções mais importantes na frente da tela principal, sem alterar o
# formulário/automação já validado nas versões anteriores.
anchor = '''        form.addView(brandCard);\n        form.addView(sectionGap());\n\n        form.addView(sectionTitle("PAINEL DE AUTOMAÇÃO"));\n'''
quick_access = '''        form.addView(brandCard);\n        form.addView(sectionGap());\n\n        // ---------- ACESSO RÁPIDO ÀS FUNÇÕES DO XCLOUD ----------\n        form.addView(sectionTitle("ACESSO RÁPIDO"));\n\n        Button quickActivate = quickActionButton("⚡  ATIVAR DISPOSITIVO");\n        quickActivate.setOnClickListener(v -> {\n            if (running) {\n                toast("Aguarde a automação terminar.");\n                return;\n            }\n            flowSpinner.setSelection(0);\n            deviceInput.requestFocus();\n            consoleView.post(() -> consoleView.smoothScrollTo(0, Math.max(0, deviceInput.getTop() - dp(80))));\n        });\n        form.addView(quickActivate, matchHeight(dp(52)));\n        form.addView(verticalSpace(8));\n\n        Button quickDashboard = quickActionButton("▦  PAINEL DE CONTROLE");\n        quickDashboard.setOnClickListener(v -> openPanelUrl(PANEL_DASHBOARD));\n        form.addView(quickDashboard, matchHeight(dp(52)));\n        form.addView(verticalSpace(8));\n\n        Button quickDevices = quickActionButton("▣  DISPOSITIVOS");\n        quickDevices.setOnClickListener(v -> openPanelUrl(PANEL_DEVICES_VIEW));\n        form.addView(quickDevices, matchHeight(dp(52)));\n        form.addView(verticalSpace(8));\n\n        Button quickResellers = quickActionButton("♙  SUB-REVENDEDORES");\n        quickResellers.setOnClickListener(v -> openPanelUrl(PANEL_SUBRESELLERS));\n        form.addView(quickResellers, matchHeight(dp(52)));\n        form.addView(verticalSpace(8));\n\n        Button quickBrand = quickActionButton("◉  MARCA");\n        quickBrand.setOnClickListener(v -> openPanelUrl(PANEL_BRANDING));\n        form.addView(quickBrand, matchHeight(dp(52)));\n        form.addView(verticalSpace(8));\n\n        Button quickSettings = quickActionButton("⚙  CONFIGURAÇÕES");\n        quickSettings.setOnClickListener(v -> openPanelUrl(PANEL_SETTINGS));\n        form.addView(quickSettings, matchHeight(dp(52)));\n\n        form.addView(sectionGap());\n        form.addView(sectionTitle("PAINEL DE AUTOMAÇÃO"));\n'''
src = replace_once(src, anchor, quick_access, "bloco de acesso rápido")

# Método auxiliar dos atalhos. Ele apenas navega no WebView já autenticado,
# portanto não interfere nos fluxos ACTIVATE/RESET/DELETE.
method_anchor = '''    private TextView sectionTitle(String text) {\n'''
methods = '''    private Button quickActionButton(String text) {\n        boolean compact = currentWidthDp() < 380 || currentHeightDp() < 700;\n        Button button = new Button(this);\n        button.setText(text);\n        button.setTextColor(Color.WHITE);\n        button.setTextSize(compact ? 13 : 14);\n        button.setTypeface(null, 1);\n        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);\n        button.setAllCaps(false);\n        button.setPadding(dp(compact ? 14 : 18), 0, dp(12), 0);\n        button.setBackgroundResource(getDrawableId("bg_secondary_button", "drawable"));\n        return button;\n    }\n\n    private void openPanelUrl(String url) {\n        if (running) {\n            toast("Aguarde a automação terminar.");\n            return;\n        }\n        String separator = url.contains("?") ? "&" : "?";\n        webView.loadUrl(url + separator + "t=" + System.currentTimeMillis());\n        showBrowser(true);\n    }\n\n'''
src = replace_once(src, method_anchor, methods + method_anchor, "métodos de acesso rápido")

main_file.write_text(src, encoding="utf-8")
print("Patch v1.9.0 aplicado: automação v1.8 preservada + atalhos diretos + botão Painel removido")
