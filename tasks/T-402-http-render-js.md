# T-402 · http_get 增加 JS 渲染模式

- 难度:⭐⭐⭐
- 状态:todo
- 波次:**Wave 1(可并行)**——文件集与 T-401/T-403/T-404 不相交。
- 允许修改的文件(白名单,严格遵守):
  - `core-tools/src/main/java/com/openclaw/clawagent/agent/WebRenderer.kt`(新建,纯 JVM 接口)
  - `core-tools/src/main/java/com/openclaw/clawagent/agent/HttpTool.kt`(加 `render_js` 参数与渲染分支)
  - `core-tools/src/test/java/com/openclaw/clawagent/agent/HttpRequestToolRenderTest.kt`(新建)
  - `app/src/main/java/com/openclaw/clawagent/agent/AndroidWebRenderer.kt`(新建,WebView 实现)
- 禁改:`AgentWiring.kt`(热点,接线归 T-405)、`core-agent/**`、其他文件。
- 依赖说明:`HttpRequestTool` 构造函数改为 `HttpRequestTool(private val renderer: WebRenderer? = null)`——**默认 null 保证不接线也能编译**;`render_js=true` 且无引擎时返回"当前环境没有可用的 JS 渲染引擎"降级错误。

## 背景

`http_get` 目前只做 HTML→纯文本,**不执行 JS**。现在大量页面是 SPA(客户端渲染),抓回来是空壳,Agent 对动态网页是瞎的。本卡引入可插拔"浏览器引擎":`:core-tools` 定义接口(保持无 Android import 的物理边界),`:app` 提供 WebView 实现。

## 任务

1. `WebRenderer` 接口:`@Throws fun render(url: String, timeoutMs: Long): String`——返回渲染后 HTML,失败抛 message 可读的异常(接口注释写明契约)。
2. `HttpRequestTool`:
   - 参数新增 `render_js`(boolean,默认 false),工具描述说明"SPA/抓回来几乎为空的页面改用 render_js=true";
   - `render_js=true` 时把 URL 交给注入的 renderer,渲染结果走**同一套** `HttpToolLogic.htmlToText` + `truncate` 管线(净化/截断逻辑与普通模式完全一致);
   - 输出标注 `HTTP 200(JS 渲染模式)`;渲染后提取不到文本时明示"页面可能反爬或需要登录";渲染失败给出错误 + "可改用普通模式重试";
   - 渲染墙钟预算 12s(页面加载 + settle)。
3. `AndroidWebRenderer`(app):WebView **必须在主线程**(平台约束)——`Handler(Looper.getMainLooper())` post + `CountDownLatch` 等待;`onPageFinished` 后延迟约 1.5s(等 XHR/fetch 内容落地)再 `evaluateJavascript("document.documentElement.outerHTML")`;回调结果经 `JSONTokener` 解 JSON 字符串字面量。
4. WebView 卫生(全部必须):`javaScriptEnabled=true` + `domStorageEnabled=true` + `blockNetworkImage=true`(省流量)+ `allowFileAccess=false` + `allowContentAccess=false` + `javaScriptCanOpenWindowsAutomatically=false`;**finally 里必然 destroy**(成功/失败/超时三条路径);全局兜底 latch 防泄漏。
5. 测试不碰网络:fake `WebRenderer`(返回固定 HTML / 抛异常)驱动渲染分支断言;无 renderer + render_js=true 的降级错误;`render_js` 缺省时走普通路径(不打网络——用非法 URL 之类的短路输入)。

## 验收标准

- CI 全绿,既有 `HttpRequestToolTest`/`HttpToolLogicAdversarialTest` 零回归。
- 渲染分支与普通分支共用净化/截断管线(测试断言同一 HTML 两种模式产出相同正文)。
- 手工(可选,真机):对某个 SPA 页面 `render_js=true` 能抓到正文。

## 交付记录

- 领取时间:2026-09-13 12:00+0800
- 完成时间:2026-09-13 12:20+0800
- commit:(见看板回填)
- 待接线:`app/.../agent/AgentWiring.kt` 中 `HttpRequestTool()` 改为 `HttpRequestTool(AndroidWebRenderer(context.applicationContext))`(由 T-405 执行)
- 关键决策:`WebRenderer` 接口置于 `:core-tools`(守住无 `android.*` import 的物理边界),`AndroidWebRenderer` 置于 `:app`;`HttpRequestTool` 构造函数加 `renderer: WebRenderer? = null`(默认空,不接线也能编译);渲染结果走**同一套** `htmlToText` + `truncate` 管线(测试断言两模式产出同一正文);渲染墙钟预算 12s;无引擎/渲染失败/空结果三条路径均降级为可读字符串(execute 永不 throw)
- 测试结果:新增 `HttpRequestToolRenderTest` **7 用例全绿**(委托渲染器/共用净化管线/无引擎降级/失败重试提示/空页反爬提示/默认 false 短路/非法 URL 先于渲染拒绝);core 全量 **181 用例零回归**
- 说明:`AndroidWebRenderer` 的 WebView 卫生(JS + DOM storage 开,图片/文件/content/弹窗关,成功/失败/超时三路径必 destroy,全局兜底 latch)本地沙盒无法编译(`:app` 需 Android SDK),由 CI 编译 + 真机验收覆盖
