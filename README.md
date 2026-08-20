# TopoPilot — AI 驱动的 eNSP 网络配置平台

基于 DeepSeek 大模型的智能网络配置助手，通过 Telnet 协议直接操作华为 eNSP 模拟器中的真实设备，实现从拓扑设计到配置部署的全流程 AI 自动化。

## 核心功能

### 🤖 智能配网（Agent 模式）
- AI 自主查询设备配置、生成命令、推送执行、验证结果
- 支持错误自动修正：推送失败 → 查 `?` 输出 → AI 分析 → 重试
- 多轮 Function Calling（20 轮上限）+ 防卡壳机制：连续重复调用检测、输出截断自动续写、轮次预警
- 轻量配置缓存，对话时无需重复查询

### 🎨 拓扑设计
- 自然语言描述需求，AI 自动设计网络拓扑
- 支持 40+ 种 eNSP 设备型号（路由器/交换机/防火墙/无线/终端）
- 导出标准 .topo 文件，可直接导入 eNSP
- 增量修改 + 全量重建两种模式

### 🔌 设备连接
- 一键扫描 eNSP 端口 → 自动连接 → LLDP 邻居识别
- 端口数少于拓扑设备数时自动二次扫描补漏
- 连接结果三态区分：成功 / 需要密码 / 连接失败，前端分区展示，不再静默丢设备
- 防火墙自动登录（含初始密码修改流程）；需要密码的设备弹出密码框处理
- 并行连接（6 线程），心跳保活，会话超时自动重连

## 技术架构

```
┌─────────────────────────────────────────────────┐
│                   Vue 3 前端                      │
│  LogicFlow 画布 │ ChatPanel │ DeviceConnector    │
│  EventSource SSE 流式接收 │ marked Markdown 渲染  │
└──────────────────┬──────────────────────────────┘
                   │ POST /chat/stream (SSE)
┌──────────────────┴──────────────────────────────┐
│               Spring Boot 3.3 后端               │
│  DeepSeek API (Agent) │ Agent 引擎 │ ToolRegistry │
│  Apache Commons Net │ PromptBuilder │ JWT + Redis │
└──────────────────┬──────────────────────────────┘
                   │ Telnet (127.0.0.1:2000-)
┌──────────────────┴──────────────────────────────┐
│              eNSP 模拟器 (Windows)                │
│  路由器/交换机/防火墙/PC/Server/无线设备          │
└─────────────────────────────────────────────────┘
```

## AI 工作流

```
用户: "配置 OSPF 让全网互通"

AI 第1轮: sendCommand("R1", "display ip interface brief")
          sendCommand("R2", "display ip routing-table")
          系统提示词中已有轻量摘要，少量查询即可

AI 第2轮: sendConfig("R1", ["system-view","ospf 1","area 0",...])
          sendConfig("R2", ["system-view","ospf 1","area 0",...])

AI 第3轮: sendCommand("R1", "display ospf peer brief")  ← 验证
          → OSPF 邻居已 Full ✅

AI 第4轮: 输出最终总结表格
```

## 项目结构

```
TopoPilot/
├── client/                    # Vue 3 前端
│   └── src/
│       ├── views/TopologyEditor.vue
│       ├── components/topology/
│       │   ├── ChatPanel.vue      # AI 聊天面板
│       │   ├── DeviceConnector.vue # 设备连接管理
│       │   └── TopologyCanvas.vue # LogicFlow 画布
│       ├── api/                   # 后端接口封装
│       └── store/                 # Pinia 状态管理
├── server/                    # Spring Boot 后端
│   └── src/main/java/com/topo/
│       ├── controller/ChatController.java  # 核心 API
│       ├── service/
│       │   ├── ChatService.java           # DeepSeek API + Agent 引擎
│       │   ├── TelnetService.java         # Telnet 协议操作
│       │   ├── PromptBuilder.java         # 系统提示词构建
│       │   ├── ToolRegistry.java          # Function Calling 工具注册
│       │   ├── TopoXmlParser.java         # .topo 文件解析
│       │   ├── TopoXmlWriter.java         # .topo 文件导出
│       │   └── CommandKnowledgeService.java # 设备知识库
│       └── model/vo/TopologyJson.java     # 拓扑数据结构
```

