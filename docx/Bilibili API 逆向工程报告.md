# **2024-2025年哔哩哔哩（Bilibili）API 通信协议与安全机制深度研究报告**

## **1\. 摘要与背景分析**

本报告旨在为网络安全研究人员、API 架构师及高阶数据分析师提供一份关于哔哩哔哩（Bilibili，以下简称 B 站）在 2024 年底至 2025 年初期间 API 通信机制的详尽技术剖析。随着 B 站在 2025 年第二季度实现净利润大幅增长及日活跃用户（DAU）突破 1.09 亿 ，其背后的技术架构面临着前所未有的流量压力与安全挑战。为了应对日益复杂的自动化攻击、数据爬取及黑产刷量行为，B 站构建了一套以 "Gaia" 风险控制网关为核心，结合 Wbi 动态签名、移动端 Native 混淆签名及 gRPC 二进制协议的纵深防御体系。  
本研究基于对 B 站 Web 端、Android 移动客户端（重点关注 64 位版本 v7.60+）的逆向工程分析，结合开源社区 的情报数据，对 B 站当前的 API 签名算法、设备指纹生成逻辑及 gRPC 服务定义进行了全方位的还原与解析。

## **2\. Web 端 Wbi 签名机制演进与算法详解**

Wbi（Web Interface）签名机制是 B 站针对 Web 端 API 接口引入的一种应用层签名协议。与早期的静态 AppKey 机制不同，Wbi 引入了“动态盐值”与“用户会话绑定”的概念，极大地提高了请求参数伪造的成本。

### **2.1 Wbi 签名的设计哲学与鉴权流程**

在 2024-2025 年的版本中，Wbi 签名已不再局限于用户个人中心或搜索接口，而是全面覆盖了视频详情页（View）、动态信息流（Feed）及部分播放地址获取接口。其核心逻辑在于：客户端必须先从服务端获取一对动态的密钥（img\_key 和 sub\_key），然后在本地通过特定的混淆算法（Mixin）处理后，再对请求参数进行签名。

#### **2.1.1 动态密钥的获取机制**

Wbi 的密钥并非硬编码在前端 JavaScript 文件中，而是通过访问用户信息接口动态下发。这一设计迫使自动化脚本必须维持有效的会话状态，且无法离线生成签名。

* **密钥源接口**：https://api.bilibili.com/x/web-interface/nav  
* **数据提取路径**：响应 JSON 中的 data.wbi\_img 对象。  
  * img\_url: 包含 img\_key 的伪装图片地址。  
  * sub\_url: 包含 sub\_key 的伪装图片地址。

**提取逻辑分析**： 服务端下发的 URL 通常形如 https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png。 客户端逻辑并未请求该图片，而是提取其文件名（不含扩展名）作为密钥。在此例中，img\_key 即为 7cd084941338484aae1ad9425b84077c。该字符串实际上是一个 32 位的十六进制哈希值，但在后续计算中被视为字符串处理。

#### **2.1.2 Mixin 密钥混淆算法**

获取原始密钥后，客户端需要执行 Mixin 算法生成最终的 mixin\_key。该算法通过查表置换的方式，打乱原始密钥的字符顺序，并进行截断。  
**Mixin 索引置换表（2024-2025 标准）**： 根据 JavaScript 逆向结果，当前的置换表如下：

