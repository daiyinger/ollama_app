
这份 diff 文件展示了对 Android 应用（看似是一个 Ollama/LLM 聊天客户端）的文件附件处理逻辑的重大改进。

主要目标是 增强文件附件的持久性、优化性能，并处理文件丢失（Uri 失效）的边缘情况。

以下是详细的修改点分析：

1. 数据模型变更 (ChatMessage.kt)
新增字段: ChatMessage 类中新增了 fileName 属性。

目的: 以前可能只保存了 fileUri，文件名需要每次通过 ContentResolver 动态查询。现在将文件名持久化保存到消息对象中。

好处: 即使原始文件被删除或 Uri 权限失效，聊天记录中仍然能显示文件名。

2. UI 渲染层变更 (ChatScreen.kt)
AttachmentView 接收对象变更: 参数从 Uri 变成了 ChatMessage 对象。

Uri 可访问性检查:

引入了 isUriAccessible 函数。

逻辑: 在渲染附件之前，先检查 Uri 指向的文件是否仍然可读。

UI 降级处理 (Graceful Degradation):

如果文件存在: 正常显示图片预览或文件图标 + 文件名（优先使用缓存的 message.fileName）。

如果文件不存在 (Uri 失效/Null): 进入 else if (message.fileName != null) 分支。此时只显示“回形针图标”和“文件名”。

意义: 这是一个关键的用户体验修复。以前如果缓存被清空，聊天记录里的图片可能会变成空白或报错；现在至少会保留一个“曾发送过某文件”的记录。

3. 业务逻辑变更 (MainViewModel.kt)
状态清洗 (loadConversation):

在加载对话时，遍历所有消息。

自动修复: 如果发现某条消息的 fileUri 不可访问（!isUriAccessible(it)），则将内存中该消息的 fileUri 置为 null，但保留其他信息（如 fileName）。这防止了 UI 层尝试加载无效 Uri 导致的崩溃或错误日志。

文件复制逻辑增强:

copyFileToInternalStorage 函数的返回值从 Uri? 变为 CopiedFile? (包含 Uri 和 fileName)。

在文件复制过程中，显式地查询并保留原始文件的 OpenableColumns.DISPLAY_NAME。

发送消息逻辑 (sendMessage):

现在构造 ChatMessage 时，会显式填入 fileName 和 fileMimeType。

处理文本附件时，将文件名也加入到了 Prompt 上下文中 (--- Attached File ---\n$it)，让大模型能知道文件名。

总结：解决了什么问题？
性能优化: 不再需要在 ChatScreen 的 Composable 中每次重绘都去查询 ContentResolver 获取文件名（这本身是一个耗时 IO 操作），而是直接读取内存中的 fileName 字段。

稳定性: 防止因文件被外部删除、临时文件清理或权限回收导致的 SecurityException 或崩溃。

用户体验: 当历史记录中的图片或文件失效时，用户不会看到一个空白气泡，而是能看到文件名，知道当时发了什么。

