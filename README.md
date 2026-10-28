# OpsPilot

Java 故障诊断 Agent：真实故障注入 → 只读证据调查 → 结构化诊断 → 人工审批 → 修复与恢复验证。

## 运行

需要 JDK 21、Maven 3.9+。在项目根目录运行：

~~~sh
mvn -B -ntp verify
java -jar target/ops-pilot-1.0.0.jar
~~~

访问 http://127.0.0.1:8080 。Windows 也可使用 scripts/start.ps1 和 scripts/stop.ps1。

无需外部数据库或容器。工作记录保存在 data/opspilot，故障实验室使用独立的 H2 内存数据库、Hikari 连接池和仅监听回环地址的 HTTP 库存服务。

## 体验

1. 点击 SQL 延迟、连接池耗尽或下游超时，实际注入故障并开始调查。
2. 展开证据档案，检查 pool.acquire、jdbc.query、http.inventory 的真实请求耗时。
3. 查看诊断和 Runbook，审核修复建议后确认执行。
4. 系统采集至少 12 个新请求，验证错误率、P95、SQL 与连接等待耗时。
5. 运行四场景评测，对比预期分类与诊断输出。评测结束恢复健康窗口。

## 两种诊断模式

默认规则基线不调用模型。它根据工具返回的指标和调用链选择分支，不读取注入场景名称，也不伪装成大模型。

模型 Agent 使用 Spring AI 的 OpenAI 兼容接口，由模型选择工具与参数。设置以下环境变量后重启，在页面选择模型模式：

~~~powershell
$env:AGENT_API_KEY = 'your-key'
$env:AGENT_BASE_URL = 'https://api.openai.com'
$env:AGENT_MODEL = 'gpt-4.1-mini'
java -jar target/ops-pilot-1.0.0.jar
~~~

base URL 不带 /v1，模型必须支持 function calling。.env.example 仅用于说明，不会自动加载。密钥仅从环境读取，不返回浏览器。模型调用产生供应商费用。规则回归通过不代表真实模型评测通过。

## Agent 在哪里

agent/AgentEngine 管理有界的“模型决策 → 工具执行 → 观察结果 → 再决策”循环。最多 8 轮、12 次工具调用、60 秒；单次模型等待最多 22 秒。SpringAiGateway 禁用框架内部自动执行工具，所有调用均由 ToolExecutor 审计与校验。

工具包括 get_metrics、query_logs、get_trace、get_recent_changes、search_runbooks、finish_diagnosis。调用链 ID 必须来自已查询的指标或日志，最终结论必须引用存在的指标、日志或调用链及 Runbook 证据。校验只能保证来源和结构，不能保证模型因果判断正确。confidence 是自评支持度，不是校准概率。

修复不属于模型工具。人工确认只能触发 Runbook 对应的三种本地幂等动作。审批绑定调查和故障窗口，20 分钟过期，重复审批返回同一回执；进程重启后的旧窗口不可执行。执行动作与持久化之间并非分布式事务，重启时中断的操作需要重新诊断确认。

## 工程边界

这是可执行的本地诊断实验室，尚未接入生产 Prometheus、Loki、OpenTelemetry 或 Kubernetes。SQL 延迟由 H2 内执行固定等待产生，用来观测 JDBC 耗时，不等同于真实索引或锁故障。日志与调用链由业务请求观测生成，保留最近 240 个请求。Runbook 是版本化关键词检索，不是向量 RAG。

默认仅监听 127.0.0.1，拒绝浏览器跨源修改请求；尚无多用户认证，不应直接公开部署。H2 持久化调查、证据、审批和评测记录。四场景评测是受控回归，不能推导生产故障识别准确率。

技术栈：Java 21、Spring Boot、Spring AI、HikariCP、H2、原生 JavaScript。GitHub Actions 执行 Maven 验证与前端语法检查。

## 后续扩展

优先接入真实 OpenTelemetry trace 和 Prometheus 指标，再扩展线程死锁、GC 压力与慢查询执行计划；加入未知故障、混合故障和误导日志评测，测量证据正确率、误修复率、工具调用成本和恢复耗时。

## JVM 观测和报告

JVM 观测页读取当前进程真实的线程状态、锁持有者、堆使用与死锁 ID，最多展示 20 个线程，每个线程最多 10 帧。get_thread_dump 也是模型可选择的受控工具。规则基线在连接池耗尽时采集该证据。当前采样覆盖工作台与实验室共享的 JVM；瞬时 WAITING 不等于死锁，单次采样不能证明持续阻塞。

证据面板提供 Markdown 报告下载，包含调查模式、窗口、结论、证据、Runbook、风险、审批和恢复记录。导出不会调用模型或执行修复。

OpsPilot = Operations + Pilot，即运维排查领航员。模型模式下，Agent 根据工具观察动态选择下一步；Java 后端承担执行边界、预算、存储和人工审批。规则模式用于验证工程闭环，不代表模型自主诊断能力。

## 模型接入检测

“模型接入”页显示密钥是否存在（不会显示密钥）、模型名和服务主机。手动检测最多发起两次真实模型请求，验证工具 JSON 和 tool_call_id 多轮回传；只发送模拟数据，不执行实验室工具。检测可能收费，30 秒内重复检测复用结果。通过不代表诊断准确率，通过后还应运行 MODEL 四场景评测。

当前适配器使用 OpenAI Chat Completions function calling，需要支持 tools、结构化参数及多轮 tool 消息的文本模型。无需 embedding 或多模态模型。推理模式的 reasoning_content 等供应商字段尚未适配，首次接入建议关闭思考模式。

百炼 Qwen 示例（以账号实际可用的模型 ID、地域为准）：

~~~powershell
$env:AGENT_API_KEY = 'your-key'
$env:AGENT_BASE_URL = 'https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/compatible-mode'
$env:AGENT_MODEL = 'qwen-plus'
$env:AGENT_EXTRA_BODY_JSON = '{"enable_thinking":false}'
java -jar target/ops-pilot-1.0.0.jar
~~~

DeepSeek 官方非思考模式示例：将 BASE_URL 设为 https://api.deepseek.com，MODEL 设为 deepseek-flash，EXTRA_BODY_JSON 设为 {"thinking":{"type":"disabled"}}。供应商参数由环境变量 AGENT_EXTRA_BODY_JSON 注入，不能覆盖 model/messages/tools/stream/api_key。模型名称和权限以供应商控制台为准，不同供应商的 API Key 不能混用。

参考：[千问 Function Calling](https://help.aliyun.com/zh/model-studio/qwen-function-calling)、[DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/)。这些是配置建议，尚未用真实供应商密钥验证。

## 本地文件配置（Windows）

将 .env.local.example 复制为 .env.local，在本机填入 API Key 和接口地址。运行 scripts/start.ps1 会自动读取它，不需要额外安装 dotenv，也不会执行配置中的命令。支持四个 AGENT_* 变量；非空文件值覆盖当前进程环境变量，空值保留已有环境。不要在值后添加行尾注释。

.env.local 已被 Git 忽略；模板文件可以提交。忽略提交不等于加密，文件仍由本机用户读取。通过 java -jar 或 IDEA 直接启动时，脚本不会运行，应在启动配置中设置对应环境变量。

修改配置后重新运行 scripts/stop.ps1 和 scripts/start.ps1。勿将真实 Key 写进 application.yml、模板文件或提交记录。
