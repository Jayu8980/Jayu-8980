package com.autotube.ai.pipeline.script

import com.autotube.ai.core.model.AppLanguage
import com.autotube.ai.core.model.EditStyle
import com.autotube.ai.core.model.Emotion
import com.autotube.ai.core.model.Scene
import com.autotube.ai.core.model.ScenePriority
import com.autotube.ai.core.model.SceneRole
import com.autotube.ai.core.model.Script
import com.autotube.ai.core.model.TopicCandidate
import com.autotube.ai.core.model.VideoRequest
import com.autotube.ai.pipeline.length.DurationModel
import kotlin.math.max

/**
 * Template-based script writer used when no LLM is available. Builds a coherent
 * hook -> intro -> 3-8 main points -> CTA script from the niche and topic, in English or Hindi
 * (Devanagari), with varied emotions/intensity and English stock-footage keywords.
 */
internal object OfflineScriptTemplates {

    private class Point(
        val id: Int,
        val sectionEn: String,
        val sectionHi: String,
        val emotion: Emotion,
        val intensity: Int,
        val visual: String,
        val keywords: List<String>,
        val en: List<String>,
        val hi: List<String>,
    ) {
        fun section(language: AppLanguage) = if (language == AppLanguage.HINDI) sectionHi else sectionEn
        fun sentences(language: AppLanguage) = if (language == AppLanguage.HINDI) hi else en
    }

