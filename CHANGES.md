# 改造说明：账号系统与音源配置移除，登录/播放改为 folia 式

本次改造移除了 CurrentMusicX 的自有账号登录系统与音源配置层，账号登录与播放逻辑改为参照
[folia-android](https://github.com/whycfawa/folia-android) 的实现：应用不提供自有账号，
改为登录各音乐平台自己的账号（网易云音乐 / QQ 音乐 / 酷狗音乐），音频始终来自平台直连或
Navidrome 自建曲库。

## 一、删除的内容

### 1. CurrentMusic 账号登录系统（整个删除）

- `data/auth/`：`AuthRepository`、`AccountVault`、`AccountSecurityRepository`、`LoginOutcome`
- `feature/auth/`：`AuthViewModel`、`LoginScreen`、`TwoFactorLoginCard`、`LoginProtectionDialog`、
  `PasswordDialog`、`AndroidPasskeyLogin`（通行密钥）
- `core/network/AuthInterceptor.kt`、`core/network/NativePasskeyRoute.kt`
  （`RequestSession` 已移到 `core/network/RequestSession.kt`）
- 随账号一并移除的功能：动态/帖子、公共聊天、发现用户、等级成长、头像挂件、网易云任务中心、
  歌单导入、管理面板、官方捐赠、CurrentMusic 服务器房间、AI 对话、云端绑定同步、
  CurrentMusic 侧听歌上报、CurrentMusic 自建歌单与站内收藏
- 依赖上述功能的入口：`user/login`、`user/security`、`user/levels`、`user/decorations`、
  `user/binding`（改名为网易云账号页）、`user/netease-tasks`、`user/playlist-import`、
  `user/admin`、`user/profile/*`、`user/announcements`、`official-donation`、`room/list`、
  `room/search`（CurrentMusic 房间）、`qq/community`、`qq/community-profile`、「发现」Tab

### 2. 音源配置（整个删除）

- LeiZ API：`data/song/LeizAudioClient.kt` 及 API Key 配置
- 服务器音源：`data/song/ServerAudioSources.kt`、`feature/settings/ServerAudioSourceDialog.kt`
  及「从服务器导入」流程
- 落雪音源：`data/lx/`（`LxSource`、`LxSourceStore`、`LxRuntime`、`LxHttpClient`、
  `LxSourceHealth`、`LxSourcePerformance`）、`feature/settings/LxSourceSettings.kt`、
  `assets/lx/`（crypto-js、host.js）及 `MusicService` 的落雪重试
- Emby：`core/emby/`、`data/emby/`、`feature/emby/` 及全部 Emby 耦合
- 音源选择状态层：`data/settings/AudioSourceSettings.kt`（`AudioProvider` / `AudioSourceAccess` /
  `AudioProviderState`）及 `AppContainer` 的 `audioSettings`、`audioSession`
- `Song` 模型的 `emby` 字段与 `MusicSource.EMBY`
- `app/build.gradle.kts`：移除 `androidx.credentials`（通行密钥）、`androidx.javascriptengine`
  与 `org.mozilla:rhino`（落雪脚本运行时）
- 保留：网易云/QQ 音乐平台直连、Navidrome 自建曲库、本地音乐、歌词来源设置

## 二、新增的内容（folia 式账号与播放）

### 1. Provider 账号模型（`feature/provider/`）

- `OnlineProvider.kt`：`OnlineProviderId`（netease / qq / kugou）、能力矩阵与
  `ProviderAccountHub`（各平台登录态快照，对应 folia 的 Omni provider 层）
- `QrLoginSession.kt`：扫码登录状态机，移植自 folia `providerLoginSession`：
  2 秒轮询、连续 2 次网络层瞬时失败容忍、TTL 到期取消、确认后不取消（让在途轮询读到 803）、
  代次（generation）丢弃迟到结果
- `ProviderAccountScreen.kt`：新的「我的」页——音乐平台账号（网易云/QQ/酷狗扫码登录与退出）、
  我的音乐、曲库（酷狗搜索、Navidrome）与设置入口

### 2. 酷狗音乐 Provider（`data/kugou/KugouRepository.kt`）

移植自 folia 的 kugou provider/transport：

- 扫码登录：`login-user.kugou.com/v2/qrcode` 取码、`/v2/get_userinfo_qrcode` 轮询
  （status 0/1/2,3/4 映射为网易云式 800/801/802/803）
- 搜索：`complexsearch.kugou.com/v2/search/song`（Android 客户端签名）
- 播放：`wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=`，仅接受 http/https 播放地址
- 登录态经 `SecureTokenStore`（`.kugou` 命名空间）加密保存，启动时恢复

### 3. 播放与账号逻辑

- 登录态变化会 `audioSources.invalidate()` 并在需要时暂停当前播放（与 QQ/网易云一致）
- QQ 音乐保留既有音质阶梯回退（如 `qq_master → hires → lossless → auto`）
- `ApiClient` 移除账号令牌、`requiresAccount`、`serverAudioSource` 等账号分支；
  `ncmbind/qr|phone` 等登录路由改由本机网易云网关应答
- `BindingRepository` 移除云端绑定同步，只保留本机扫码/验证码登录、状态、会员与勋章
- 「音源与网络」页只保留歌词来源、CurrentMusic 服务器线路与 Navidrome 自建曲库

## 三、验证

- 636 个 Kotlin 源文件全部通过括号/字符串感知的配平检查（唯一报告项为改动前既有的
  `QqDirectMusicClient.kt` 字符字面量误报，原始仓库同样存在）
- 全部 `io.github.currencortex.music.*` 导入可解析；无任何已删除符号的残留引用
- 未改动 `Room` 数据库结构；`Song` 移除字段均为带默认值的可序列化字段，
  `ApiJson` 配置了 `ignoreUnknownKeys`，旧队列快照可正常解码
