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

# Estado usado apenas pelos atalhos de navegação do painel.
src = replace_once(
    src,
    '    private String currentPlaylist = "";\n',
    '    private String currentPlaylist = "";\n'
    '    private String pendingPanelSection = "";\n',
    "campo pendingPanelSection",
)

# Coloca as funções mais importantes na frente da tela principal, sem alterar o
# formulário/automação já validado nas versões anteriores.
anchor = '''        form.addView(brandCard);\n        form.addView(sectionGap());\n\n        form.addView(sectionTitle("PAINEL DE AUTOMAÇÃO"));\n'''
quick_access = '''        form.addView(brandCard);\n        form.addView(sectionGap());\n\n        // ---------- ACESSO RÁPIDO ÀS FUNÇÕES DO XCLOUD ----------\n        form.addView(sectionTitle("ACESSO RÁPIDO"));\n\n        Button quickActivate = quickActionButton("⚡  ATIVAR DISPOSITIVO");\n        quickActivate.setOnClickListener(v -> {\n            if (running) {\n                toast("Aguarde a automação terminar.");\n                return;\n            }\n            flowSpinner.setSelection(0);\n            deviceInput.requestFocus();\n            consoleView.post(() -> consoleView.smoothScrollTo(0, Math.max(0, deviceInput.getTop() - dp(80))));\n        });\n        form.addView(quickActivate, matchHeight(dp(52)));\n        form.addView(verticalSpace(8));\n\n        Button quickDashboard = quickActionButton("▦  PAINEL DE CONTROLE");\n        quickDashboard.setOnClickListener(v -> openPanelUrl(PANEL_DASHBOARD));\n        form.addView(quickDashboard, matchHeight(dp(52)));\n        form.addView(verticalSpace(8));\n\n        Button quickDevices = quickActionButton("▣  DISPOSITIVOS");\n        quickDevices.setOnClickListener(v -> openPanelUrl(PANEL_DEVICES));\n        form.addView(quickDevices, matchHeight(dp(52)));\n        form.addView(verticalSpace(8));\n\n        Button quickResellers = quickActionButton("♙  SUB-REVENDEDORES");\n        quickResellers.setOnClickListener(v -> openPanelSection("sub-revendedores"));\n        form.addView(quickResellers, matchHeight(dp(52)));\n        form.addView(verticalSpace(8));\n\n        Button quickBrand = quickActionButton("◉  MARCA");\n        quickBrand.setOnClickListener(v -> openPanelSection("marca"));\n        form.addView(quickBrand, matchHeight(dp(52)));\n        form.addView(verticalSpace(8));\n\n        Button quickSettings = quickActionButton("⚙  CONFIGURAÇÕES");\n        quickSettings.setOnClickListener(v -> openPanelSection("configuracoes"));\n        form.addView(quickSettings, matchHeight(dp(52)));\n\n        form.addView(sectionGap());\n        form.addView(sectionTitle("PAINEL DE AUTOMAÇÃO"));\n'''
src = replace_once(src, anchor, quick_access, "bloco de acesso rápido")

# Métodos auxiliares dos atalhos. Eles apenas navegam no WebView já autenticado,
# portanto não interferem nos fluxos ACTIVATE/RESET/DELETE.
method_anchor = '''    private TextView sectionTitle(String text) {\n'''
methods = '''    private Button quickActionButton(String text) {\n        boolean compact = currentWidthDp() < 380 || currentHeightDp() < 700;\n        Button button = new Button(this);\n        button.setText(text);\n        button.setTextColor(Color.WHITE);\n        button.setTextSize(compact ? 13 : 14);\n        button.setTypeface(null, 1);\n        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);\n        button.setAllCaps(false);\n        button.setPadding(dp(compact ? 14 : 18), 0, dp(12), 0);\n        button.setBackgroundResource(getDrawableId("bg_secondary_button", "drawable"));\n        return button;\n    }\n\n    private void openPanelUrl(String url) {\n        if (running) {\n            toast("Aguarde a automação terminar.");\n            return;\n        }\n        pendingPanelSection = "";\n        String separator = url.contains("?") ? "&" : "?";\n        webView.loadUrl(url + separator + "t=" + System.currentTimeMillis());\n        showBrowser(true);\n    }\n\n    private void openPanelSection(String section) {\n        if (running) {\n            toast("Aguarde a automação terminar.");\n            return;\n        }\n        pendingPanelSection = section == null ? "" : section;\n        webView.loadUrl(PANEL_DASHBOARD + "?t=" + System.currentTimeMillis());\n        showBrowser(true);\n    }\n\n'''
src = replace_once(src, method_anchor, methods + method_anchor, "métodos de acesso rápido")

# Quando o dashboard terminar de carregar, clica no item correspondente do menu
# lateral. Usamos os rótulos visíveis em vez de fixar rotas internas do site,
# deixando o app mais resistente a mudanças de URL do Xtream.
page_anchor = '''        String lower = url.toLowerCase();\n\n        if (manualRefreshing) {\n'''
page_logic = '''        String lower = url.toLowerCase();\n\n        if (!running && authenticated && pendingPanelSection != null && !pendingPanelSection.isEmpty()) {\n            final String section = pendingPanelSection;\n            pendingPanelSection = "";\n            handler.postDelayed(\n                    () -> webView.evaluateJavascript(JsScripts.openPanelSection(section), null),\n                    700\n            );\n        }\n\n        if (manualRefreshing) {\n'''
src = replace_once(src, page_anchor, page_logic, "navegação para seção")

# Script isolado para encontrar itens do menu por texto. Não envia eventos à
# automação e não toca nos scripts usados para ativar/resetar/excluir.
js_anchor = '''        private static String helperFunctions() {\n'''
js_method = '''        static String openPanelSection(String section) {\n            return "(function(){"\n                    + "const SECTION=" + quote(section) + ";"\n                    + "const norm=s=>(s||'').normalize('NFD').replace(/[\\u0300-\\u036f]/g,'').toLowerCase().trim();"\n                    + "const aliases={"\n                    + "'sub-revendedores':['sub-revendedores','sub revendedores','sub-resellers','resellers'],"\n                    + "'marca':['marca','brand'],"\n                    + "'configuracoes':['configuracoes','settings']"\n                    + "};"\n                    + "const terms=(aliases[SECTION]||[SECTION]).map(norm);"\n                    + "let tries=0;const timer=setInterval(()=>{tries++;"\n                    + "const els=[...document.querySelectorAll('a,button,[role=\\\"menuitem\\\"]')];"\n                    + "const target=els.find(el=>{const t=norm(el.innerText||el.textContent);return t&&terms.some(term=>t===term||t.includes(term));});"\n                    + "if(target){clearInterval(timer);target.click();return;}"\n                    + "if(tries>=20)clearInterval(timer);"\n                    + "},300);"\n                    + "})();true;";\n        }\n\n'''
src = replace_once(src, js_anchor, js_method + js_anchor, "script de navegação")

main_file.write_text(src, encoding="utf-8")
print("Patch v1.9.0 aplicado: acesso rápido + versionCode 18 + versionName 1.9.0")
