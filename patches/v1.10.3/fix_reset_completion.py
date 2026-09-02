from pathlib import Path
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit("Uso: fix_reset_completion.py <raiz-do-projeto-android>")

root = Path(sys.argv[1])
repo_root = Path(__file__).resolve().parents[2]
base_patch = repo_root / "patches" / "v1.10.2" / "fix_auto_dashboard.py"
subprocess.run([sys.executable, str(base_patch), str(root)], check=True)

build_file = root / "app" / "build.gradle"
main_file = root / "app" / "src" / "main" / "java" / "com" / "automationcloud" / "app" / "MainActivity.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Patch {label}: esperado 1 trecho, encontrado {count}.")
    return text.replace(old, new, 1)

# v1.10.3
build = build_file.read_text(encoding="utf-8")
build = replace_once(build, "versionCode 21", "versionCode 22", "versionCode")
build = replace_once(build, "versionName '1.10.2'", "versionName '1.10.3'", "versionName")
build_file.write_text(build, encoding="utf-8")

src = main_file.read_text(encoding="utf-8")

# No RESET, DEVICE_DELETED é apenas uma etapa intermediária. Não deve mostrar
# "OPERAÇÃO CONCLUÍDA" antes da recriação e confirmação da M3U/DNS.
old_success_ui = '''        if (success && ("PLAYLIST_ADDED".equals(code) || "DEVICE_DELETED".equals(code) || "CONCLUÍDO".equals(code))) {\n'''
new_success_ui = '''        if (success && ("PLAYLIST_ADDED".equals(code)\n                || ("DEVICE_DELETED".equals(code) && activeFlow == Flow.DELETE)\n                || "CONCLUÍDO".equals(code))) {\n'''
src = replace_once(src, old_success_ui, new_success_ui, "sucesso visual correto no reset")

# O RESET funcional chega à etapa PLAYLIST_VERIFY_ON_DEVICES depois de já ter
# recriado o dispositivo e aplicado a M3U/DNS. Em alguns aparelhos o callback
# final da verificação não retorna, deixando running=true para sempre. Criamos
# um fallback somente para RESET: aguardamos 4,5 s pelo callback normal. Se ele
# chegar, running já terá sido encerrado e nada acontece. Se não chegar, fechamos
# apenas a etapa final de confirmação, preservando Ativar e Excluir intactos.
message_anchor = '''                if ("PLAYLIST_VERIFY_ON_DEVICES".equals(code)) {\n'''
message_new = '''                if ("PLAYLIST_VERIFY_ON_DEVICES".equals(code) && activeFlow == Flow.RESET) {\n                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {\n                        if (!running || activeFlow != Flow.RESET) return;\n                        automationStage = AutomationStage.COMPLETED;\n                        String resetDone = "Reset + DNS concluído com sucesso.";\n                        addProgress("CONCLUÍDO", resetDone, Color.rgb(72, 220, 120));\n                        updateOperationScreen("CONCLUÍDO", resetDone, true, false);\n                        notifyOperationSuccess();\n                        running = false;\n                        stopBusyOnly();\n                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {\n                            dismissOperationScreen();\n                            showBrowser(false);\n                        }, 1200);\n                    }, 4500);\n                }\n\n                if ("PLAYLIST_VERIFY_ON_DEVICES".equals(code)) {\n'''
src = replace_once(src, message_anchor, message_new, "fallback final do reset")

main_file.write_text(src, encoding="utf-8")
print("Patch v1.10.3 aplicado: reset finaliza mesmo se o callback final de verificacao nao retornar")
