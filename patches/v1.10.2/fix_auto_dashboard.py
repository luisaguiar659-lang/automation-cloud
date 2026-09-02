from pathlib import Path
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit("Uso: fix_auto_dashboard.py <raiz-do-projeto-android>")

root = Path(sys.argv[1])
repo_root = Path(__file__).resolve().parents[2]
base_patch = repo_root / "patches" / "v1.10.0" / "pro_operation_screen.py"
subprocess.run([sys.executable, str(base_patch), str(root)], check=True)

build_file = root / "app" / "build.gradle"
main_file = root / "app" / "src" / "main" / "java" / "com" / "automationcloud" / "app" / "MainActivity.java"


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Patch {label}: esperado 1 trecho, encontrado {count}.")
    return text.replace(old, new, 1)

# v1.10.2
build = build_file.read_text(encoding="utf-8")
build = replace_once(build, "versionCode 20", "versionCode 21", "versionCode")
build = replace_once(build, "versionName '1.10.1'", "versionName '1.10.2'", "versionName")
build_file.write_text(build, encoding="utf-8")

src = main_file.read_text(encoding="utf-8")

# O Handler global do motor pode ter callbacks limpos ao encerrar a automação.
# Para o fechamento da interface usamos um Handler exclusivo da UI, que não é
# afetado pelo reset interno dos fluxos Ativar / Reset / Excluir.
old_success = '''        handler.postDelayed(() -> {\n            dismissOperationScreen();\n            showBrowser(false);\n        }, 1400);\n'''
new_success = '''        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {\n            dismissOperationScreen();\n            showBrowser(false);\n        }, 1400);\n'''
src = replace_once(src, old_success, new_success, "handler independente para sucesso")

old_error = '''        handler.postDelayed(this::dismissOperationScreen, 3200);\n'''
new_error = '''        new android.os.Handler(android.os.Looper.getMainLooper())\n                .postDelayed(this::dismissOperationScreen, 3200);\n'''
src = replace_once(src, old_error, new_error, "handler independente para erro")

main_file.write_text(src, encoding="utf-8")
print("Patch v1.10.2 aplicado: retorno automático robusto ao dashboard")