    private val POINTS = listOf(
        Point(
            1, "Where it all began", "शुरुआत कहाँ से हुई", Emotion.CALM, 4,
            "Archival style footage and old photographs related to {x}",
            listOf("{x} history", "vintage archive footage", "old photographs", "historical documents"),
            listOf(
                "To understand {x}, you have to go back to where it started.",
                "It didn't begin as something big. It started with a few curious people asking simple questions.",
                "Over time, those small ideas grew into something nobody could ignore.",
                "And a lot of what we take for granted today comes straight from those early days.",
                "It's a reminder that every big thing starts small.",
            ),
            listOf(
                "{x} को समझने के लिए हमें वहाँ जाना होगा जहाँ से इसकी शुरुआत हुई।",
                "शुरुआत में ये कोई बड़ी चीज़ नहीं थी। कुछ जिज्ञासु लोगों ने बस आसान से सवाल पूछे थे।",
                "धीरे-धीरे वही छोटे विचार इतने बड़े हो गए कि कोई उन्हें नज़रअंदाज़ नहीं कर सका।",
                "आज हम जिन बातों को आम मानते हैं, उनमें से कई उसी दौर से आई हैं।",
                "ये याद दिलाता है कि हर बड़ी चीज़ छोटी शुरुआत से ही बनती है।",
            ),
        ),
        Point(
            2, "How it actually works", "ये असल में काम कैसे करता है", Emotion.CALM, 5,
            "Close-up details and simple mechanisms that explain {x}",
            listOf("{x} close up", "gears mechanism", "domino effect", "abstract diagram animation"),
            listOf(
                "So how does {x} actually work?",
                "At its core, it comes down to a few simple pieces working together.",
                "When one piece changes, everything else adjusts, a bit like a row of dominoes.",
                "Once you see that pattern, the whole thing suddenly clicks.",
                "And honestly, it's simpler than most explanations make it sound.",
            ),
            listOf(
                "तो {x} असल में काम कैसे करता है?",
                "इसकी जड़ में कुछ आसान से हिस्से हैं जो मिलकर काम करते हैं।",
                "जब एक हिस्सा बदलता है, तो बाकी सब भी बदल जाते हैं, बिल्कुल डोमिनो की तरह।",
                "एक बार ये पैटर्न समझ आ जाए, तो पूरी बात अचानक साफ़ हो जाती है।",
                "सच कहूँ तो ये उतना मुश्किल नहीं है जितना लोग बताते हैं।",
            ),
        ),
        Point(
            3, "The biggest myth", "सबसे बड़ा भ्रम", Emotion.DRAMATIC, 7,
            "A person looking doubtful, question marks and a magnifying glass over evidence about {x}",
            listOf("person shaking head", "magnifying glass", "question mark", "confused person"),
            listOf(
                "Now here's the myth almost everyone believes about {x}.",
                "It sounds logical, and that's exactly why it spreads so fast.",
                "But when you look at the evidence, it just doesn't hold up.",
                "The truth is more nuanced, and way more interesting.",
                "So next time someone repeats it, you'll know better.",
            ),
            listOf(
                "अब बात करते हैं उस भ्रम की, जिसे लगभग हर कोई {x} के बारे में सच मानता है।",
                "ये सुनने में सही लगता है, और इसीलिए ये इतनी तेज़ी से फैलता है।",
                "लेकिन जब हम सबूत देखते हैं, तो ये बात टिकती नहीं।",
                "सच्चाई थोड़ी पेचीदा है, और कहीं ज़्यादा दिलचस्प भी।",
                "तो अगली बार कोई ये बात दोहराए, तो आपको सच पता होगा।",
            ),
        ),
        Point(
            4, "The surprising part", "सबसे हैरान करने वाली बात", Emotion.EXCITING, 8,
            "Big dramatic reveal shots, timelapses and slow motion connected to {x}",
            listOf("surprised face", "city timelapse", "slow motion explosion", "{x} cinematic"),
            listOf(
                "And this is the part that surprises people the most.",
                "The scale of {x} is far bigger than most of us imagine.",
                "Small changes can lead to huge results, sometimes overnight.",
                "Once you notice it, you start seeing it everywhere.",
                "That's what makes {x} so fascinating.",
            ),
            listOf(
                "और अब वो बात जो लोगों को सबसे ज़्यादा हैरान करती है।",
                "{x} का दायरा हमारी सोच से कहीं ज़्यादा बड़ा है।",
                "छोटे-छोटे बदलाव कभी-कभी रातों-रात बड़े नतीजे दे देते हैं।",
                "एक बार ये दिख जाए, तो ये आपको हर जगह दिखने लगेगा।",
                "यही चीज़ {x} को इतना दिलचस्प बनाती है।",
            ),
        ),
        Point(
            5, "Why it matters to you", "ये आपके लिए क्यों ज़रूरी है", Emotion.HAPPY, 5,
            "Everyday life scenes: people at home, on busy streets, morning routines",
            listOf("people daily life", "morning routine", "busy street", "family at home"),
            listOf(
                "But why should you care about {x}?",
                "Because it quietly shapes your everyday life, from your choices to your routines.",
                "Most of the time you don't even notice it happening.",
                "And once you understand it, you can actually use it to your advantage.",
                "That's where things get really practical.",
            ),
            listOf(
                "लेकिन आपको {x} की परवाह क्यों करनी चाहिए?",
                "क्योंकि ये चुपचाप आपकी रोज़ की ज़िंदगी को, आपके फ़ैसलों से लेकर आदतों तक, प्रभावित करता है।",
                "ज़्यादातर वक्त हमें पता भी नहीं चलता कि ये हो रहा है।",
                "और जब आप इसे समझ लेते हैं, तो इसका फ़ायदा भी उठा सकते हैं।",
                "यहीं से बात सच में काम की हो जाती है।",
            ),
        ),
        Point(
            6, "What experts notice", "जानकार क्या देखते हैं", Emotion.SUSPENSE, 6,
            "Researchers and experts studying data, charts and details of {x}",
            listOf("scientist working", "researcher laboratory", "data charts screen", "person studying"),
            listOf(
                "Experts who study {x} notice something the rest of us miss.",
                "They don't just look at the obvious results. They look at the patterns behind them.",
                "And those patterns tell a very different story.",
                "It's the difference between guessing and really understanding.",
                "Here's the takeaway: look one level deeper than everyone else.",
            ),
            listOf(
                "जो लोग {x} को गहराई से समझते हैं, वो एक ऐसी चीज़ देखते हैं जो बाकी लोग नहीं देख पाते।",
                "वो सिर्फ़ ऊपर से दिखने वाले नतीजे नहीं देखते, बल्कि उनके पीछे का पैटर्न देखते हैं।",
                "और वही पैटर्न एक बिल्कुल अलग कहानी बताते हैं।",
                "यही फ़र्क है अंदाज़ा लगाने और सच में समझने में।",
                "सीख ये है कि बाकी सबसे एक कदम गहराई में देखिए।",
            ),
        ),
        Point(
            7, "How to get started", "शुरुआत कैसे करें", Emotion.INSPIRING, 6,
            "Someone planning in a notebook, taking a first step, sunrise",
            listOf("person writing notes", "notebook planning", "first step", "sunrise motivation"),
            listOf(
                "So if you want to get into {x}, where do you start?",
                "Start small. Pick one simple thing and do it consistently for a week.",
                "Don't worry about being perfect. Progress beats perfection every time.",
                "Track what works, drop what doesn't, and keep going.",
                "You'll be surprised how fast it adds up.",
            ),
            listOf(
                "तो अगर आप {x} में शुरुआत करना चाहते हैं, तो कहाँ से शुरू करें?",
                "छोटे से शुरू करें। एक आसान चीज़ चुनिए और एक हफ़्ते तक रोज़ कीजिए।",
                "परफ़ेक्ट होने की चिंता मत कीजिए। लगातार आगे बढ़ना ही असली जीत है।",
                "जो काम करे उसे रखिए, जो न करे उसे छोड़िए, और चलते रहिए।",
                "आप हैरान होंगे कि कितनी जल्दी फ़र्क दिखने लगता है।",
            ),
        ),
        Point(
            8, "What's next", "आगे क्या होगा", Emotion.INSPIRING, 7,
            "Futuristic technology, glowing city skyline at night, a bright horizon",
            listOf("futuristic technology", "city skyline night", "innovation abstract", "horizon sunrise"),
            listOf(
                "So where is {x} heading next?",
                "New ideas and new tools are changing it faster than ever.",
                "What feels impossible today might be completely normal in a few years.",
                "And the people paying attention right now will be the first to benefit.",
                "The story of {x} is still being written.",
            ),
            listOf(
                "तो {x} आगे किस दिशा में जा रहा है?",
                "नए विचार और नए साधन इसे पहले से कहीं तेज़ी से बदल रहे हैं।",
                "जो आज नामुमकिन लगता है, वो कुछ सालों में बिल्कुल आम हो सकता है।",
                "और जो लोग अभी ध्यान दे रहे हैं, उन्हें सबसे पहले फ़ायदा मिलेगा।",
                "{x} की कहानी अभी लिखी जा रही है।",
            ),
        ),
    )

