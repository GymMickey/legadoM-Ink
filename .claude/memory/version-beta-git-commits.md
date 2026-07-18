---
name: version-beta-git-commits
description: 版本号 beta 计数从 build_number.txt 改为 Git 提交数
metadata:
  type: project
---

## 版本号：build_number.txt → Git 提交数

### 背景

旧方案：`build_number.txt` 每次编译自动 +1，当前已到 105，且会无限增长。版本号反映的是编译次数而非代码演进。

新方案：改用 `git rev-list HEAD --count`（Git 提交总数），每次 commit 才有意义。

### 改动范围

`app/build.gradle`：删除 buildNumberFile 块、将 gitCommits 计算提前、版本号改用 gitCommits。

不需要改其他文件。`build_number.txt` 可以保留不删（不再被读取），也可以顺手删掉。

---

## 代码窗口提示词

复制以下内容到代码窗口：

```
修改文件：app/build.gradle

目标：将版本号 beta 计数从 build_number.txt 改为 Git 提交数。

当前代码（第 28-39 行）：
def buildNumberFile = rootProject.file("build_number.txt")
def buildNumber = 1
if (buildNumberFile.exists()) {
    def content = buildNumberFile.text.trim()
    if (content.isInteger()) {
        buildNumber = Integer.parseInt(content) + 1
    }
}
buildNumberFile.text = buildNumber.toString()

def name = "阅读"
def version = "3.26-beta " + buildNumber
def gitCommits = 0

操作步骤（按顺序）：

步骤 1：删除 buildNumberFile 块（第 28-36 行）
删除以下 9 行：
    def buildNumberFile = rootProject.file("build_number.txt")
    def buildNumber = 1
    if (buildNumberFile.exists()) {
        def content = buildNumberFile.text.trim()
        if (content.isInteger()) {
            buildNumber = Integer.parseInt(content) + 1
        }
    }
    buildNumberFile.text = buildNumber.toString()

步骤 2：将 git 提交数计算提前到 version 之前
当前 gitCommits 的定义和 try/catch 分散在两处（第 40 行 + 第 46-52 行）。
把这两处合并，放到 version 行之前：

在第 28 行（原 buildNumberFile 位置）插入：
    def gitCommits = 0
    try {
        def result = 'git rev-list HEAD --count'.execute().text.trim()
        if (result && result.isNumber()) {
            gitCommits = Integer.parseInt(result)
        }
    } catch (Exception ignored) {
    }

步骤 3：删除原来的 def gitCommits = 0（原第 40 行）
现在这一行已经移到前面了，原位置删除。

步骤 4：版本号改用 gitCommits
将：
    def version = "3.26-beta " + buildNumber
改为：
    def version = "3.26-beta " + gitCommits

步骤 5（可选）：删除 build_number.txt 文件
不再需要，可以直接删除。

编译验证：
./gradlew clean assembleDebug

不 commit，不 push。
```