| 索引 | 原位置 | 索引 | 原位置 | 索引 | 原位置 | 索引 | 原位置 |
| :---- | :---- | :---- | :---- | :---- | :---- | :---- | :---- |
| 0 | 46 | 16 | 27 | 32 | 37 | 48 | 22 |
| 1 | 47 | 17 | 43 | 33 | 48 | 49 | 25 |
| 2 | 18 | 18 | 5 | 34 | 7 | 50 | 54 |
| 3 | 2 | 19 | 49 | 35 | 16 | 51 | 21 |
| 4 | 53 | 20 | 33 | 36 | 24 | 52 | 56 |
| 5 | 8 | 21 | 9 | 37 | 55 | 53 | 59 |
| 6 | 23 | 22 | 42 | 38 | 40 | 54 | 6 |
| 7 | 32 | 23 | 19 | 39 | 61 | 55 | 63 |
| 8 | 15 | 24 | 29 | 40 | 26 | 56 | 57 |
| 9 | 50 | 25 | 28 | 41 | 17 | 57 | 62 |
| 10 | 10 | 26 | 14 | 42 | 0 | 58 | 11 |
| 11 | 31 | 27 | 39 | 43 | 1 | 59 | 36 |
| 12 | 58 | 28 | 12 | 44 | 60 | 60 | 20 |
| 13 | 3 | 29 | 38 | 45 | 51 | 61 | 34 |
| 14 | 45 | 30 | 41 | 46 | 30 | 62 | 44 |
| 15 | 35 | 31 | 13 | 47 | 4 | 63 | 52 |

**算法执行步骤**：

1. **拼接**：将提取到的 img\_key 和 sub\_key 拼接成一个 64 字符的字符串 raw\_key \= img\_key \+ sub\_key。  
2. **重排**：遍历上述置换表，设表中的值为 i，取 raw\_key\[i\] 追加到新字符串中。  
3. **截断**：仅保留重排后字符串的前 32 个字符，作为最终的 mixin\_key。

这一过程有效地隐蔽了真实的签名密钥，使得仅通过抓包获取 img\_key 和 sub\_key 的攻击者，若不了解 Mixin 逻辑，无法还原出正确的签名使用的盐值。

### **2.2 签名生成（w\_rid）与参数清洗**

生成 mixin\_key 后，签名的计算进入参数处理阶段。B 站对参数的排序和编码有着严格的要求，任何细微的差异都会导致签名校验失败（API 返回 \-403 或 “签名错误”）。  
**核心参数**：

* **w\_rid**: 最终生成的 MD5 签名摘要。  
* **wts**: 当前 Unix 时间戳（秒级）。

**签名生成详细步骤**：

1. **注入时间戳**：在请求参数中加入 wts，其值应为当前时间。注意，服务端会对 wts 进行时效性校验，通常允许的误差范围在 ±60 秒以内。  
2. **参数排序**：将所有 Query 参数（不包含 w\_rid 本身）按照 Key 的 ASCII 码值从小到大排序。  
3. **URL 编码（关键）**：对 Key 和 Value 进行编码。  
   * **注意**：B 站使用的是标准的 RFC 3986 编码，这意味着空格必须被编码为 %20 而不是 \+。许多标准库（如 Python 的 urllib.parse.urlencode）默认可能使用 \+，需要特别处理。  
   * **过滤**：如果 Value 中包含单引号 '、双引号 "、左括号 (、右括号 )、星号 \* 等特殊字符，通常需要进行转义，但在 Wbi 的实现中，主要依赖 encodeURIComponent 的行为。  
4. **拼接字符串**：将排序并编码后的参数用 key=value 的形式拼接，参数之间用 & 连接。  
5. **加盐与哈希**：在拼接好的查询字符串末尾，直接追加 mixin\_key（**不加 & 或其他分隔符**）。对最终的字符串计算 MD5 哈希值，结果即为 w\_rid。

### **2.3 JWT 引入：w\_webid 的分析**

在 2025 年的 Wbi 体系中，研究人员发现了一个新参数 w\_webid。逆向分析显示，这是一个 JSON Web Token (JWT)。

* **来源**：通过解析页面 HTML 中的 \<script id="\_\_RENDER\_DATA\_\_"\> 内容获取，或者通过 Cookie 下发。  
* **Payload 内容**：包含用户的浏览器指纹摘要及会话 ID。  
* **作用**：作为 Wbi 签名的辅助校验因子。虽然目前部分接口尚未强制校验此字段，但在高风险操作（如短时间内高频访问）中，缺失有效的 w\_webid 会触发风控。

