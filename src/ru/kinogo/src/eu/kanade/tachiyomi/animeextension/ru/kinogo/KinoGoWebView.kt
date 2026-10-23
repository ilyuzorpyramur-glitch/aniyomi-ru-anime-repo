package eu.kanade.tachiyomi.animeextension.ru.kinogo

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The player hosts (kinogo667.lat and friends) sit behind a DDoS-Guard
 * JavaScript challenge that rejects any non-browser TLS client. Loading the
 * embed in a real WebView is the only way to get its playlist tokens and to
 * exchange them for HLS urls.
 */
internal class KinoGoWebView {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private class Bridge(private val latch: CountDownLatch) {
        @Volatile
        var result: String? = null

        @JavascriptInterface
        fun setResponse(value: String) {
            result = value
            latch.countDown()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun eval(embedUrl: String, wantFiles: Boolean, timeoutSec: Long = 45): String? {
        val latch = CountDownLatch(1)
        val bridge = Bridge(latch)
        var webView: WebView? = null

        handler.post {
            val view = WebView(context)
            webView = view
            with(view.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                userAgentString = USER_AGENT
            }
            view.addJavascriptInterface(bridge, BRIDGE)
            view.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    val script = "$JS_LIB\n" + """
                        (function poll(n) {
                          if (document.documentElement.innerHTML.indexOf('"file"') >= 0 || n <= 0) {
                            kgResolve($wantFiles).then(function (r) {
                              $BRIDGE.setResponse(r);
                            }).catch(function (e) {
                              $BRIDGE.setResponse(JSON.stringify({ error: String(e) }));
                            });
                          } else {
                            setTimeout(function () { poll(n - 1); }, 1500);
                          }
                        })(20);
                    """.trimIndent()
                    v?.evaluateJavascript(script, null)
                }
            }
            view.loadUrl(embedUrl, mapOf("Referer" to REFERER))
        }

        latch.await(timeoutSec, TimeUnit.SECONDS)
        handler.post {
            webView?.stopLoading()
            webView?.destroy()
        }

        return bridge.result
    }

    companion object {
        private const val BRIDGE = "kinoGoBridge"
        private const val REFERER = "https://kinogo-10.biz/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"

        private val JS_LIB = """
            async function kgResolve(wantFiles) {
              var html = document.documentElement.innerHTML;
              var m = html.match(/"file":"([^"]+)"/);
              if (!m) return JSON.stringify({ error: "no-file" });
              var raw = m[1];
              var start = raw.indexOf("W3si");
              if (start < 0) return JSON.stringify({ error: "no-marker" });
              var b = raw.slice(start).replace(/[^A-Za-z0-9+/]/g, "");
              b = b.slice(0, b.length - b.length % 4);
              var txt;
              try { txt = atob(b); } catch (e) { return JSON.stringify({ error: "b64" }); }
              var un = function (s) {
                return s.replace(/\\u([0-9a-fA-F]{4})/g, function (_, h) {
                  return String.fromCharCode(parseInt(h, 16));
                }).replace(/\\\//g, "/").replace(/&quot;/g, '"');
              };
              var re = /"title":"((?:\\.|[^"\\])*)","title2":"((?:\\.|[^"\\])*)","data":"([^"]+)"/g;
              var out = [];
              var x;
              while ((x = re.exec(txt)) !== null) {
                var item = {
                  title: un(x[1]),
                  title2: un(x[2]),
                  data: x[3].replace(/\\\//g, "/"),
                  file: ""
                };
                if (wantFiles) {
                  try {
                    var r = await fetch("/api/playlist/load", {
                      method: "POST",
                      headers: { "Content-Type": "application/json" },
                      body: JSON.stringify(item.data)
                    });
                    var j = await r.json();
                    item.file = j.file || "";
                  } catch (e) {
                    item.file = "";
                  }
                }
                out.push(item);
              }
              return JSON.stringify(out);
            }
        """.trimIndent()
    }
}
