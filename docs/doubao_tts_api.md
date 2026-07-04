# 豆包 TTS 语音合成 API 文档

## 基本信息

| 项目 | 内容 |
|------|------|
| **接口名称** | 豆包 TTS 语音合成 |
| **接口路径** | `api/app/api/doubao_tts` |
| **请求方式** | GET / POST |
| **完整地址** | `https://api.sway99.com/api/app/api/doubao_tts` |

## 请求参数

| 参数名 | 类型 | 必填 | 说明 |
|--------|------|------|------|
| `action` | string | 否 | 操作类型：`synthesize`（合成语音，默认）/ `speakers`（获取音色列表） |
| `text` | string | 否 | 要合成的文本内容（action=synthesize时必填），最长1000字符 |
| `speaker` | string | 否 | 音色选择，默认 `taozi`（桃子女声） |
| `speed` | number | 否 | 语速调节，范围 -100~100，默认 0。负数减速，正数加速 |
| `pitch` | string | 否 | 音调调节，范围 -100~100，默认 0。负数降调，正数升调 |
| `api_key` | string | **是** | API密钥 |

## 可用音色列表

| 音色标识 | 名称 | 风格描述 |
|----------|------|----------|
| `taozi` | 桃子 - 豆包默认 | 朗读女声、通用、自然 |
| `shuangkuai` | 爽快 | 活泼女声、轻快、活力 |
| `tianmei` | 甜美 | 甜美女声、温柔、亲切 |
| `qingche` | 清澈 | 清澈女声、清晰、明亮 |
| `male` | 阳光 | 男声对话、男声、自然 |
| `chenwen` | 沉稳 | 成熟男声、稳重、专业 |
| `en_female` | 英文女声 | 英文内容 |

## 返回格式

### 成功返回

```json
{
  "code": 0,
  "msg": "合成成功",
  "data": {
    "audio_url": "https://api.sway99.com/storage/tts/20260505/a1b2c3d4e5f6g7h8.aac",
    "format": "aac",
    "file_size": 24576,
    "text": "你好，我是豆包助手，很高兴为您服务",
    "speaker": "taozi",
    "speed": 0,
    "pitch": 0
  }
}
```

### 返回参数说明

| 参数名 | 类型 | 说明 |
|--------|------|------|
| `code` | number | 状态码，0=成功 |
| `msg` | string | 提示信息 |
| `data` | object | 返回数据对象 |
| `data.audio_url` | string | 音频文件访问URL |
| `data.format` | string | 音频格式，固定为 `aac` |
| `data.file_size` | string | 音频文件大小（字节） |
| `data.text` | string | 合成的文本内容 |
| `data.speaker` | string | 使用的音色 |
| `data.speed` | string | 语速参数 |
| `data.pitch` | string | 音调参数 |

## 请求示例

```
GET https://api.sway99.com/api/app/api/doubao_tts?action=synthesize&text=你好世界&speaker=taozi&speed=0&pitch=0&api_key=8c97b4c7-4ecb-5dc2-3d9b-5987571224c2
```

## Legado TTS 源配置说明

### 配置文件

提供两个版本：

| 文件 | 说明 | 特点 |
|------|------|------|
| `doubao_tts.json` | 简化版 | 单行 JSON，直接复制粘贴即可，固定桃子女声 |
| `doubao_tts_full.json` | 完整版 | 支持 7 种音色选择 + 音调调节，需长按引擎配置 |

### 导入方式（复制粘贴）

1. 打开文件，**全选复制**单行 JSON 内容
2. 打开 Legado 应用
3. 进入「朗读引擎」→ 点击右上角「+」→「导入」
4. 在文本框中**粘贴** JSON 内容
5. 点击确定导入

### 配置参数说明（完整版）

- **音色**: 可选择 7 种不同的音色（桃子/爽快/甜美/清澈/阳光/沉稳/英文女声）
- **音调**: -100 到 100 的整数值，默认 0
- **语速**: 通过 Legado 的语速滑块控制（5-50），自动转换为 API 的 -100~100 范围

### 技术实现

**简化版**（doubao_tts.json）：
- 所有逻辑内联在 `url` 字段的 `@js:` 中
- 使用 IIFE 立即执行函数
- 固定使用桃子女声（taozi）

**完整版**（doubao_tts_full.json）：
- 使用 `jsLib` 定义函数和音色映射表
- 通过 `source.getLoginInfoMap()` 获取用户配置
- `loginUi` 提供音色下拉框和音调输入框
- `loginUrl` 定义空的 login 函数（必需）

### 数据流

```
朗读文本 → @js: 调用 JS 函数
    ↓
构建 API 请求 URL（含 text/speaker/speed/pitch/api_key）
    ↓
java.ajax() 请求豆包 API
    ↓
解析 JSON 响应获取 audio_url
    ↓
返回音频 URL → Legado 下载并播放
```

### 注意事项

1. API 有频率限制，请勿频繁调用
2. 单次合成文本最长 1000 字符
3. 音频格式为 AAC
4. API 密钥已内置在配置中