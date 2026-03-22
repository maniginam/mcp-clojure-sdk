# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Added
- **Client support**: Full MCP client implementation via `client.clj` and `stdio_client.clj`
  - All MCP client methods: `list-tools!`, `call-tool!`, `list-resources!`, `read-resource!`,
    `list-prompts!`, `get-prompt!`, `complete!`, `set-logging-level!`, `ping!`,
    `subscribe-resource!`, `unsubscribe-resource!`, `list-resource-templates!`
  - Auto-paginating helpers: `list-all-tools!`, `list-all-resources!`, `list-all-prompts!`,
    `list-all-resource-templates!`
  - `with-connection` macro for automatic lifecycle management
  - Sampling handler support for server-initiated LLM requests
  - Client receives and handles server notifications (tools/resources/prompts changed)
- **Dynamic registration/deregistration**: Register and remove tools, resources, prompts,
  templates, and completions at runtime with automatic capability updates
- **Pagination**: Cursor-based pagination for all list handlers with configurable `page-size`
- **Progress notifications**: `notify-progress!` for long-running tool progress updates
- **Cancellation tracking**: `cancelled?` and `clear-cancelled!` for cooperative cancellation
- **Resource template handlers**: Templates with `:handler` resolve URIs via `resources/read`
- **Helper functions**: `server/tool`, `server/resource`, `server/prompt` for concise definitions;
  `text-content`, `image-content`, `tool-error`, `prompt-message` for building responses
- **Instructions**: Server can provide LLM instructions via `:instructions` in config
- **Protocol version negotiation**: Client and server negotiate supported protocol versions
- **Improved tool response coercion**: Handles nil, strings, maps, sequences, and pre-formed responses
- **Validation**: Invalid-params error (-32602) for tools/call, resources/read, prompts/get
- **Roots**: Server can request roots from client; auto-refresh on roots/list_changed notification
- **Completions**: Register completion handlers per prompt/resource argument
- **Log level filtering**: Server filters log notifications based on client-set threshold
- **Resource subscriptions**: Track client subscriptions; notify on resource updates
- **Integration tests**: End-to-end tests using piped streams for client-server communication

## [1.0.105] - 2025-03-18
### Changed
- Internals change: Created Clojure specs for the entire MCP specification
  - The SDK stubs out all the request and notification methods that it does not currently support
  - Improves the error reporting of servers built on top of `mcp-clojure-sdk`
- Bumped version of `examples` jar to `1.2.0` to highlight improved internals

### Removed

### Fixed

## 1.0.65 - 2025-03-16
### Added
- `stdio_server` implementation of MCP
- `examples` folder shows `tools` and `prompts` based servers

[Unreleased]: https://github.com/io.modelcontext/clojure-sdk/compare/fb947ebc8dd59fc778b886d832850f38974cbdc6...HEAD
[1.1.105]: https://github.com/io.modelcontext/clojure-sdk/compare/e0e410ee115256362d964df1272ea42428bf9a21...fb947ebc8dd59fc778b886d832850f38974cbdc6
