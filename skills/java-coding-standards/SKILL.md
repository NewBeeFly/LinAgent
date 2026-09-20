---
name: java-coding-standards
description: Java 代码规范与工程约定，涉及写代码、重构、命名时使用
resident: true
---
# Java 编码规范

- 包名全小写；类名 PascalCase；方法/变量 camelCase
- 优先使用 Java 21 语法（record、sealed、switch pattern、文本块）
- 大文本不硬编码，文件化 + 启动加载缓存
- 多处复用的能力上提父类或公共服务