    /** Which points to use first when fewer than eight fit. */
    private val POINT_PRIORITY = listOf(2, 4, 3, 5, 1, 7, 6, 8)

    private val HOOKS_EN = listOf(
        "Most people get {x} completely wrong, and the real story is way stranger.",
        "What if everything you've heard about {x} was only half true?",
        "Here's something about {x} that almost nobody talks about.",
        "Give me a minute, and you'll never see {x} the same way again.",
    )
    private val HOOKS_HI = listOf(
        "{x} के बारे में ज़्यादातर लोग गलत सोचते हैं, और असली कहानी कहीं ज़्यादा हैरान करने वाली है।",
        "क्या हो अगर {x} के बारे में आपने जो सुना है, वो आधा ही सच हो?",
        "{x} की एक ऐसी बात है जिसके बारे में लगभग कोई बात नहीं करता।",
        "बस एक मिनट दीजिए, और {x} को देखने का आपका नज़रिया बदल जाएगा।",
    )
    private val INTROS_EN = listOf(
        "Today we're breaking down {t}. No fluff, just the parts that actually matter.",
        "Let's dig into {t}, step by step, so it finally makes sense.",
        "This is {t}, and by the end you'll know more than most people ever will.",
    )
    private val INTROS_HI = listOf(
        "आज हम बात करेंगे {t} की। बिना किसी फालतू बात के, सिर्फ़ काम की बातें।",
        "चलिए {t} को आसान भाषा में, कदम दर कदम समझते हैं।",
        "ये है {t}, और वीडियो के आख़िर तक आप इसके बारे में ज़्यादातर लोगों से ज़्यादा जानेंगे।",
    )
    private val INTRO_EXTRA_EN = listOf(
        "We'll start simple and build up to the surprising stuff.",
        "Stick around, because the last point is the one people remember.",
    )
    private val INTRO_EXTRA_HI = listOf(
        "हम आसान बातों से शुरू करेंगे और फिर गहराई में जाएँगे।",
        "आख़िर तक ज़रूर रुकिए, क्योंकि आख़िरी बात सबसे दिलचस्प है।",
    )
    private val CTAS_EN = listOf(
        "If this changed how you see {x}, hit like and subscribe for more. Which part surprised you most? Tell me in the comments.",
        "Want more stories like this about {x}? Subscribe, and drop your questions below. I read every one.",
        "That's {x} like you've never heard it. Follow for more, and share this with someone who'd love it.",
    )
    private val CTAS_HI = listOf(
        "अगर इस वीडियो ने {x} को देखने का आपका नज़रिया बदला, तो लाइक और सब्सक्राइब ज़रूर करें। कौन सी बात सबसे हैरान करने वाली लगी? कमेंट में बताइए।",
        "{x} पर ऐसी और कहानियों के लिए चैनल को सब्सक्राइब करें। अपने सवाल नीचे कमेंट में लिखें।",
        "ये थी {x} की असली कहानी। ऐसे और वीडियो के लिए फ़ॉलो करें, और इसे किसी दोस्त के साथ शेयर करें।",
    )
    private val FILLERS_EN = listOf(
        "Think about that for a second.",
        "And that's not even the most interesting part.",
        "It sounds simple, but the details matter.",
        "Here's a quick example that makes it clear.",
        "Imagine seeing {x} through fresh eyes for the first time.",
        "This is exactly where most people stop paying attention.",
        "Keep that in mind, because it comes back later.",
        "It's one of those ideas that seems obvious only after you hear it.",
        "In other words, small details can change the whole picture.",
        "That's why {x} keeps surprising even the people who know it well.",
        "Let that sink in for a moment.",
        "So what does that mean in real life?",
    )
    private val FILLERS_HI = listOf(
        "एक पल के लिए इस पर सोचिए।",
        "और ये अभी सबसे दिलचस्प बात भी नहीं है।",
        "सुनने में आसान लगता है, लेकिन असली खेल बारीकियों में है।",
        "एक छोटा सा उदाहरण इसे और साफ़ कर देगा।",
        "कल्पना कीजिए कि आप {x} को पहली बार नई नज़र से देख रहे हैं।",
        "यहीं पर ज़्यादातर लोग ध्यान देना छोड़ देते हैं।",
        "इस बात को याद रखिए, क्योंकि ये आगे काम आएगी।",
        "ये उन बातों में से है जो सुनने के बाद ही आसान लगती हैं।",
        "यानी छोटी-छोटी बातें पूरी तस्वीर बदल सकती हैं।",
        "इसीलिए {x} उन लोगों को भी हैरान करता है जो इसे अच्छी तरह जानते हैं।",
        "इस बात को एक पल के लिए महसूस कीजिए।",
        "तो असल ज़िंदगी में इसका क्या मतलब है?",
    )

