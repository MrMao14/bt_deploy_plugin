# 宝塔部署（IDEA 插件）

在 IDEA 右侧工具栏把打包产物一键发到宝塔服务器：**打包 → 上传 → 解压 → 重启**，全程走宝塔面板 API，不需要在服务器上开 SSH。

作者：MrMao14。桌面版见 [bt-deploy](https://github.com/MrMao14/bt-deploy)。

## 界面

- 顶部选择**面板**，面板可以新增、设置、删除，也可以直接「测试连接」
- 下面是**部署配置卡片**，每个卡片保存自己的本地路径、远程目录和重启方式
- 选中卡片后可执行：**部署 / 打包并部署 / 查看日志 / 编辑 / 删除**

「部署」直接上传当前已经存在的产物；「打包并部署」先执行 IDEA 的 **Build Project**（构建全部模块），构建成功后再上传，构建失败不会继续部署。

## 功能

- 多个面板，每个项目的面板与部署配置独立保存
- 面板地址 / API 密钥配置，「测试连接」验证签名与白名单
- 一个部署配置可添加多个目录、文件或已有 `.zip`，合并成一个包上传
- 打开部署配置时会从面板读取网站目录和 Java 项目：远程目录可直接选，Java 项目选中后自动带出项目目录，服务名也提供常用下拉项；读取失败仍可手动输入
- 上传到远程目录的 `bak/` 下自动保留最近 3 份备份
- 解压后按配置执行：不重启 / 重载 Web 服务器 / 重启系统服务 / 重启 Java 项目
- 每个部署配置有独立日志，「查看日志」可刷新或清空
- 部署成功/失败弹出通知

## 前置条件

服务器端需要在宝塔面板 **面板设置 → API 接口** 打开 API、生成密钥，并建议配置 IP 白名单。面板端口要能从本机访问。

## 使用

1. 打开 IDEA 右侧的「宝塔部署」
2. 顶部「新增」面板，填写面板地址（如 `https://1.2.3.4:8888`）和 API 密钥，点「测试连接」
3. 点「新增配置」，选择本地路径、远程目录、Java 项目和部署后动作；远程目录旁的「刷新」会重新从面板读取网站/Java 项目列表
4. 选中配置卡片，点「部署」或「打包并部署」
5. 需要排查时点「查看日志」

本地选择目录时默认保留目录名：选 `.../dist` 部署到 `/www/wwwroot/site`，结果是 `/www/wwwroot/site/dist/…`；取消勾选「保留目录名」则把目录内容直接铺到目标目录。

## 配置与日志

- 面板、部署配置保存在项目 `.idea/bt-deploy.xml`（本机项目配置，`.gitignore` 已忽略 `.idea/`）
- 部署日志保存在 IDE 内存中，按部署配置区分，重启 IDE 后清空

## 开发

需要 JDK 25（IDEA 2026.3 自带的 JBR 即可，把 `JAVA_HOME` 指向它）。项目默认从 `local.properties` 读取本机 IDEA 安装目录作为编译平台，避免下载完整 IDEA 分发包：

```properties
platformLocalPath=C:/Users/you/AppData/Local/Programs/IntelliJ IDEA
```

没有 `local.properties` 时会改为下载 IntelliJ IDEA 2026.3。IDEA 里导入 Gradle 工程时，**Gradle JVM** 也选 2026.3 自带的 JBR 25。

```bash
# JAVA_HOME 指向 IDEA 自带的 JBR（Windows 示例）
export JAVA_HOME="$LOCALAPPDATA/Programs/IntelliJ IDEA/jbr"

./gradlew runIde        # 启动带插件的沙箱 IDEA
./gradlew buildPlugin   # 产物在 build/distributions/
```

插件基于 IntelliJ Platform 2026.3（since-build `263`）。
