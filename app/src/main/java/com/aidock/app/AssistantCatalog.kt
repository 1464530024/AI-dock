package com.aidock.app

import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.Color

/** 助手归属：国内 / 国外。仅用于抽屉分组，不影响任何逻辑。 */
enum class Region { DOMESTIC, FOREIGN }

/**
 * 一个 AI 助手站点。
 *
 * @param id        稳定标识，用于 DataStore 持久化与 WebView 状态 Map 的 key
 * @param name      显示名称（顶部栏 / 抽屉）
 * @param shortName 首字缩写，用于无障碍描述与极简场景
 * @param url       首页地址（站点改版时可在此统一修改）
 * @param domain    抽屉副标题，同时用于让用户明确当前加载的是哪个站点
 * @param color     该助手的主色，App 壳层主题由它派生
 * @param logoRes   官方 LOGO（已统一裁成圆形、五档密度），抽屉与顶栏使用
 * @param region    国内 / 国外分组
 * @param mono      LOGO 是否为纯黑单色标记（如 OpenAI 六瓣结）。
 *                  单色标记在深色主题下会消失在深色底上，渲染时改用 onSurface 着色。
 */
data class Assistant(
    val id: String,
    val name: String,
    val shortName: String,
    val url: String,
    val domain: String,
    val color: Color,
    @DrawableRes val logoRes: Int,
    val region: Region = Region.DOMESTIC,
    val mono: Boolean = false
)

object AssistantCatalog {

    /**
     * 顺序即抽屉顺序：国内 8 个在前，国外 4 个在后。
     *
     * 主色取自各平台官方 LOGO 的实际主色调（脚本采样），仅用于壳层配色，不影响网页本身。
     * LOGO 均为各站点官方发布的图标原件：
     *  - 豆包      doubao.com 官方 192px 图标
     *  - 通义千问  alicdn 官方 150px 图标
     *  - 腾讯元宝  yuanbao.tencent.com favicon 内嵌 128px PNG 帧
     *  - DeepSeek fe-static.deepseek.com 官方 180px 图标
     *  - Kimi      kimi.com PWA 512px 图标
     *  - 讯飞星火  xinghuo.xfyun.cn 官网 LOGO 矢量原件（渲染为位图）
     *  - 智谱清言  chatglm.cn 官方 64px 图标（放大 + 锐化补偿）
     *  - 文心一言  chat.baidu.com PWA manifest 官方 512px 图标
     *  - ChatGPT   OpenAI 官方六瓣结矢量件（2026 现版单色标记）
     *  - Gemini    Google 官方四角星矢量件，套官方蓝紫渐变
     *  - Claude    Anthropic 官方星芒矢量件，套品牌珊瑚色
     *  - Copilot   Microsoft Copilot 官方缎带矢量件
     */
    val all: List<Assistant> = listOf(
        Assistant(
            id = "doubao",
            name = "豆包",
            shortName = "豆",
            url = "https://www.doubao.com/chat/",
            domain = "doubao.com",
            color = Color(0xFF2B5CE6),
            logoRes = R.drawable.logo_doubao
        ),
        Assistant(
            id = "qwen",
            name = "通义千问",
            shortName = "通",
            url = "https://tongyi.aliyun.com/qianwen/",
            domain = "tongyi.aliyun.com",
            color = Color(0xFF6B4FEC),
            logoRes = R.drawable.logo_qwen
        ),
        Assistant(
            id = "yuanbao",
            name = "腾讯元宝",
            shortName = "元",
            url = "https://yuanbao.tencent.com/chat/",
            domain = "yuanbao.tencent.com",
            color = Color(0xFF02C160),
            logoRes = R.drawable.logo_yuanbao
        ),
        Assistant(
            id = "deepseek",
            name = "DeepSeek",
            shortName = "D",
            url = "https://chat.deepseek.com/",
            domain = "chat.deepseek.com",
            color = Color(0xFF4D6BFE),
            logoRes = R.drawable.logo_deepseek
        ),
        Assistant(
            id = "kimi",
            name = "Kimi",
            shortName = "K",
            url = "https://www.kimi.com/",
            domain = "kimi.com",
            color = Color(0xFF1F1F1F),
            logoRes = R.drawable.logo_kimi
        ),
        Assistant(
            id = "spark",
            name = "讯飞星火",
            shortName = "星",
            url = "https://xinghuo.xfyun.cn/desk",
            domain = "xinghuo.xfyun.cn",
            color = Color(0xFFD93025),
            logoRes = R.drawable.logo_spark
        ),
        Assistant(
            id = "chatglm",
            name = "智谱清言",
            shortName = "智",
            url = "https://chatglm.cn/",
            domain = "chatglm.cn",
            color = Color(0xFF1444F3),
            logoRes = R.drawable.logo_chatglm
        ),
        Assistant(
            id = "ernie",
            name = "文心一言",
            shortName = "文",
            url = "https://yiyan.baidu.com/",
            domain = "yiyan.baidu.com",
            color = Color(0xFF2932E1),
            logoRes = R.drawable.logo_ernie
        ),
        Assistant(
            id = "chatgpt",
            name = "ChatGPT",
            shortName = "G",
            url = "https://chatgpt.com/",
            domain = "chatgpt.com",
            color = Color(0xFF10A37F),
            logoRes = R.drawable.logo_chatgpt,
            region = Region.FOREIGN,
            mono = true
        ),
        Assistant(
            id = "gemini",
            name = "Gemini",
            shortName = "Ge",
            url = "https://gemini.google.com/app",
            domain = "gemini.google.com",
            color = Color(0xFF4285F4),
            logoRes = R.drawable.logo_gemini,
            region = Region.FOREIGN
        ),
        Assistant(
            id = "claude",
            name = "Claude",
            shortName = "C",
            url = "https://claude.ai/new",
            domain = "claude.ai",
            color = Color(0xFFD97757),
            logoRes = R.drawable.logo_claude,
            region = Region.FOREIGN
        ),
        Assistant(
            id = "copilot",
            name = "Copilot",
            shortName = "Co",
            url = "https://copilot.microsoft.com/",
            domain = "copilot.microsoft.com",
            color = Color(0xFF0F6CBD),
            logoRes = R.drawable.logo_copilot,
            region = Region.FOREIGN
        )
    )

    val domestic: List<Assistant> = all.filter { it.region == Region.DOMESTIC }
    val foreign: List<Assistant> = all.filter { it.region == Region.FOREIGN }

    fun byId(id: String?): Assistant =
        all.firstOrNull { it.id == id } ?: all.first()
}
