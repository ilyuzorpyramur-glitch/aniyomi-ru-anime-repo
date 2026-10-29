package eu.kanade.tachiyomi.animeextension.ru.anistar

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * deep-ent.ru — внешний плеер (Next.js), который отдаёт видео только когда iframe
 * открыт со страницы сайта (иначе API отвечает «Плеер недоступен для этого сайта»).
 *
 * Поэтому грузим не embed напрямую, а страницу релиза: она сама подставляет iframe
 * с правильным Referer, а мы перехватываем запрошенный m3u8. Для конкретной серии
 * подменяем src у iframe на embed с `&episode=N`.
 */
class DeepEntResolver(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val context: Application by injectLazy()

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    data class Result(
        val streams: List<String> = emptyList(),
        val apiBodies: List<String> = emptyList(),
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(pageUrl: String, embedUrl: String?, episode: Int?, timeoutSeconds: Long): Result {
        val latch = CountDownLatch(1)
        val streams = LinkedHashSet<String>()
        val bodies = mutableListOf<String>()
        var webView: WebView? = null
        val bridge = Bridge { payload -> if (bodies.size < 20) bodies += payload }

        handler.post {
            val view = WebView(context)
            webView = view
            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = headers["User-Agent"]
            }
            view.addJavascriptInterface(bridge, BRIDGE)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString().orEmpty()
                    if (url.contains(".m3u8")) {
                        streams += url
                        latch.countDown()
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(HOOK_JS.replace("%B%", BRIDGE), null)
                    if (embedUrl != null && episode != null) {
                        view?.evaluateJavascript(frameJs(embedUrl, episode), null)
                    }
                }
            }
            view.loadUrl(pageUrl)
        }

        latch.await(timeoutSeconds, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }

        runCatching {
            java.io.File(context.cacheDir, "deepent.jsonl").writeText(bodies.joinToString("\n"))
        }

        return Result(streams.toList(), bodies)
    }

    private fun frameJs(embedUrl: String, episode: Int): String {
        val target = if (embedUrl.contains("episode=")) embedUrl else "$embedUrl&episode=$episode"
        return """
            (function(){
              try {
                var f = document.getElementById('global_frameW');
                if (f) { f.src = '$target'; }
              } catch (e) {}
            })();
        """.trimIndent()
    }

    class Bridge(private val sink: (String) -> Unit) {
        @JavascriptInterface
        fun capture(payload: String) {
            if (payload.length in 20..300_000) sink(payload)
        }
    }

    companion object {
        private const val BRIDGE = "AniStarBridge"

        private val HOOK_JS = """
            (function(){
              var B = window['%B%'];
              if (!B) return;
              var of = window.fetch;
              if (of) {
                window.fetch = function(){
                  return of.apply(this, arguments).then(function(r){
                    try { r.clone().text().then(function(t){ if (t && t.length < 200000) B.capture(t); }); } catch(e){}
                    return r;
                  });
                };
              }
            })();
        """.trimIndent()
    }
}