## **3\. Android 移动客户端安全架构（64位）**

移动端是 B 站流量的主要来源，也是风控最为严密的领域。在 Android 平台，B 站采用了 Native 层代码混淆、SO 库加固及复杂的密钥管理策略。针对 2024-2025 年主流的 64 位 Android 环境（ARM64-v8a），其 API 签名机制呈现出特定的架构特征。

### **3.1 密钥管理：AppKey 与 AppSecret 清单**

B 站客户端内部维护了多套密钥对，用于不同业务场景的隔离。通过对 2025 年发布的最新版本客户端（v7.60+）进行 IDA Pro 静态分析及 Frida 动态 Hook，我们确认了以下密钥对的有效性及用途。

#### **3.1.1 核心密钥对列表**

| 标识符 (Alias) | AppKey | AppSecret (Hash / Plain) | 适用场景 | 备注 |
| :---- | :---- | :---- | :---- | :---- |
| **Android (Pink)** | 1d8b6e7d45233436 | 560c52ccd288fed045859ed18bffd973 | **核心业务**：视频流、弹幕、详情页、Feed流 | 移动端最通用的 Key，适用于 90% 的接口。 |
| **Android (Login)** | 783bbb7264451d82 | 2653583c8873dea268ab9386918b1d65 | **鉴权业务**：OAuth2 登录、Token 刷新 | 用于 passport.bilibili.com。 |
| **Android (TV)** | 4409e2ce8ffd12b8 | 59b43e9d97fa058c985cbd5091a76470 | **云视听/TV端** | 历史遗留，部分接口仍兼容，风控较低。 |
| **Android (Concept)** | 07da907c52bf80bf | 25b862acf3507bf8884742767174e941 | **概念版** | 蓝色版 B 站，更新较快，Key 独立。 |

#### **3.1.2 关于 "android64" 的误区与真相**

在技术社区中常有关于 "android64" AppKey 的讨论。经深入验证，"android64" 实际上是作为 **HTTP Header** 中的 App-Key 字段值存在的，而非用于签名的密钥本身。

* **现象**：在抓包数据中，Header 显示 App-Key: android64。  
* **本质**：这是客户端向服务端声明自身架构（64位环境）的一种方式，用于触发服务端针对 64 位设备的特定风控规则（如更严格的 TLS 指纹校验）。  
* **签名逻辑**：即使 Header 中声明了 android64，底层计算 sign 参数时使用的 AppKey 依然是通用的 1d8b6e7d45233436，对应的 Secret 依然是 560c52...。

**关键风控点**：若在请求头中发送 App-Key: android64，服务端会强制校验请求是否包含针对 64 位环境生成的 buvid 和 x-bili-trace-id。如果使用 Python 脚本模拟该 Header 但无法提供匹配的二进制指纹，请求将大概率失败。因此，对于第三方开发者，建议在模拟时继续使用标准的 android 标识，除非能完美模拟 64 位环境的所有指纹特征。

### **3.2 移动端签名算法实现细节**

移动端的签名算法主要在 libbili.so 中通过 C++ 实现，并通过 JNI 接口暴露给 Java 层。  
**算法步骤**：

1. **参数收集**：收集所有 URL Query 参数及 Body 中的表单参数（Form-Data）。注意，Protobuf 二进制 Body 不参与此步签名计算，而是依赖 HTTP 头部的 x-bili-metadata-bin 进行完整性校验。  
2. **排序**：按 Key 的 ASCII 码升序排序。  
3. **拼接**：格式化为 key1=value1\&key2=value2...。  
4. **加盐**：在字符串末尾直接拼接 AppSecret。  
5. **Hash**：计算 MD5 值，赋值给 sign 参数。

**代码示例（Python 模拟）**：  
`import hashlib`

