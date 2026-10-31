package com.cyj265.iptvplayer.data

import fi.iki.elonen.NanoHTTPD

/**
 * 局域网管理服务（扫码管理）。
 *
 * 机顶盒上起一个轻量 HTTP 服务（端口 19090），手机与盒子同一局域网时，
 * 用浏览器打开 http://<盒子IP>:19090 即可：
 * - 查看/添加/删除直播源（每行一个 URL，可多行）；
 * - 设置节目指南（EPG/XMLTV）URL；
 * - 直接播放任意直播流。
 * 保存后盒子自动刷新播放列表。
 *
 * 界面为内嵌 HTML（深色，适配手机浏览器）。
 */
class LanRemoteServer(
    private val onSave: (sources: List<String>, epgUrl: String?) -> Unit,
    private val onPlayDirect: (String) -> Unit,
    private val getStateJson: () -> String,
    private val token: String
) : NanoHTTPD(19090) {

    /** 访问令牌：只有扫过 App 内二维码（URL 带 ?k=令牌）的设备才能操作。 */
    private fun authorized(session: IHTTPSession): Boolean {
        return session.parms["k"] == token
    }

    override fun serve(session: IHTTPSession): Response {
        if (!authorized(session)) {
            return newFixedLengthResponse(
                Response.Status.FORBIDDEN, "text/html; charset=utf-8",
                """
                <!DOCTYPE html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>揽星 TV · 管理</title>
                <style>body{background:#0e1116;color:#e8ecf2;font-family:sans-serif;text-align:center;margin-top:80px;padding:0 24px}</style>
                </head><body><h2>无权访问</h2>
                <p>请回到机顶盒，在设置里打开“扫码管理”用手机扫描二维码访问。</p>
                </body></html>
                """.trimIndent()
            )
        }
        return try {
            when (session.uri) {
                "/", "" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", pageHtml)
                "/api/state" -> newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", getStateJson())
                "/api/save" -> handleSave(session)
                "/api/play" -> handlePlay(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8", "404")
            }
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8", "bad request: " + e.message)
        }
    }

    private fun form(session: IHTTPSession): Map<String, String> {
        val fields = HashMap<String, String>()
        session.parseBody(fields)
        val out = HashMap<String, String>()
        for ((k, v) in session.parms) out[k] = v ?: ""
        for ((k, v) in fields) {
            if (k.startsWith("NanoHttpd")) continue
            out[k] = v ?: ""
        }
        return out
    }

    private fun handleSave(session: IHTTPSession): Response {
        val f = form(session)
        val sources = (f["sources"] ?: "")
            .split("\n", "\r\n")
            .map { it.trim() }
            .filter { it.startsWith("http") }
        val epg = f["epg"]?.trim()?.ifEmpty { null }
        if (sources.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html; charset=utf-8",
                resultHtml("未保存：请至少填写一个有效的直播源地址（http 开头）")
            )
        }
        onSave(sources, epg)
        return newFixedLengthResponse(
            Response.Status.OK, "text/html; charset=utf-8",
            resultHtml("已保存 " + sources.size + " 个直播源，机顶盒正在刷新播放列表…<br>你可以关闭本页面了。")
        )
    }

    private fun handlePlay(session: IHTTPSession): Response {
        val f = form(session)
        val url = f["url"]?.trim()
        if (url.isNullOrEmpty() || !url.startsWith("http")) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html; charset=utf-8",
                resultHtml("未播放：请输入有效的直播流地址（http 开头）")
            )
        }
        onPlayDirect(url)
        return newFixedLengthResponse(
            Response.Status.OK, "text/html; charset=utf-8",
            resultHtml("已开始播放，返回机顶盒即可观看。")
        )
    }

    private fun resultHtml(msg: String): String = """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>揽星 TV · 管理</title>
        <style>
        body{background:#0e1116;color:#e8ecf2;font-family:sans-serif;max-width:560px;margin:24px auto;padding:0 16px}
        .box{background:#1a1e24;border-radius:12px;padding:24px;margin-top:16px;text-align:center}
        .ok{color:#4ade80;font-size:17px}
        a{color:#7cb0ff}
        </style></head><body>
        <div class="box"><div class="ok">$msg</div>
        <p><a href="/?k=$token">返回管理页</a></p></div>
        </body></html>
    """.trimIndent()

    private val pageHtml: String = """
        <!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>揽星 TV · 局域网管理</title>
        <style>
        body{background:#0e1116;color:#e8ecf2;font-family:-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;max-width:560px;margin:24px auto;padding:0 16px}
        h1{font-size:22px;margin:8px 0}
        .sub{color:#9aa3b0;font-size:13px;margin-bottom:20px}
        .card{background:#1a1e24;border-radius:14px;padding:20px;margin-bottom:16px}
        .card h2{font-size:15px;color:#7cb0ff;margin:0 0 10px}
        label{display:block;font-size:13px;color:#9aa3b0;margin:10px 0 6px}
        textarea,input[type=text]{width:100%;box-sizing:border-box;background:#101216;color:#e8ecf2;border:1px solid #2a2f38;border-radius:8px;padding:12px;font-size:14px;margin-bottom:6px}
        textarea{min-height:120px;line-height:1.7}
        button{width:100%;background:#2f7cf6;color:#fff;border:0;border-radius:8px;padding:13px;font-size:15px;margin-top:6px}
        button.gray{background:#232830;color:#e8ecf2}
        .hint{color:#6b7280;font-size:12px;line-height:1.6;margin-top:8px}
        .status{background:#101216;border-radius:8px;padding:10px 12px;font-size:12px;color:#9aa3b0;margin-bottom:10px;white-space:pre-wrap;line-height:1.6}
        #toast{position:fixed;left:50%;bottom:30px;transform:translateX(-50%);background:#1f5cc8;color:#fff;padding:10px 18px;border-radius:20px;font-size:13px;opacity:0;transition:opacity .3s;pointer-events:none}
        </style></head><body>
        <h1>揽星 TV · 管理</h1>
        <div class="sub">手机与机顶盒在同一局域网内。保存后盒子会自动刷新。</div>

        <div class="card">
          <h2>当前状态</h2>
          <div class="status" id="state">加载中…</div>
        </div>

        <div class="card">
          <h2>直播源（每行一个，支持多行）</h2>
          <textarea id="sources" placeholder="https://example.com/list.m3u"></textarea>
          <div class="hint">支持 .m3u / .m3u8 / .txt 播放列表地址，每行填一个，可同时配置多个源。</div>
        </div>

        <div class="card">
          <h2>节目指南 EPG（可选）</h2>
          <input type="text" id="epg" placeholder="https://example.com/epg.xml">
          <button class="gray" onclick="save()">保存直播源与节目指南</button>
        </div>

        <div class="card">
          <h2>直接播放</h2>
          <input type="text" id="direct" placeholder="http://example.com/live.m3u8">
          <button onclick="play()">立即播放</button>
        </div>

        <div id="toast"></div>
        <script>
        var K='$token';
        function loadState(){
          fetch('/api/state?k='+K).then(r=>r.json()).then(d=>{
            var src = d.sources || [];
            var txt = '直播源：' + src.length + ' 个' + (src.length? '（当前第 ' + (d.active+1) + ' 个）':'');
            txt += '\n' + src.map((s,i)=>(i===d.active?'▸ ':'  ')+s).join('\n');
            txt += '\n\n节目指南：' + (d.epg && d.epg.trim()? d.epg : '未设置');
            if(d.epgCount>0) txt += '\n已加载 ' + d.epgCount + ' 条节目';
            document.getElementById('state').textContent = txt;
            document.getElementById('sources').value = src.join('\n');
            document.getElementById('epg').value = d.epg || '';
          }).catch(()=>{
            document.getElementById('state').textContent = '状态获取失败';
          });
        }
        function toast(m){var t=document.getElementById('toast');t.textContent=m;t.style.opacity=1;setTimeout(()=>t.style.opacity=0,2500);}
        function save(){
          var fd=new FormData();
          fd.append('sources', document.getElementById('sources').value);
          fd.append('epg', document.getElementById('epg').value);
          fetch('/api/save?k='+K,{method:'POST',body:fd}).then(r=>r.text()).then(t=>{
            document.open();document.write(t);document.close();
          }).catch(()=>toast('保存失败'));
        }
        function play(){
          var fd=new FormData();
          fd.append('url', document.getElementById('direct').value);
          fetch('/api/play?k='+K,{method:'POST',body:fd}).then(r=>r.text()).then(t=>{
            document.open();document.write(t);document.close();
          }).catch(()=>toast('请求失败'));
        }
        loadState();
        </script>
        </body></html>
    """.trimIndent()
}