    private val HOOK_KEYWORDS = listOf("{x} cinematic", "dramatic close up", "mystery dark background", "person looking surprised")
    private val INTRO_KEYWORDS = listOf("{x} overview", "aerial landscape", "person looking curious", "establishing shot")
    private val CTA_KEYWORDS = listOf("person smiling at camera", "smartphone subscribe", "thumbs up", "friends sharing phone")

    /** The subject used inside templates: the niche as typed, else the topic title. */
    fun subjectOf(request: VideoRequest, topic: TopicCandidate?): String =
        ContentText.sanitizeLine(request.niche).ifBlank { topic?.title?.let(ContentText::sanitizeLine).orEmpty() }
            .ifBlank { if (request.language == AppLanguage.HINDI) "ये विषय" else "this topic" }

    fun build(request: VideoRequest, topic: TopicCandidate, variant: Int = 0): Script {
        val language = request.language
        val subject = subjectOf(request, topic)
        val title = ContentText.sanitizeLine(topic.title).ifBlank { defaultTitle(subject, language) }
        val sceneCount = DurationModel.recommendedSceneCount(request)
        val totalWords = DurationModel.targetWords(request, DurationModel.centreMs(request), sceneCount)
        val usedFillers = mutableSetOf<Int>()

        val hook = hookScene(request, subject, variant)
        val intro = introScene(request, subject, title, variant, totalWords)
        val cta = ctaScene(request, subject, variant)

        val mainSceneCount = max(3, sceneCount - 3)
        val pointCount = minOf(8, mainSceneCount)
        val chosenIds = POINT_PRIORITY.take(pointCount).toSet()
        val points = POINTS.filter { it.id in chosenIds }
        val essentialIds = POINT_PRIORITY.take(3).toSet()
        val mainBudget = max(10, (totalWords - hook.wordCount - intro.wordCount - cta.wordCount) / mainSceneCount)

        val mains = mutableListOf<Scene>()
        points.forEachIndexed { pIndex, point ->
            val parts = mainSceneCount / pointCount + if (pIndex < mainSceneCount % pointCount) 1 else 0
            val pool = point.sentences(language).map { fill(it, subject, title) }
            var cursor = 0
            for (part in 0 until parts) {
                val sentences = mutableListOf<String>()
                var words = 0
                while (words < mainBudget * 0.9) {
                    val next = if (cursor < pool.size) {
                        pool[cursor++]
                    } else {
                        nextFiller(language, subject, title, usedFillers, point.id * 31 + part + variant) ?: break
                    }
                    sentences += next
                    words += ContentText.wordCount(next)
                }
                if (sentences.isEmpty()) continue
                mains += Scene(
                    sceneNumber = 0,
                    role = SceneRole.MAIN,
                    narrationText = sentences.joinToString(" "),
                    visualDescription = fill(point.visual, englishSubject(subject), title),
                    searchKeywords = keywords(point.keywords, subject),
                    onScreenText = ContentText.limitWords(point.section(language), 6),
                    emotion = point.emotion,
                    intensity = (point.intensity + if (part > 0) -1 else 0).coerceIn(1, 10),
                    priority = if (point.id in essentialIds && part == 0) ScenePriority.ESSENTIAL else ScenePriority.OPTIONAL,
                    keyWords = pickKeyWords(sentences.joinToString(" "), subject),
                    sectionTitle = point.section(language),
                )
            }
        }

        val scenes = (listOf(hook, intro) + mains + cta).mapIndexed { i, s -> s.copy(sceneNumber = i + 1) }
        return Script(
            title = title,
            language = language,
            scenes = scenes,
            style = if (request.isShorts) EditStyle.FAST_SHORTS else EditStyle.DOCUMENTARY,
        )
    }

