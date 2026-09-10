from pathlib import Path
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit("Uso: fix_reset_delete.py <raiz-do-projeto-android>")

root = Path(sys.argv[1])
repo_root = Path(__file__).resolve().parents[2]
base_patch = repo_root / "patches" / "v1.10.3" / "fix_reset_completion.py"
subprocess.run([sys.executable, str(base_patch), str(root)], check=True)

build_file = root / "app" / "build.gradle"
main_file = root / "app" / "src" / "main" / "java" / "com" / "automationcloud" / "app" / "MainActivity.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Patch {label}: esperado 1 trecho, encontrado {count}.")
    return text.replace(old, new, 1)

# v1.10.4
build = build_file.read_text(encoding="utf-8")
build = replace_once(build, "versionCode 22", "versionCode 23", "versionCode")
build = replace_once(build, "versionName '1.10.3'", "versionName '1.10.4'", "versionName")
build_file.write_text(build, encoding="utf-8")

src = main_file.read_text(encoding="utf-8")

# DELETE: se o menu já oferece Excluir, não desativa primeiro. O caminho anterior
# preferia Desativar e dependia da linha continuar visível para só então excluir.
old_delete = '''        if (activeFlow == Flow.DELETE) {\n            if (deleteInjected) return;\n\n            deleteInjected = true;\n            webView.evaluateJavascript(\n                    JsScripts.deleteDevice(currentDevice, currentOperationId, false),\n                    null\n            );\n            return;\n        }\n'''
new_delete = '''        if (activeFlow == Flow.DELETE) {\n            if (deleteInjected) return;\n\n            deleteInjected = true;\n            webView.evaluateJavascript(\n                    JsScripts.deleteDevice(currentDevice, currentOperationId, true),\n                    null\n            );\n            return;\n        }\n'''
src = replace_once(src, old_delete, new_delete, "delete direto")

# RESET: a primeira etapa é uma exclusão real. Também deve priorizar Excluir
# diretamente, evitando ficar preso no ciclo Desativar -> linha desaparece.
old_reset = '''        if (activeFlow == Flow.RESET && !resetCycleCompleted) {\n            // RESET reutiliza exatamente o mesmo fluxo de EXCLUIR que já foi validado.\n            // Só depois de DEVICE_DELETED ele passa para o mesmo fluxo de ATIVAR + M3U.\n            if (deleteInjected) return;\n\n            deleteInjected = true;\n            webView.evaluateJavascript(\n                    JsScripts.deleteDevice(currentDevice, currentOperationId, false),\n                    null\n            );\n            return;\n        }\n'''
new_reset = '''        if (activeFlow == Flow.RESET && !resetCycleCompleted) {\n            // RESET reutiliza o fluxo de EXCLUIR, priorizando exclusão direta quando\n            // o painel já oferece a ação. Só após DEVICE_DELETED recria + aplica M3U.\n            if (deleteInjected) return;\n\n            deleteInjected = true;\n            webView.evaluateJavascript(\n                    JsScripts.deleteDevice(currentDevice, currentOperationId, true),\n                    null\n            );\n            return;\n        }\n'''
src = replace_once(src, old_reset, new_reset, "reset com delete direto")

# O painel pode demorar para refletir a remoção. Antes eram 12 x 500 ms = 6 s.
# Damos até 30 x 500 ms = 15 s antes de considerar falha.
old_verify = '''                    + "function verifyDeleted(){post('progress','DELETE_VERIFYING','Confirmando exclusão...');let n=0;const v=setInterval(()=>{n++;const row=findRow();if(!row){clearInterval(v);post('success','DEVICE_DELETED','Dispositivo removido e confirmado.');}else if(n>=12){clearInterval(v);post('error','DELETE_NOT_CONFIRMED','O painel não confirmou a exclusão do dispositivo.');}},500);}"\n'''
new_verify = '''                    + "function verifyDeleted(){post('progress','DELETE_VERIFYING','Confirmando exclusão...');let n=0;const v=setInterval(()=>{n++;const row=findRow();if(!row){clearInterval(v);post('success','DEVICE_DELETED','Dispositivo removido e confirmado.');}else if(n>=30){clearInterval(v);post('error','DELETE_NOT_CONFIRMED','O painel não confirmou a exclusão do dispositivo.');}},500);}"\n'''
src = replace_once(src, old_verify, new_verify, "timeout de confirmação do delete")

main_file.write_text(src, encoding="utf-8")
print("Patch v1.10.4 aplicado: DELETE/RESET priorizam exclusao direta e aguardam ate 15s pela confirmacao")