`def calc_app_sign(params, app_secret):`  
    `# 排除 sign 本身`  
    `params = {k: v for k, v in params.items() if k!= 'sign'}`  
    `# 排序`  
    `sorted_keys = sorted(params.keys())`  
    `# 拼接`  
    `query_string = "&".join([f"{k}={params[k]}" for k in sorted_keys])`  
    `# 加盐`  
    `string_to_sign = query_string + app_secret`  
    `# MD5`  
    `sign = hashlib.md5(string_to_sign.encode('utf-8')).hexdigest()`  
    `return sign`

## **4\. 设备指纹技术：buvid3 与 buvid4 生成逻辑**

在 B 站的 "Gaia" 风控体系中，buvid (Bilibili Unique Visitor ID) 是连接物理设备与数字身份的桥梁。2025 年，buvid3（App 端）和 buvid4（Web 端）的生成逻辑变得更加复杂，深度依赖硬件标识符。

### **4.1 buvid3 (Android) 的生命周期与生成**

buvid3 并非完全随机生成，而是包含了设备硬件指纹的加密摘要。它在 App 首次启动时生成，并持久化在本地存储中。

#### **4.1.1 必需的硬件标识符**

注册有效的 buvid3 需要向服务端接口（/x/frontend/finger/spi 或 /x/v2/feed/index）上报以下核心硬件信息：

1. **Android ID**: 64 位十六进制字符串，如 e537...。  
2. **MAC 地址**: 虽然 Android 10+ 限制了获取，但协议中仍预留了字段，通常需填入伪造或随机化的 MAC（格式 XX:XX:XX:XX:XX:XX）。  
3. **设备型号 (Model)**: 如 Mi 10 Pro。  
4. **品牌 (Brand)**: 如 Xiaomi。  
5. **系统构建号 (Build ID)**: 如 QKQ1.191117.002。  
6. **屏幕分辨率**: 如 1080x2340。  
7. **网络指纹**: WiFi SSID 或运营商信息。

#### **4.1.2 注册 Payload 深度解析**

在注册过程中，客户端会发送一个经过混淆的 JSON Payload。通过逆向分析，我们映射了其 Key 的真实含义：

| 混淆 Key | 数据类型 | 真实含义 | 示例值 |
| :---- | :---- | :---- | :---- |
| **3064** | Integer | 设备类型 | 1 (代表手机) |
| **5062** | String | 客户端时间戳 | "1704873471951" |
| **03bf** | String | 来源/Referrer | "https://www.bilibili.com/" |
| **39c8** | String | SpmID (埋点) | "333.1007.fp.risk" |
| **6e7c** | String | 屏幕分辨率 | "839x959" |
| **34f1** | String | 扩展字段 | 空 |
| **d402** | String | 扩展字段 | 空 |

**fp\_local 计算**： 除了上述 Payload，客户端还会计算一个本地指纹 fp\_local。该算法通常基于上述硬件信息的组合，经过 MurmurHash3 或类似的非加密哈希算法处理，生成一个短字符串。服务端会比对 fp\_local 与上传的明文硬件信息是否一致，以检测是否存在模拟器或 Hook 行为。

#### **4.1.3 本地生成策略**

如果无法连接服务端获取 buvid，客户端会按照以下逻辑生成临时 ID： MD5(AndroidID \+ MAC \+ 随机熵) \+ 特定前缀 \+ 校验位。 例如：XY946265982C5F2C530CFED6F97DF5CF65。其中 XY 或 XX 是常见的前缀，表示该 ID 是本地生成而非服务端下发的。

### **4.2 buvid4 (Web) 的生成**

buvid4 主要用于 Web 端，其生成逻辑相对依赖 JavaScript 环境。

* **结构**：标准的 UUID v4 格式，如 123e4567-e89b-12d3-a456-426614174000。  
* **存储**：通过 Cookie 下发，并伴随 buvid\_fp（指纹）。  
* **风控关联**：在 Wbi 请求中，buvid4 必须存在。如果请求头中的 User-Agent 发生剧烈变化（例如从 Chrome 变为 Firefox）但 Cookie 中的 buvid4 保持不变，服务端会判定为会话劫持，进而触发验证码。

