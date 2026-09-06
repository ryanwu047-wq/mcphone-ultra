package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.io.Store;
import com.mcphoneultra.client.util.Exec;
import com.november.mcphone.api.client.ui.IPhonePage;

import java.nio.file.Path;

/** Python 编程：编辑 .py 文件，调用系统 Python 运行（未安装时明确提示）。 */
public final class PythonApp extends BaseApp {

    public PythonApp() {
        super("python", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new CodeEditorPage(Paths.dir("python"), ".py", PythonApp::runPython);
    }

    private static String runPython(String code) {
        String py = Exec.pythonPath();
        if (py.isEmpty()) {
            return "未找到 Python。\n請先安裝 Python 3 並加入 PATH，\n或修改 PATH 後重啟遊戲。";
        }
        Path tmp = Paths.file("python", "_run.py");
        Store.writeAll(tmp, code);
        Exec.Result r = Exec.run(py, Exec.norm(tmp));
        StringBuilder sb = new StringBuilder();
        if (!r.stdout().isEmpty()) sb.append(r.stdout());
        if (!r.stderr().isEmpty()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("【stderr】\n").append(r.stderr());
        }
        if (r.code() != 0) {
            sb.append("\n【退出碼 ").append(r.code()).append("】");
        }
        if (sb.isEmpty()) sb.append("（沒有輸出）");
        return sb.toString();
    }
}
