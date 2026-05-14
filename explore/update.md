# 更新指南

> `explore/` 目录是项目的知识索引，用于在后续读取代码时快速建立上下文，减少探索代码所需的 token。
> 当项目代码发生变更时，需要同步更新这里的文档。

## 什么时候需要更新

| 触发场景 | 需要更新的文档 |
|----------|---------------|
| 新增/修改/删除工具 | [06-tools.md](06-tools.md) |
| 新增/修改/删除 executor | [06-tools.md](06-tools.md)、[04-mod-module.md](04-mod-module.md) |
| 修改系统架构或数据流 | [02-architecture.md](02-architecture.md) |
| 修改 MCP 协议或传输层 | [03-mcp-protocol.md](03-mcp-protocol.md) |
| 修改 Fabric Mod 生命周期 | [04-mod-module.md](04-mod-module.md) |
| 修改 MCP Host 模块 | [05-mcp-host-module.md](05-mcp-host-module.md) |
| 修改区域监控逻辑 | [07-monitoring.md](07-monitoring.md) |
| 修改配置项或配置方式 | [08-configuration.md](08-configuration.md) |
| 修改知识库机制 | [09-knowledge-system.md](09-knowledge-system.md) |
| 新增模块/子项目 | [02-architecture.md](02-architecture.md) + 创建新文档 |
| 删除模块/功能 | 对应文档 + [README.md](README.md) 中的链接 |

## 通用原则

1. **保持同步** — 文档内容应与代码实现保持一致。如果代码变而文档不变，探索文档反而会误导读者。
2. **保持简洁** — 每条信息应当是直接在代码中获取不到的关键摘要，而非大段代码粘贴。
3. **文件路径准确** — 引用的文件路径和行号应当是当前代码库中实际存在的。
4. **渐进式** — `README.md` 只放概览和链接，细节在子文档中。
5. **删除比过时好** — 如果某功能已被移除，从对应文档中删掉相关描述，不要再保留"已废弃"之类的内容。
6. **更新 README.md 中的链接** — 新增/删除子文档时确保 README.md 中的链接矩阵保持正确。