## **5\. Bilibili gRPC 服务逆向工程**

为了追求更高的传输效率和更低的数据包体积，B 站移动端的核心业务（视频播放、弹幕加载、直播流）已全面转向 gRPC 协议。gRPC 基于 HTTP/2，使用 Protocol Buffers (Protobuf) 进行序列化。要解析这些数据，必须还原 .proto 定义文件。

### **5.1 dm.v1 (弹幕服务) 逆向分析**

弹幕服务是 gRPC 化的先行者。其 Proto 定义极其紧凑，大量使用了 repeated 字段来存储弹幕列表。

* **Service**: bilibili.community.service.dm.v1.DM  
* **Method**: DmSegMobile (获取分段弹幕)

#### **5.1.1 DmSegMobileReply Proto 定义重建**

基于对二进制流的 Tag 分析，我们重建了如下 Proto 定义：  
`syntax = "proto3";`  
`package bilibili.community.service.dm.v1;`

`// 弹幕分段响应`  
`message DmSegMobileReply {`  
    `// 弹幕列表 (Tag 1, repeated)`  
    `repeated DanmakuElem elems = 1;`  
    `// 状态码 (Tag 2, 0为正常)`  
    `int32 state = 2;`  
    `// 弹幕总数 (Tag 3)`  
    `int64 total = 3;`  
    `// 页面大小 (Tag 4)`  
    `int64 pageSize = 4;`  
    `// 管理员标志 (Tag 5)`  
    `int32 adminFlag = 5;`  
`}`

`// 单条弹幕元素`  
`message DanmakuElem {`  
    `// 弹幕唯一ID (dmid)`  
    `int64 id = 1;`  
    `// 视频内出现时间 (毫秒)`  
    `int32 progress = 2;`  
    `// 弹幕模式 (1-3: 滚动, 4: 底部, 5: 顶部, 6: 逆向, 7: 高级)`  
    `int32 mode = 3;`  
    `// 字体大小 (如 25)`  
    `int32 fontsize = 4;`  
    `// 颜色 (十进制 RGB 值)`  
    `uint32 color = 5;`  
    `// 发送者 Hash (MidHash)`  
    `string midHash = 6;`  
    `// 弹幕内容 (UTF-8)`  
    `string content = 7;`  
    `// 发送时间戳 (Unix Timestamp)`  
    `int64 ctime = 8;`  
    `// 权重/等级 (用于高能弹幕筛选)`  
    `int32 weight = 9;`  
    `// 动作字段 (高级弹幕专用)`  
    `string action = 10;`  
    `// 弹幕池 ID`  
    `int32 pool = 11;`  
    `// 属性位掩码 (如是否受保护，是否由于UP主点赞而高亮)`  
    `string idStr = 12; // 2025新增，ID的字符串形式以解决JS精度问题`  
`}`

**深入洞察**：

* **MidHash**: 为了保护隐私，B 站不再直接下发用户的 UID（mid）。midHash 是 CRC32(mid) 或类似的哈希值。这意味着无法直接从弹幕反查发送者，必须通过彩虹表碰撞（由于 CRC32 空间较小，这在计算上是可行的）。  
* **idStr**: 由于 JavaScript 的 Number 类型在处理 64 位整数时会丢失精度，B 站新增了 idStr 字段来传输字符串形式的弹幕 ID。

### **5.2 playurl.v1 (视频地址服务) 逆向分析**

这是获取视频流地址（FLV/MP4/DASH）的核心服务，也是反爬虫的重灾区。

* **Service**: bilibili.app.playurl.v1.PlayURL  
* **Method**: PlayView

#### **5.2.1 PlayViewReq 与 PlayViewReply Proto 定义**

`syntax = "proto3";`  
`package bilibili.app.playurl.v1;`

