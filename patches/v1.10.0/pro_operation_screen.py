from pathlib import Path
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit("Uso: pro_operation_screen.py <raiz-do-projeto-android>")

root = Path(sys.argv[1])
repo_root = Path(__file__).resolve().parents[2]
base_patch = repo_root / "patches" / "v1.9.0" / "front_dashboard.py"
subprocess.run([sys.executable, str(base_patch), str(root)], check=True)

build_file = root / "app" / "build.gradle"
main_file = root / "app" / "src" / "main" / "java" / "com" / "automationcloud" / "app" / "MainActivity.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Patch {label}: esperado 1 trecho, encontrado {count}.")
    return text.replace(old, new, 1)


# ---- versão v1.10.0 ----
build = build_file.read_text(encoding="utf-8")
build = replace_once(build, "versionCode 18", "versionCode 19", "versionCode")
build = replace_once(build, "versionName '1.9.0'", "versionName '1.10.0'", "versionName")
build_file.write_text(build, encoding="utf-8")

src = main_file.read_text(encoding="utf-8")

# ProgressBar para a nova tela de operação.
src = replace_once(
    src,
    "import android.widget.LinearLayout;\n",
    "import android.widget.LinearLayout;\nimport android.widget.ProgressBar;\n",
    "import ProgressBar",
)

# Componentes visuais da operação. Nenhum deles altera o motor WebView/JS.
src = replace_once(
    src,
    "    private TextView connectionStatus;\n",
    "    private TextView connectionStatus;\n"
    "    private AlertDialog operationDialog;\n"
    "    private TextView operationIcon;\n"
    "    private TextView operationTitle;\n"
    "    private TextView operationDevice;\n"
    "    private TextView operationStep;\n"
    "    private TextView operationHint;\n"
    "    private ProgressBar operationProgress;\n",
    "campos da tela de operação",
)

# Abre a experiência profissional exatamente quando uma operação real começa.
begin_anchor = '''        addProgress(\n                "INÍCIO",\n                flowLabel(activeFlow),\n                Color.rgb(255, 193, 7)\n        );\n\n        webView.loadUrl(\n'''
begin_new = '''        addProgress(\n                "INÍCIO",\n                flowLabel(activeFlow),\n                Color.rgb(255, 193, 7)\n        );\n\n        showOperationScreen();\n        updateOperationScreen("INÍCIO", flowLabel(activeFlow), false, false);\n\n        webView.loadUrl(\n'''
src = replace_once(src, begin_anchor, begin_new, "abertura da tela de operação")

# Reflete cada etapa que o motor funcional já envia, sem interferir nas decisões.
message_anchor = '''                addProgress(code, message, color);\n\n                if ("PLAYLIST_VERIFY_ON_DEVICES".equals(code)) {\n'''
message_new = '''                addProgress(code, message, color);\n                updateOperationScreen(code, message, "success".equals(type), "error".equals(type));\n\n                if ("PLAYLIST_VERIFY_ON_DEVICES".equals(code)) {\n'''
src = replace_once(src, message_anchor, message_new, "atualização visual das etapas")

# Estado final de sucesso permanece visível por um instante antes de voltar ao painel.
finish_anchor = '''        automationStage = AutomationStage.COMPLETED;\n        notifyOperationSuccess();\n        running = false;\n'''
finish_new = '''        automationStage = AutomationStage.COMPLETED;\n        updateOperationScreen("CONCLUÍDO", message, true, false);\n        notifyOperationSuccess();\n        handler.postDelayed(this::dismissOperationScreen, 1700);\n        running = false;\n'''
src = replace_once(src, finish_anchor, finish_new, "final visual de sucesso")

# Em erro, mostra um estado claro e fecha automaticamente depois de alguns segundos.
error_anchor = '''        addProgress(\n                "ERRO",\n                message,\n                Color.rgb(255, 92, 92)\n        );\n\n        stopBusyOnly();\n'''
error_new = '''        addProgress(\n                "ERRO",\n                message,\n                Color.rgb(255, 92, 92)\n        );\n        updateOperationScreen("ERRO", message, false, true);\n        handler.postDelayed(this::dismissOperationScreen, 3200);\n\n        stopBusyOnly();\n'''
src = replace_once(src, error_anchor, error_new, "final visual de erro")

# Ao sair da conta, garante que nenhuma tela de processamento fique aberta.
logout_anchor = '''    private void logout() {\n        running = false;\n'''
logout_new = '''    private void logout() {\n        dismissOperationScreen();\n        running = false;\n'''
src = replace_once(src, logout_anchor, logout_new, "fechar operação no logout")

# Métodos visuais isolados. Não navegam, não injetam JavaScript e não alteram stages.
methods_anchor = '''    private String friendlyStatus(String code, String message) {\n'''
methods = r'''    private void showOperationScreen() {
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

        if (success && ("PLAYLIST_ADDED".equals(code) || "DEVICE_DELETED".equals(code) || "CONCLUÍDO".equals(code))) {
            if (operationIcon != null) operationIcon.setText("✓");
            if (operationIcon != null) operationIcon.setTextColor(Color.rgb(183, 255, 60));
            if (operationTitle != null) operationTitle.setText("OPERAÇÃO CONCLUÍDA");
            if (operationHint != null) operationHint.setText("Tudo certo. Preparando para a próxima operação.");
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

'''
src = replace_once(src, methods_anchor, methods + methods_anchor, "métodos da tela profissional")

main_file.write_text(src, encoding="utf-8")
print("Patch v1.10.0 aplicado: tela profissional de Ativar / Reset / Excluir, preservando o motor funcional")