    fun hookScene(request: VideoRequest, subject: String, variant: Int = 0): Scene {
        val pool = if (request.language == AppLanguage.HINDI) HOOKS_HI else HOOKS_EN
        val text = fill(Seeded.pick(pool, subject, "hook", variant), subject, subject)
        return Scene(
            sceneNumber = 1,
            role = SceneRole.HOOK,
            narrationText = text,
            visualDescription = "A striking, mysterious opening shot about ${englishSubject(subject).ifBlank { "the topic" }}",
            searchKeywords = keywords(HOOK_KEYWORDS, subject),
            onScreenText = if (request.language == AppLanguage.HINDI) "सच जानकर हैरान होंगे" else "You've been told wrong",
            emotion = Emotion.SUSPENSE,
            intensity = 8,
            priority = ScenePriority.ESSENTIAL,
            keyWords = pickKeyWords(text, subject),
        )
    }

    private fun introScene(request: VideoRequest, subject: String, title: String, variant: Int, totalWords: Int): Scene {
        val hindi = request.language == AppLanguage.HINDI
        val pool = if (hindi) INTROS_HI else INTROS_EN
        val sentences = mutableListOf(fill(Seeded.pick(pool, subject, "intro", variant), subject, title))
        if (!request.isShorts && totalWords > 400) sentences += fill(Seeded.pick(if (hindi) INTRO_EXTRA_HI else INTRO_EXTRA_EN, subject, "intro2", variant), subject, title)
        val text = sentences.joinToString(" ")
        return Scene(
            sceneNumber = 2,
            role = SceneRole.INTRO,
            narrationText = text,
            visualDescription = "Wide establishing shots that introduce ${englishSubject(subject).ifBlank { "the topic" }}",
            searchKeywords = keywords(INTRO_KEYWORDS, subject),
            onScreenText = ContentText.limitWords(title, 6),
            emotion = Emotion.CALM,
            intensity = 5,
            priority = ScenePriority.ESSENTIAL,
            keyWords = pickKeyWords(text, subject),
            sectionTitle = if (hindi) "परिचय" else "Intro",
        )
    }