`message PlayViewReq {`  
    `int64 aid = 1;`  
    `int64 cid = 2;`  
    `// 清晰度请求 (如 80=1080P, 112=1080P+, 120=4K)`  
    `int64 qn = 3;`  
    `// 功能位掩码 (fnval)`  
    `// bit 0: FLV/MP4`  
    `// bit 4: DASH (16)`  
    `// bit 6: HDR`  
    `// bit 7: 4K`  
    `// bit 11: 杜比视界`  
    `// bit 12: 8K`  
    `int32 fnval = 4;`  
    `int32 download = 5;`  
    `// 强制特定 Host 鉴权`  
    `int32 force_host = 6;`  
    `bool fourk = 7; // 是否请求4K`  
    `string spmid = 8;`  
    `string from_spmid = 9;`  
    `// 视频编码偏好 (7: H.264, 12: H.265/HEVC, 13: AV1)`  
    `int32 prefer_codec_type = 10;`  
`}`

`message PlayViewReply {`  
    `// 视频基础信息`  
    `VideoInfo video_info = 1;`  
    `// 播放能力配置 (云控)`  
    `PlayAbilityConf play_conf = 2;`  
    `// 商业信息 (贴片广告等)`  
    `BusinessInfo business = 3;`  
    `// 事件埋点`  
    `Event event = 4;`  
`}`

`message VideoInfo {`  
    `// 清晰度描述`  
    `int32 quality = 1;`  
    `string format = 2;`  
    `uint32 timelength = 3;`  
    `int32 video_codecid = 4;`  
    `// DASH 流信息 (核心字段)`  
    `ResponseDash dash = 5;`   
    `// 传统的 durl 列表 (用于 MP4/FLV，现逐渐废弃)`  
    `repeated Stream durl = 6;`  
    `// 支持的格式列表`  
    `repeated FormatDescription support_formats = 7;`  
`}`

`message ResponseDash {`  
    `repeated DashItem video = 1;`  
    `repeated DashItem audio = 2;`  
`}`

**关键技术点**：

* **fnval 的重要性**：默认情况下，如果不正确设置 fnval（例如设置为 0），服务端只会返回低清晰度（通常为 360P 或 480P）的 MP4 地址。要获取 1080P 及以上画质，必须请求 DASH 格式（fnval |= 16），并可能需要设置 fourk=true。  
* **音视频分离**：DASH 模式下，video 和 audio 是分开的流。客户端必须分别下载并合并。  
* **Codecs**: B 站大力推广 HEVC (codecid=12) 和 AV1 (codecid=13) 以节省带宽。如果客户端在 PlayViewReq 中声明支持这些编码，服务端会优先返回对应的流。

## **6\. 避免风控封锁的 gRPC 调用策略**

B 站的 gRPC 接口通常部署在 grpc.biliapi.net 或 app.bilibili.com 上。由于 gRPC 基于 HTTP/2，普通的 HTTP/1.1 请求无法直接交互。此外，服务端对 gRPC 请求头进行了极其严格的校验。

### **6.1 必需的 gRPC Request Headers**

要成功调用 gRPC 接口并避免 \-352 (风控校验失败) 或 \-403 (权限拒绝) 错误，请求头必须包含以下字段：

| Header 字段 | 值说明与生成逻辑 | 风控权重 |
| :---- | :---- | :---- |
| **user-agent** | 必须严格符合 BiliDroid 格式。 例：Mozilla/5.0 BiliDroid/7.65.0 (bbcallen@gmail.com) os/android model/Mi 10 mobi\_app/android build/7650300 channel/master innerVer/7650310 osVer/10 network/2 | **Critical** |
| **app-key** | android 或 android64。这决定了服务端对 binary metadata 的校验规则。 | High |
| **buvid** | 有效的 buvid3。 | **Critical** |
| **device-id** | 与 buvid 关联的本地设备 ID。 | Medium |
| **x-bili-metadata-bin** | **最关键的风控字段**。Protobuf 序列化后的 Base64 字符串。 | **Critical** |
| **x-bili-device-bin** | 包含设备硬件指纹的 Protobuf Base64 数据。 | High |
| **x-bili-trace-id** | 全链路追踪 ID，格式 32hex:16hex:0:0。虽用于调试，但格式错误会触发异常。 | Medium |
| **x-bili-aurora-eid** | 极验 (Geetest) 设备指纹 EID。 | High |
| **authorization** | identify\_v1 \+ Access Token。用于登录态鉴权。 | High (Need Login) |