## 快速开始

### 环境要求
- JDK 17+, Maven 3.8+
- Node.js 18+, npm 9+
- MySQL 8.0+, Redis 7+
- eNSP 模拟器（Windows）

### 后端配置
`application.yml` 含敏感配置，不随仓库分发（被 .gitignore 忽略）。克隆后在 `server/src/main/resources/` 下新建 `application.yml`，最小内容如下：

```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/topo_assistant?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai
    username: root
    password: 你的MySQL密码
  data:
    redis:
      host: localhost
      port: 6379

jwt:
  secret: ${JWT_SECRET:长度至少32字节的base64字符串}
  expiration: 21600000

deepseek:
  api-key: ${DEEPSEEK_API_KEY:}
  model: deepseek-chat

ensp:
  host: ${ENSP_HOST:127.0.0.1}
```

敏感项全部走环境变量（完整清单见根目录 `.env.example`）：
```bash
cd server
# Windows: set DEEPSEEK_API_KEY=sk-xxx && mvn spring-boot:run
# Mac:     export DEEPSEEK_API_KEY=sk-xxx && mvn spring-boot:run
mvn spring-boot:run
```

### 前端配置
```bash
cd client
npm install
npm run dev
# 开发环境 /api 由 vite 代理转发到 localhost:8080，无需改代码
```

### Docker 一键部署（Windows + Docker Desktop）
```bash
# 在 TopoPilot 根目录（已装 Docker Desktop）
set DEEPSEEK_API_KEY=sk-xxx
docker compose up -d --build
```
- 访问地址：http://localhost:8088（前端 nginx，自动反代 /api 到后端）
- 四个容器：mysql（自动执行 db/init.sql 建表）、redis、backend、web
- **eNSP 不用动**：先在本机启动 eNSP 拓扑，后端容器通过 `host.docker.internal` Telnet 本机设备端口
- 后端环境变量（compose 已配置默认值，完整清单见根目录 `.env.example`）：
  - `DEEPSEEK_API_KEY`：必填，DeepSeek API Key
  - `ENSP_HOST`：eNSP 所在主机，默认 `host.docker.internal`（本机）
  - `MYSQL_ROOT_PASSWORD`：MySQL root 密码，默认 `12345678`
  - `JWT_SECRET`：JWT 签名密钥（至少 32 字节），不设置时使用内置默认值，生产环境建议自定义
- 端口冲突：8080/8088 被占用时改 docker-compose.yml 里 ports 的左边端口
- 数据持久化：MySQL 数据在 `mysql-data` 卷；`docker compose down -v` 可完全重置
- Swagger 文档：http://localhost:8080/swagger-ui.html

### 使用流程
1. 在 eNSP 中打开拓扑并启动设备
2. 在 TopoPilot 中**导入 .topo 文件**或**AI 设计拓扑**
3. 点击**扫描端口 → 一键连接**
4. 在聊天框描述配置需求，AI 自动完成

## 技术特点

| 分类 | 技术 | 说明 |
|------|------|------|
| AI | Function Calling | AI 自主调用 4 个工具（查询/配置/验证/诊断） |
| AI | Agent 架构 | 多轮推理闭环，配置→验证→修正 |
| AI | Prompt Engineering | 双模式提示词，40+ 设备精确接口规范 |
| 通信 | SSE 流式 | 逐 token 推送，打字机效果 |
| 通信 | Telnet 协议 | 逐字符发送 + 分页读取 + 心跳保活 |
| 设备 | LLDP 邻居识别 | 自动匹配端口到拓扑设备 |
| 设备 | 轻量配置缓存 | 连接时查摘要，避免重复查询 |
| 导出 | .topo 格式 | 完整兼容 eNSP v1.3，支持 UTF-16LE 编码 |
