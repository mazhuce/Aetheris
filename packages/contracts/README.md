# contracts

跨语言接口契约的唯一来源（Single Source of Truth）。

- REST/HTTP 服务使用 **OpenAPI**。
- 高性能内部调用使用 **Protobuf/gRPC**。
- 各语言客户端/服务端从契约生成代码，避免手工同步导致的连接问题。
