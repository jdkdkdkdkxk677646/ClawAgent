# T-401 · run_js 代码执行沙箱(Rhino)

- 难度:⭐⭐⭐
- 状态:todo
- 波次:**Wave 1(可并行)**——文件集与 T-402/T-403/T-404 不相交。
- 允许修改的文件(白名单,严格遵守):
  - `core-tools/src/main/java/com/openclaw/clawagent/agent/JsTool.kt`(新建)
  - `core-tools/src/main/java/com/openclaw/clawagent/agent/Toolsets.kt`(注册进 `Toolsets.core()`)
  - `core-tools/build.gradle.kts`(仅允许新增 `org.mozilla:rhino:1.7.14` 依赖)
  - `core-tools/src/test/java/com/openclaw/clawagent/agent/JsToolTest.kt`(新建)
- 禁改:`app/**`、`core-agent/**`、其他 core-tools 文件。
- 交付时必填"待接线"条目(见交付记录模板):`AgentWiring.kt` 的注册由 T-405 统一做。

## 背景

模型"脑算"日期、统计、批量文本处理时容易出错,而 Agent 目前没有任何**执行与验证**手段(`calculator` 只覆盖四则和幂运算)。给 Agent 一只"代码外挂":在受限沙箱里跑 JavaScript,把"我觉得"变成"我验证过"。这是第五批里价值最高的一张卡。

## 任务

1. 新建 `JsTool : AgentTool`,name=`run_js`,内嵌 **Rhino 1.7.14** 解释执行。参数:`code`(必填)、`timeout_ms`(可选,默认 3000,下限 500、上限 8000)。
2. **沙箱硬约束(全部必须)**:
   - `setOptimizationLevel(-1)`——解释器模式,ART 上无 JIT;
   - `setAllowJavaAccess(false)`——封死 `java.lang.Runtime` 等 LiveConnect 逃逸路径;
   - `ContextFactory.observeInstructionCount` 指令预算(约 5000 万)+ `future.get` 墙钟超时**双保险**,超限抛 Error 并捕获为可读错误字符串;
   - `setMaximumInterpreterStackDepth(256)` 防递归爆栈;
   - 每次运行 `initStandardObjects` 全新作用域——两次调用之间无状态泄漏;
   - `code` 长度上限 20000 字符。
3. `console.log/info/warn/error` 捕获进输出;返回值为对象/数组时用 `NativeJSON.stringify` 序列化;undefined 明示"无返回值"。
4. 遵循项目约定:`execute` 永不 throw;所有失败(语法错误/运行异常/超时/缺参)返回中文可读错误字符串,附 Rhino 行号。
5. 工具描述要教模型何时用它:精确计算、JSON/文本加工、日期推算等"口算易错"场景;并说明 ES6 支持范围(箭头函数/let/const/模板字符串可用,Promise/async 不保证)。

## 验收标准

- CI 全绿,既有用例零回归。
- `JsToolTest` 至少覆盖:精确计算正确、console 捕获、对象返回 JSON 序列化、`while(true){}` 在限期(<5s)内中止且返回可读错误、Java 访问被拒(断言 `java.lang.Runtime` 不可用)、两次运行间无状态泄漏、缺参/坏 JSON 降级为错误字符串。
- 手工(可选):`{"code":"JSON.parse('[1,2,3]').map(x=>x*2)"}` 返回 `[4,6,8]` 之类结果。

## 交付记录

(领取时填:领取时间;完成后填:完成时间 / commit / 关键决策 / 测试结果)
- 领取时间:—
- 完成时间:—
- commit:—
- 待接线:`app/.../agent/AgentWiring.kt` 的 `forAndroid` 工具清单中增加 `JsTool()` 一行(由 T-405 执行)
- 关键决策/测试结果:—