    fun ctaScene(request: VideoRequest, subject: String, variant: Int = 0): Scene {
        val hindi = request.language == AppLanguage.HINDI
        val raw = fill(Seeded.pick(if (hindi) CTAS_HI else CTAS_EN, subject, "cta", variant), subject, subject)
        val text = if (request.isShorts) ContentText.sentences(raw).first() else raw
        return Scene(
            sceneNumber = 99,
            role = SceneRole.CTA,
            narrationText = text,
            visualDescription = "Friendly closing shot, a person smiling, a phone showing a subscribe button",
            searchKeywords = keywords(CTA_KEYWORDS, subject),
            onScreenText = if (hindi) "सब्सक्राइब करें" else "Subscribe for more",
            emotion = Emotion.INSPIRING,
            intensity = 6,
            priority = ScenePriority.ESSENTIAL,
            keyWords = if (hindi) listOf("सब्सक्राइब") else listOf("subscribe"),
            sectionTitle = if (hindi) "आख़िरी बात" else "Final thoughts",
        )
    }

    /**
     * Additional main scene for offline expansion: an unused point if any, otherwise a variation of
     * an existing point ("deeper look") built from fillers so it never repeats sentences verbatim.
     */
    fun extraMainScene(request: VideoRequest, script: Script, subject: String, salt: Int, targetWords: Int): Scene {
        val language = request.language
        val usedSections = script.scenes.map { it.sectionTitle.lowercase() }.toSet()
        val existingText = script.scenes.joinToString(" ") { it.narrationText }
        val fresh = POINT_PRIORITY.map { id -> POINTS.first { it.id == id } }
            .firstOrNull { it.section(language).lowercase() !in usedSections }
        val point = fresh ?: POINTS[Seeded.between(0, POINTS.size - 1L, subject, "extra", salt).toInt()]
        val usedFillers = mutableSetOf<Int>()
        val sentences = mutableListOf<String>()
        var words = 0
        if (fresh != null) {
            for (s in point.sentences(language)) {
                if (words >= targetWords) break
                val t = fill(s, subject, script.title)
                sentences += t
                words += ContentText.wordCount(t)
            }
        } else {
            val lead = if (language == AppLanguage.HINDI) "अब इसी बात को एक और नज़रिए से देखते हैं।" else "Now let's look at that from another angle."
            sentences += lead
            words += ContentText.wordCount(lead)
            point.sentences(language).map { fill(it, subject, script.title) }
                .filter { it !in existingText }
                .forEach { if (words < targetWords) { sentences += it; words += ContentText.wordCount(it) } }
        }
        while (words < targetWords) {
            val f = nextFiller(language, subject, script.title, usedFillers, salt, exclude = existingText) ?: break
            sentences += f
            words += ContentText.wordCount(f)
        }
        val section = if (fresh != null) point.section(language) else point.section(language) + if (language == AppLanguage.HINDI) " (भाग 2)" else " (part 2)"
        val text = sentences.joinToString(" ")
        return Scene(
            sceneNumber = 0,
            role = SceneRole.MAIN,
            narrationText = text,
            visualDescription = fill(point.visual, englishSubject(subject), script.title),
            searchKeywords = keywords(point.keywords, subject),
            onScreenText = ContentText.limitWords(point.section(language), 6),
            emotion = point.emotion,
            intensity = point.intensity,
            priority = ScenePriority.OPTIONAL,
            keyWords = pickKeyWords(text, subject),
            sectionTitle = section,
        )
    }

