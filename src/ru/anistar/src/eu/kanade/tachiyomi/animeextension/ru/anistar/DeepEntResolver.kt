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
 * deep-ent.ru — внешний плеер (Next.js), который сам подписывает запросы к своему API.
 * Обычный HTTP-клиент получает 403 «Плеер недоступен для этого сайта», поэтому
 * страницу грузим в WebView, перехватываем запрошенные m3u8 и ответы API с сериями.
 */
class DeepEntResolver(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val context: Application by injectLazy()

    private val handler by lazy { Handler(Looper.getMainLooper()) }

    data class Result(
        val streams: List<String> = emptyList(),
        val episodeNumbers: List<Int> = emptyList(),
        val apiBodies: List<String> = emptyList(),
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun resolve(embedUrl: String, timeoutSeconds: Long): Result {
        val latch = CountDownLatch(1)
        val streams = LinkedHashSet<String>()
        val bodies = mutableListOf<String>()
        var webView: WebView? = null
        val bridge = Bridge(latch) { payload -> if (bodies.size < 20) bodies += payload }
        val bridgeName = "AniStarBridge"

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
            view.addJavascriptInterface(bridge, bridgeName)
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
                    view?.evaluateJavascript(HOOK_JS.replace("%B%", bridgeName), null)
                }
            }
            view.loadUrl(embedUrl)
        }

        latch.await(timeoutSeconds, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }

        runCatching {
            java.io.File(context.cacheDir, "deepent.jsonl").writeText(bodies.joinToString("\n"))
        }

        val numbers = bodies
            .flatMap { body ->
                (EPISODE_JSON_REGEX.findAll(body) + EPISODE_ALT_REGEX.findAll(body))
                    .map { m -> m.groupValues[1].toIntOrNull() }
            }
            .filterNotNull()
            .distinct()
            .sorted()

        return Result(streams.toList(), numbers, bodies)
    }

    class Bridge(private val latch: CountDownLatch, private val sink: (String) -> Unit) {
        @JavascriptInterface
        fun capture(payload: String) {
            if (payload.length in 20..300_000) sink(payload)
        }

        @JavascriptInterface
        fun done() = latch.countDown()
    }

    companion object {
        private val EPISODE_JSON_REGEX = Regex(""""number"\s*:\s*(\d{1,4})""")

        private val EPISODE_ALT_REGEX = Regex(""""episode"\s*:\s*(\d{1,4})""")

        private val HOOK_JS = """
            (function(){
              var B = window['%B%'];
              if (!B) return;
              var of = window.fetch;
              if (of) {
                window.fetch = function(){
                  return of.apply(this, arguments).then(function(r){
                    try { r.clone().text().then(function(t){ if (t && t.indexOf('episode') > -1) B.capture(t); }); } catch(e){}
                    return r;
                  });
                };
              }
              try {
                var seg = location.pathname.split('/').filter(Boolean)[0] || '';
                var qs = new URLSearchParams(location.search);
                var api = '/api/v1/anime/' + seg + '?season=' + (qs.get('season') || '') +
                          '&partner=' + (qs.get('partner') || '');
                fetch(api, { headers: { 'accept': 'application/json' } })
                  .then(function(r){ return r.text(); })
                  .then(function(t){ if (t) B.capture(t); })
                  .catch(function(){});
              } catch (e) {}
              var oo = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function(){
                this.addEventListener('load', function(){
                  try { if (this.responseText && this.responseText.indexOf('episode') > -1) B.capture(this.responseText); } catch(e){}
                });
                return oo.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