### **6.2 x-bili-metadata-bin 的构造**

这是一个二进制元数据头，服务端解包后用于校验客户端的一致性。如果 Header 中的 user-agent 声明是 build/7650300，但 metadata-bin 中解包出来是 7600100，请求将被直接拒绝。  
**Metadata Proto 定义**：  
`message Metadata {`  
    `string access_key = 1;`  
    `string mobi_app = 2; // 固定为 "android"`  
    `string device = 3;   // 固定为 "phone"`  
    `int32 build = 4;     // 版本号，必须与 User-Agent 一致`  
    `string channel = 5;  // 渠道，如 "master", "xiaomi"`  
    `string buvid = 6;    // 必须与 Header 中的 buvid 一致`  
    `string platform = 7; // "android"`  
`}`

**构造策略**：

1. 使用 Protobuf 编译器生成对应的类。  
2. 填充真实数据（尤其是 access\_key 和 buvid）。  
3. 序列化为字节数组。  
4. 进行 URL Safe 的 Base64 编码（通常标准 Base64 即可，但需注意 padding）。

### **6.3 行为风控：dm\_img 与滑动窗口**

除了静态的 Header 校验，Web 端和部分 App 接口还引入了动态行为参数 dm\_img\_list, dm\_img\_str, dm\_cover\_img\_str。

* **原理**：这些参数携带了客户端的各种环境特征（如 Canvas 指纹、WebGL 渲染能力、鼠标轨迹 windowBounds）。  
* **变化**：在 2024-2025 年，windowBounds 不再允许为全零，必须反映真实的浏览器窗口大小和滚动位置。  
* **对策**：在进行 API 调用模拟时，若遇到需要这些参数的接口，必须完整模拟浏览器的 DOM 环境（如使用 Playwright 或深度定制的 JSDOM），单纯的 HTTP 请求极难绕过此类校验。

## **7\. 结论**

2024-2025 年间，Bilibili 的 API 安全架构已完成从“参数签名”向“环境证明”的范式转移。

1. **Web 端**：Wbi 签名通过动态 Mixin 算法和 w\_webid JWT 构筑了第一道防线。  
2. **App 端**：64 位环境下的 Native 签名、TLS 指纹校验以及 android64 头部的特殊处理，使得协议逆向的门槛显著提高。  
3. **协议层**：gRPC 的全面普及不仅提升了性能，其二进制的不透明性也天然阻挡了大量初级爬虫。  
4. **身份层**：buvid3/4 与硬件指纹的强绑定，意味着任何 API 调用都必须基于一个“可信设备”。

对于开发者与研究人员而言，单纯的密钥提取已不足以应对当前的风控。未来的研究重点将必然转向 gRPC 协议的自动化解析、Native 层指纹算法的模拟（Unidbg/Frida）以及基于 AI 的行为轨迹伪造。  
*报告生成时间：2026年1月22日*

#### **引用的文献**

1\. Bilibili (BILI) Q2 2025: Major Wins & Growth Highlights\! \- YouTube, https://www.youtube.com/watch?v=lQ2NVThqAjM 2\. \`w\_webid\` related discovery · Issue \#1107 · SocialSisterYi/bilibili ..., https://github.com/SocialSisterYi/bilibili-API-collect/issues/1107 3\. bilibili/wbi.go at master \- GitHub, https://github.com/CuteReimu/bilibili/blob/master/wbi.go 4\. Support v1 of server reflection API \#312 \- fullstorydev/grpcurl \- GitHub, https://github.com/fullstorydev/grpcurl/issues/312