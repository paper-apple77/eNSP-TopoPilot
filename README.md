# TopoPilot — AI 驱动的 eNSP 网络配置平台

基于 DeepSeek 大模型的智能网络配置助手，通过 Telnet 协议直接操作华为 eNSP 模拟器中的真实设备，实现从拓扑设计到配置部署的全流程 AI 自动化。

## 核心功能

### 🤖 智能配网（Agent 模式）
- AI 自主查询设备配置、生成命令、推送执行、验证结果
- 支持错误自动修正：推送失败 → 查 `?` 输出 → AI 分析 → 重试
- 多轮 Function Calling，最多无上限轮次
- 轻量配置缓存，对话时无需重复查询

### 🎨 拓扑设计
- 自然语言描述需求，AI 自动设计网络拓扑
- 支持 40+ 种 eNSP 设备型号（路由器/交换机/防火墙/无线/终端）
- 导出标准 .topo 文件，可直接导入 eNSP
- 增量修改 + 全量重建两种模式

### 🔌 设备连接
- 一键扫描 eNSP 端口 → 自动连接 → LLDP 邻居识别
- 防火墙自动登录（含初始密码修改流程）
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
```bash
cd server
cp src/main/resources/application-example.yml src/main/resources/application.yml
# 编辑 application.yml，填入 DeepSeek API Key 和数据库密码
mvn spring-boot:run
```

### 前端配置
```bash
cd client
npm install
npm run dev
```

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
