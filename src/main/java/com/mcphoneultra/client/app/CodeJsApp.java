package com.mcphoneultra.client.app;

import com.mcphoneultra.client.io.Paths;
import com.mcphoneultra.client.util.JsEngine;
import com.november.mcphone.api.client.ui.IPhonePage;

/** JS 编程：编辑 .js 文件，用内置 GraalJS 引擎直接运行。 */
public final class CodeJsApp extends BaseApp {

    public CodeJsApp() {
        super("codejs", true);
    }

    @Override
    protected IPhonePage createPage() {
        return new CodeEditorPage(Paths.dir("js"), ".js", code -> JsEngine.run(code, 5000));
    }
}