    /** Offline rewrite of one scene to about [targetWords] (or a light variation when null). */
    fun rewriteOffline(scene: Scene, language: AppLanguage, subject: String, title: String, targetWords: Int?, salt: Int): Scene {
        val sentences = ContentText.sentences(scene.narrationText).toMutableList()
        if (sentences.isEmpty()) return scene
        val target = targetWords ?: (scene.wordCount + 6)
        if (scene.wordCount > target) {
            while (sentences.size > 1 && ContentText.wordCount(sentences.joinToString(" ")) > target) {
                // Keep the opening sentence; drop from the end (CTA keeps its first, strongest line).
                sentences.removeAt(sentences.size - 1)
            }
            var text = sentences.joinToString(" ")
            if (ContentText.wordCount(text) > target + 3) {
                text = ContentText.ensureTerminalPunctuation(ContentText.limitWords(text, max(4, target)), language)
            }
            return scene.copy(narrationText = text, keyWords = pickKeyWords(text, subject, scene.keyWords))
        }
        val used = mutableSetOf<Int>()
        val existing = scene.narrationText
        var words = scene.wordCount
        val additions = mutableListOf<String>()
        while (words < target) {
            val f = nextFiller(language, subject, title, used, salt + scene.sceneNumber * 7, exclude = existing) ?: break
            additions += f
            words += ContentText.wordCount(f)
        }
        if (additions.isEmpty()) return scene
        // Insert after the first sentence so hook/CTA keep their opening and closing lines.
        val merged = when (scene.role) {
            SceneRole.CTA -> additions + sentences
            else -> listOf(sentences.first()) + additions + sentences.drop(1)
        }
        val text = merged.joinToString(" ")
        return scene.copy(narrationText = text, keyWords = pickKeyWords(text, subject, scene.keyWords))
    }

    fun defaultTitle(subject: String, language: AppLanguage): String =
        if (language == AppLanguage.HINDI) "$subject: वो सच जो कोई नहीं बताता" else "The truth about $subject"

    /** English version of the subject for stock queries; empty when the subject is not Latin script. */
    fun englishSubject(subject: String): String = if (ContentText.isAscii(subject)) subject.lowercase() else ""

    fun keywords(templates: List<String>, subject: String): List<String> {
        val en = englishSubject(subject).split(' ').filter { it.isNotBlank() }.take(3).joinToString(" ")
        return templates.map { t ->
            if (en.isBlank()) t.replace("{x}", "").replace(Regex("\\s+"), " ").trim() else t.replace("{x}", en)
        }.filter { it.isNotBlank() && it.length > 2 }.distinct().take(5)
    }

    fun pickKeyWords(text: String, subject: String, previous: List<String> = emptyList()): List<String> {
        val normalized = ContentText.words(text).map(ContentText::normalizeToken).toSet()
        val kept = previous.filter { kw -> ContentText.words(kw).all { ContentText.normalizeToken(it) in normalized } }
        if (kept.isNotEmpty()) return kept.take(3)
        val subjectWord = ContentText.contentWords(subject).firstOrNull()
            ?.takeIf { ContentText.normalizeToken(it) in normalized }
        val longest = ContentText.contentWords(text).sortedByDescending { it.length }
        return (listOfNotNull(subjectWord) + longest).distinctBy(ContentText::normalizeToken).take(2)
    }

    private fun nextFiller(
        language: AppLanguage,
        subject: String,
        title: String,
        used: MutableSet<Int>,
        salt: Int,
        exclude: String = "",
    ): String? {
        val pool = if (language == AppLanguage.HINDI) FILLERS_HI else FILLERS_EN
        val start = Seeded.between(0, pool.size - 1L, subject, "filler", salt).toInt()
        for (k in pool.indices) {
            val idx = (start + k) % pool.size
            if (idx in used) continue
            val text = fill(pool[idx], subject, title)
            used += idx
            if (exclude.isNotEmpty() && exclude.contains(text)) continue
            return text
        }
        return null
    }

    private fun fill(template: String, subject: String, title: String): String =
        template.replace("{x}", subject).replace("{t}", title).replace(Regex("\\s+"), " ").trim()
}
