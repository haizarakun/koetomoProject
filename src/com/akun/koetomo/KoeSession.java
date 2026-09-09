package com.akun.koetomo;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

public class KoeSession {
    static final String APP_VERSION = "3.9.101";
    static final String BASE_URL = "https://api.meetscom.com";
    static final String BASE_URL2 = "https://api2.meetscom.com";
    private JSONObject previewCache = null;
    private static String lastPostSig = null;
    private static long lastPostAt = 0L;
    static final String PNG_FALLBACK = "https://d34we8vh702akg.cloudfront.net/";
    static final String UA = "okhttp/4.12.0";  // 公式アプリと同一(OkHttpデフォルトUA)。ログインWAFが独自UAを弾くため一致させる。
    private JSONObject clientDefines = null;
    private final Map<String, String> hostCache = new java.util.concurrent.ConcurrentHashMap<String, String>();
    // 共有BANリストで自動非表示にするUID集合(端末内フィルタ。koetomo本体のブロックには触れない)。
    private final java.util.Set<Long> bannedUids = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private volatile int lastVsns = -999;
    // メモリ軽量化: 名前キャッシュはアクセス順LinkedHashMapで最大1500件に制限し、古いものから自動破棄する
    /**
     * 投稿デコレーション(公式の「タイムライン背景画像」)のキャッシュ: user_id → 画像パス。
     * 公式 PostItem と同じく、その人の timeline_image_enabled が真のときだけ覚える。
     */
    private final Map<Long, String> decoCache = java.util.Collections.synchronizedMap(new HashMap<Long, String>());
    /** このセッションでデコレーションを確認済みの user_id(名前は端末に永続キャッシュされるので、別途 1 回は取り直す)。 */
    private final java.util.Set<Long> decoChecked = java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());

    /** /api/v2/users のユーザー1件からデコレーション画像を覚える(無ければ消す)。 */
    private void rememberDecoration(long uid, JSONObject user) {
        if (uid == 0 || user == null) return;
        decoChecked.add(Long.valueOf(uid));
        String path = truthy(user.opt("timeline_image_enabled")) ? user.optString("timeline_image_file_path", "") : "";
        if (path.length() > 0 && !"null".equals(path)) decoCache.put(Long.valueOf(uid), path);
        else decoCache.remove(Long.valueOf(uid));
    }

    private final Map<Long, String[]> nameCache = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<Long, String[]>(256, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Long, String[]> eldest) {
            return size() > 1500;
        }
    });
    private String pngServer = null;
    private final SharedPreferences prefs;
    private Context appContext = null;
    private SecureStore secureStore = null;
    SecureStore secure() { if (secureStore == null) secureStore = new SecureStore(appContext); return secureStore; }
    private volatile boolean sessionExpiredSeen = false;
    // 403 + code:1000(認証エラー)が何回続いたか。サーバー側の一時的な拒否で即ログアウトさせないため、
    // 連続して出たときだけ「本当にセッションが切れた」と判断する。
    private volatile int authErrStreak = 0;
    private String skywayHost = null;
    // 診断ログ(HTTP層)。認証トークンは伏せて記録する。
    private static final java.util.List<String> DEBUG_LOG = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
    private static volatile boolean DEBUG_LOG_ENABLED = true;

    private static String redactLog(String s) {
        if (s == null) {
            return "";
        }
        try {
            String r = s;
            // クエリ/フォーム形式: auth_token=xxx / password=xxx など
            r = r.replaceAll("(?i)(auth_token|session_?token|authtoken|access_token|skyway_?token|jwt|credential|password|new_password|current_password|pass|pwd|email|device_uid)=[^&\\s\"]+", "$1=***");
            // JSON形式: "auth_token":"xxx" (トークン/パスワード等が本文にエコーされても伏せる)
            r = r.replaceAll("(?i)(\"(?:auth_token|session_?token|authtoken|access_token|skyway_?token|jwt|credential|token|password|new_password|current_password|pass|pwd|email|device_uid)\"\\s*:\\s*\")[^\"]*\"", "$1***\"");
            // ヘッダー形式: Set-Cookie: xxx / Authorization: Bearer xxx（ログイン応答のヘッダーダンプ対策）
            r = r.replaceAll("(?im)^(\\s*(?:set-cookie|cookie|authorization|x-auth[\\w-]*|www-authenticate)\\s*:\\s*).*$", "$1***");
            return r;
        } catch (Exception e) {
            return s;
        }
    }

    private static void dbgLog(String line) {
        if (!DEBUG_LOG_ENABLED || line == null) {
            return;
        }
        try {
            synchronized (DEBUG_LOG) {
                DEBUG_LOG.add(line);
                while (DEBUG_LOG.size() > 500) {
                    DEBUG_LOG.remove(0);
                }
            }
        } catch (Exception e) {
        }
    }

    // 失敗しているGETエンドポイントに対し候補パラメータを総当たりし、どれが200で通るかを診断ログに記録する。
    private void probeTry(JSONArray out, JSONArray hits, String label, String path, Map<String, String> q) {
        try {
            Resp r = request("GET", path, q, (Map<String, String>) null);
            String line = (r.status == 200 ? "OK200 " : ("NG" + r.status + " ")) + label;
            if (r.status != 200 && r.body != null) {
                line += "  " + truncate(redactLog(r.body.toString()), 100);
            }
            dbgLog(nowStr() + "  [PROBE] " + line);
            out.put(line);
            if (r.status == 200 && hits != null) {
                hits.put(label + "  ->  " + path);
            }
        } catch (Exception e) {
            dbgLog(nowStr() + "  [PROBE] ERR " + label + "  " + e);
        }
    }

    private String probeEndpoints() {
        JSONArray out = new JSONArray();
        JSONArray hits = new JSONArray();
        try {
            // まず認証不要の公開設定ファイルを解析(koetomo の実機能一覧が分かる)
            probeConfigFiles(out);
            // 未実装の公式機能を発見(存在すれば 200/400、無ければ 404)
            discoverFeatures(out, hits);
            dbgLog(nowStr() + "  [PROBE] ===== 完了  ✅存在=" + hits.length() + "件 / 試行" + out.length() + "件 =====");
            for (int i = 0; i < hits.length(); i++) {
                dbgLog(nowStr() + "  [PROBE] ✅✅HIT: " + hits.optString(i));
            }
            return new JSONObject().put("ok", true).put("hit_count", hits.length()).put("hits", hits).put("results", out).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 認証不要の公開設定ファイルを取得し、応援トーク/ランキング関連の定義を抜き出してログに出す。
    // ここに ranking の種別enum や必須パラメータ名が定義されていれば、キャプチャ無しで解ける。
    private void probeConfigFiles(JSONArray out) {
        String[] urls = {
            "https://api.meetscom.com/config/client_defines.json",
            "https://api2.meetscom.com/config/client_defines.json",
            "https://api.meetscom.com/config/release/3.9.101.json",
            "https://api.meetscom.com/config/release/android_3.9.101.json",
            "https://api.meetscom.com/config/cheering_talk.json"
        };
        String[] keys = {"cheering", "ranking", "receiver_user", "talk_histor", "sent_coin", "participating", "\"period\"", "\"term\"", "coin"};
        try {
            dbgLog(nowStr() + "  [CONFIG] ===== 公開設定ファイル解析 =====");
            for (int u = 0; u < urls.length; u++) {
                String url = urls[u];
                String[] r;
                try {
                    r = httpText(url);
                } catch (Exception e) {
                    dbgLog(nowStr() + "  [CONFIG] ERR " + url + "  " + e);
                    continue;
                }
                String status = r.length > 0 ? r[0] : "-1";
                String body = r.length > 1 && r[1] != null ? r[1] : "";
                dbgLog(nowStr() + "  [CONFIG] GET " + url + "  → " + status + "  (" + body.length() + "B)");
                out.put("CONFIG " + status + " " + url + " (" + body.length() + "B)");
                if (!"200".equals(status) || body.length() == 0) {
                    continue;
                }
                // JSONとして読めればトップレベルキー一覧を出す
                try {
                    JSONObject cfg = new JSONObject(body);
                    JSONArray names = cfg.names();
                    StringBuilder sb = new StringBuilder();
                    if (names != null) {
                        for (int i = 0; i < names.length() && i < 60; i++) {
                            sb.append(names.optString(i)).append(", ");
                        }
                    }
                    dbgLog(nowStr() + "  [CONFIG] top-keys: " + truncate(sb.toString(), 400));
                    // client_system_params 配下の機能キーを全部出す(koetomo の実機能一覧)
                    JSONObject sys = cfg.optJSONObject("client_system_params");
                    if (sys != null) {
                        JSONArray fk = sys.names();
                        if (fk != null) {
                            StringBuilder fb = new StringBuilder();
                            for (int i = 0; i < fk.length(); i++) {
                                fb.append(fk.optString(i)).append(", ");
                                if (fb.length() > 300) {
                                    dbgLog(nowStr() + "  [CONFIG] 機能キー: " + fb.toString());
                                    fb.setLength(0);
                                }
                            }
                            if (fb.length() > 0) {
                                dbgLog(nowStr() + "  [CONFIG] 機能キー: " + fb.toString());
                            }
                            out.put("client_system_params keys: " + fk.length() + "個");
                        }
                    }
                } catch (Exception e) {
                    dbgLog(nowStr() + "  [CONFIG] head: " + truncate(body, 200));
                }
                // キーワード周辺を抜き出す
                String low = body.toLowerCase();
                for (int k = 0; k < keys.length; k++) {
                    String kw = keys[k].toLowerCase();
                    int idx = 0;
                    int found = 0;
                    while (found < 5) {
                        int p = low.indexOf(kw, idx);
                        if (p < 0) {
                            break;
                        }
                        int s = Math.max(0, p - 70);
                        int e = Math.min(body.length(), p + 140);
                        String win = body.substring(s, e).replace("\n", " ").replace("\r", " ");
                        dbgLog(nowStr() + "  [CONFIG] «" + keys[k] + "» " + truncate(win, 210));
                        out.put(keys[k] + ": " + truncate(win, 210));
                        idx = p + kw.length();
                        found++;
                    }
                }
            }
            dbgLog(nowStr() + "  [CONFIG] ===== 解析完了 =====");
        } catch (Exception e) {
            dbgLog(nowStr() + "  [CONFIG] 例外 " + e);
        }
    }

    // 未実装候補のエンドポイントを叩き、存在するか(404以外)を判定する。
    // 存在(200/400/403/500) → hits に記録して後で実装対象にする。404 → koetomo に無い。
    private void discTry(JSONArray out, JSONArray hits, String feature, String path) {
        try {
            Resp r = request("GET", path, (Map<String, String>) null, (Map<String, String>) null);
            boolean exists = r.status != 404 && r.status != -1 && r.status != 0;
            String tag = exists ? ("★存在" + r.status) : ("NG" + r.status);
            String line = tag + "  [" + feature + "] " + path;
            dbgLog(nowStr() + "  [DISC] " + line);
            out.put(line);
            if (exists && hits != null) {
                hits.put("[" + feature + "] " + path + "  (status " + r.status + ")");
            }
        } catch (Exception e) {
            dbgLog(nowStr() + "  [DISC] ERR [" + feature + "] " + path + "  " + e);
        }
    }

    private void discoverFeatures(JSONArray out, JSONArray hits) {
        dbgLog(nowStr() + "  [DISC] ===== 発見スキャン開始(v36 重要部を先頭に) =====");
        String uid = "";
        try {
            long id = userId();
            if (id > 0) {
                uid = String.valueOf(id);
            }
        } catch (Exception e) {
        }
        // 対照(1件): /api/users/<でたらめ> は200空スタブ=偽陽性の基準。他プレフィックスは正しく404が返る。
        dumpBody(out, "★対照:でたらめ", "/api/users/zzqqxx_notreal", null);

        // ================= 最終総まとめスキャン: 未テスト6カテゴリ =================
        // 1) リポスト/引用投稿
        discTry(out, hits, "リポスト", "/api/reposts");
        discTry(out, hits, "リポスト", "/api/timeline_posts/reposted");
        discTry(out, hits, "リポスト", "/api/shared_posts");
        discTry(out, hits, "リポスト", "/api/repost_posts");
        // 2) チャット(DM)リクエスト承認/拒否
        discTry(out, hits, "チャット申請", "/api/chat/requests");
        discTry(out, hits, "チャット申請", "/api/chats/requests");
        discTry(out, hits, "チャット申請", "/api/message_requests");
        discTry(out, hits, "チャット申請", "/api/chat_requests");
        // 3) 通知設定(種類別オン/オフ)
        discTry(out, hits, "通知設定", "/api/notification_settings");
        discTry(out, hits, "通知設定", "/api/settings/notifications");
        discTry(out, hits, "通知設定", "/api/notification_setting");
        dumpBody(out, "通知設定:body", "/api/notification_settings", null);
        // 4) 投稿スレッド
        discTry(out, hits, "スレッド", "/api/threads");
        discTry(out, hits, "スレッド", "/api/post_threads");
        discTry(out, hits, "スレッド", "/api/timeline_posts/threads");
        // 5) おすすめ/通話タイムライン
        discTry(out, hits, "おすすめTL", "/api/timeline_posts/recommended");
        discTry(out, hits, "おすすめTL", "/api/recommended_timeline_posts");
        discTry(out, hits, "通話TL", "/api/call_timeline_posts");
        discTry(out, hits, "通話TL", "/api/timeline_posts/call");
        // 6) 非表示/個別ミュート
        discTry(out, hits, "非表示投稿", "/api/hidden_posts");
        discTry(out, hits, "非表示ユーザー", "/api/hidden_users");
        discTry(out, hits, "非表示", "/api/hides");
        discTry(out, hits, "ミュートワード", "/api/hidden/words");
        // 追加: 会話ルート/ブックマーク投稿など
        discTry(out, hits, "会話", "/api/conversations");
        discTry(out, hits, "検索履歴", "/api/search_histories");

        dbgLog(nowStr() + "  [DISC] ===== 最終スキャン終了 =====");
    }

    // 指定エンドポイントの実レスポンス本文を(トークン秘匿+切り詰めて)ログに出す。構造把握用。
    private void dumpBody(JSONArray out, String label, String path, Map<String, String> q) {
        try {
            Resp r = request("GET", path, q, (Map<String, String>) null);
            String body = r.body != null ? r.body.toString() : "(null)";
            dbgLog(nowStr() + "  [BODY] " + label + " (status " + r.status + "): " + truncate(redactLog(body), 500));
            out.put("BODY " + label + " (" + r.status + "): " + truncate(body, 300));
        } catch (Exception e) {
            dbgLog(nowStr() + "  [BODY] ERR " + label + "  " + e);
        }
    }

    // 送金コイン等の月次パラメータ用に現在の年月(YYYYMM)を返す
    private static String ymNow() {
        try {
            return new java.text.SimpleDateFormat("yyyyMM").format(new java.util.Date());
        } catch (Exception e) {
            return "202608";
        }
    }

    private String getNativeLog() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (DEBUG_LOG) {
                for (int i = 0; i < DEBUG_LOG.size(); i++) {
                    arr.put(DEBUG_LOG.get(i));
                }
            }
            return new JSONObject().put("ok", true).put("count", arr.length()).put("log", arr).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String clearNativeLog() {
        try {
            synchronized (DEBUG_LOG) {
                DEBUG_LOG.clear();
            }
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String setDebugLogEnabled(boolean enabled) {
        DEBUG_LOG_ENABLED = enabled;
        try {
            return new JSONObject().put("ok", true).put("enabled", enabled).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    static class Resp {
        JSONObject body;
        int status;
        int vsns = -999;
        boolean sessionExpired = false;
        boolean authError = false;
        /** サーバーが「対象のデータが存在しません」(HTML の 404)を返した = 単に空。別ホストへ回す必要はない */
        boolean noData = false;

        Resp(int i, JSONObject jSONObject) {
            this.status = i;
            this.body = jSONObject;
        }
    }

    public KoeSession(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = context.getSharedPreferences("koetomo", 0);
        this.secureStore = new SecureStore(context);
        try { Secrets.init(context); } catch (Throwable ig) { }
        try {
            System.setProperty("http.keepAlive", "true");
            System.setProperty("http.maxConnections", "8");
        } catch (Exception e) {
        }
        loadHostCache();
        loadNameCache();
    }

    private void loadNameCache() {
        try {
            String saved = this.prefs.getString("name_cache", null);
            if (saved != null && saved.length() > 0) {
                JSONObject obj = new JSONObject(saved);
                java.util.Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray arr = obj.optJSONArray(k);
                    if (arr != null && arr.length() >= 2) {
                        try {
                            if (arr.optString(0, "").length() == 0) continue; // 空名は取り直させる
                            this.nameCache.put(Long.valueOf(Long.parseLong(k)), new String[]{arr.optString(0, ""), arr.optString(1, "")});
                        } catch (Exception e) {
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    private volatile boolean nameCacheSaveScheduled = false;
    /** 名前キャッシュの保存はまとめて行う(スクロール中に何十回も全件シリアライズ+書き込みしない)。3秒後に1回だけ書く。 */
    private void saveNameCache() {
        if (nameCacheSaveScheduled) return;
        nameCacheSaveScheduled = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
                nameCacheSaveScheduled = false;
                saveNameCacheNow();
            }
        }, "koe-namecache-save");
        t.setDaemon(true);
        t.start();
    }

    private void saveNameCacheNow() {
        try {
            JSONObject obj = new JSONObject();
            synchronized (this.nameCache) {
                int count = 0;
                for (Map.Entry<Long, String[]> e : this.nameCache.entrySet()) {
                    if (count >= 800) {
                        break;
                    }
                    String[] v = e.getValue();
                    if (v != null && v.length >= 2) {
                        obj.put(String.valueOf(e.getKey()), new JSONArray().put(v[0] == null ? "" : v[0]).put(v[1] == null ? "" : v[1]));
                        count++;
                    }
                }
            }
            this.prefs.edit().putString("name_cache", obj.toString()).apply();
        } catch (Exception e) {
        }
    }

    private void loadHostCache() {
        try {
            String saved = this.prefs.getString("host_cache", null);
            if (saved != null && saved.length() > 0) {
                JSONObject obj = new JSONObject(saved);
                java.util.Iterator<String> it = obj.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    String v = obj.optString(k, null);
                    if (v != null && v.length() > 0) {
                        this.hostCache.put(k, v);
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    private void saveHostCache() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, String> e : this.hostCache.entrySet()) {
                obj.put(e.getKey(), e.getValue());
            }
            this.prefs.edit().putString("host_cache", obj.toString()).apply();
        } catch (Exception e) {
        }
    }

    private void appendRoomHistory(String str, String str2, String str3, String str4, String str5) {
        try {
            JSONArray loadRoomHistoryArr = loadRoomHistoryArr();
            JSONObject put = new JSONObject().put("owner_user_id", str).put("room_token", str2).put("joined_at", nowStr());
            if (str3 != null && str3.length() > 0) {
                put.put("owner_name", str3);
            }
            if (str4 != null && str4.length() > 0) {
                put.put("owner_icon", iconUrl(str4));
            }
            if (str5 != null && str5.length() > 0) {
                put.put("room_title", str5);
            }
            loadRoomHistoryArr.put(put);
            while (loadRoomHistoryArr.length() > 500) {
                loadRoomHistoryArr.remove(0);
            }
            this.prefs.edit().putString("room_history", loadRoomHistoryArr.toString()).apply();
        } catch (Exception e) {
        }
    }

    private long arrUid(JSONArray jSONArray, int i) {
        JSONObject optJSONObject = jSONArray.optJSONObject(i);
        return optJSONObject != null ? optJSONObject.optLong("userId", optJSONObject.optLong("user_id", optJSONObject.optLong("id"))) : jSONArray.optLong(i, 0);
    }

    private String authToken() {
        String v = this.prefs.getString("auth_token", (String) null);
        if (v == null) return null;
        if (SecureStore.isEncrypted(v)) {
            return secure().decrypt(v);
        }
        // 旧バージョンの平文トークンを初回読み取り時に暗号化して保存し直す
        if (SecureStore.supported()) {
            try { this.prefs.edit().putString("auth_token", secure().encrypt(v)).apply(); } catch (Exception ig) { }
        }
        return v;
    }

    private JSONObject awsJson(String str, String str2, JSONObject jSONObject) throws Exception {
        HttpURLConnection httpURLConnection = (HttpURLConnection) new URL(str).openConnection();
        httpURLConnection.setConnectTimeout(15000);
        httpURLConnection.setReadTimeout(30000);
        httpURLConnection.setRequestMethod("POST");
        httpURLConnection.setDoOutput(true);
        httpURLConnection.setRequestProperty("Content-Type", "application/x-amz-json-1.1");
        httpURLConnection.setRequestProperty("X-Amz-Target", str2);
        OutputStream outputStream = httpURLConnection.getOutputStream();
        outputStream.write(jSONObject.toString().getBytes("UTF-8"));
        outputStream.flush();
        outputStream.close();
        int responseCode = httpURLConnection.getResponseCode();
        String readBody = readBody(httpURLConnection, responseCode);
        httpURLConnection.disconnect();
        if (responseCode >= 200 && responseCode < 300) {
            return new JSONObject(readBody);
        }
        throw new Exception("Cognito失敗 HTTP " + responseCode + ": " + truncate(readBody, 200));
    }

    private String blockUser(String str) {
        HashMap hashMap = new HashMap();
        hashMap.put("target_id", str);
        hashMap.put("version", "android_" + APP_VERSION);
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        return okResult(request("POST", "/api/relation/block/regists", (Map<String, String>) null, hashMap));
    }

    private String buildProfile(long j, boolean z) {
        JSONObject jSONObject;
        JSONArray optJSONArray;
        JSONObject optJSONObject;
        String str = "";
        String str2 = "";
        // v3 を別スレッドで並行取得(v2と独立なのでレイテンシ短縮)
        final long jf = j;
        final Resp[] r2h = new Resp[1];
        Thread t2 = new Thread(new Runnable() {
            public void run() {
                try { r2h[0] = request("GET", "/api/v3/users/" + jf, q1("fields", "core,chat,friend,follow,block"), (Map<String, String>) null); }
                catch (Throwable th) { r2h[0] = null; }
            }
        });
        t2.start();
        Resp request = request("GET", "/api/v2/users", q1("ids", String.valueOf(j)), (Map<String, String>) null);
        String decoPath = "";
        if (!(request.body == null || (optJSONArray = request.body.optJSONArray("user_info")) == null || optJSONArray.length() <= 0 || (optJSONObject = optJSONArray.optJSONObject(0)) == null)) {
            str = optJSONObject.optString("name", "");
            str2 = optJSONObject.optString("profile_picture_file_path", "");
            rememberDecoration(j, optJSONObject);
            // 自分のプロフィールでは表示フラグに関係なく、設定されている画像パスをそのまま返す(設定画面の表示用)
            decoPath = optJSONObject.optString("timeline_image_file_path", "");
            if ("null".equals(decoPath)) decoPath = "";
        }
        try { t2.join(9000); } catch (Exception e) {}
        Resp request2 = r2h[0];
        if (request2 == null) {
            request2 = request("GET", "/api/v3/users/" + j, q1("fields", "core,chat,friend,follow,block"), (Map<String, String>) null);
        }
        if (request2.status != 200 || request2.body == null) {
            jSONObject = null;
        } else {
            JSONObject optJSONObject2 = request2.body.optJSONObject("data");
            JSONObject optJSONObject3 = optJSONObject2 != null ? optJSONObject2.optJSONObject("user_info") : null;
            if (optJSONObject3 == null && optJSONObject2 != null) {
                optJSONObject3 = optJSONObject2.optJSONObject("userInfo");
            }
            if (optJSONObject3 == null) {
                optJSONObject3 = request2.body.optJSONObject("user_info");
            }
            if (optJSONObject3 == null) {
                optJSONObject3 = request2.body.optJSONObject("userInfo");
            }
            if (optJSONObject3 != null) {
                optJSONObject2 = optJSONObject3;
            }
            jSONObject = optJSONObject2 == null ? request2.body : optJSONObject2;
        }
        if (jSONObject != null) {
            if (str.length() == 0) {
                str = jSONObject.optString("name", "");
            }
            if (str2.length() == 0) {
                str2 = jSONObject.optString("profile_picture_file_path", jSONObject.optString("profilePictureFilePath", ""));
            }
        }
        String str3 = "v2name=[" + str + "] v3HTTP=" + request2.status + " " + (request2.body != null ? truncate(request2.body.toString(), 350) : "(なし)");
        try {
            JSONObject jSONObject2 = new JSONObject();
            jSONObject2.put("user_id", j);
            jSONObject2.put("name", str);
            if (str.length() > 0) { this.nameCache.put(Long.valueOf(j), new String[]{str, str2}); saveNameCache(); } // プロフィールで得た名前は一覧/返信表示にも反映
            jSONObject2.put("comment", jSONObject != null ? jSONObject.optString("comment", "") : "");
            jSONObject2.put("icon_url", iconUrl(str2));
            jSONObject2.put("deco_url", decoPath.length() > 0 ? iconUrl(decoPath) : ""); // 投稿デコレーション(公開背景)
            String[] strArr = {"header_image_file_path", "headerImageFilePath", "header_image", "headerImage", "header_image_url", "headerImageUrl", "header_url", "cover_image", "cover_image_file_path", "coverImageFilePath", "header"};
            String str4 = "";
            JSONObject[] jSONObjectArr = {jSONObject, request2.body != null ? request2.body.optJSONObject("data") : null, request2.body};
            for (int i = 0; i < jSONObjectArr.length && str4.length() == 0; i++) {
                JSONObject jSONObject3 = jSONObjectArr[i];
                if (jSONObject3 != null) {
                    str4 = firstStr(jSONObject3, strArr);
                    if (str4.length() == 0) {
                        JSONObject optJSONObject4 = jSONObject3.optJSONObject("header_images");
                        if (optJSONObject4 == null) {
                            optJSONObject4 = jSONObject3.optJSONObject("header_image");
                        }
                        if (optJSONObject4 == null) {
                            optJSONObject4 = jSONObject3.optJSONObject("header");
                        }
                        if (optJSONObject4 != null) {
                            str4 = firstStr(optJSONObject4, "url", "file_path", "path", "original", "large", "medium");
                        }
                    }
                }
            }
            jSONObject2.put("header_url", str4.length() > 0 ? iconUrl(str4) : "");
            jSONObject2.put("follower_count", jSONObject != null ? jSONObject.optInt("follower_count", jSONObject.optInt("followerCount", 0)) : 0);
            jSONObject2.put("followee_count", jSONObject != null ? jSONObject.optInt("followee_count", jSONObject.optInt("followeeCount", 0)) : 0);
            if (jSONObject != null && !jSONObject.isNull("age")) {
                jSONObject2.put("age", jSONObject.opt("age"));
            }
            if (j == userId()) {
                // birthdayは本人限定の非公開フィールドでプロフィール取得系APIには含まれないため、
                // ログイン時/プロフィール保存成功時にキャッシュした値をここで返す(空の場合もある)。
                jSONObject2.put("birthday", birthday());
            }
            if (z) {
                jSONObject2.put("is_following", relFollowing(jSONObject));
                jSONObject2.put("is_followed", relFollowed(jSONObject));
                // 「友達」は声とも本体の関係で、フォローとは別。ボタンの出し分けに生のフラグを渡す。
                //   is_friend            … 友達が成立している
                //   is_friend_requestee  … 自分が相手に申請した(申請中)
                //   is_friend_requester  … 相手から申請が来ている(承認できる)
                jSONObject2.put("is_friend", jSONObject != null
                        && (jSONObject.optBoolean("is_friend", false) || jSONObject.optBoolean("isFriend", false)));
                jSONObject2.put("friend_requested", jSONObject != null
                        && (jSONObject.optBoolean("is_friend_requestee", false) || jSONObject.optBoolean("isFriendRequestee", false)));
                jSONObject2.put("friend_incoming", jSONObject != null
                        && (jSONObject.optBoolean("is_friend_requester", false) || jSONObject.optBoolean("isFriendRequester", false)));
                jSONObject2.put("friend_count", jSONObject != null ? jSONObject.optInt("friend_count", jSONObject.optInt("friendCount", 0)) : 0);
                jSONObject2.put("area_name", jSONObject != null ? jSONObject.optString("area_name", jSONObject.optString("areaName", "")) : "");
                jSONObject2.put("login_status", jSONObject != null ? jSONObject.optString("login_status_with_unit", jSONObject.optString("loginStatusWithUnit", "")) : "");
                int lst0 = loginStateOf(jSONObject);
                if (lst0 >= 0) jSONObject2.put("login_state", lst0);
                int optInt = jSONObject != null ? jSONObject.optInt("sex", jSONObject.optInt("gender", 0)) : 0;
                jSONObject2.put("gender", optInt == 1 ? "男性" : optInt == 2 ? "女性" : "");
                // アカウント開設日: user_info 直下 → 応答全体(深さ優先)の順で探す
                String ca = jSONObject != null ? firstStr(jSONObject, "created_at", "createdAt", "registered_at", "registeredAt", "created_time", "register_date", "joined_at", "created") : "";
                if (ca.length() == 0) {
                    // ユーザー本体を表す層の直下だけを見る(header_images 等の created_at を誤認しないため深追いしない)
                    JSONObject b2 = request2.body, d2 = b2 != null ? b2.optJSONObject("data") : null;
                    JSONObject[] cands = {d2, d2 != null ? d2.optJSONObject("user") : null, d2 != null ? d2.optJSONObject("user_info") : null, b2 != null ? b2.optJSONObject("user") : null, b2 != null ? b2.optJSONObject("user_info") : null, (request.body != null && request.body.optJSONArray("user_info") != null) ? request.body.optJSONArray("user_info").optJSONObject(0) : null};
                    for (JSONObject c : cands) {
                        if (c == null) continue;
                        ca = firstStr(c, "created_at", "createdAt", "registered_at", "registeredAt", "register_date", "joined_at", "created");
                        if (ca.length() > 0 && !"0".equals(ca)) break;
                        ca = "";
                    }
                }
                jSONObject2.put("created_at", ca);
                // API が返すユーザー情報をそのまま同梱(プロフィールの「詳細情報」で全項目表示)。auth_token 等の機微キーは除外
                if (jSONObject != null) {
                    JSONObject rawU = new JSONObject();
                    java.util.Iterator<String> it = jSONObject.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (k == null || k.indexOf("token") >= 0 || k.indexOf("password") >= 0 || k.equals("email")) continue;
                        rawU.put(k, jSONObject.opt(k));
                    }
                    jSONObject2.put("raw_user", rawU);
                }
                // v3 users が返す追加情報(公式アプリは一部しか表示していない)
                if (jSONObject != null) {
                    if (jSONObject.has("liked_count")) jSONObject2.put("liked_count", jSONObject.optLong("liked_count", 0));
                    if (jSONObject.has("suspend_flag")) jSONObject2.put("suspended", truthy(jSONObject.opt("suspend_flag")));
                    if (jSONObject.has("is_sms_authenticated")) jSONObject2.put("sms_verified", truthy(jSONObject.opt("is_sms_authenticated")));
                    if (jSONObject.has("age_verification_status")) jSONObject2.put("age_verification", jSONObject.opt("age_verification_status"));
                    if (jSONObject.has("active_follows")) jSONObject2.put("active_follows", jSONObject.opt("active_follows"));
                    if (jSONObject.has("passive_follows")) jSONObject2.put("passive_follows", jSONObject.opt("passive_follows"));
                    if (jSONObject.has("feature")) jSONObject2.put("feature", jSONObject.opt("feature"));
                }
                try { dbgLog(nowStr() + "  [PROFILE] uid=" + j + " created_at=" + (ca.length() > 0 ? ca : "(なし)") + " keys=" + (jSONObject != null ? topKeys(jSONObject) : "-")); } catch (Exception ignored) {} // 個人情報(誕生日等)をログに残さない: キー名のみ
            }
            return new JSONObject().put("ok", true).put("profile", jSONObject2).put("raw", str3).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String changePassword(String str, String str2, String str3) {
        try {
            HashMap hashMap = new HashMap();
            if (str == null) {
                str = "";
            }
            hashMap.put("current_password", str);
            if (str2 == null) {
                str2 = "";
            }
            hashMap.put("new_password", str2);
            if (str3 == null) {
                str3 = "";
            }
            hashMap.put("new_password_confirmation", str3);
            hashMap.put("version", "android_3.9.101");
            String authToken = authToken();
            if (authToken != null) {
                hashMap.put("auth_token", authToken);
            }
            Resp http = http("PUT", "https://api.meetscom.com/api/account/passwords", (Map<String, String>) null, hashMap);
            if (http.status == 404 || http.status >= 500) {
                http = http("PUT", "https://api2.meetscom.com/api/account/passwords", (Map<String, String>) null, hashMap);
            }
            if (http.status >= 200 && http.status < 300) {
                return new JSONObject().put("ok", true).toString();
            }
            return new JSONObject().put("ok", false).put("status", http.status).put("raw", http.body != null ? truncate(http.body.toString(), 300) : "").toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /**
     * 手を挙げる/降ろす・発言者にする(公式 OkHttpSingleton.switchTalkRoomRole 相当)。
     *   PUT https://api2.meetscom.com/api/rooms/{id}/change_role
     *   本文(form): role=speaker|speaker_applicant|listener, target_id={uid}
     *   認証はヘッダー(X-Auth-Token / X-App-Version)のみ。クエリには何も付けない。
     * role と target_id をクエリに載せていると本文が空になり、サーバーが HTTP 400 を返す。
     */
    private String changeRole(String str, String str2, String str3) {
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("role", str3);
        f.put("target_id", str2);
        String path = "/api/rooms/" + str + "/change_role";
        Resp r = http("PUT", BASE_URL2 + path, (Map<String, String>) null, (Map<String, String>) f);
        if (r.status == 404 || r.status <= 0 || r.status >= 500) {
            Resp r2 = http("PUT", BASE_URL + path, (Map<String, String>) null, (Map<String, String>) f);
            if (r2.status >= 200 && r2.status < 300) r = r2;
        }
        dbgLog(nowStr() + "  [ROLE] " + str3 + " room=" + str + " target=" + str2 + " HTTP " + r.status
                + " " + truncate(redactLog(r.body != null ? r.body.toString() : ""), 200));
        return okResultStatus(r);
    }

    private JSONObject cognitoCredentials(JSONObject jSONObject) throws Exception {
        String str = "https://cognito-identity." + jSONObject.getString("region") + ".amazonaws.com/";
        return awsJson(str, "AWSCognitoIdentityService.GetCredentialsForIdentity", new JSONObject().put("IdentityId", awsJson(str, "AWSCognitoIdentityService.GetId", new JSONObject().put("IdentityPoolId", jSONObject.getString("pool_id"))).getString("IdentityId"))).getJSONObject("Credentials");
    }

    private void collectUids(JSONArray jSONArray, JSONArray jSONArray2) throws Exception {
        if (jSONArray2 != null) {
            for (int i = 0; i < jSONArray2.length(); i++) {
                long arrUid = arrUid(jSONArray2, i);
                if (arrUid != 0) {
                    jSONArray.put(new JSONObject().put("user_id", arrUid));
                }
            }
        }
    }

    private static long commentUid(JSONObject jSONObject) {
        JSONObject optJSONObject = jSONObject.optJSONObject("user");
        long optLong = jSONObject.optLong("user_id", jSONObject.optLong("userId", 0));
        return (optLong != 0 || optJSONObject == null) ? optLong : optJSONObject.optLong("id", optJSONObject.optLong("user_id", 0));
    }

    private String communitiesResult(Resp resp) {
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            Object opt = resp.body.opt("data");
            JSONArray optJSONArray = opt instanceof JSONArray ? (JSONArray) opt : opt instanceof JSONObject ? ((JSONObject) opt).optJSONArray("communities") : null;
            JSONArray jSONArray = optJSONArray == null ? new JSONArray() : optJSONArray;
            JSONArray jSONArray2 = new JSONArray();
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject = jSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    jSONArray2.put(new JSONObject().put("id", optJSONObject.opt("id")).put("name", optJSONObject.optString("name", "")).put("description", optJSONObject.optString("description", "")).put("icon_url", iconUrl(optJSONObject.optString("image_file_path", ""))).put("participant_count", optJSONObject.optInt("participant_count", 0)));
                }
            }
            return new JSONObject().put("ok", true).put("communities", jSONArray2).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String createCommunity(String str, String str2, boolean z) {
        if (str == null || str.length() == 0) {
            return jsonErr("コミュニティ名を入力してください");
        }
        try {
            JSONObject put = new JSONObject().put("name", str);
            if (str2 == null) {
                str2 = "";
            }
            JSONObject put2 = put.put("description", str2).put("category_id", 0).put("is_open", z).put("image_file_path", "").put("voice_file_path", "").put("md5", "");
            // 公式 CommunityApi.createCommunity は @Body JSON + X-App-Version / X-Auth-Token ヘッダー。
            // (httpJson が両ヘッダーを付ける) URL に認証を並べるのは当方の書き方だった。
            Resp rc = httpJson("POST", BASE_URL + "/api/communities", put2);
            if (rc.status == 404 || rc.status >= 500) {
                rc = httpJson("POST", BASE_URL2 + "/api/communities", put2);
            }
            return okResult(rc);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String createCommunityComment(String str, String str2, String str3) {
        HashMap hashMap = new HashMap();
        hashMap.put("description", str3);
        hashMap.put("image_file_path", "");
        hashMap.put("voice_file_path", "");
        hashMap.put("md5", "");
        return okResult(request("POST", "/api/communities/" + str + "/posts/" + str2 + "/comments", q1("parent_id", ""), hashMap));
    }

    private String createCommunityPost(String str, String str2) {
        HashMap hashMap = new HashMap();
        hashMap.put("description", str2);
        hashMap.put("image_file_path", "");
        hashMap.put("voice_file_path", "");
        hashMap.put("md5", "");
        return okResult(request("POST", "/api/communities/" + str + "/posts", (Map<String, String>) null, hashMap));
    }

    // 自分がオーナーの枠を取得（公式 OkHttpSingleton: GET api2 /api/rooms?owner_user_id=<uid>）
    private JSONArray myOwnedRooms() {
        long uid = userId();
        if (uid <= 0) return null;
        JSONArray rooms = roomsByOwner(String.valueOf(uid), "owned");
        if (rooms != null && rooms.length() > 0) return rooms;
        // owner_user_id 検索で出ない場合に備え、公開一覧から自分がオーナーの枠を探す
        JSONArray found = new JSONArray();
        for (int page = 1; page <= 3; page++) {
            HashMap<String, String> pq = new HashMap<String, String>();
            pq.put("page", String.valueOf(page));
            pq.put("order", "1");
            Resp pr = httpApi2("GET", "/api/rooms", pq, (Map<String, String>) null);
            if (pr == null || pr.status != 200 || pr.body == null) break;
            JSONArray list = roomsFromBody(pr.body);
            if (list == null || list.length() == 0) break;
            for (int i = 0; i < list.length(); i++) {
                JSONObject ro = list.optJSONObject(i);
                if (ro == null) continue;
                long ow = ro.optLong("owner_user_id", ro.optLong("owner", 0));
                if (ow == uid) found.put(ro);
            }
        }
        dbgLog(nowStr() + "  [ROOM] owned rooms (一覧から検索) = " + found.length());
        return found.length() > 0 ? found : rooms;
    }

    // アプリ終了・タスク終了時に、自分が開いている枠を閉じる（ベストエフォート）
    public void closeMyRoomOnExit() {
        final String rid = myOpenRoomId;
        if (rid == null || rid.length() == 0) return;
        myOpenRoomId = null;
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    int st = closeRoomById(rid);
                    dbgLog(nowStr() + "  [ROOM] 終了時クローズ room=" + rid + " -> " + st);
                } catch (Exception e) {}
            }
        });
        th.setDaemon(false);
        th.start();
        try { th.join(2500); } catch (Exception e) {}
    }

    // 枠を閉じる（公式 TalkRoomApi.closeTalkRoom: DELETE api2 /api/rooms/{id}）
    private int closeRoomById(String roomId) {
        Resp r = httpApi2("DELETE", "/api/rooms/" + roomId, (Map<String, String>) null, (Map<String, String>) null);
        dbgLog(nowStr() + "  [ROOM] close " + roomId + " -> " + (r == null ? -1 : r.status));
        return r == null ? -1 : r.status;
    }

    // 枠作成が「作成超過 / 作成重複(既に作成済み)」で弾かれたかどうか。サーバーの文言ゆれを吸収する。
    private static boolean isRoomExistsError(String body) {
        if (body == null) return false;
        return body.contains("超過") || body.contains("最大値") || body.contains("これ以上ルーム")
                || body.contains("重複") || body.contains("既にルーム") || body.contains("既に作成")
                || body.contains("すでにルーム") || body.contains("すでに作成");
    }

    // 「ルーム作成超過」時に、閉じ忘れて残っている自分の枠を閉じる。開催中(参加者がいる)枠は閉じない。
    private int closeMyStaleRooms() {
        JSONArray rooms = myOwnedRooms();
        if (rooms == null || rooms.length() == 0) {
            dbgLog(nowStr() + "  [ROOM] stale check: 自分の枠は見つからず");
            return 0;
        }
        long uid = userId();
        int closed = 0;
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject ro = rooms.optJSONObject(i);
            if (ro == null) continue;
            long owner = ro.optLong("owner_user_id", ro.optLong("owner", 0));
            if (owner != 0 && owner != uid) continue;
            if (ro.optString("closed_at", "").length() > 0 && !"null".equals(ro.optString("closed_at"))) continue;
            long rid = ro.optLong("room_id", ro.optLong("id", 0));
            if (rid <= 0) continue;
            JSONArray sp = ro.optJSONArray("speakers");
            JSONArray ls = ro.optJSONArray("listeners");
            int others = 0;
            for (int k = 0; sp != null && k < sp.length(); k++) {
                JSONObject u = sp.optJSONObject(k);
                long su = u == null ? sp.optLong(k, 0) : u.optLong("user_id", 0);
                if (su != 0 && su != uid) others++;
            }
            for (int k = 0; ls != null && k < ls.length(); k++) {
                JSONObject u = ls.optJSONObject(k);
                long su = u == null ? ls.optLong(k, 0) : u.optLong("user_id", 0);
                if (su != 0 && su != uid) others++;
            }
            if (others > 0) {
                dbgLog(nowStr() + "  [ROOM] stale skip room=" + rid + " (他に" + others + "人)");
                continue;
            }
            int st = closeRoomById(String.valueOf(rid));
            if (st >= 200 && st < 300) closed++;
        }
        dbgLog(nowStr() + "  [ROOM] stale closed=" + closed + " / owned=" + rooms.length());
        return closed;
    }

    private String createRoom(String str, boolean z, boolean z2) { return createRoom(str, z, z2, 1); }

    private String createRoom(String str, boolean z, boolean z2, int connType) {
        // 公式 OkHttpSingleton.postTalkRoom:
        //   POST api2 /api/rooms  FORM: description のみ
        //   認証は X-Auth-Token / X-App-Version ヘッダー（auth_token/version は body に入れない）
        // 旧サーバー(api.meetscom.com)に投げると 400 で拒否されるため api2 を優先する。
        // 公式 TalkRoomApi.createTalkRoom: FORM description / is_public / connection_type
        HashMap<String, String> form = new HashMap<String, String>();
        form.put("description", str == null ? "" : str);
        form.put("is_public", z ? "1" : "0");
        form.put("connection_type", (connType == 2) ? "2" : "1");
        Resp r = httpApi2("POST", "/api/rooms", (Map<String, String>) null, form);
        dbgLog(nowStr() + "  [ROOM] create HTTP " + r.status + (r.body != null ? " " + truncate(redactLog(r.body.toString()), 200) : ""));
        // 「ルーム作成超過」= 前回の枠が閉じられずに残っている。誰もいない自分の枠を閉じて1回だけ再試行する。
        if (r.status == 400 && r.body != null) {
            String bodyText = r.body.toString();
            if (isRoomExistsError(bodyText)) {
                int closed = closeMyStaleRooms();
                // 一覧に出てこない自分の枠(非公開など)に備えて、直近に自分が開いた枠IDでも閉じてみる
                if (closed == 0 && myOpenRoomId != null && myOpenRoomId.length() > 0) {
                    int st = closeRoomById(myOpenRoomId);
                    dbgLog(nowStr() + "  [ROOM] 直近の自分の枠を閉じる room=" + myOpenRoomId + " HTTP " + st);
                    if (st >= 200 && st < 300) { closed++; myOpenRoomId = null; }
                }
                if (closed > 0) {
                    r = httpApi2("POST", "/api/rooms", (Map<String, String>) null, form);
                    dbgLog(nowStr() + "  [ROOM] create retry HTTP " + r.status + (r.body != null ? " " + truncate(redactLog(r.body.toString()), 200) : ""));
                }
            }
        }
        try {
            if (r.status >= 200 && r.status < 300) {
                return joinCall("null");
            }
            String serverMsg = null;
            if (r.body != null) {
                serverMsg = r.body.optString("displayable_detail", null);
                if (serverMsg == null || serverMsg.length() == 0) serverMsg = r.body.optString("detail", null);
                if (serverMsg == null || serverMsg.length() == 0) serverMsg = r.body.optString("message", null);
                if (serverMsg == null || serverMsg.length() == 0) serverMsg = r.body.optString("error", null);
            }
            long existingRoomId = 0;
            if (r.status == 400 && r.body != null && isRoomExistsError(r.body.toString())) {
                JSONArray owned = myOwnedRooms();
                for (int i = 0; owned != null && i < owned.length(); i++) {
                    JSONObject ro = owned.optJSONObject(i);
                    if (ro == null) continue;
                    long rid = ro.optLong("room_id", ro.optLong("id", 0));
                    if (rid > 0) { existingRoomId = rid; break; }
                }
            }
            String msg;
            if (existingRoomId > 0) {
                msg = "すでに開いている自分の枠があります。そちらに戻るか、枠を閉じてから作成してください。";
            } else if (r.status == 400 && r.body != null && isRoomExistsError(r.body.toString())) {
                msg = "サーバー側にあなたの枠が残っていますが、その枠を見つけられませんでした（他の参加者がいる可能性があります）。通話タブの一覧から自分の枠に入って「枠を終了」してから、もう一度お試しください。";
            } else if (r.status == 400 && r.body != null && isRoomExistsError(r.body.toString())) {
                msg = "いま枠を作成できません（サーバー側の作成数上限）。少し時間を置いてからもう一度お試しください。何度も続く場合は管理者に報告してください。";
            } else if (r.status == 401 || (r.body != null && r.sessionExpired)) {
                msg = "ログインの有効期限が切れています。ログインし直してください。";
            } else if (r.status == 400 || r.status == 403) {
                msg = (serverMsg != null && serverMsg.length() > 0)
                        ? ("枠を作成できませんでした：" + serverMsg)
                        : "枠の作成がサーバーに拒否されました(status " + r.status + ")。すでに枠を開いている、または年齢確認/SMS認証が未完了の場合に起きます。";
            } else if (r.status == 404) {
                msg = "枠作成APIが見つかりませんでした(404)。";
            } else if (r.status >= 500) {
                msg = "サーバーエラーで枠を作成できませんでした(status " + r.status + ")。時間を置いて再試行してください。";
            } else if (r.status <= 0) {
                msg = "通信できませんでした。電波状況を確認してもう一度お試しください。";
            } else {
                msg = "枠を作成できませんでした(status " + r.status + ")。";
            }
            JSONObject out = new JSONObject().put("ok", false).put("status", r.status).put("message", msg)
                    .put("raw", r.body != null ? truncate(r.body.toString(), 300) : "");
            if (existingRoomId > 0) out.put("existing_room_id", existingRoomId);
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 投稿の共通POST。
    //   /api/timeline_posts = 「話そう」= 通話募集 → purpose/topic を付与
    //   /api/feed_posts     = 「つぶやく」= 通常のタイムライン → play_time を付ける(purpose/topicなし)
    // ※公式はエンドポイント名と意味が逆。api→api2 フォールバック。
    private Resp postToSeries(String endpoint, String description, String purpose, String imagePath, String voicePath, String md5) {
        return postToSeries(endpoint, description, purpose, imagePath, voicePath, md5, "0");
    }

    private Resp postToSeries(String endpoint, String description, String purpose, String imagePath, String voicePath, String md5, String playTime) {
        HashMap<String, String> hashMap = new HashMap<String, String>();
        hashMap.put("version", "android_" + APP_VERSION);
        boolean isFeed = endpoint.contains("feed_posts");
        if (!isFeed) {
            hashMap.put("purpose", (purpose == null || purpose.length() == 0) ? "0" : purpose);
            hashMap.put("topic", "0");
        } else {
            // 公式 generateFeedPostRequest は録音の長さ(秒)をそのまま送る
            hashMap.put("play_time", (playTime == null || playTime.length() == 0) ? "0" : playTime);
        }
        if (description != null && description.length() > 0) hashMap.put("description", description);
        if (imagePath != null && imagePath.length() > 0) hashMap.put("image_file_path", imagePath);
        if (voicePath != null && voicePath.length() > 0) hashMap.put("voice_file_path", voicePath);
        // 公式は md5 を「画像を付けたときだけ」送る(MD5Manager.add は image_file_path の直後のみ)。
        // 音声にも付けていたので、サーバー側の検証と食い違っていた。
        if (imagePath != null && imagePath.length() > 0 && md5 != null && md5.length() > 0) hashMap.put("md5", md5);
        String authToken = authToken();
        if (authToken != null) hashMap.put("auth_token", authToken);
        // 同じ内容の投稿が短時間に二度飛ぶのを防ぐ(タップの二重発火・再送による二重投稿対策)
        String postSig = endpoint + "\u0000" + (description == null ? "" : description) + "\u0000"
                + (imagePath == null ? "" : imagePath) + "\u0000" + (voicePath == null ? "" : voicePath);
        long nowMs = System.currentTimeMillis();
        synchronized (KoeSession.class) {
            if (postSig.equals(lastPostSig) && nowMs - lastPostAt < 15000) {
                dbgLog(nowStr() + "  [POST] 同一内容の連続投稿を抑止 " + endpoint);
                return new Resp(200, (JSONObject) null);
            }
        }
        // 公式(OkHttpSingleton.generateFeedPostRequest / generateTimelinePostRequest)と同じく server1 のみ。
        Resp http = http("POST", BASE_URL + endpoint, (Map<String, String>) null, hashMap);
        // 応答が無かった(タイムアウト)ときは、サーバー側では投稿が作られていることが多い。
        // 再送すると二重投稿になるので、自分の最新投稿を確認して同じ内容があれば成功扱いにする。
        if (http.status <= 0) {
            JSONObject found = findJustPostedPost(endpoint, description, imagePath, voicePath);
            if (found != null) {
                dbgLog(nowStr() + "  [POST] 応答なしだったが投稿は作成済み id=" + found.optString("id"));
                http = new Resp(200, found);
            }
        }
        // 二重投稿の抑止は「成功した投稿」に対してだけ効かせる。失敗(403 など)を覚えてしまうと、
        // 直後の再試行が偽の 200 を返して「投稿できた」ように見えてしまう。
        if (http.status >= 200 && http.status < 300) {
            synchronized (KoeSession.class) {
                lastPostSig = postSig;
                lastPostAt = System.currentTimeMillis();
            }
        }
        dbgLog(nowStr() + "  [POST] " + endpoint + " HTTP " + http.status + (http.status != 200 && http.status != 201 && http.body != null ? " " + truncate(redactLog(http.body.toString()), 200) : ""));
        return http;
    }

    /**
     * 直近 3 分以内に作られた自分の投稿から、いま送った内容と同じものを探す(応答なし時の確認用)。
     * 見つからなければ null。
     */
    private JSONObject findJustPostedPost(String endpoint, String description, String imagePath, String voicePath) {
        try {
            String myId = String.valueOf(userId());
            if (myId.equals("0")) return null;
            HashMap<String, String> q = new HashMap<String, String>();
            q.put("target_id", myId);
            q.put("count", "5");
            q.put("version", "android_" + APP_VERSION);
            Resp r = http("GET", BASE_URL2 + endpoint, q, (Map<String, String>) null);
            if (r.status != 200 || r.body == null) return null;
            JSONArray arr = firstArray(r.body, "feed_posts", "timeline_posts", "posts");
            if (arr == null) return null;
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.optJSONObject(i);
                if (p == null) continue;
                long created = parseIsoMillis(p.optString("created_at", ""));
                if (created > 0 && now - created > 180000) continue;
                boolean sameText = eqOrEmpty(description, p.optString("description", ""));
                boolean sameImage = (imagePath == null || imagePath.length() == 0) || p.optString("image_file_path", "").contains(imagePath);
                boolean sameVoice = (voicePath == null || voicePath.length() == 0) || p.optString("voice_file_path", "").contains(voicePath);
                if (sameText && sameImage && sameVoice) return p;
            }
        } catch (Exception e) {
        }
        return null;
    }

    private static boolean eqOrEmpty(String a, String b) {
        return (a == null ? "" : a.trim()).equals(b == null ? "" : b.trim());
    }

    private static long parseIsoMillis(String iso) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US);
            f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(iso.replace("Z", "").replaceAll("\\.\\d+$", "")).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private String createPost(String endpoint, String str, String str2) {
        return okResult(postToSeries(endpoint, str, str2, null, null, null));
    }

    private String createPostWithImage(String endpoint, String str, String str2, String str3) {
        if (str3 != null) {
            try {
                if (str3.length() != 0) {
                    int indexOf = str3.indexOf(44);
                    if (str3.startsWith("data:") && indexOf >= 0) {
                        str3 = str3.substring(indexOf + 1);
                    }
                    byte[] decode = Base64.decode(str3, 0);
                    // 送られてきたのが既にJPEG(WebView側で長辺1280・品質88に縮小済み)なら、
                    // PNGへ再エンコードせずそのまま上げる。PNG化すると数MBに膨らんで投稿が遅くなる。
                    boolean srcIsJpeg = decode.length > 3 && (decode[0] & 255) == 0xFF && (decode[1] & 255) == 0xD8 && (decode[2] & 255) == 0xFF;
                    if (srcIsJpeg) {
                        String jpgName = UUID.randomUUID().toString().replace("-", "") + ".jpg";
                        String jpgKey = jpgName;
                        JSONObject cfgJ = imageS3Config();
                        String pathJ = cfgJ.optString("path", "");
                        if (pathJ != null && pathJ.length() > 0) {
                            jpgKey = pathJ.replaceAll("^/+", "").replaceAll("/+$", "") + "/" + jpgName;
                        }
                        JSONObject credJ = cognitoCredentials(cfgJ);
                        String errJ = s3PutBytes(cfgJ, credJ, decode, jpgKey, "image/jpeg");
                        if (errJ == null) {
                            dbgLog(nowStr() + "  [IMGPOST] JPEGのまま送信 " + decode.length + "B key=" + jpgKey);
                            Resp rj = postToSeries(endpoint, str, str2, jpgName, null, md5Hex(decode));
                            if (rj.status >= 200 && rj.status < 300) return okResult(rj);
                            dbgLog(nowStr() + "  [IMGPOST] JPEG投稿がHTTP " + rj.status + " のためPNGで再試行");
                        } else {
                            dbgLog(nowStr() + "  [IMGPOST] JPEGアップロード失敗のためPNGで再試行: " + truncate(errJ, 120));
                        }
                    }
                    Bitmap decodeByteArray = BitmapFactory.decodeByteArray(decode, 0, decode.length);
                    if (decodeByteArray == null) {
                        dbgLog(nowStr() + "  [IMGPOST] デコード失敗 bytes=" + decode.length);
                        return jsonErr("画像を読み込めませんでした");
                    }
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    decodeByteArray.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    dbgLog(nowStr() + "  [IMGPOST] png=" + byteArray.length + "B S3設定取得中…");
                    JSONObject imageS3Config = imageS3Config();
                    dbgLog(nowStr() + "  [IMGPOST] S3設定 keys=" + truncate(redactLog(imageS3Config.toString()), 160));
                    JSONObject cognitoCredentials = cognitoCredentials(imageS3Config);
                    boolean credOk = cognitoCredentials != null && (cognitoCredentials.has("AccessKeyId") || cognitoCredentials.has("accessKeyId") || cognitoCredentials.length() > 0);
                    dbgLog(nowStr() + "  [IMGPOST] cognito=" + (credOk ? "取得OK" : "取得失敗") );
                    // S3のキーは images/<uuid>.png（フォルダ付き）だが、
                    // サーバへ送る image_file_path は「ファイル名だけ」(<uuid>.png)。
                    // 公式は uploadFile が uuidファイル名のみを返し、それを image_file_path に入れる。
                    // フォルダ付きで送るとサーバが更に images/ を前置し images/images/... となって画像が外れる。
                    String bareName = UUID.randomUUID().toString().replace("-", "") + ".png";
                    String uploadKey = bareName;
                    String optString = imageS3Config.optString("path", "");
                    if (optString != null && optString.length() > 0) {
                        uploadKey = optString.replaceAll("^/+", "").replaceAll("/+$", "") + "/" + bareName;
                    }
                    String s3PutPng = s3PutPng(imageS3Config, cognitoCredentials, byteArray, uploadKey);
                    if (s3PutPng != null) {
                        dbgLog(nowStr() + "  [IMGPOST] S3アップロード失敗: " + truncate(s3PutPng, 200));
                        return jsonErr("画像アップロード失敗: " + s3PutPng);
                    }
                    dbgLog(nowStr() + "  [IMGPOST] S3アップロードOK key=" + uploadKey + " 送信path=" + bareName + " → 投稿POST");
                    return okResult(postToSeries(endpoint, str, str2, bareName, null, md5Hex(byteArray)));
                }
            } catch (Exception e) {
                return errJson(e);
            }
        }
        return jsonErr("画像がありません");
    }

    private String createPostWithVoice(String endpoint, String str, String str2, String str3, String str4) {
        return createPostWithVoice(endpoint, str, str2, str3, str4, "0", "0");
    }

    private String createPostWithVoice(String endpoint, String str, String str2, String str3, String str4, String purpose) {
        return createPostWithVoice(endpoint, str, str2, str3, str4, purpose, "0");
    }

    private String createPostWithVoice(String endpoint, String str, String str2, String str3, String str4, String purpose, String playTime) {
        if (str != null) {
            try {
                if (str.length() != 0) {
                    int indexOf = str.indexOf(44);
                    if (str.startsWith("data:") && indexOf >= 0) {
                        str = str.substring(indexOf + 1);
                    }
                    byte[] decode = Base64.decode(str, 0);
                    if (decode.length == 0) {
                        return jsonErr("音声データが空です");
                    }
                    if (str2 == null || str2.length() == 0) {
                        str2 = "webm";
                    }
                    String str5 = (str3 == null || str3.length() == 0) ? "audio/webm" : str3;
                    JSONObject imageS3Config = imageS3Config();
                    JSONObject cognitoCredentials = cognitoCredentials(imageS3Config);
                    String bareVoice = UUID.randomUUID().toString().replace("-", "") + "." + str2;
                    String voiceKey = bareVoice;
                    String optString = imageS3Config.optString("path", "");
                    if (optString != null && optString.length() > 0) {
                        voiceKey = optString.replaceAll("^/+", "").replaceAll("/+$", "") + "/" + bareVoice;
                    }
                    String s3PutBytes = s3PutBytes(imageS3Config, cognitoCredentials, decode, voiceKey, str5);
                    if (s3PutBytes != null) {
                        return jsonErr(s3PutBytes);
                    }
                    // voice_file_path もファイル名だけ送る（画像と同じ理由）
                    return okResult(postToSeries(endpoint, str4, (purpose == null || purpose.length() == 0) ? "0" : purpose, null, bareVoice, null, playTime));
                }
            } catch (Exception e) {
                return errJson(e);
            }
        }
        return jsonErr("音声がありません");
    }

    private static long deepFindLong(Object obj, String str) {
        if (obj instanceof JSONObject) {
            JSONObject jSONObject = (JSONObject) obj;
            if (jSONObject.has(str)) {
                long optLong = jSONObject.optLong(str, 0);
                if (optLong != 0) {
                    return optLong;
                }
            }
            Iterator<String> keys = jSONObject.keys();
            while (keys.hasNext()) {
                long deepFindLong = deepFindLong(jSONObject.opt(keys.next()), str);
                if (deepFindLong != 0) {
                    return deepFindLong;
                }
            }
        } else if (obj instanceof JSONArray) {
            JSONArray jSONArray = (JSONArray) obj;
            for (int i = 0; i < jSONArray.length(); i++) {
                long deepFindLong2 = deepFindLong(jSONArray.opt(i), str);
                if (deepFindLong2 != 0) {
                    return deepFindLong2;
                }
            }
        }
        return 0;
    }

    // 公式 TimelineApiServer1: DELETE api/feed_posts/{feed_post_id}/comments/{comment_id}?version=android_…&auth_token=… (server1)
    private String deleteFeedPostComment(String postId, String commentId) {
        String[] hosts = new String[]{BASE_URL, BASE_URL2};
        Resp last = new Resp(0, (JSONObject) null);
        for (String host : hosts) {
            HashMap<String, String> q = new HashMap<String, String>();
            q.put("version", "android_" + APP_VERSION);
            String at = authToken();
            if (at != null) q.put("auth_token", at);
            Resp r = http("DELETE", host + "/api/feed_posts/" + postId + "/comments/" + commentId, q, (Map<String, String>) null);
            dbgLog(nowStr() + "  [COMMENT-DEL] " + host + " post=" + postId + " comment=" + commentId + " -> " + r.status);
            if (r.status >= 200 && r.status < 300) return okResultStatus(r);
            last = r;
        }
        return okResultStatus(last);
    }

    private String deleteOwnPost(String str) {
        return deleteOwnPost(str, false);
    }

    // 公式(TimelineApiServer1.deleteFeedPost / deleteTimelinePost)は server1(api.meetscom.com) に
    // DELETE、version=android_3.9.101 で送る。api2 は 403「権限がありません」を返すので server1 のみ。
    // 種別(つぶやき/話そう)が分からないことがあるため両パスを順に試す。
    private String deleteOwnPost(String str, boolean isTalk) {
        String[] paths = isTalk ? new String[]{"/api/timeline_posts/", "/api/feed_posts/"} : new String[]{"/api/feed_posts/", "/api/timeline_posts/"};
        Resp last = new Resp(0, (JSONObject) null);
        for (String path : paths) {
            HashMap<String, String> q = new HashMap<>();
            q.put("version", "android_" + APP_VERSION);
            String authToken = authToken();
            if (authToken != null) q.put("auth_token", authToken);
            Resp r = http("DELETE", BASE_URL + path + str, q, (Map<String, String>) null);
            dbgLog(nowStr() + "  [POSTDEL] " + path + str + " -> " + r.status);
            if (r.status >= 200 && r.status < 300) {
                try { return new JSONObject().put("ok", true).put("kind", path.contains("timeline") ? "talk" : "feed").toString(); } catch (Exception e) { return "{\"ok\":true}"; }
            }
            last = r;
        }
        try {
            String raw = last.body != null ? truncate(last.body.toString(), 300) : "";
            return new JSONObject().put("ok", false).put("status", last.status).put("raw", raw).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 公式アプリの HeaderInterceptor と同じ: ハイフンを除いた UUID を全リクエストに付ける。
    // これが無いとサーバーが 503 "リクエストエラー" で弾く。
    private String newRequestId() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }

    // 公式アプリは起動時に config/release/<ver>.json を読み、現在のAPIサーバードメイン
    // (api_server_domain)を使ってログイン等を送る。ハードコードの api.meetscom.com が
    // 古いホストで、ログインだけ503になっている可能性があるため、設定から実ホストを解決する。
    private String cachedLoginBase = null;
    private String resolveLoginBase() {
        if (cachedLoginBase != null) return cachedLoginBase;
        String base = "https://api.meetscom.com";
        try {
            String saved = this.prefs.getString("login_base", null);
            if (saved != null && saved.length() > 0) { cachedLoginBase = saved; return saved; }
            String[] r = httpText("https://api.meetscom.com/config/release/3.9.101.json");
            if (r != null && r.length > 1 && "200".equals(r[0]) && r[1] != null) {
                String dom = extractApiDomain(r[1]);
                if (dom != null && dom.length() > 0) {
                    base = dom.startsWith("http") ? dom : ("https://" + dom);
                    if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                }
            }
        } catch (Exception e) {
        }
        dbgLog(nowStr() + "  [LOGINHOST] resolved=" + base);
        cachedLoginBase = base;
        try { this.prefs.edit().putString("login_base", base).apply(); } catch (Exception e) {}
        return base;
    }

    // JSON文字列から api_server_domain / apiServerDomain 等のドメイン値を抜き出す。
    private String extractApiDomain(String json) {
        try {
            String[] pats = {"api_server_domain", "apiServerDomain", "api_host", "apiHost"};
            for (String key : pats) {
                int i = json.indexOf("\"" + key + "\"");
                while (i >= 0) {
                    int colon = json.indexOf(':', i + key.length() + 2);
                    if (colon < 0) break;
                    int q1 = json.indexOf('"', colon + 1);
                    if (q1 < 0) break;
                    int q2 = json.indexOf('"', q1 + 1);
                    if (q2 < 0) break;
                    String val = json.substring(q1 + 1, q2).trim();
                    // "2" 付き(api_server_domain2)や空値はスキップして次を探す
                    if (val.length() > 0 && val.contains(".") && !val.contains(" ")) {
                        return val;
                    }
                    i = json.indexOf("\"" + key + "\"", q2);
                }
            }
        } catch (Exception e) {
        }
        return null;
    }

    // ログイン系POST。稀に503(ゲートウェイ一時エラー)が返るため、503時のみ短い待機後に1回だけ再試行する。
    private Resp httpLoginPost(String url, Map<String, String> fields) {
        // 設定から解決した実ホストに向ける(古い api.meetscom.com が503を返す場合の対策)。
        try {
            String base = resolveLoginBase();
            if (base != null && base.length() > 0 && url.startsWith("https://api.meetscom.com")) {
                url = base + url.substring("https://api.meetscom.com".length());
            }
        } catch (Exception e) {
        }
        // 503 "リクエストエラー" は Rails の Rack::Attack(ログイン試行のIP単位レート制限)。
        // リトライするとカウントが増えて制限が延びるだけなので、1回だけ送る。
        return http("POST", url, (Map<String, String>) null, fields, false);
    }

    private String deviceUid() {
        // 公式アプリは Settings.Secure ANDROID_ID を device_uid に使う。
        // ログインの不正検知が独自形式(ランダムUUID)を弾くため、公式と同じ ANDROID_ID を優先する。
        try {
            if (this.appContext != null) {
                String androidId = android.provider.Settings.Secure.getString(
                        this.appContext.getContentResolver(), "android_id");
                if (androidId != null && androidId.length() > 0) {
                    return androidId;
                }
            }
        } catch (Exception e) {
        }
        String string = this.prefs.getString("device_uid", (String) null);
        if (string != null) {
            return string;
        }
        String uuid = UUID.randomUUID().toString();
        this.prefs.edit().putString("device_uid", uuid).apply();
        return uuid;
    }

    private String doTipping(String str, String str2, String str3) {
        try {
            // 公式 OkHttpSingleton.generateTippingRequest:
            //   POST api2 /api/tippings  form: target_id(相手), item_pack_id, referer_id(画面の種類), room_id(枠内のみ)
            // 以前は相手のIDを referer_id に入れていて、送り先が伝わっていなかった。
            HashMap hashMap = new HashMap();
            hashMap.put("target_id", str2);
            hashMap.put("item_pack_id", str);
            boolean inRoom = (str3 != null && str3.length() > 0 && !"0".equals(str3));
            hashMap.put("referer_id", inRoom ? "3" : "1"); // TippingScreen: 1=Profile, 3=GroupTalking
            if (inRoom) hashMap.put("room_id", str3);
            Resp request = httpApi2("POST", "/api/tippings", (Map<String, String>) null, hashMap);
            dbgLog(nowStr() + "  [TIP] pack=" + str + " to=" + str2 + " HTTP " + request.status
                    + (request.status >= 200 && request.status < 300 ? "" : " " + truncate(redactLog(request.body != null ? request.body.toString() : ""), 200)));
            if (request.status >= 200 && request.status < 300) {
                return new JSONObject().put("ok", true).toString();
            }
            return new JSONObject().put("ok", false).put("status", request.status).put("body", truncate(request.body != null ? request.body.toString() : "", 200)).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private static boolean emptyAllowed(String str) {
        return str.equals("max_id") || str.equals("max_created_at") || str.equals("max_last_posted_at") || str.equals("keyword") || str.equals("fields") || str.equals("purchase_token");
    }

    private static String enc(String str) {
        try {
            return URLEncoder.encode(str == null ? "" : str, "UTF-8");
        } catch (Exception e) {
            return str == null ? "" : str;
        }
    }

    private synchronized String ensureSkywayHost() {
        String str;
        JSONObject jSONObject = null;
        synchronized (this) {
            if (this.skywayHost != null) {
                str = this.skywayHost;
            } else {
                try {
                    // 公開設定(client_defines)は数時間は変わらないので端末に保存し、セッション(Activity/Service)ごとに
                    // release.json + client_defines.json を取り直さない(起動・バックグラウンド確認の通信を削減)
                    JSONObject optJSONObject3 = null;
                    try {
                        long ts = this.prefs.getLong("client_defines_ts", 0);
                        String cached = this.prefs.getString("client_defines_json", null);
                        if (cached != null && cached.length() > 2 && System.currentTimeMillis() - ts < 6L * 3600L * 1000L) {
                            optJSONObject3 = new JSONObject(cached);
                        }
                    } catch (Exception ig) { optJSONObject3 = null; }
                    if (optJSONObject3 == null) {
                    Resp http = http("GET", "https://api.meetscom.com/config/release/3.9.101.json", (Map<String, String>) null, (Map<String, String>) null);
                    JSONObject optJSONObject = http.body != null ? http.body.optJSONObject("data") : null;
                    JSONObject optJSONObject2 = optJSONObject != null ? optJSONObject.optJSONObject("client_defines") : null;
                    String optString = optJSONObject2 != null ? optJSONObject2.optString("url", "") : "";
                    if (optString.length() > 0) {
                        Resp http2 = http("GET", optString, (Map<String, String>) null, (Map<String, String>) null);
                        optJSONObject3 = http2.body != null ? http2.body.optJSONObject("data") : null;
                        if (optJSONObject3 == null) {
                            optJSONObject3 = http2.body;
                        }
                        if (optJSONObject3 != null) {
                            try { this.prefs.edit().putString("client_defines_json", optJSONObject3.toString()).putLong("client_defines_ts", System.currentTimeMillis()).apply(); } catch (Exception ig) {}
                        }
                    }
                    }
                    if (optJSONObject3 != null) {
                        this.clientDefines = optJSONObject3;
                        if (optJSONObject3 != null) {
                            JSONObject optJSONObject4 = optJSONObject3.optJSONObject("client_system_params");
                            if (optJSONObject4 != null) {
                                jSONObject = optJSONObject4.optJSONObject("skyway");
                            }
                            String optString2 = jSONObject != null ? jSONObject.optString("auth_token_endpoint_server", "") : "";
                            if (optString2.length() > 0) {
                                if (!optString2.startsWith("http")) {
                                    optString2 = "https://" + optString2;
                                }
                                this.skywayHost = optString2;
                            }
                        }
                    }
                } catch (Exception e) {
                }
                if (this.skywayHost == null) {
                    this.skywayHost = "https://skyway-auth.meetscom.com";
                }
                str = this.skywayHost;
            }
        }
        return str;
    }

    // Pusher（公式のチャット即時受信に使われている）の接続情報を config から取り出す
    // JSON のどの階層にあっても "pusher": {"key":..., "cluster":...} を見つける
    private static JSONObject findPusher(Object node, int depth) {
        if (node == null || depth > 6) return null;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            JSONObject p = o.optJSONObject("pusher");
            if (p != null && p.optString("key", "").length() > 0) return p;
            java.util.Iterator<String> ks = o.keys();
            while (ks.hasNext()) {
                JSONObject f = findPusher(o.opt(ks.next()), depth + 1);
                if (f != null) return f;
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length() && i < 50; i++) {
                JSONObject f = findPusher(a.opt(i), depth + 1);
                if (f != null) return f;
            }
        }
        return null;
    }

    private String getPusherConfig() {
        try {
            // 一度取れたら端末内に控える(毎回の設定取得をなくす)
            String ck = this.prefs.getString("pusher_cfg", null);
            if (ck != null && ck.length() > 0) {
                try {
                    JSONObject c = new JSONObject(ck);
                    if (c.optString("key", "").length() > 0) return c.put("ok", true).toString();
                } catch (Exception ig) {}
            }
            // 公式(AppConfigDataStore)は POST /api/master/system_params の data.system_params.pusher を見ている
            HashMap<String, String> f = new HashMap<String, String>();
            f.put("version", "android_" + APP_VERSION);
            Resp sp = request("POST", "/api/master/system_params", (Map<String, String>) null, f);
            dbgLog(nowStr() + "  [PUSHER] system_params HTTP " + sp.status);
            JSONObject pusher = findPusher(sp != null ? sp.body : null, 0);
            if (pusher == null) {
                ensureSkywayHost(); // clientDefines を読み込む
                pusher = findPusher(this.clientDefines, 0);
            }
            String key = pusher != null ? pusher.optString("key", "") : "";
            String cluster = pusher != null ? pusher.optString("cluster", "") : "";
            dbgLog(nowStr() + "  [PUSHER] key=" + (key.length() > 0 ? "あり" : "なし") + " cluster=" + cluster);
            JSONObject out = new JSONObject().put("ok", key.length() > 0).put("key", key).put("cluster", cluster);
            if (key.length() > 0) {
                try { this.prefs.edit().putString("pusher_cfg", new JSONObject().put("key", key).put("cluster", cluster).toString()).apply(); } catch (Exception ig) {}
            }
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private static String errJson(Exception exc) {
        try {
            return new JSONObject().put("ok", false).put("error", exc.getMessage() == null ? "error" : exc.getMessage()).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"error\"}";
        }
    }

    private String exportToken() {
        try {
            String authToken = authToken();
            JSONObject put = new JSONObject().put("ok", authToken != null);
            if (authToken == null) {
                authToken = "";
            }
            return put.put("token", authToken).put("user_id", userId()).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String extractError(JSONObject jSONObject) {
        JSONObject optJSONObject;
        if (jSONObject == null) {
            return null;
        }
        JSONArray optJSONArray = jSONObject.optJSONArray("errors");
        if (!(optJSONArray == null || optJSONArray.length() <= 0 || (optJSONObject = optJSONArray.optJSONObject(0)) == null)) {
            String optString = optJSONObject.optString("displayable_detail", "");
            if (optString.length() > 0) {
                return optString;
            }
            String optString2 = optJSONObject.optString("detail", "");
            if (optString2.length() > 0) {
                return optString2;
            }
            String optString3 = optJSONObject.optString("title", "");
            if (optString3.length() > 0) {
                return optString3;
            }
        }
        JSONObject optJSONObject2 = jSONObject.optJSONObject("data");
        if (optJSONObject2 == null) {
            return null;
        }
        String optString4 = optJSONObject2.optString("error", "");
        if (optString4.length() > 0) {
            return optString4;
        }
        return null;
    }

    /** 候補キーのうち「要素のある配列」を優先して返す(空配列 user_info と本命 liked_users_info が同居する応答対策)。全部空なら最初に見つかった空配列。 */
    private static JSONArray firstNonEmptyArray(JSONObject jSONObject, String... strArr) {
        JSONArray empty = null;
        for (String k : strArr) {
            JSONArray a = jSONObject.optJSONArray(k);
            if (a != null) {
                if (a.length() > 0) return a;
                if (empty == null) empty = a;
            }
        }
        return empty;
    }

    private static JSONArray firstArray(JSONObject jSONObject, String... strArr) {
        for (String optJSONArray : strArr) {
            JSONArray optJSONArray2 = jSONObject.optJSONArray(optJSONArray);
            if (optJSONArray2 != null) {
                return optJSONArray2;
            }
        }
        return null;
    }

    private static String firstNonEmpty(String... strArr) {
        if (strArr != null) {
            for (String str : strArr) {
                if (str != null && str.length() > 0) {
                    return str;
                }
            }
        }
        return "";
    }

    /**
     * 公式アプリと同じ「オンライン状態」の判定値。
     * koetomo は login_status を整数(0=オンライン, 1=最近, 2=以前)で返し、
     * login_status_with_unit は表示用の文字列("6時間以内"等)。
     * 文字列だけ見ていると本当にオンラインの相手を灰色にしてしまうので、整数側も必ず持ち回る。
     */
    private static int loginStateOf(JSONObject u) {
        if (u == null) return -1;
        Object v = u.opt("login_status");
        if (v == null) v = u.opt("loginStatus");
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) {
            try { return Integer.parseInt(((String) v).trim()); } catch (Exception ignore) { return -1; }
        }
        return -1;
    }

    private static String firstStr(JSONObject jSONObject, String... strArr) {
        for (String optString : strArr) {
            String optString2 = jSONObject.optString(optString, "");
            if (optString2.length() > 0) {
                return optString2;
            }
        }
        return "";
    }


    // 「フォロー中」「フォロワー」一覧の各行に フォロー中／フォロー を出すための土台。
    // /api/v2/users/{id}/followees の各要素には関係のフラグが入っていないため、
    // 自分のフォロー先・フォロワーのID集合を作って突き合わせる。
    // 何度も開くものなので短時間キャッシュし、フォロー操作のたびに捨てる。
    private java.util.Set<Long> myFolloweeIds = null, myFollowerIds = null;
    private long relSetAt = 0L;
    // 一覧を開くたびに取り直すと毎回3往復ぶん待たされて目に見えて遅くなる。
    // フォロー操作をしたときは clearRelationSets() で捨てるので、長めに持っておいて問題ない。
    private static final long REL_SET_TTL = 600000L;

    void clearRelationSets() {
        synchronized (this) { myFolloweeIds = null; myFollowerIds = null; relSetAt = 0L; }
    }

    // 自分が出した友達申請のID集合(短時間キャッシュ)。申請ボタンの出し分けに使う。
    private java.util.HashSet<Long> sentFriendIds = null;
    private long sentFriendAt = 0L;

    void clearFriendCache() {
        synchronized (this) { sentFriendIds = null; sentFriendAt = 0L; }
    }

    private java.util.HashSet<Long> sentFriendRequestIds() {
        synchronized (this) {
            if (sentFriendIds != null && System.currentTimeMillis() - sentFriendAt < 120000L) return sentFriendIds;
        }
        java.util.HashSet<Long> out = new java.util.HashSet<Long>();
        try {
            Resp r = request("GET", "/api/followings", (Map<String, String>) null, (Map<String, String>) null);
            if (r.status == 200 && r.body != null) {
                JSONArray arr = normalizeUserList(r.body);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject u = arr.optJSONObject(i);
                    if (u != null) out.add(Long.valueOf(u.optLong("user_id", 0)));
                }
            }
        } catch (Throwable ig) {
        }
        synchronized (this) { sentFriendIds = out; sentFriendAt = System.currentTimeMillis(); }
        return out;
    }

    private java.util.Set<Long> idsOfList(String kind) {
        java.util.HashSet<Long> out = new java.util.HashSet<Long>();
        long me = userId();
        if (me == 0) return out;
        for (int page = 1; page <= 5; page++) {
            Resp r = httpApi2("GET", "/api/v2/users/" + me + "/" + kind, q1("page", String.valueOf(page)), (Map<String, String>) null);
            if (r == null || r.status != 200 || r.body == null) break;
            JSONArray arr = normalizeUserList(r.body);
            if (arr == null || arr.length() == 0) break;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject u = arr.optJSONObject(i);
                if (u != null) out.add(Long.valueOf(u.optLong("user_id", 0)));
            }
            if (arr.length() < 20) break; // 最終ページ(これ以上ページを捲らない)
        }
        return out;
    }

    /**
     * 自分がフォローしている全ユーザーIDを返す(最後のページまで捲る／内部キャッシュあり)。
     * 枠一覧の「フォロー中」絞り込みが 1 ページ目(20人)しか見ておらず、
     * 21人目以降をフォローしている人の枠が出てこなかったため追加。
     */
    private String getFolloweeIds() {
        try {
            ensureRelationSets();
            java.util.Set<Long> set;
            synchronized (this) { set = myFolloweeIds; }
            JSONArray arr = new JSONArray();
            if (set != null) {
                for (Long v : set) {
                    if (v != null && v.longValue() != 0) arr.put(v.longValue());
                }
            }
            return new JSONObject().put("ok", true).put("ids", arr).put("count", arr.length()).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private void ensureRelationSets() {
        boolean needFe, needFr;
        synchronized (this) {
            boolean fresh = System.currentTimeMillis() - relSetAt < REL_SET_TTL;
            needFe = !(fresh && myFolloweeIds != null);
            needFr = !(fresh && myFollowerIds != null);
        }
        if (!needFe && !needFr) return;
        final java.util.Set<Long>[] out = new java.util.Set[2];
        Thread t1 = null, t2 = null;
        if (needFe) { t1 = new Thread(new Runnable() { public void run() { try { out[0] = idsOfList("followees"); } catch (Throwable ig) {} } }); t1.start(); }
        if (needFr) { t2 = new Thread(new Runnable() { public void run() { try { out[1] = idsOfList("followers"); } catch (Throwable ig) {} } }); t2.start(); }
        try { if (t1 != null) t1.join(15000); } catch (Exception ig) {}
        try { if (t2 != null) t2.join(15000); } catch (Exception ig) {}
        synchronized (this) {
            if (out[0] != null) myFolloweeIds = out[0];
            if (out[1] != null) myFollowerIds = out[1];
            relSetAt = System.currentTimeMillis();
        }
        dbgLog(nowStr() + "  [RELSET] followees=" + (myFolloweeIds == null ? -1 : myFolloweeIds.size())
                + " followers=" + (myFollowerIds == null ? -1 : myFollowerIds.size()));
    }

    /** 自分の一覧を見ているときは、その一覧自体が片側の答えなので、足りない側だけ用意する。 */
    private void ensureRelationSetsFor(boolean needFollowees, boolean needFollowers) {
        synchronized (this) {
            boolean fresh = System.currentTimeMillis() - relSetAt < REL_SET_TTL;
            if ((!needFollowees || (fresh && myFolloweeIds != null))
                    && (!needFollowers || (fresh && myFollowerIds != null))) return;
        }
        if (needFollowees && needFollowers) { ensureRelationSets(); return; }
        java.util.Set<Long> got = idsOfList(needFollowees ? "followees" : "followers");
        synchronized (this) {
            if (needFollowees) myFolloweeIds = got; else myFollowerIds = got;
            if (myFolloweeIds != null && myFollowerIds != null) relSetAt = System.currentTimeMillis();
        }
        dbgLog(nowStr() + "  [RELSET] " + (needFollowees ? "followees=" : "followers=") + got.size() + " (必要な側だけ)");
    }

    private String followList(String userIdStr, String page, String kind) {
        long uid = 0;
        try {
            if (userIdStr != null && userIdStr.length() > 0 && !userIdStr.equals("null")) {
                uid = Long.parseLong(userIdStr);
            }
        } catch (Exception e) {
            uid = 0;
        }
        if (uid == 0) {
            uid = userId();
        }
        try {
            String path = "/api/v2/users/" + uid + "/" + kind;
            String p = (page == null || page.length() == 0) ? "1" : page;
            // 公式仕様: ヘッダ認証。http()直叩きでクエリ認証の混入を避ける。
            Resp resp = httpApi2("GET", path, q1("page", p), (Map<String, String>) null);
            if (resp.status == 200 && resp.body != null) {
                JSONArray users = normalizeUserList(resp.body);
                // 一覧そのものが関係を表しているので、それを最優先で反映する。
                //   followees を見ている = そこに並ぶ全員を自分がフォローしている
                //   followers を見ている = そこに並ぶ全員が自分をフォローしている
                boolean listIsFollowees = "followees".equals(kind);
                boolean mine = (uid == userId());
                java.util.Set<Long> fe = null, fr = null;
                try {
                    if (mine) {
                        // 自分の followees を見ている → 相手が自分をフォローしているか(=followers)だけ要る
                        ensureRelationSetsFor(!listIsFollowees, listIsFollowees);
                    } else {
                        ensureRelationSets();
                    }
                } catch (Throwable ig) {}
                synchronized (this) { fe = myFolloweeIds; fr = myFollowerIds; }
                for (int i = 0; i < users.length(); i++) {
                    JSONObject u = users.optJSONObject(i);
                    if (u == null) continue;
                    long id = u.optLong("user_id", 0);
                    boolean following = u.optBoolean("is_following", false);
                    boolean followed = u.optBoolean("is_followed", false);
                    if (mine && listIsFollowees) following = true;
                    if (mine && !listIsFollowees) followed = true;
                    if (fe != null && fe.contains(Long.valueOf(id))) following = true;
                    if (fr != null && fr.contains(Long.valueOf(id))) followed = true;
                    u.put("is_following", following);
                    u.put("is_followed", followed);
                    if (following) u.put("requested", false);
                }
                return new JSONObject().put("ok", true).put("users", users).toString();
            }
            return new JSONObject().put("ok", false).put("status", resp.status).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // data.X / トップレベルX / data配列 / item.user のいずれからでもユーザー配列を正規化する汎用版
    private JSONArray usersFrom(JSONObject body, String... arrayKeys) {
        JSONArray arr = null;
        Object data = body.opt("data");
        if (data instanceof JSONArray) {
            arr = (JSONArray) data;
        } else if (data instanceof JSONObject) {
            JSONObject d = (JSONObject) data;
            for (int i = 0; i < arrayKeys.length && arr == null; i++) {
                arr = d.optJSONArray(arrayKeys[i]);
            }
            if (arr == null) arr = d.optJSONArray("users");
            if (arr == null) arr = d.optJSONArray("user_info");
        }
        if (arr == null) {
            for (int i = 0; i < arrayKeys.length && arr == null; i++) {
                arr = body.optJSONArray(arrayKeys[i]);
            }
        }
        if (arr == null) arr = body.optJSONArray("users");
        if (arr == null) arr = new JSONArray();
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject it = arr.optJSONObject(i);
            if (it == null) continue;
            JSONObject u = it.optJSONObject("user");
            if (u == null) u = it;
            long id = u.optLong("user_id", u.optLong("id", 0));
            String nm = firstNonEmpty(u.optString("nickname", ""), u.optString("name", ""));
            if (nm.length() == 0) nm = "user " + id;
            String icon = firstNonEmpty(u.optString("profile_picture_file_path", ""), u.optString("profilePictureFilePath", ""));
            try {
                JSONObject o = new JSONObject().put("user_id", id).put("name", nm).put("icon_url", iconUrl(icon));
                String extra = firstNonEmpty(u.optString("biography", ""), it.optString("status", ""), it.optString("created_at", ""));
                if (extra.length() > 0) o.put("sub", truncate(extra, 60));
                out.put(o);
            } catch (Exception e) {
            }
        }
        return out;
    }

    // ===== 発見された未実装機能(全て200確認済み) =====
    private String getRecommendedUsers(String page) {
        try {
            String p = (page == null || page.length() == 0) ? "1" : page;
            Resp r = request("GET", "/api/users/recommended", q1("page", p), (Map<String, String>) null);
            if (r.status != 200 || r.body == null) return gracefulUnavailable(r, "users", "recommended_users");
            return new JSONObject().put("ok", true).put("users", usersFrom(r.body, "users", "recommended_users")).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getHimaUsers(String page) {
        try {
            String p = (page == null || page.length() == 0) ? "1" : page;
            Resp r = request("GET", "/api/users/hima", q1("page", p), (Map<String, String>) null);
            if (r.status != 200 || r.body == null) return gracefulUnavailable(r, "users", "hima_users");
            return new JSONObject().put("ok", true).put("users", usersFrom(r.body, "users", "hima_users")).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getBirthdayUsers() {
        try {
            Resp r = request("GET", "/api/users/following_born_today", (Map<String, String>) null, (Map<String, String>) null);
            if (r.status != 200 || r.body == null) return gracefulUnavailable(r, "users", "birthday_users");
            return new JSONObject().put("ok", true).put("users", usersFrom(r.body, "users", "birthday_users")).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getFollowRequests() {
        try {
            Resp r = request("GET", "/api/users/follow_requests", (Map<String, String>) null, (Map<String, String>) null);
            if (r.status != 200 || r.body == null) return gracefulUnavailable(r, "users", "follow_requests");
            return new JSONObject().put("ok", true).put("users", usersFrom(r.body, "users", "follow_requests", "requests")).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String sendFriendRequest(String targetId) {
        if (targetId == null || targetId.length() == 0) return jsonErr("target_id不明");
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("target_id", targetId);
        f.put("version", "android_" + APP_VERSION);
        String at = authToken();
        if (at != null) f.put("auth_token", at);
        Resp r = http("POST", BASE_URL + "/api/relation/follow", (Map<String, String>) null, f);
        if (r.status == 404 || r.status >= 500) {
            r = http("POST", BASE_URL2 + "/api/relation/follow", (Map<String, String>) null, f);
        }
        dbgLog(nowStr() + "  [FRIEND-REQ] send target=" + targetId + " -> " + r.status);
        clearRelationSets();
        clearFriendCache();
        clearRelationsCache();
        return okResultStatus(r);
    }

    /** 自分が出した申請を取り消す。公式 generateKoetomoWithdrawRequest: DELETE api/relation/follows?target_id= */
    private String cancelFriendRequest(String targetId) {
        return friendRelationDelete("/api/relation/follows", targetId, "cancel");
    }

    /** 来ている申請を断る。公式 generateKoetomoDeleteRequest: DELETE api/relation/followers?target_id= */
    private String denyFriendRequest(String targetId) {
        return friendRelationDelete("/api/relation/followers", targetId, "deny");
    }

    /** 友達をやめる。公式 generateFriendsDeleteRequest: DELETE api/relation/friends?target_id= */
    private String removeFriend(String targetId) {
        return friendRelationDelete("/api/relation/friends", targetId, "remove");
    }

    private String friendRelationDelete(String path, String targetId, String tag) {
        if (targetId == null || targetId.length() == 0) return jsonErr("target_id不明");
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("target_id", targetId);
        q.put("version", "android_" + APP_VERSION);
        String at = authToken();
        if (at != null) q.put("auth_token", at);
        Resp r = http("DELETE", BASE_URL + path, q, (Map<String, String>) null);
        if (r.status == 404 || r.status >= 500) {
            r = http("DELETE", BASE_URL2 + path, q, (Map<String, String>) null);
        }
        dbgLog(nowStr() + "  [FRIEND-REQ] " + tag + " " + path + " target=" + targetId + " -> " + r.status);
        clearRelationSets();
        clearFriendCache();
        clearRelationsCache();
        return okResultStatus(r);
    }

    // 公式 FriendFragment.getFriendsData は 1回の呼び出しで3つのタブぶんを受け取っている:
    //   POST https://api.meetscom.com/api/v2/dive/relations
    //     FORM: without_chat_id=false, include_blocked_user=true
    //   応答: { "friends":[…声とも], "followers":[…届いている申請], "follows":[…送った申請] }
    // GET /api/followers はただのフォロワー一覧で、申請一覧ではなかった。
    private JSONObject relationsCache = null;
    private long relationsAt = 0L;

    private JSONObject fetchRelations() {
        synchronized (this) {
            if (relationsCache != null && System.currentTimeMillis() - relationsAt < 15000L) return relationsCache;
        }
        // 公式 generateFormBuilder() は auth_token と version を必ずフォームに入れる。
        // ヘッダーだけでは受け付けてもらえないため、ここでも同じものを入れる。
        HashMap<String, String> f = new HashMap<String, String>();
        String at = authToken();
        if (at != null) f.put("auth_token", at);
        f.put("version", "android_" + APP_VERSION);
        f.put("without_chat_id", "false");
        f.put("include_blocked_user", "true");
        Resp r = http("POST", BASE_URL + "/api/v2/dive/relations", (Map<String, String>) null, f);
        if (r.status == 404 || r.status >= 500 || r.status <= 0) {
            r = http("POST", BASE_URL2 + "/api/v2/dive/relations", (Map<String, String>) null, f);
        }
        JSONObject body = (r.status == 200 && r.body != null) ? r.body : null;
        JSONObject data = null;
        if (body != null) {
            Object d = body.opt("data");
            if (d instanceof JSONObject) {
                data = (JSONObject) d;
            } else if (d instanceof String) {
                // 旧APIは data が JSON文字列で来ることがある
                try {
                    String ds = ((String) d).trim();
                    if (ds.startsWith("{")) data = new JSONObject(ds);
                } catch (Exception ignore) {
                }
            }
            if (data == null || (!data.has("friends") && !data.has("followers") && !data.has("follows"))) {
                if (body.has("friends") || body.has("followers") || body.has("follows")) data = body;
            }
            if (data == null) data = body;
        }
        StringBuilder keys = new StringBuilder();
        if (data != null) {
            java.util.Iterator<String> it = data.keys();
            while (it.hasNext()) { if (keys.length() > 0) keys.append(","); keys.append(it.next()); }
        }
        dbgLog(nowStr() + "  [RELATIONS] POST api/v2/dive/relations -> " + r.status
                + (data != null ? " friends=" + arrLen(data, "friends") + " followers=" + arrLen(data, "followers")
                    + " follows=" + arrLen(data, "follows") + " keys=[" + keys + "]"
                  : " " + truncate(redactLog(r.body != null ? r.body.toString() : "(応答なし)"), 200)));
        synchronized (this) { relationsCache = data; relationsAt = System.currentTimeMillis(); }
        return data;
    }

    private static int arrLen(JSONObject o, String key) {
        JSONArray a = o != null ? o.optJSONArray(key) : null;
        return a == null ? -1 : a.length();
    }

    void clearRelationsCache() {
        synchronized (this) { relationsCache = null; relationsAt = 0L; }
    }

    private JSONArray relationsArray(String key) {
        JSONObject data = fetchRelations();
        if (data == null) return new JSONArray();
        JSONArray raw = data.optJSONArray(key);
        if (raw == null) {
            // 応答のキー名が違う場合に備えて、同じ意味の名前も見る
            String[] alt;
            if ("friends".equals(key)) alt = new String[]{"friend", "friend_users", "koetomo", "koetomos", "koetomo_users"};
            else if ("followers".equals(key)) alt = new String[]{"follower", "incoming", "incoming_requests", "follower_users"};
            else alt = new String[]{"follow", "outgoing", "outgoing_requests", "followings", "follow_users"};
            for (int i = 0; i < alt.length && raw == null; i++) raw = data.optJSONArray(alt[i]);
        }
        if (raw == null) return new JSONArray();
        try {
            return normalizeUserList(new JSONObject().put("users", raw));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 届いている友達申請。公式では relations 応答の "followers"。 */
    private String getFriendRequestsIn() {
        try {
            JSONArray users = relationsArray("followers");
            return new JSONObject().put("ok", true).put("users", users).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 送った友達申請。公式では relations 応答の "follows"。 */
    private String getFriendRequestsOut() {
        try {
            JSONArray users = relationsArray("follows");
            return new JSONObject().put("ok", true).put("users", users).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String followUser(String str) {
        HashMap hashMap = new HashMap();
        hashMap.put("target_id", str);
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        // 公式 OkHttpSingleton.generateKoetomoFollowRequest:
        //   POST api.meetscom.com/api/relation/follow  フォーム: auth_token, version, target_id
        // (以前使っていた /api/relation/new_follow/following は公式には存在しない)
        // 公式 generateFeedFollowRequest: POST api/followings (form target_id)。
        // これが本命で、/api/relation/follow は別系統(友達申請)。
        Resp r = http("POST", BASE_URL + "/api/followings", (Map<String, String>) null, hashMap);
        dbgLog(nowStr() + "  [FOLLOW] POST /api/followings -> " + r.status);
        clearRelationSets();
        if (r.status < 200 || r.status >= 300) {
            // 予備は公式の TimelineApiServer1.follow(POST api/relation/new_follow/following, クエリ)。
            // /api/relation/follow は公式では「友達申請」で意味が違うため使わない。
            Resp rb = http("POST", BASE_URL + "/api/relation/new_follow/following", hashMap, (Map<String, String>) null);
            dbgLog(nowStr() + "  [FOLLOW-FB] new_follow/following -> " + rb.status);
            if (rb.status >= 200 && rb.status < 300) r = rb;
        }
        dbgLog(nowStr() + "  [FOLLOW] target=" + str + " HTTP " + r.status + " vsns=" + r.vsns
                + (r.status >= 200 && r.status < 300 ? "" : " " + truncate(redactLog(r.body != null ? r.body.toString() : ""), 200)));
        // 成功時でも非0の X-Vsns-Status を返すため、HTTPステータスのみで判定する
        return okResultStatus(r);
    }

    private String getAccountBalance() {
        HashMap hashMap = new HashMap();
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        // /api/account/profile はサーバー側で廃止(404)。動作している /api/v3/users/{id} から取得する
        Resp http2 = request("GET", "/api/v3/users/" + userId(), (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (http2.status != 200 || http2.body == null) {
                return new JSONObject().put("ok", false).put("status", http2.status).toString();
            }
            JSONObject optJSONObject = http2.body.optJSONObject("data");
            if (optJSONObject == null) {
                optJSONObject = http2.body;
            }
            JSONObject optJSONObject2 = optJSONObject.optJSONObject("user");
            if (optJSONObject2 == null) {
                optJSONObject2 = optJSONObject;
            }
            long optLong = optJSONObject2.optLong("coin_amount", optJSONObject2.optLong("coin", optJSONObject2.optLong("coins", -1)));
            return new JSONObject().put("ok", true).put("coin", optLong).put("point", optJSONObject2.optLong("point_amount", optJSONObject2.optLong("point", optJSONObject2.optLong("points", -1)))).put("good_talk_count", optJSONObject2.optLong("good_talk_count", -1)).put("raw", truncate(http2.body.toString(), 400)).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getActivityHeatmap() {
        try {
            JSONArray loadRoomHistoryArr = loadRoomHistoryArr();
            JSONObject jSONObject = new JSONObject();
            for (int i = 0; i < loadRoomHistoryArr.length(); i++) {
                JSONObject optJSONObject = loadRoomHistoryArr.optJSONObject(i);
                if (optJSONObject != null) {
                    String optString = optJSONObject.optString("joined_at", "");
                    if (optString.length() >= 10) {
                        String substring = optString.substring(0, 10);
                        jSONObject.put(substring, jSONObject.optInt(substring, 0) + 1);
                    }
                }
            }
            return new JSONObject().put("ok", true).put("counts", jSONObject).put("total", loadRoomHistoryArr.length()).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getAnnouncements() {
        Resp request = request("GET", "/api/room_announcements", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (request.status != 200 || request.body == null) {
                return jsonStatus(request);
            }
            Object opt = request.body.opt("data");
            JSONArray firstArray = opt instanceof JSONArray ? (JSONArray) opt : opt instanceof JSONObject ? firstArray((JSONObject) opt, "room_announcements", "announcements") : null;
            JSONArray jSONArray = firstArray == null ? new JSONArray() : firstArray;
            JSONArray jSONArray2 = new JSONArray();
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject = jSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    jSONArray2.put(new JSONObject().put("user_id", commentUid(optJSONObject)));
                }
            }
            resolveNames(jSONArray2, "user_id");
            JSONArray jSONArray3 = new JSONArray();
            for (int i2 = 0; i2 < jSONArray.length(); i2++) {
                JSONObject optJSONObject2 = jSONArray.optJSONObject(i2);
                if (optJSONObject2 != null) {
                    long commentUid = commentUid(optJSONObject2);
                    String[] strArr = this.nameCache.get(Long.valueOf(commentUid));
                    JSONObject optJSONObject3 = optJSONObject2.optJSONObject("user");
                    String optString = optJSONObject3 != null ? optJSONObject3.optString("name", "") : "";
                    if (optString.length() == 0 && strArr != null) {
                        optString = strArr[0];
                    }
                    String str = (optString.length() != 0 || commentUid == 0) ? optString : "user " + commentUid;
                    String optString2 = optJSONObject3 != null ? optJSONObject3.optString("profile_picture_file_path", "") : "";
                    jSONArray3.put(new JSONObject().put("description", optJSONObject2.optString("description", "")).put("user_id", commentUid).put("name", str).put("icon_url", iconUrl((optString2.length() != 0 || strArr == null) ? optString2 : strArr[1])).put("open_at", optJSONObject2.optString("open_at", optJSONObject2.optString("openAt", ""))).put("created_at", optJSONObject2.optString("created_at", optJSONObject2.optString("createdAt", ""))));
                }
            }
            return new JSONObject().put("ok", true).put("announcements", jSONArray3).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getBadges(String str) {
        Resp request = (str == null || str.length() <= 0 || str.equals("null")) ? request("GET", "/api/my_badges", (Map<String, String>) null, (Map<String, String>) null) : request("GET", "/api/users/" + str + "/badges", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (request.status != 200 || request.body == null) {
                return jsonStatus(request);
            }
            Object opt = request.body.opt("data");
            JSONArray firstArray = opt instanceof JSONArray ? (JSONArray) opt : opt instanceof JSONObject ? firstArray((JSONObject) opt, "badges", "user_badges", "my_badges") : null;
            JSONArray jSONArray = firstArray == null ? new JSONArray() : firstArray;
            JSONArray jSONArray2 = new JSONArray();
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject = jSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    Object badgeId = optJSONObject.opt("id");
                    if (badgeId == null) {
                        badgeId = optJSONObject.opt("badge_id");
                    }
                    jSONArray2.put(new JSONObject().put("id", badgeId != null ? badgeId : JSONObject.NULL).put("name", firstStr(optJSONObject, "name", "title")).put("icon_url", iconUrl(firstStr(optJSONObject, "image_file_path", "imageFilePath", "badge_image_file_path", "badgeImageFilePath", "icon"))).put("description", optJSONObject.optString("description", "")));
                }
            }
            return new JSONObject().put("ok", true).put("badges", jSONArray2).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getBlockList() {
        Resp request = request("GET", "/api/v2/blocked_users", q1("page", "1"), (Map<String, String>) null);
        dbgLog(nowStr() + "  [BLOCKLIST] HTTP " + request.status + " " + (request.body != null ? truncate(redactLog(request.body.toString()), 300) : "(null)"));
        try {
            if (request.status == 200 && request.body != null) {
                JSONArray users = normalizeUserList(request.body);
                dbgLog(nowStr() + "  [BLOCKLIST] parsed users=" + users.length());
                return new JSONObject().put("ok", true).put("users", users).put("raw", truncate(request.body.toString(), 300)).toString();
            }
            return new JSONObject().put("ok", false).put("status", request.status).put("raw", request.body != null ? truncate(request.body.toString(), 300) : "").toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getBookmarks(String maxCreatedAt) {
        // 公式 TimelineApi(TYPE_2=api2): GET api/bookmark_posts?max_created_at=… 応答は data.posts
        // request() は api→api2 の順で、api が 200 空応答を返すと api2 に行かないので api2 を直接叩く。
        HashMap<String, String> q = new HashMap<String, String>();
        if (maxCreatedAt != null && maxCreatedAt.length() > 0 && !"null".equals(maxCreatedAt)) q.put("max_created_at", maxCreatedAt);
        // 公式は QueryMap に count(1ページ件数) を必ず入れる。count 無しだと空配列が返る(communities/bookmarks と同じ挙動)。
        q.put("count", "30");
        q.put("version", "android_" + APP_VERSION);
        String at = authToken();
        if (at != null) q.put("auth_token", at);
        Resp r = http("GET", BASE_URL2 + "/api/bookmark_posts", q, (Map<String, String>) null);
        if (r.status != 200) {
            r = http("GET", BASE_URL + "/api/bookmark_posts", q, (Map<String, String>) null);
        }
        dbgLog(nowStr() + "  [BOOKMARK] list -> " + r.status + (r.body != null ? " " + truncate(redactLog(r.body.toString()), 400) : ""));
        return postsResult(r, "posts", true);
    }

    private String getCallRecords() {
        HashMap hashMap = new HashMap();
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        Resp http = http("GET", "https://api2.meetscom.com/api/call_records/others", hashMap, (Map<String, String>) null);
        if (http.status == 404 || http.status >= 500) {
            http = http("GET", "https://api.meetscom.com/api/call_records/others", hashMap, (Map<String, String>) null);
        }
        return parseRecordsBody(http);
    }

    // チャットオブジェクトから最後のメッセージ本文を抜き出す(フィールド名は複数候補を試す)
    private String chatLastMessage(JSONObject c) {
        if (c == null) return "";
        // 添付系のみのメッセージを先に判定（テキストが空でも種別を出す）
        if (c.optString("last_message_image_url", "").length() > 0 || c.optString("last_image_url", "").length() > 0) return "[画像]";
        if (c.optString("last_message_voice_url", "").length() > 0 || c.optString("last_voice_url", "").length() > 0) return "[音声]";
        String[] direct = {"last_message_text", "last_message_preview", "latest_message_text", "last_message_body", "last_message", "latest_message", "message_preview", "preview", "snippet"};
        for (int i = 0; i < direct.length; i++) {
            Object o = c.opt(direct[i]);
            if (o instanceof String && ((String) o).trim().length() > 0) return (String) o;
        }
        String[] objs = {"last_message", "latest_message", "message", "recent_message", "last_chat_message", "last_chat"};
        for (int i = 0; i < objs.length; i++) {
            Object o = c.opt(objs[i]);
            if (o instanceof JSONObject) {
                String v = firstStr((JSONObject) o, "text", "message", "body", "content", "description", "comment");
                if (v.length() > 0) return v;
                if (((JSONObject) o).opt("attachment") != null || ((JSONObject) o).optString("image_url", "").length() > 0) return "[画像]";
                if (((JSONObject) o).optString("voice_url", "").length() > 0) return "[音声]";
            }
        }
        // 汎用ディープスキャン: フィールド名が判らなくても、
        // メッセージらしいキーの非空文字列を拾う（未知のAPI形状への保険）
        String deep = deepFindMessage(c, 0);
        return deep == null ? "" : deep;
    }

    // メッセージ本文らしき値をJSONオブジェクトから探す。best-effort。
    private String deepFindMessage(JSONObject o, int depth) {
        if (o == null || depth > 2) return null;
        String best = null; int bestRank = 999;
        java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) {
            String k = it.next();
            String kl = k.toLowerCase();
            Object v = o.opt(k);
            if (v instanceof JSONObject) {
                String sub = deepFindMessage((JSONObject) v, depth + 1);
                if (sub != null && bestRank > 50) { best = sub; bestRank = 50; }
                continue;
            }
            if (!(v instanceof String)) continue;
            String s = ((String) v).trim();
            if (s.length() == 0) continue;
            // 除外: URL / タイムスタンプ / ID / フラグ的な短い記号
            if (kl.endsWith("_id") || kl.equals("id") || kl.endsWith("_at") || kl.endsWith("_url")
                || kl.contains("token") || kl.contains("uuid") || kl.contains("path")
                || kl.contains("icon") || kl.contains("picture") || kl.contains("name")
                || kl.contains("status") || kl.contains("type") || kl.contains("time")
                || kl.contains("date")) continue;
            if (s.startsWith("http://") || s.startsWith("https://")) continue;
            // メッセージらしいキーを優先度付け
            int rank;
            if (kl.contains("last_message") || kl.contains("latest_message")) rank = 0;
            else if (kl.contains("message")) rank = 1;
            else if (kl.equals("text") || kl.endsWith("_text")) rank = 2;
            else if (kl.contains("preview") || kl.contains("snippet")) rank = 3;
            else if (kl.equals("body") || kl.equals("content") || kl.equals("comment")) rank = 4;
            else continue; // メッセージ系キーでなければ拾わない
            if (rank < bestRank) { best = (String) v; bestRank = rank; }
        }
        return best;
    }

    // チャット一覧の各行に出す直近メッセージのプレビューを /api/messages から1件取得する
    private String fetchChatPreview(String chatId, String targetId) {
        try {
            HashMap hashMap = new HashMap();
            hashMap.put("chat_id", chatId);
            hashMap.put("target_id", targetId);
            hashMap.put("page", "1");
            Resp r = request("GET", "/api/messages", hashMap, (Map<String, String>) null);
            if (r == null || r.status != 200 || r.body == null) return "";
            JSONArray arr = r.body.optJSONArray("messages");
            if (arr == null) {
                JSONObject d = r.body.optJSONObject("data");
                if (d != null) arr = firstArray(d, "messages", "chat_messages", "message", "data");
                if (arr == null && (r.body.opt("data") instanceof JSONArray)) arr = (JSONArray) r.body.opt("data");
            }
            if (arr == null || arr.length() == 0) return "";
            // 応答は新しい順(index0が最新)想定。最初の非空本文を採用。
            for (int i = 0; i < Math.min(arr.length(), 4); i++) {
                JSONObject m = arr.optJSONObject(i);
                if (m == null) continue;
                String t = firstStr(m, "text_message", "text", "message", "content", "body");
                if (t != null && t.trim().length() > 0) return t;
                int mt = m.optInt("message_type", 0);
                if (m.optString("image_url", "").length() > 0 || m.optString("image_file", "").length() > 0 || mt == 2) return "[画像]";
                if (m.optString("voice_url", "").length() > 0 || mt == 3) return "[音声]";
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    private String getChats() throws org.json.JSONException {
        long userId = userId();
        HashMap hashMap = new HashMap();
        hashMap.put("uid", String.valueOf(userId));
        hashMap.put("offset", "0");
        hashMap.put("count", "20");
        // 公式 ChatApi.getChatRoomList は server1(api.meetscom.com) 固定・version=android_3.9.101。
        // api2 も 200 を返すが data 包み・user_info の無い旧形式で、相手の名前が取れない。
        hashMap.put("version", "android_" + APP_VERSION);
        String at = authToken();
        if (at != null) hashMap.put("auth_token", at);
        Resp request = http("GET", BASE_URL + "/api/chats", hashMap, (Map<String, String>) null);
        if (request.status <= 0 || request.status >= 500) {
            request = http("GET", BASE_URL2 + "/api/chats", hashMap, (Map<String, String>) null); // 旧サーバー不調時の予備(名前は別途補う)
        }
        String str = "[uid=" + userId + " HTTP " + request.status + "] " + (request.body != null ? truncate(request.body.toString(), 500) : "(ボディなし)");
        if (userId == 0) {
            try {
                return new JSONObject().put("ok", false).put("error", "user_id未取得。再ログインしてください。").put("raw", str).toString();
            } catch (Exception e) {
                return errJson(e);
            }
        } else if (request.status != 200 || request.body == null) {
            return new JSONObject().put("ok", false).put("status", request.status).put("raw", str).toString();
        } else {
            // 応答は {"data":{"chats":[...],"user_info":[...]}} と、data 包みなしの {"chats":[...]} の 2 形式がある
            JSONObject optJSONObject = request.body.optJSONObject("data");
            JSONObject jSONObject = optJSONObject == null ? request.body : optJSONObject;
            JSONArray optJSONArray = jSONObject.optJSONArray("chats");
            JSONArray jSONArray = optJSONArray == null ? new JSONArray() : optJSONArray;
            try { if (jSONArray.length() > 0 && jSONArray.optJSONObject(0) != null) { dbgLog(nowStr() + "  [CHATS] chat_obj: " + truncate(redactLog(jSONArray.optJSONObject(0).toString()), 700)); } } catch (Exception ig) {}
            JSONArray optJSONArray2 = jSONObject.optJSONArray("user_info");
            HashMap hashMap2 = new HashMap();
            if (optJSONArray2 != null) {
                for (int i = 0; i < optJSONArray2.length(); i++) {
                    JSONObject optJSONObject2 = optJSONArray2.optJSONObject(i);
                    if (optJSONObject2 != null) {
                        hashMap2.put(Long.valueOf(optJSONObject2.optLong("user_id")), optJSONObject2);
                    }
                }
            }
            // user_info が同梱されない形式では、相手の名前とアイコンを名前キャッシュ(/api/v2/users)から補う
            if (hashMap2.isEmpty() && jSONArray.length() > 0) {
                try { resolveNames(jSONArray, "user_id"); } catch (Exception ignored) {}
            }
            JSONArray jSONArray2 = new JSONArray();
            final java.util.List<String[]> toFetch = new java.util.ArrayList<String[]>();
            final java.util.List<Integer> fetchIdx = new java.util.ArrayList<Integer>();
            for (int i2 = 0; i2 < jSONArray.length(); i2++) {
                JSONObject optJSONObject3 = jSONArray.optJSONObject(i2);
                if (optJSONObject3 != null) {
                    long optLong = optJSONObject3.optLong("user_id");
                    JSONObject jSONObject2 = (JSONObject) hashMap2.get(Long.valueOf(optLong));
                    if (jSONObject2 == null) {
                        String[] cached = this.nameCache.get(Long.valueOf(optLong));
                        if (cached != null && cached.length > 1 && cached[0] != null && cached[0].length() > 0) {
                            jSONObject2 = new JSONObject().put("name", cached[0]).put("profile_picture_file_path", cached[1] == null ? "" : cached[1]);
                        }
                    }
                    String preview = chatLastMessage(optJSONObject3);
                    int idx = jSONArray2.length();
                    jSONArray2.put(new JSONObject().put("chat_id", optJSONObject3.opt("id")).put("target_id", optLong).put("name", jSONObject2 != null ? jSONObject2.optString("name", "user " + optLong) : "user " + optLong).put("icon_url", jSONObject2 != null ? iconUrl(jSONObject2.optString("profile_picture_file_path", "")) : "").put("last_sent_at", optJSONObject3.optString("last_sent_at", "")).put("last_message", preview).put("unread_count", optJSONObject3.optInt("unread_count", 0)).put("last_message_user", optJSONObject3.optLong("last_message_user", 0)));
                    // リスト応答に本文が無い場合は、後で /api/messages から取得する
                    if (preview.length() == 0) {
                        String cid = optJSONObject3.optString("id", "");
                        if (cid.length() == 0 && optJSONObject3.opt("id") != null) cid = String.valueOf(optJSONObject3.opt("id"));
                        if (cid.length() > 0) { toFetch.add(new String[]{cid, String.valueOf(optLong)}); fetchIdx.add(Integer.valueOf(idx)); }
                    }
                }
            }
            // 取得済みのプレビューを再利用する。
            // 会話一覧を開くたびに毎回8本の /api/messages を投げ直していたのが、
            // チャット画面の表示が重い主因だった。last_sent_at が変わっていなければ本文も変わらない。
            try {
                android.content.SharedPreferences cp = this.prefs;
                String cachedRaw = cp.getString("chat_preview_cache", "{}");
                JSONObject cache = new JSONObject(cachedRaw);
                for (int k = toFetch.size() - 1; k >= 0; k--) {
                    int idx = fetchIdx.get(k).intValue();
                    JSONObject row = jSONArray2.optJSONObject(idx);
                    if (row == null) continue;
                    String ck = toFetch.get(k)[0] + "@" + row.optString("last_sent_at", "");
                    String hit = cache.optString(ck, "");
                    if (hit.length() > 0) {
                        row.put("last_message", hit);
                        toFetch.remove(k);
                        fetchIdx.remove(k);
                    }
                }
                this.previewCache = cache;
            } catch (Exception ig) {
                this.previewCache = null;
            }
            // 直近メッセージを並列取得（見えている上位のみ・全体タイムアウト付き）
            if (!toFetch.isEmpty()) {
                int n = Math.min(toFetch.size(), 8);
                final String[] results = new String[n];
                Thread[] ths = new Thread[n];
                for (int k = 0; k < n; k++) {
                    final int kk = k;
                    final String cid = toFetch.get(k)[0];
                    final String tid = toFetch.get(k)[1];
                    ths[k] = new Thread(new Runnable() { public void run() { try { results[kk] = fetchChatPreview(cid, tid); } catch (Throwable t) { results[kk] = ""; } } });
                    ths[k].start();
                }
                long deadline = System.currentTimeMillis() + 7000;
                for (int k = 0; k < n; k++) { try { long left = deadline - System.currentTimeMillis(); ths[k].join(left > 0 ? left : 1); } catch (Exception ignore) {} }
                for (int k = 0; k < n; k++) {
                    if (results[k] != null && results[k].length() > 0) {
                        try {
                            JSONObject row = jSONArray2.getJSONObject(fetchIdx.get(k).intValue());
                            row.put("last_message", results[k]);
                            if (this.previewCache != null) {
                                this.previewCache.put(toFetch.get(k)[0] + "@" + row.optString("last_sent_at", ""), results[k]);
                            }
                        } catch (Exception ignore) {}
                    }
                }
                // キャッシュを保存(古い会話の分が無限に増えないよう上限を設ける)
                try {
                    if (this.previewCache != null) {
                        if (this.previewCache.length() > 120) this.previewCache = new JSONObject();
                        this.prefs.edit().putString("chat_preview_cache", this.previewCache.toString()).apply();
                    }
                } catch (Exception ignore) {}
            }
            return new JSONObject().put("ok", true).put("rooms", jSONArray2).put("raw", str).toString();
        }
    }

    private String getCoinHistory() {
        // 公式 generateGetCoinHistoryRequest は order を必ず付ける
        return historyResult(request("GET", "/api/v2/coin_histories", q2("page", "1", "order", "desc"), (Map<String, String>) null), "coin_histories", "histories", "data");
    }

    private String getCommunitiesFeed(String page) {
        Resp resp = request("GET", "/api/communities/participating_posts", q1("page", page), (Map<String, String>) null);
        String raw = "[HTTP " + resp.status + "] " + (resp.body != null ? truncate(resp.body.toString(), 300) : "(なし)");
        try {
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp.status).put("raw", raw).toString();
            }
            JSONArray posts = resp.body.optJSONArray("posts");
            Object data = resp.body.opt("data");
            if (posts == null && data instanceof JSONArray) {
                posts = (JSONArray) data;
            }
            if (posts == null && data instanceof JSONObject) {
                posts = firstArray((JSONObject) data, "posts", "community_posts", "feed");
            }
            if (posts == null) {
                posts = new JSONArray();
            }
            JSONArray result = new JSONArray();
            for (int i = 0; i < posts.length(); i++) {
                JSONObject p = posts.optJSONObject(i);
                if (p == null) {
                    continue;
                }
                JSONObject user = p.optJSONObject("user");
                JSONObject out = new JSONObject();
                out.put("community_name", firstStr(p, "community_name", "group_name"));
                String userName = (user != null) ? user.optString("name", "") : firstStr(p, "user_name", "name");
                out.put("name", userName);
                String iconPath = user != null ? user.optString("profile_picture_file_path", "") : "";
                out.put("icon_url", iconUrl(iconPath));
                out.put("text", firstStr(p, "text", "message", "body", "content"));
                out.put("created_at", firstStr(p, "created_at", "createdAt"));
                result.put(out);
            }
            return new JSONObject().put("ok", true).put("posts", result).put("raw", raw).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCommunityCategories() {
        Resp resp = request("GET", "/api/communities/categories", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp.status).toString();
            }
            JSONArray categories = resp.body.optJSONArray("categories");
            Object data = resp.body.opt("data");
            if (categories == null && data instanceof JSONArray) {
                categories = (JSONArray) data;
            }
            if (categories == null && data instanceof JSONObject) {
                categories = firstArray((JSONObject) data, "categories", "community_categories");
            }
            if (categories == null) {
                categories = new JSONArray();
            }
            JSONArray result = new JSONArray();
            for (int i = 0; i < categories.length(); i++) {
                JSONObject c = categories.optJSONObject(i);
                if (c == null) {
                    continue;
                }
                JSONObject out = new JSONObject();
                out.put("id", c.optLong("id", 0));
                out.put("name", firstStr(c, "name", "title", "label"));
                result.put(out);
            }
            return new JSONObject().put("ok", true).put("categories", result).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCommunityComments(String str, String str2) {
        Resp request = request("GET", "/api/communities/" + str + "/posts/" + str2 + "/comments", q1("page", "1"), (Map<String, String>) null);
        try {
            if (request.status != 200 || request.body == null) {
                return jsonStatus(request);
            }
            Object opt = request.body.opt("data");
            JSONArray firstArray = opt instanceof JSONArray ? (JSONArray) opt : opt instanceof JSONObject ? firstArray((JSONObject) opt, "comments", "post_comments") : null;
            JSONArray jSONArray = firstArray == null ? new JSONArray() : firstArray;
            JSONArray jSONArray2 = new JSONArray();
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject = jSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    jSONArray2.put(new JSONObject().put("user_id", commentUid(optJSONObject)));
                }
            }
            resolveNames(jSONArray2, "user_id");
            JSONArray jSONArray3 = new JSONArray();
            for (int i2 = 0; i2 < jSONArray.length(); i2++) {
                JSONObject optJSONObject2 = jSONArray.optJSONObject(i2);
                if (optJSONObject2 != null) {
                    long commentUid = commentUid(optJSONObject2);
                    String[] strArr = this.nameCache.get(Long.valueOf(commentUid));
                    JSONObject optJSONObject3 = optJSONObject2.optJSONObject("user");
                    String optString = optJSONObject3 != null ? optJSONObject3.optString("name", "") : "";
                    if (optString.length() == 0 && strArr != null) {
                        optString = strArr[0];
                    }
                    String str3 = (optString.length() != 0 || commentUid == 0) ? optString : "user " + commentUid;
                    String optString2 = optJSONObject3 != null ? optJSONObject3.optString("profile_picture_file_path", "") : "";
                    jSONArray3.put(new JSONObject().put("user_id", commentUid).put("name", str3).put("icon_url", iconUrl((optString2.length() != 0 || strArr == null) ? optString2 : strArr[1])).put("text", firstStr(optJSONObject2, "description", "comment", "text")).put("created_at", optJSONObject2.optString("created_at", optJSONObject2.optString("createdAt", ""))));
                }
            }
            return new JSONObject().put("ok", true).put("comments", jSONArray3).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCommunityInfo(String str) {
        Resp request = request("GET", "/api/communities/" + str, (Map<String, String>) null, (Map<String, String>) null);
        String str2 = "[HTTP " + request.status + "] " + (request.body != null ? truncate(request.body.toString(), 300) : "(なし)");
        try {
            if (request.status != 200 || request.body == null) {
                return new JSONObject().put("ok", false).put("status", request.status).put("raw", str2).toString();
            }
            JSONObject optJSONObject = request.body.optJSONObject("community");
            if (optJSONObject == null) {
                optJSONObject = request.body.optJSONObject("data");
            }
            if (optJSONObject == null) {
                optJSONObject = request.body;
            }
            return new JSONObject().put("ok", true).put("name", firstStr(optJSONObject, "name", "title")).put("description", firstStr(optJSONObject, "description", "detail", "bio", "text")).put("member_count", optJSONObject.optLong("member_count", optJSONObject.optLong("members_count", optJSONObject.optLong("user_count")))).put("icon_url", iconUrl(firstStr(optJSONObject, "cover_image", "image", "icon", "thumbnail"))).put("raw", str2).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCommunityMembers(String str) { return getCommunityMembers(str, null); }

    // cursor(= 前ページ最後の joined_at)を渡すと続きを取得できる（公式 max_joined_at）
    private String getCommunityMembers(String str, String cursor) {
        Resp request = request("GET", "/api/communities/" + str + "/members", cQ("count", "20", "max_joined_at", cursor), (Map<String, String>) null);
        try {
            if (request.status != 200 || request.body == null) {
                return jsonStatus(request);
            }
            Object opt = request.body.opt("data");
            JSONArray firstArray = opt instanceof JSONArray ? (JSONArray) opt : opt instanceof JSONObject ? firstArray((JSONObject) opt, "users", "members", "user_info") : null;
            JSONArray jSONArray = firstArray == null ? new JSONArray() : firstArray;
            JSONArray jSONArray2 = new JSONArray();
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject = jSONArray.optJSONObject(i);
                if (optJSONObject != null) {
                    JSONObject optJSONObject2 = optJSONObject.optJSONObject("user");
                    if (optJSONObject2 == null) {
                        optJSONObject2 = optJSONObject;
                    }
                    long optLong = optJSONObject2.optLong("user_id", optJSONObject2.optLong("userId", optJSONObject.optLong("user_id")));
                    jSONArray2.put(new JSONObject().put("user_id", optLong).put("name", optJSONObject2.optString("name", optLong != 0 ? "user " + optLong : "")).put("icon_url", iconUrl(optJSONObject2.optString("profile_picture_file_path", optJSONObject2.optString("profilePictureFilePath", "")))));
                }
            }
            return new JSONObject().put("ok", true).put("users", jSONArray2).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCommunityPosts(String str) {
        Resp request = request("GET", "/api/communities/" + str + "/posts", q1("page", "1"), (Map<String, String>) null);
        try {
            if (request.status != 200 || request.body == null) {
                return jsonStatus(request);
            }
            JSONObject optJSONObject = request.body.optJSONObject("data");
            JSONObject jSONObject = optJSONObject == null ? new JSONObject() : optJSONObject;
            JSONArray optJSONArray = jSONObject.optJSONArray("posts");
            JSONArray jSONArray = optJSONArray == null ? new JSONArray() : optJSONArray;
            JSONArray optJSONArray2 = jSONObject.optJSONArray("liked_ids");
            JSONArray optJSONArray3 = optJSONArray2 == null ? jSONObject.optJSONArray("likedIds") : optJSONArray2;
            HashSet hashSet = new HashSet();
            if (optJSONArray3 != null) {
                for (int i = 0; i < optJSONArray3.length(); i++) {
                    hashSet.add(Long.valueOf(optJSONArray3.optLong(i)));
                }
            }
            resolveNames(jSONArray, "user_id");
            JSONArray jSONArray2 = new JSONArray();
            for (int i2 = 0; i2 < jSONArray.length(); i2++) {
                JSONObject optJSONObject2 = jSONArray.optJSONObject(i2);
                if (optJSONObject2 != null) {
                    long optLong = optJSONObject2.optLong("user_id");
                    String[] strArr = this.nameCache.get(Long.valueOf(optLong));
                    jSONArray2.put(new JSONObject().put("id", optJSONObject2.opt("id")).put("user_id", optLong).put("name", strArr != null ? strArr[0] : "user " + optLong).put("icon_url", strArr != null ? iconUrl(strArr[1]) : "").put("text", optJSONObject2.optString("description", "")).put("liked", hashSet.contains(Long.valueOf(optJSONObject2.optLong("id")))).put("like_count", optJSONObject2.optInt("liked_count", optJSONObject2.optInt("likedCount", 0))));
                }
            }
            return new JSONObject().put("ok", true).put("posts", jSONArray2).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCommunityRules(String communityId) {
        Resp resp = request("GET", "/api/communities/" + communityId + "/rules", (Map<String, String>) null, (Map<String, String>) null);
        String raw = "[HTTP " + resp.status + "] " + (resp.body != null ? truncate(resp.body.toString(), 300) : "(なし)");
        try {
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp.status).put("raw", raw).toString();
            }
            JSONArray rules = resp.body.optJSONArray("rules");
            Object data = resp.body.opt("data");
            if (rules == null && data instanceof JSONArray) {
                rules = (JSONArray) data;
            }
            if (rules == null && data instanceof JSONObject) {
                rules = firstArray((JSONObject) data, "rules");
            }
            if (rules == null) {
                rules = new JSONArray();
            }
            JSONArray result = new JSONArray();
            for (int i = 0; i < rules.length(); i++) {
                JSONObject r = rules.optJSONObject(i);
                if (r == null) {
                    continue;
                }
                JSONObject out = new JSONObject();
                out.put("title", firstStr(r, "title", "name", "heading"));
                out.put("text", firstStr(r, "text", "description", "body", "content"));
                result.put(out);
            }
            return new JSONObject().put("ok", true).put("rules", result).put("raw", raw).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getFeedPost(String postId) {
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("version", "android_" + APP_VERSION);
        String authToken = authToken();
        if (authToken != null) {
            fields.put("auth_token", authToken);
        }
        Resp resp = http("GET", "https://api2.meetscom.com/api/feed_posts/" + postId, fields, (Map<String, String>) null);
        String raw = "[/api/feed_posts/" + postId + "@api2 HTTP " + resp.status + "]";
        try {
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp.status).put("raw", raw).toString();
            }
            JSONObject post = resp.body.optJSONObject("post_info");
            if (post == null) {
                post = resp.body.optJSONObject("post");
            }
            if (post == null) {
                post = resp.body.optJSONObject("feed_post");
            }
            JSONObject dataObj = resp.body.optJSONObject("data");
            if (post == null && dataObj != null) {
                JSONObject nested = dataObj.optJSONObject("post_info");
                if (nested == null) {
                    nested = dataObj.optJSONObject("post");
                }
                if (nested == null) {
                    nested = dataObj.optJSONObject("feed_post");
                }
                post = nested;
            }
            JSONObject postData;
            if (post != null) {
                postData = post;
            } else if (dataObj != null) {
                postData = dataObj;
            } else {
                postData = resp.body;
            }
            JSONObject user = postData.optJSONObject("user");
            long uid = user != null ? user.optLong("id", user.optLong("user_id")) : 0;
            uid = postData.optLong("user_id", uid);
            String name = user != null ? user.optString("name", "") : "";
            String iconPath = user != null ? user.optString("profile_picture_file_path", "") : "";
            if (name.length() == 0 && uid != 0) {
                JSONArray idsArr = new JSONArray();
                idsArr.put(new JSONObject().put("user_id", uid));
                resolveNames(idsArr, "user_id");
                String[] cached = this.nameCache.get(Long.valueOf(uid));
                if (cached != null) {
                    if (cached[0] != null) {
                        name = cached[0];
                    }
                    if (iconPath.length() == 0 && cached[1] != null) {
                        iconPath = cached[1];
                    }
                }
            }
            JSONObject out = new JSONObject();
            out.put("id", postData.opt("id"));
            out.put("user_id", uid);
            out.put("name", name.length() > 0 ? name : ("user " + uid));
            out.put("icon_url", iconUrl(iconPath));
            out.put("text", firstStr(postData, "description", "comment", "text", "message", "body"));
            out.put("image_url", iconUrl(postData.optString("image_file_path", postData.optString("image", ""))));
            // 音声投稿: タイムラインと同じ形で voice_url / play_time を返す（詳細画面で再生できるように）
            out.put("voice_url", voiceUrl(firstNonEmpty(postData.optString("voice_file_path", ""), postData.optString("voice_url", ""), postData.optString("sound_file_url", ""), postData.optString("audio_url", ""))));
            if (postData.has("play_time") && !postData.isNull("play_time")) {
                out.put("play_time", postData.opt("play_time"));
            }
            out.put("created_at", postData.optString("created_at", ""));
            out.put("likes", postData.optInt("liked_user_count", postData.optInt("good_count", postData.optInt("likes_count", 0))));
            out.put("comments", postData.optInt("comment_count", postData.optInt("comments_count", 0)));
            out.put("bookmarked", postData.optBoolean("bookmarked", false));
            JSONObject result = new JSONObject().put("ok", true).put("post", out);
            result.put("raw", raw + " " + truncate(resp.body.toString(), 260));
            return result.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getFollowActivity() {
        JSONArray jSONArray = null;
        Resp request = request("GET", "/api/following_posts", qOpt("max_created_at", ""), (Map<String, String>) null);
        try {
            if (request.status != 200 || request.body == null) {
                return new JSONObject().put("ok", false).put("status", request.status).toString();
            }
            JSONObject optJSONObject = request.body.optJSONObject("data");
            if (optJSONObject != null) {
                jSONArray = optJSONObject.optJSONArray("following_posts");
                if (jSONArray == null) {
                    jSONArray = optJSONObject.optJSONArray("timeline_posts");
                }
                if (jSONArray == null) {
                    jSONArray = optJSONObject.optJSONArray("posts");
                }
            }
            if (jSONArray == null) {
                jSONArray = new JSONArray();
            }
            JSONArray normalizePosts = normalizePosts(jSONArray);
            JSONArray jSONArray2 = new JSONArray();
            for (int i = 0; i < normalizePosts.length(); i++) {
                JSONObject optJSONObject2 = normalizePosts.optJSONObject(i);
                if (optJSONObject2 != null) {
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("type", 100);
                    jSONObject.put("user_id", optJSONObject2.optLong("user_id"));
                    jSONObject.put("name", optJSONObject2.optString("name", ""));
                    jSONObject.put("icon_url", optJSONObject2.optString("icon_url", ""));
                    String optString = optJSONObject2.optString("text", "");
                    jSONObject.put("message", optString.length() > 0 ? "が投稿: " + truncate(optString, 44) : optJSONObject2.optString("image_url", "").length() > 0 ? "が画像を投稿しました" : optJSONObject2.optString("voice_url", "").length() > 0 ? "が音声を投稿しました" : "が投稿しました");
                    jSONObject.put("feed_post_id", optJSONObject2.opt("id"));
                    jSONObject.put("created_at", optJSONObject2.optString("created_at", ""));
                    jSONArray2.put(jSONObject);
                }
            }
            return new JSONObject().put("ok", true).put("notifications", jSONArray2).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getFollowingTimeline(String str) {
        return postsResult(request2("GET", "/api/following_posts", qOpt("max_created_at", str), (Map<String, String>) null), "following_posts", false);
    }

    /**
     * 友達画面の一覧。「友達(声とも)」と「相互フォロー」の両方を1つの配列で返し、
     * 各行に is_friend / is_following / is_followed を付ける(画面側のタブで振り分ける)。
     * どこか1つが失敗しても残りは必ず出す。
     */
    private String getFriendsList(String str) {
        JSONArray users = new JSONArray();
        java.util.HashSet<Long> have = new java.util.HashSet<Long>();

        // ---- 1) 友達(声とも) ----
        //   公式 FriendFragment: POST api/v2/dive/relations の "friends"
        //   予備: POST api/dive/friends (generateFriendsListRequest)
        try {
            JSONArray fr = relationsArray("friends");
            if (fr.length() == 0) {
                HashMap<String, String> ff = new HashMap<String, String>();
                String at = authToken();
                if (at != null) ff.put("auth_token", at);
                ff.put("version", "android_" + APP_VERSION);
                Resp r = http("POST", BASE_URL + "/api/dive/friends", (Map<String, String>) null, ff);
                if (r.status == 404 || r.status >= 500 || r.status <= 0) {
                    r = http("POST", BASE_URL2 + "/api/dive/friends", (Map<String, String>) null, ff);
                }
                if (r.status == 200 && r.body != null) fr = normalizeUserList(r.body);
                dbgLog(nowStr() + "  [FRIENDS] 予備 api/dive/friends HTTP " + r.status + " n=" + fr.length()
                        + (r.status != 200 && r.body != null ? " " + truncate(redactLog(r.body.toString()), 140) : ""));
            }
            for (int i = 0; i < fr.length(); i++) {
                JSONObject u = fr.optJSONObject(i);
                if (u == null) continue;
                long id = u.optLong("user_id", 0);
                if (id == 0 || !have.add(Long.valueOf(id))) continue;
                u.put("is_friend", true);
                users.put(u);
            }
            dbgLog(nowStr() + "  [FRIENDS] 友達=" + users.length());
        } catch (Throwable ig) {
            dbgLog(nowStr() + "  [FRIENDS] 友達の取得で例外: " + ig);
        }

        // ---- 2) 相互フォロー ----
        java.util.Set<Long> fe = null, fol = null;
        try {
            ensureRelationSets();
            synchronized (this) { fe = myFolloweeIds; fol = myFollowerIds; }
        } catch (Throwable ig) {
        }
        try {
            if (fe != null && fol != null) {
                // すでに載っている友達にフォロー関係の印を付ける
                for (int i = 0; i < users.length(); i++) {
                    JSONObject u = users.optJSONObject(i);
                    if (u == null) continue;
                    Long id = Long.valueOf(u.optLong("user_id", 0));
                    u.put("is_following", fe.contains(id));
                    u.put("is_followed", fol.contains(id));
                }
                // まだ載っていない相互フォローの人を足す
                java.util.HashSet<Long> both = new java.util.HashSet<Long>(fe);
                both.retainAll(fol);
                both.removeAll(have);
                dbgLog(nowStr() + "  [FRIENDS] フォロー中=" + fe.size() + " フォロワー=" + fol.size() + " 相互(未掲載)=" + both.size());
                if (!both.isEmpty()) {
                    long me = userId();
                    for (int page = 1; page <= 5 && !both.isEmpty(); page++) {
                        Resp rf = httpApi2("GET", "/api/v2/users/" + me + "/followees", q1("page", String.valueOf(page)), (Map<String, String>) null);
                        if (rf == null || rf.status != 200 || rf.body == null) break;
                        JSONArray arr = normalizeUserList(rf.body);
                        if (arr == null || arr.length() == 0) break;
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject u = arr.optJSONObject(i);
                            if (u == null) continue;
                            Long id = Long.valueOf(u.optLong("user_id", 0));
                            if (!both.remove(id)) continue;
                            if (!have.add(id)) continue;
                            u.put("is_following", true);
                            u.put("is_followed", true);
                            u.put("requested", false);
                            u.put("is_friend", false);
                            users.put(u);
                        }
                        if (arr.length() < 20) break;
                    }
                }
            }
        } catch (Throwable ig) {
            dbgLog(nowStr() + "  [FRIENDS] 相互の取得で例外: " + ig);
        }

        // ---- 3) ここまでで相互が1人も出ていなければ、自前計算で入れ直す ----
        int nMutual = 0;
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.optJSONObject(i);
            if (u != null && u.optBoolean("is_following", false) && u.optBoolean("is_followed", false)) nMutual++;
        }
        if (nMutual == 0) {
            try {
                JSONArray mu = computeMutualFollows("1");
                for (int i = 0; i < mu.length(); i++) {
                    JSONObject u = mu.optJSONObject(i);
                    if (u == null) continue;
                    long id = u.optLong("user_id", 0);
                    JSONObject exist = null;
                    for (int k = 0; k < users.length(); k++) {
                        JSONObject e2 = users.optJSONObject(k);
                        if (e2 != null && e2.optLong("user_id", -1) == id) { exist = e2; break; }
                    }
                    if (exist != null) {
                        exist.put("is_following", true);
                        exist.put("is_followed", true);
                    } else {
                        u.put("is_following", true);
                        u.put("is_followed", true);
                        u.put("is_friend", false);
                        users.put(u);
                        have.add(Long.valueOf(id));
                    }
                    nMutual++;
                }
                dbgLog(nowStr() + "  [FRIENDS] 自前計算の相互=" + mu.length());
            } catch (Throwable ig) {
            }
        }

        // ---- 4) 友達がどこからも取れなかったときの保険 ----
        int nFriend = 0;
        for (int i = 0; i < users.length(); i++) {
            JSONObject u = users.optJSONObject(i);
            if (u != null && u.optBoolean("is_friend", false)) nFriend++;
        }
        if (nFriend == 0 && nMutual > 0) {
            // koetomo では「申請を送り合った状態」= 友達 なので、相互フォローを友達として出す
            try {
                for (int i = 0; i < users.length(); i++) {
                    JSONObject u = users.optJSONObject(i);
                    if (u == null) continue;
                    if (u.optBoolean("is_following", false) && u.optBoolean("is_followed", false)) { u.put("is_friend", true); nFriend++; }
                }
            } catch (Throwable ig) {
            }
            dbgLog(nowStr() + "  [FRIENDS] 友達一覧が取れないため相互を友達として表示 " + nFriend + "人");
        }

        // ---- 5) 相互を先に並べる ----
        try {
            JSONArray sorted = new JSONArray();
            for (int pass = 0; pass < 2; pass++) {
                for (int i = 0; i < users.length(); i++) {
                    JSONObject u = users.optJSONObject(i);
                    if (u == null) continue;
                    boolean mutual = u.optBoolean("is_following", false) && u.optBoolean("is_followed", false);
                    if ((pass == 0) == mutual) sorted.put(u);
                }
            }
            if (sorted.length() == users.length()) users = sorted;
        } catch (Throwable ig) {
        }

        dbgLog(nowStr() + "  [FRIENDS] 合計=" + users.length() + " 友達=" + nFriend + " 相互=" + nMutual);
        try {
            return new JSONObject().put("ok", true).put("users", users).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }
    private JSONArray computeMutualFollows(String page) {
        JSONArray out = new JSONArray();
        try {
            long me = userId();
            if (me == 0) return out;
            java.util.Map<Long, JSONObject> followees = new java.util.HashMap<Long, JSONObject>();
            for (String sub : new String[]{"followees", "followers"}) {
                boolean isFollowee = sub.equals("followees");
                for (int pg = 1; pg <= 3; pg++) {
                    HashMap<String, String> q = new HashMap<String, String>();
                    q.put("page", String.valueOf(pg));
                    Resp resp = httpApi2("GET", "/api/v2/users/" + me + "/" + sub, q, (Map<String, String>) null);
                    if (resp.status != 200 || resp.body == null) break;
                    JSONArray arr = normalizeUserList(resp.body);
                    if (arr.length() == 0) break;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject u = arr.optJSONObject(i);
                        if (u == null) continue;
                        long uid = u.optLong("user_id", 0);
                        if (uid == 0) continue;
                        if (isFollowee) {
                            followees.put(Long.valueOf(uid), u);
                        } else if (followees.containsKey(Long.valueOf(uid))) {
                            out.put(u); // フォロワーかつフォロー中 = 相互
                        }
                    }
                    if (arr.length() < 20) break;
                }
            }
        } catch (Exception ignore) {}
        return out;
    }

    private Map<String, String> likeFields2(String page) {
        HashMap<String, String> m = new HashMap<String, String>();
        m.put("page", page);
        m.put("version", "android_" + APP_VERSION);
        String t = authToken();
        if (t != null) m.put("auth_token", t);
        return m;
    }

    private String getGiftHistory() {
        JSONArray jSONArray = null;
        HashMap hashMap = new HashMap();
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        Resp http = http("GET", "https://api2.meetscom.com/api/receive_tippings", hashMap, (Map<String, String>) null);
        try {
            if (http.status != 200 || http.body == null) {
                return new JSONObject().put("ok", false).put("status", http.status).put("raw", http.body != null ? truncate(http.body.toString(), 400) : "").toString();
            }
            JSONObject optJSONObject = http.body.optJSONObject("data");
            if (optJSONObject != null) {
                jSONArray = optJSONObject.optJSONArray("receive_tippings");
                if (jSONArray == null) {
                    jSONArray = optJSONObject.optJSONArray("tippings");
                }
                if (jSONArray == null) {
                    jSONArray = optJSONObject.optJSONArray("data");
                }
            }
            if (jSONArray == null) {
                jSONArray = http.body.optJSONArray("receive_tippings");
            }
            if (jSONArray == null) {
                jSONArray = http.body.optJSONArray("data");
            }
            if (jSONArray == null) {
                jSONArray = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("gifts", jSONArray).put("raw", truncate(http.body.toString(), 500)).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getItemPacks() {
        JSONArray jSONArray = null;
        try {
            Resp request = request("GET", "/api/item_packs", (Map<String, String>) null, (Map<String, String>) null);
            if (request.status != 200 || request.body == null) {
                return new JSONObject().put("ok", false).put("status", request.status).toString();
            }
            JSONObject optJSONObject = request.body.optJSONObject("data");
            if (optJSONObject != null) {
                jSONArray = firstArray(optJSONObject, "item_packs", "items", "data");
            }
            if (jSONArray == null) {
                jSONArray = firstArray(request.body, "item_packs", "items", "data");
            }
            if (jSONArray == null) {
                jSONArray = new JSONArray();
            }
            JSONArray jSONArray2 = new JSONArray();
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject2 = jSONArray.optJSONObject(i);
                if (optJSONObject2 != null) {
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("id", optJSONObject2.optInt("item_id", optJSONObject2.optInt("id", 0)));
                    jSONObject.put("name", firstNonEmpty(optJSONObject2.optString("name", ""), optJSONObject2.optString("item_name", "")));
                    jSONObject.put("coin", optJSONObject2.optInt("coin_amount", optJSONObject2.optInt("coin", optJSONObject2.optInt("point", 0))));
                    jSONObject.put("icon_url", iconUrl(firstNonEmpty(optJSONObject2.optString("image_file_path", ""), optJSONObject2.optString("thumbnail_file_path", ""), optJSONObject2.optString("icon_file_path", ""), optJSONObject2.optString("file_path", ""))));
                    jSONArray2.put(jSONObject);
                }
            }
            return new JSONObject().put("ok", true).put("items", jSONArray2).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getLivePulse() {
        int total = 0;
        int speakers = 0;
        int listeners = 0;
        ArrayList<JSONObject> topRooms = new ArrayList<JSONObject>();
        boolean capped = false;
        try {
            // 実数を出すため複数ページを集計(20未満のページで打ち切り)。名前解決はしない(軽量)。
            // 「60+」のような上限表示をやめて実数を出すため、上限は暴走防止の安全弁として高めに設定(通常はここに到達しない)。
            // 高速化: 枠の総数は1ページ目の metadata.total_count から取れるので、ページ巡回は2ページまで
            // (発言者/聞き専の人数は最初の40枠分。それ以上ある場合は「+」表示)
            int maxPages = 2;
            int metaTotal = -1;
            for (int page = 1; page <= maxPages; page++) {
                Resp r = request("GET", "/api/rooms", q2("page", String.valueOf(page), "order", "1"), (Map<String, String>) null);
                if (r.status != 200 || r.body == null) break;
                if (page == 1) { try { JSONObject md = r.body.optJSONObject("metadata"); if (md != null && md.has("total_count")) metaTotal = md.optInt("total_count", -1); } catch (Exception ignored) {} }
                Object d = r.body.opt("data");
                JSONArray arr = null;
                if (d instanceof JSONArray) {
                    arr = (JSONArray) d;
                } else if (d instanceof JSONObject) {
                    arr = ((JSONObject) d).optJSONArray("rooms");
                    if (arr == null) arr = ((JSONObject) d).optJSONArray("talk_rooms");
                }
                if (arr == null) break;
                int n = arr.length();
                total += n;
                for (int i = 0; i < n; i++) {
                    JSONObject ro = arr.optJSONObject(i);
                    if (ro == null) continue;
                    JSONArray sp = ro.optJSONArray("speakers");
                    JSONArray ls = ro.optJSONArray("listeners");
                    int sc = sp == null ? 0 : sp.length();
                    int lc = ls == null ? 0 : ls.length();
                    speakers += sc;
                    listeners += lc;
                    JSONObject t = new JSONObject().put("title", ro.optString("description", "")).put("owner_user_id", ro.optLong("owner", ro.optLong("owner_user_id"))).put("speaker_count", sc).put("listener_count", lc).put("member_count", sc + lc);
                    // 上位5件だけ保持(参加者数順)
                    int pos = topRooms.size();
                    for (int k = 0; k < topRooms.size(); k++) {
                        if (sc + lc > topRooms.get(k).optInt("member_count")) { pos = k; break; }
                    }
                    if (pos < 5) {
                        topRooms.add(pos, t);
                        if (topRooms.size() > 5) topRooms.remove(topRooms.size() - 1);
                    }
                }
                if (n < 20) break;
                if (page == maxPages) capped = true;
            }
            JSONArray topArr = new JSONArray();
            for (JSONObject t : topRooms) topArr.put(t);
            if (metaTotal >= total) total = metaTotal;
            return new JSONObject().put("ok", true).put("open_rooms", total).put("online_receivers", 0).put("speakers", speakers).put("listeners", listeners).put("top_rooms", topArr).put("capped", capped).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getMessages(String str, String str2) {
        HashMap hashMap = new HashMap();
        hashMap.put("chat_id", str);
        hashMap.put("target_id", str2);
        hashMap.put("page", "1");
        Resp request = request("GET", "/api/messages", hashMap, (Map<String, String>) null);
        String str3 = "[chat_id=" + str + " target_id=" + str2 + " HTTP " + request.status + "] " + (request.body != null ? truncate(request.body.toString(), 450) : "(なし)");
        try {
            if (request.status != 200 || request.body == null) {
                return new JSONObject().put("ok", false).put("status", request.status).put("raw", str3).toString();
            }
            JSONArray optJSONArray = request.body.optJSONArray("messages");
            if (optJSONArray == null) {
                JSONObject optJSONObject = request.body.optJSONObject("data");
                JSONArray firstArray = optJSONObject != null ? firstArray(optJSONObject, "messages", "chat_messages", "message", "data") : optJSONArray;
                Object opt = request.body.opt("data");
                optJSONArray = (firstArray != null || !(opt instanceof JSONArray)) ? firstArray : (JSONArray) opt;
            }
            JSONArray jSONArray = optJSONArray == null ? new JSONArray() : optJSONArray;
            JSONArray jSONArray2 = new JSONArray();
            // 画像/音声メッセージは binary_file_path（非公開バケット）。表示には署名付きURLが要る。
            JSONObject s3cfg = null, s3creds = null;
            for (int length = jSONArray.length() - 1; length >= 0; length--) {
                JSONObject optJSONObject2 = jSONArray.optJSONObject(length);
                if (optJSONObject2 != null) {
                    int mtype = optJSONObject2.optInt("message_type", 1);
                    String binPath = firstStr(optJSONObject2, "binary_file_path", "binaryFilePath");
                    String imgPath = firstStr(optJSONObject2, "image_file_path", "imageFilePath", "image_url", "imageUrl", "image");
                    String imgUrl = "", voiceUrl = "";
                    if (binPath.length() > 0 && (mtype == 2 || mtype == 3)) {
                        try {
                            if (s3cfg == null) { s3cfg = imageS3Config(); s3creds = cognitoCredentials(s3cfg); }
                            String prefix = s3cfg.optString("path", "");
                            String key = binPath;
                            if (!key.contains("/") && prefix != null && prefix.length() > 0) {
                                key = prefix.replaceAll("^/+", "").replaceAll("/+$", "") + "/" + binPath;
                            }
                            String signed = s3PresignGet(s3cfg, s3creds, key, 900);
                            if (mtype == 3) voiceUrl = signed; else imgUrl = signed;
                        } catch (Exception e) {
                            dbgLog(nowStr() + "  [CHAT] 添付URL生成失敗 " + e);
                        }
                    }
                    if (imgUrl.length() == 0 && imgPath.length() > 0) imgUrl = iconUrl(imgPath);
                    jSONArray2.put(new JSONObject().put("id", optJSONObject2.opt("id")).put("user_id", optJSONObject2.optLong("user_id", optJSONObject2.optLong("userId"))).put("text", firstStr(optJSONObject2, "text_message", "text", "message", "content", "body")).put("image_url", imgUrl).put("voice_url", voiceUrl).put("message_type", mtype).put("play_time", optJSONObject2.optInt("play_time", 0)).put("is_read", optJSONObject2.optInt("is_read", 0) == 1 || optJSONObject2.optBoolean("is_read", false)).put("sent_at", firstStr(optJSONObject2, "sent_at", "sentAt", "created_at", "createdAt")));
                }
            }
            dbgLog(nowStr() + "  [CHAT] messages=" + jSONArray2.length() + " chat=" + str + " (添付あり=" + countAttach(jSONArray2) + ")");
            return new JSONObject().put("ok", true).put("messages", jSONArray2).put("my_user_id", userId()).put("raw", str3).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getMissedCalls() {
        return userHistoryResult(request("GET", "/api/v2/missed_calls", q1("page", "1"), (Map<String, String>) null), "missed_calls", "data");
    }

    private String getModerationSettings() {
        try {
            return new JSONObject().put("ok", true).put("settings", new JSONObject().put("auto_approve", this.prefs.getBoolean("mod_auto_approve", false)).put("auto_reject", this.prefs.getBoolean("mod_auto_reject", false)).put("auto_raise_hand", this.prefs.getBoolean("mod_auto_raise_hand", false))).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getMyCallRecords() {
        HashMap hashMap = new HashMap();
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        Resp http = http("GET", "https://api.meetscom.com/api/call_records", hashMap, (Map<String, String>) null);
        if (http.status == 404 || http.status >= 500) {
            http = http("GET", "https://api2.meetscom.com/api/call_records", hashMap, (Map<String, String>) null);
        }
        return parseRecordsBody(http);
    }

    private String getMyCommunities() {
        return communitiesResult(request("GET", "/api/communities/participating", cQ("count", "20", "order_condition", "1"), (Map<String, String>) null));
    }

    private String getMyProfile() {
        long userId = userId();
        return userId == 0 ? jsonErr("user_idが取得できていません。ログアウトして再ログインしてください。") : buildProfile(userId, false);
    }
    // 通知1件から相手ユーザーIDを解決する。no_user_info=true の通常通知では "user" オブジェクトが
    // 省略されることがあり、さらに返信/コメント系の通知は liked_user_id 以外のキー(actor/replier等)で
    // 相手を持つ場合があるため、複数のネストオブジェクト名・フラットキー名を総当りで見る。
    // (「通知から返信した人のプロフィールをタップできない」の主因: uid=0 のまま viewProfile が呼べていなかった)
    private static final String[] NOTIF_USER_OBJ_KEYS = {"user", "liked_user", "actor", "commenter", "comment_user", "replier", "from_user", "sender", "target_user"};
    private static final String[] NOTIF_USER_ID_KEYS = {"liked_user_id", "user_id", "from_user_id", "sender_id", "actor_id", "commenter_id", "comment_user_id", "replier_id", "owner_user_id", "target_user_id"};
    private long resolveNotifUserId(JSONObject n) {
        if (n == null) return 0;
        for (String key : NOTIF_USER_OBJ_KEYS) {
            JSONObject u = n.optJSONObject(key);
            if (u != null) {
                long uid = u.optLong("user_id", u.optLong("id", 0));
                if (uid != 0) return uid;
            }
        }
        for (String key : NOTIF_USER_ID_KEYS) {
            long uid = n.optLong(key, 0);
            if (uid != 0) return uid;
        }
        return 0;
    }
    private JSONObject resolveNotifUserObj(JSONObject n) {
        if (n == null) return null;
        for (String key : NOTIF_USER_OBJ_KEYS) {
            JSONObject u = n.optJSONObject(key);
            if (u != null) return u;
        }
        return null;
    }

    // ===== 公式アプリにあって未実装だった機能 =====

    /**
     * フォロワーを外す(相手に自分をフォローさせない)。
     * 公式 TimelineApiServer1.deleteFollower: DELETE api/relation/new_follow/follower?target_id=…
     * ブロックせずに一方的なフォローだけ切りたいときに使う。
     */
    private String removeFollower(String targetId) {
        if (targetId == null || targetId.length() == 0) return jsonErr("target_id不明");
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("target_id", targetId);
        q.put("version", "android_" + APP_VERSION);
        String at = authToken();
        if (at != null) q.put("auth_token", at);
        Resp r = http("DELETE", BASE_URL + "/api/relation/new_follow/follower", q, (Map<String, String>) null);
        if (r.status == 404 || r.status >= 500) {
            r = http("DELETE", BASE_URL2 + "/api/relation/new_follow/follower", q, (Map<String, String>) null);
        }
        dbgLog(nowStr() + "  [FOLLOWER-DEL] target=" + targetId + " -> " + r.status);
        clearRelationSets();
        clearRelationsCache();
        return okResultStatus(r);
    }

    /**
     * 通話(ダイブ)の履歴。
     * 公式 generateDiveTalkHistoryRequest: GET api2 /api/v2/talk_histories?page=
     */
    private String getDiveTalkHistories(String page) {
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("page", (page == null || page.length() == 0) ? "1" : page);
        Resp r = httpApi2("GET", "/api/v2/talk_histories", q, (Map<String, String>) null);
        dbgLog(nowStr() + "  [TALKHIST] v2/talk_histories -> " + r.status);
        return okList(r, "histories", "talk_histories", "histories", "data");
    }

    /**
     * 運営からのお知らせ。
     * 公式 generateInfoRequest: POST api/system/info  FORM: par, page
     * par は種別(0=すべて)。公式のお知らせ画面がこれを使っている。
     */
    private String getSystemInfo(String par, String page) {
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("par", (par == null || par.length() == 0) ? "0" : par);
        f.put("page", (page == null || page.length() == 0) ? "1" : page);
        Resp r = request("POST", "/api/system/info", (Map<String, String>) null, f);
        dbgLog(nowStr() + "  [SYSINFO] api/system/info -> " + r.status);
        return okList(r, "info", "info", "infos", "system_info", "notices", "data");
    }

    /**
     * サーバー側のログアウト。
     * 公式 generateLogoutRequest: POST api/account/logout（フォームは auth_token / version のみ）
     * 端末側で消すだけだと、サーバーには端末のトークンが残ったままになる。
     */
    private String serverLogout() {
        Resp r = request("POST", "/api/account/logout", (Map<String, String>) null, new HashMap<String, String>());
        dbgLog(nowStr() + "  [LOGOUT] api/account/logout -> " + r.status);
        return okResultStatus(r);
    }

    /**
     * コミュニティのコメントをブックマーク。
     * 公式 CommunityApi.bookmarkComment / deleteBookmarkedComment:
     *   POST|DELETE /api/communities/{id}/posts/{post_id}/comments/{comment_id}/bookmark
     */
    private String bookmarkCommunityComment(String communityId, String postId, String commentId, boolean add) {
        if (communityId == null || postId == null || commentId == null
                || communityId.length() == 0 || postId.length() == 0 || commentId.length() == 0) {
            return jsonErr("パラメータ不明");
        }
        String path = "/api/communities/" + communityId + "/posts/" + postId + "/comments/" + commentId + "/bookmark";
        return okResult(request(add ? "POST" : "DELETE", path, (Map<String, String>) null, add ? new HashMap<String, String>() : null));
    }

    /**
     * 試聴(のぞき見)した時間をサーバーに送る。
     * 公式 TalkRoomApi.sendTrialTime: PUT /api/trial_listenings/{id}?duration=秒
     */
    private String sendTrialTime(String listeningId, String durationSec) {
        if (listeningId == null || listeningId.length() == 0) return jsonErr("trial_listening_id不明");
        return okResult(request("PUT", "/api/trial_listenings/" + listeningId,
                q1("duration", (durationSec == null || durationSec.length() == 0) ? "0" : durationSec), (Map<String, String>) null));
    }

    private String getNotifications(String kind) {
        return getNotifications(kind, "1");
    }

    // ===== 公式にあった残りのAPI(第2弾) =====

    /**
     * 通話(SkyWay)の接続ログ。公式 generateSkywayConnectionRequest:
     *   POST api/skyway/connections  FORM: ConnectionId, CallerId, CalleeId, TargetId, token
     * 通話品質の計測に使う。失敗しても通話自体には影響しない。
     */
    private String skywayConnectLog(String connectionId, String callerId, String calleeId, String targetId, String token) {
        if (connectionId == null || connectionId.length() == 0) return jsonErr("connection_id不明");
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("ConnectionId", connectionId);
        f.put("CallerId", callerId == null ? "0" : callerId);
        f.put("CalleeId", calleeId == null ? "0" : calleeId);
        f.put("TargetId", targetId == null ? "0" : targetId);
        f.put("token", token == null ? "" : token);
        Resp r = request("POST", "/api/skyway/connections", (Map<String, String>) null, f);
        dbgLog(nowStr() + "  [SKYWAY] connect log -> " + r.status);
        return okResultStatus(r);
    }

    /**
     * 通話(SkyWay)の切断ログ。公式 generateSkywayDisonnectionRequest:
     *   POST api/skyway/disconnections  FORM: ConnectionId, CallDuration
     */
    private String skywayDisconnectLog(String connectionId, String durationSec) {
        if (connectionId == null || connectionId.length() == 0) return jsonErr("connection_id不明");
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("ConnectionId", connectionId);
        f.put("CallDuration", (durationSec == null || durationSec.length() == 0) ? "0" : durationSec);
        Resp r = request("POST", "/api/skyway/disconnections", (Map<String, String>) null, f);
        dbgLog(nowStr() + "  [SKYWAY] disconnect log -> " + r.status);
        return okResultStatus(r);
    }

    /**
     * 中断した購入トークンの確認。公式 generateAbortedPurchaseTokenRequest:
     *   GET api2 /api/purchase_token
     * 前回の購入が途中で止まっていないかをサーバーに問い合わせる(読み取りのみ)。
     */
    private String getAbortedPurchaseToken() {
        Resp r = httpApi2("GET", "/api/purchase_token", (Map<String, String>) null, (Map<String, String>) null);
        dbgLog(nowStr() + "  [PURCHASE] aborted token -> " + r.status);
        if (r.status != 200 || r.body == null) return jsonStatus(r);
        try { return new JSONObject().put("ok", true).put("data", r.body).toString(); }
        catch (Exception e) { return errJson(e); }
    }

    /**
     * 購入トークンをサーバーに登録する。公式 generateAddPurchaseTokenRequest:
     *   POST api2 /api/purchase_token  FORM: item_id, purchase_token
     * ※Google Play が発行した購入トークンが必要。トークンは呼び出し側(Play課金の完了)から渡す。
     */
    private String addPurchaseToken(String itemId, String purchaseToken) {
        if (itemId == null || itemId.length() == 0 || purchaseToken == null || purchaseToken.length() == 0) {
            return jsonErr("item_id / purchase_token 不明");
        }
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("item_id", itemId);
        f.put("purchase_token", purchaseToken);
        Resp r = httpApi2("POST", "/api/purchase_token", (Map<String, String>) null, f);
        dbgLog(nowStr() + "  [PURCHASE] add token item=" + itemId + " -> " + r.status);
        return okResultStatus(r);
    }

    /**
     * サブスクの登録。公式 SubscriptionApi.subscribe:
     *   POST /api/subscription  JSON {"receipt":…, "signature":…}
     * ※Google Play の receipt と signature が必要(Play課金の完了から渡す)。
     */
    private String subscribe(String receipt, String signature) {
        if (receipt == null || receipt.length() == 0) return jsonErr("receipt不明");
        try {
            JSONObject body = new JSONObject();
            body.put("receipt", receipt);
            body.put("signature", signature == null ? "" : signature);
            Resp r = httpJson("POST", BASE_URL + "/api/subscription", body);
            if (r.status == 404 || r.status >= 500) r = httpJson("POST", BASE_URL2 + "/api/subscription", body);
            dbgLog(nowStr() + "  [SUBSCRIBE] -> " + r.status);
            return okResultStatus(r);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /**
     * メールで新規登録。公式 generateSignupRequest:
     *   POST api/account/signup  FORM: email, password, name, sex, birthday, device_uid, version …
     * captcha トークン(etat2/vt2/gt2)は要求されたときだけ付ける。
     */
    private String signup(String email, String password, String name, String sex, String birthday, String deviceUid) {
        if (email == null || email.length() == 0 || password == null || password.length() == 0) {
            return jsonErr("メールアドレスとパスワードを入力してください");
        }
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("email", email);
        f.put("password", password);
        f.put("name", name == null ? "" : name);
        f.put("sex", (sex == null || sex.length() == 0) ? "0" : sex);
        f.put("birthday", birthday == null ? "" : birthday);
        f.put("device_uid", deviceUid == null ? deviceUid() : deviceUid);
        f.put("birthday_input_error", "");
        Resp r = request("POST", "/api/account/signup", (Map<String, String>) null, f);
        dbgLog(nowStr() + "  [SIGNUP] -> " + r.status);
        return okResultStatus(r);
    }

    /**
     * 新規登録メールの認証。公式 generateSignupAuthRequest:
     *   POST api/account/signup_auth  FORM: token
     */
    private String signupAuth(String token) {
        if (token == null || token.length() == 0) return jsonErr("token不明");
        Resp r = request("POST", "/api/account/signup_auth", (Map<String, String>) null, q1("token", token));
        dbgLog(nowStr() + "  [SIGNUP-AUTH] -> " + r.status);
        return okResultStatus(r);
    }

    private String getNotifications(String kind, String page) {
        boolean important = "important".equals(kind);
        String path = important ? "/api/user_notifications" : "/api/regular_notifications";
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("page", (page == null || page.length() == 0) ? "1" : page);
        if (!important) {
            fields.put("no_user_info", "true");
        }
        fields.put("version", "android_" + APP_VERSION);
        String authToken = authToken();
        if (authToken != null) {
            fields.put("auth_token", authToken);
        }
        Resp resp = http("GET", "https://api2.meetscom.com" + path, fields, (Map<String, String>) null);
        String raw = "[" + path + "@api2 HTTP " + resp.status + "] " + (resp.body != null ? truncate(resp.body.toString(), 300) : "(なし)");
        try {
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp.status).put("raw", raw).toString();
            }
            JSONArray items = resp.body.optJSONArray("notice_relations");
            if (items == null) {
                items = resp.body.optJSONArray("notifications");
            }
            if (items == null) {
                items = resp.body.optJSONArray("activities");
            }
            Object data = resp.body.opt("data");
            if (items == null && data instanceof JSONArray) {
                items = (JSONArray) data;
            }
            if (items == null && data instanceof JSONObject) {
                items = firstArray((JSONObject) data, "notice_relations", "notifications", "activities");
            }
            if (items == null) {
                items = new JSONArray();
            }

            JSONArray idsArr = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject n = items.optJSONObject(i);
                if (n == null) {
                    continue;
                }
                long uid = resolveNotifUserId(n);
                if (uid != 0) {
                    idsArr.put(new JSONObject().put("user_id", uid));
                }
            }
            resolveNames(idsArr, "user_id");

            JSONArray result = new JSONArray();
            for (int i = 0; i < items.length(); i++) {
                JSONObject n = items.optJSONObject(i);
                if (n == null) {
                    continue;
                }
                JSONObject user = resolveNotifUserObj(n);
                long uid = resolveNotifUserId(n);
                String[] cached = uid != 0 ? this.nameCache.get(Long.valueOf(uid)) : null;

                String name = user != null ? user.optString("name", "") : "";
                if (name.length() == 0 && cached != null && cached[0] != null) {
                    name = cached[0];
                }
                if (name.length() == 0) {
                    name = firstStr(n, "user_name", "name");
                }

                String iconPath = user != null ? user.optString("profile_picture_file_path", "") : "";
                if (iconPath.length() == 0 && cached != null && cached[1] != null) {
                    iconPath = cached[1];
                }

                int type = n.optInt("notification_type", -1);
                type = n.optInt("message_type", type);
                String message = firstStr(n, "message", "text", "body", "description");
                if (message.length() == 0 && type >= 0) {
                    message = notifText(type);
                }
                if (message.length() == 0) {
                    message = firstStr(n, "title", "content");
                }

                JSONObject out = new JSONObject();
                out.put("name", name);
                out.put("message", message);
                out.put("type", String.valueOf(type));
                out.put("icon_url", iconUrl(iconPath));
                // 返信/いいね通知の対象投稿IDは feed_post_id ではなく target_id に入る(公式 NotificationHistoryItem.targetId)
                long feedPostId = n.optLong("feed_post_id", 0);
                if (feedPostId == 0) feedPostId = n.optLong("target_id", 0);
                if (feedPostId == 0) feedPostId = n.optLong("post_id", 0);
                if (feedPostId == 0) feedPostId = n.optLong("feed_post_identification", 0);
                out.put("feed_post_id", feedPostId);
                out.put("target_id", n.optLong("target_id", 0));
                long cid = n.optLong("community_id", 0);
                if (cid == 0) { JSONObject co = n.optJSONObject("community"); if (co != null) cid = co.optLong("id", 0); }
                if (cid == 0) cid = deepFindLong(n, "community_id");
                out.put("community_id", cid);
                out.put("community_name", n.optString("community_name", ""));
                out.put("community_post_id", n.optLong("community_post_id", 0));
                out.put("community_talk_room_id", n.optLong("community_talk_room_id", 0));
                out.put("room_id", n.optString("room_id", ""));
                if (i < 3) dbgLog(nowStr() + "  [NOTIF] type=" + type + " keys=" + topKeys(n));
                out.put("chat_id", n.optLong("chat_id", 0));
                out.put("user_id", uid);
                out.put("created_at", firstStr(n, "created_at", "createdAt"));
                result.put(out);
            }
            return new JSONObject().put("ok", true).put("notifications", result).put("raw", raw).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getPointHistory() {
        return historyResult(request("GET", "/api/point_histories", q1("page", "1"), (Map<String, String>) null), "point_histories", "histories", "data");
    }

    private String getRecordComments(String str) {
        JSONArray jSONArray = null;
        HashMap hashMap = new HashMap();
        hashMap.put("page", "1");
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        Resp http = http("GET", "https://api2.meetscom.com/api/call_records/" + str + "/comments", hashMap, (Map<String, String>) null);
        if (http.status == 404 || http.status >= 500) {
            http = http("GET", "https://api.meetscom.com/api/call_records/" + str + "/comments", hashMap, (Map<String, String>) null);
        }
        try {
            if (http.status != 200 || http.body == null) {
                return new JSONObject().put("ok", false).put("status", http.status).put("raw", http.body != null ? truncate(http.body.toString(), 300) : "").toString();
            }
            JSONObject optJSONObject = http.body.optJSONObject("data");
            if (optJSONObject != null) {
                jSONArray = optJSONObject.optJSONArray("comments");
                if (jSONArray == null) {
                    jSONArray = optJSONObject.optJSONArray("call_record_comments");
                }
                if (jSONArray == null) {
                    jSONArray = optJSONObject.optJSONArray("data");
                }
            }
            if (jSONArray == null) {
                jSONArray = http.body.optJSONArray("comments");
            }
            if (jSONArray == null) {
                jSONArray = http.body.optJSONArray("data");
            }
            JSONArray jSONArray2 = jSONArray == null ? new JSONArray() : jSONArray;
            JSONArray jSONArray3 = new JSONArray();
            for (int i = 0; i < jSONArray2.length(); i++) {
                JSONObject optJSONObject2 = jSONArray2.optJSONObject(i);
                if (optJSONObject2 != null) {
                    JSONObject optJSONObject3 = optJSONObject2.optJSONObject("user");
                    long optLong = optJSONObject2.optLong("user_id", 0);
                    if (optLong == 0 && optJSONObject3 != null) {
                        optLong = optJSONObject3.optLong("id", 0);
                    }
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("user_id", optLong);
                    jSONObject.put("text", firstNonEmpty(optJSONObject2.optString("text", ""), optJSONObject2.optString("comment", "")));
                    jSONObject.put("created_at", optJSONObject2.optString("created_at", ""));
                    jSONArray3.put(jSONObject);
                }
            }
            resolveNames(jSONArray3, "user_id");
            return new JSONObject().put("ok", true).put("comments", jSONArray3).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getRoomComments(String str) {
        JSONArray jSONArray = null;
        HashMap hashMap = new HashMap();
        hashMap.put("room_id", str);
        Resp request = request2("GET", "/api/room_comments", hashMap, (Map<String, String>) null);
        try {
            if (request.status == 404) {
                // 404「対象のデータが存在しません」= コメントがまだ無いだけ。エラー扱いせず空で返す(通話中に毎回ポーリングされるため)
                return new JSONObject().put("ok", true).put("comments", new JSONArray()).toString();
            }
            if (request.status != 200 || request.body == null) {
                return new JSONObject().put("ok", false).put("status", request.status).toString();
            }
            JSONObject optJSONObject = request.body.optJSONObject("data");
            if (optJSONObject != null) {
                jSONArray = optJSONObject.optJSONArray("room_comments");
                if (jSONArray == null) {
                    jSONArray = optJSONObject.optJSONArray("comments");
                }
                if (jSONArray == null) {
                    jSONArray = optJSONObject.optJSONArray("data");
                }
            }
            if (jSONArray == null) {
                jSONArray = request.body.optJSONArray("room_comments");
            }
            if (jSONArray == null) {
                jSONArray = request.body.optJSONArray("comments");
            }
            JSONArray jSONArray2 = jSONArray == null ? new JSONArray() : jSONArray;
            JSONArray jSONArray3 = new JSONArray();
            for (int i = 0; i < jSONArray2.length(); i++) {
                JSONObject optJSONObject2 = jSONArray2.optJSONObject(i);
                if (optJSONObject2 != null) {
                    JSONObject optJSONObject3 = optJSONObject2.optJSONObject("user");
                    long optLong = optJSONObject2.optLong("user_id", 0);
                    if (optLong == 0 && optJSONObject3 != null) {
                        optLong = optJSONObject3.optLong("id", 0);
                    }
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("id", optJSONObject2.opt("id"));
                    jSONObject.put("user_id", optLong);
                    jSONObject.put("name", optJSONObject3 != null ? firstNonEmpty(optJSONObject3.optString("nickname", ""), optJSONObject3.optString("name", "")) : "");
                    jSONObject.put("text", firstNonEmpty(optJSONObject2.optString("comment", ""), optJSONObject2.optString("text", ""), optJSONObject2.optString("message", "")));
                    jSONObject.put("created_at", optJSONObject2.optString("created_at", ""));
                    // 規制対象コメント(公式 RoomComment.is_explicit) / ペナルティ期間中ユーザー
                    jSONObject.put("is_explicit", truthy(optJSONObject2.opt("is_explicit")) ? 1 : 0);
                    Object pen = optJSONObject2.opt("is_in_penalty_period");
                    if (pen == null && optJSONObject3 != null) pen = optJSONObject3.opt("is_in_penalty_period");
                    jSONObject.put("is_in_penalty_period", truthy(pen));
                    jSONArray3.put(jSONObject);
                }
            }
            JSONArray jSONArray4 = new JSONArray();
            for (int i2 = 0; i2 < jSONArray3.length(); i2++) {
                JSONObject optJSONObject4 = jSONArray3.optJSONObject(i2);
                if (optJSONObject4 != null && optJSONObject4.optString("name", "").length() == 0 && optJSONObject4.optLong("user_id", 0) > 0) {
                    jSONArray4.put(optJSONObject4);
                }
            }
            if (jSONArray4.length() > 0) {
                resolveNames(jSONArray4, "user_id");
            }
            return new JSONObject().put("ok", true).put("comments", jSONArray3).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getRoomHistory() throws org.json.JSONException {
        boolean z;
        long j;
        long j2;
        JSONArray loadRoomHistoryArr = loadRoomHistoryArr();
        JSONArray jSONArray = new JSONArray();
        for (int i = 0; i < loadRoomHistoryArr.length(); i++) {
            JSONObject optJSONObject = loadRoomHistoryArr.optJSONObject(i);
            if (optJSONObject != null && optJSONObject.optString("owner_name", "").length() == 0) {
                try {
                    j2 = Long.parseLong(optJSONObject.optString("owner_user_id", "0"));
                } catch (Exception e) {
                    j2 = 0;
                }
                if (j2 > 0) {
                    jSONArray.put(new JSONObject().put("user_id", j2));
                }
            }
        }
        if (jSONArray.length() > 0) {
            resolveNames(jSONArray, "user_id");
            int i2 = 0;
            boolean z2 = false;
            while (i2 < loadRoomHistoryArr.length()) {
                JSONObject optJSONObject2 = loadRoomHistoryArr.optJSONObject(i2);
                if (optJSONObject2 == null) {
                    z = z2;
                } else {
                    if (optJSONObject2.optString("owner_name", "").length() == 0) {
                        try {
                            j = Long.parseLong(optJSONObject2.optString("owner_user_id", "0"));
                        } catch (Exception e2) {
                            j = 0;
                        }
                        try {
                            String[] strArr = this.nameCache.get(Long.valueOf(j));
                            if (!(strArr == null || strArr[0] == null || strArr[0].length() <= 0)) {
                                optJSONObject2.put("owner_name", strArr[0]);
                                if (strArr[1] != null && strArr[1].length() > 0) {
                                    optJSONObject2.put("owner_icon", iconUrl(strArr[1]));
                                }
                                z = true;
                            }
                        } catch (Exception e3) {
                            return errJson(e3);
                        }
                    }
                    z = z2;
                }
                i2++;
                z2 = z;
            }
            if (z2) {
                try {
                    this.prefs.edit().putString("room_history", loadRoomHistoryArr.toString()).apply();
                } catch (Exception e4) {
                }
            }
        }
        return new JSONObject().put("ok", true).put("history", loadRoomHistoryArr).toString();
    }

    private Resp getSkywayToken(String str) {
        String ensureSkywayHost = ensureSkywayHost();
        HashMap hashMap = new HashMap();
        hashMap.put("channelName", str);
        hashMap.put("memberName", userId() + "_" + str);
        String authToken = authToken();
        if (authToken == null) {
            authToken = "";
        }
        hashMap.put("sessionToken", authToken);
        Resp r = http("POST", ensureSkywayHost + "/authenticate", (Map<String, String>) null, hashMap);
        if (r == null || r.status != 200) {
            dbgLog(nowStr() + "  [SKYWAY] authenticate ch=" + str + " -> " + (r == null ? -1 : r.status)
                    + (r != null && r.body != null ? " " + truncate(redactLog(r.body.toString()), 200) : ""));
        }
        return r;
    }

    private String getCheeringVoiceCall(String channel, String targetUserId) {
        if (channel == null || channel.length() == 0) {
            return jsonErr("channel不明");
        }
        try {
            Resp skywayResp = getSkywayToken(channel);
            if (skywayResp.status != 200) {
                return new JSONObject().put("ok", false).put("status", skywayResp.status)
                        .put("message", "通話サーバーへの認証に失敗しました").toString();
            }
            String skywayAuthToken = skywayTokenOf(skywayResp);
            if (skywayAuthToken.length() == 0) {
                return new JSONObject().put("ok", false).put("error", "AuthTokenが取得できませんでした(応答キーを診断ログに記録)").toString();
            }
            long targetUid = 0;
            try {
                if (targetUserId != null && targetUserId.length() > 0) {
                    targetUid = Long.parseLong(targetUserId.trim());
                }
            } catch (Exception e) {
            }
            JSONObject call = new JSONObject();
            call.put("auth_token", skywayAuthToken);
            call.put("channel", channel);
            call.put("member", userId() + "_" + channel);
            // 1対1通話はグループ枠ではないので room_id は null。channel文字列を入れると通話終了時に存在しない枠へ room_leave が飛ぶ
            call.put("room_id", JSONObject.NULL);
            // 公式(PhoneManager.connect): 自分のIDが相手より「小さい」側が P2PRoom.findOrCreate で
            // 部屋を作り、大きい側は find して join する。受け手/発信者の区別ではない点に注意。
            //   isOwner = (myUserId < targetUserId)
            long myUid = userId();
            boolean owner = (myUid != 0 && targetUid != 0) ? (myUid < targetUid) : false;
            call.put("is_owner", owner);
            call.put("owner_user_id", (myUid != 0 && targetUid != 0) ? Math.min(myUid, targetUid) : targetUid);
            // participants は「通話相手」。自分自身を入れると誰と話しているのか分からなくなる。
            JSONArray participants = new JSONArray();
            if (targetUid != 0 && targetUid != myUid) {
                String[] cached = this.nameCache.get(Long.valueOf(targetUid));
                participants.put(new JSONObject().put("user_id", targetUid)
                        .put("name", cached != null ? cached[0] : ("user " + targetUid))
                        .put("icon_url", cached != null ? iconUrl(cached[1]) : ""));
            }
            if (targetUid == myUid) {
                dbgLog(nowStr() + "  [CHEER] 警告: 通話相手が自分自身になっています(target_id=" + targetUid + ")");
            }
            call.put("participants", participants);
            // 1対1通話であることを明示。グループ枠の「オーナー権限/枠を閉じる/発言者の許可」は存在せず、
            // 公式(SkyWayPhoneForOneOnOne)も両者が無条件で音声を publish する。
            call.put("one_on_one", true);
            return new JSONObject().put("ok", true).put("call", call).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // SkyWay /authenticate のレスポンスから authToken を頑健に抽出。data.authToken / トップレベル authToken / token 等を順に試す。
    private String skywayTokenOf(Resp resp) {
        if (resp == null || resp.body == null) {
            return "";
        }
        JSONObject b = resp.body;
        JSONObject data = b.optJSONObject("data");
        String[] keys = {"authToken", "token", "auth_token", "skyway_token", "skywayToken", "jwt", "credential"};
        for (int i = 0; i < keys.length; i++) {
            if (data != null) {
                String v = data.optString(keys[i], "");
                if (v.length() > 0) {
                    return v;
                }
            }
            String v2 = b.optString(keys[i], "");
            if (v2.length() > 0) {
                return v2;
            }
        }
        try {
            StringBuilder sb = new StringBuilder("[skyway応答のキー(トークン抽出失敗)] top:{ ");
            java.util.Iterator<String> it = b.keys();
            while (it.hasNext()) {
                sb.append(it.next()).append(" ");
            }
            sb.append("}");
            if (data != null) {
                sb.append(" data:{ ");
                java.util.Iterator<String> it2 = data.keys();
                while (it2.hasNext()) {
                    sb.append(it2.next()).append(" ");
                }
                sb.append("}");
            }
            dbgLog(nowStr() + "  " + sb.toString());
        } catch (Exception e) {
        }
        return "";
    }

    private String getTalkRequestHistory() {
        // 公式 generateTalkRequestHistoryRequest: POST api/dive/talking_requests (フォーム, ページ指定なし)
        HashMap<String, String> tf = new HashMap<String, String>();
        return userHistoryResult(request("POST", "/api/dive/talking_requests", (Map<String, String>) null, tf), "talking_requests", "data");
    }

    // 通常のタイムライン(=「つぶやく」)は /api/feed_posts。is_talk=false でタグ付け。
    private String getTimeline(String str) {
        return tagPostsTalk(postsResult(request2("GET", "/api/feed_posts", qOpt("max_id", str), (Map<String, String>) null), "feed_posts", false), false);
    }

    // 通話募集(=「話そう」)は /api/timeline_posts。全件が通話募集なので is_talk=true でタグ付け(purposeでの絞り込みはしない)。
    private String getFeedTimeline(String str) {
        return tagPostsTalk(postsResult(request2("GET", "/api/timeline_posts", qOpt("max_id", str), (Map<String, String>) null), "timeline_posts", false), true);
    }

    // postsResult の JSON 文字列内の各投稿の is_talk を、取得元エンドポイントに合わせて確定させる。
    private String tagPostsTalk(String jsonResult, boolean isTalk) {
        try {
            JSONObject o = new JSONObject(jsonResult);
            JSONArray posts = o.optJSONArray("posts");
            if (posts != null) {
                for (int i = 0; i < posts.length(); i++) {
                    JSONObject p = posts.optJSONObject(i);
                    // 取得元が通話募集ならtrue。そうでなくても purpose を持つ投稿は通話募集のまま(バッジを消さない)。
                    if (p != null) p.put("is_talk", isTalk || p.optBoolean("is_talk", false));
                }
            }
            return o.toString();
        } catch (Exception e) {
            return jsonResult;
        }
    }

    private String getTimelineComments(String postId) throws org.json.JSONException {
        return getTimelineComments(postId, "1");
    }

    private String getTimelineComments(String postId, String page) throws org.json.JSONException {
        Map<String, String> q = new HashMap<>();
        q.put("page", (page == null || page.length() == 0) ? "1" : page);
        q.put("version", "android_3.9.101");
        String at = authToken();
        if (at != null) {
            q.put("auth_token", at);
        }
        Resp resp = http("GET", "https://api2.meetscom.com/api/feed_posts/" + postId + "/comments", q, null);
        String bodyPreview = resp.body != null ? truncate(resp.body.toString(), 300) : "(なし)";
        String raw = "[comments@api2 HTTP " + resp.status + "] " + bodyPreview;
        try {
            if (resp.status != 200 || resp.body == null) {
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("status", resp.status);
                err.put("raw", raw);
                return err.toString();
            }
            JSONObject body = resp.body;
            absorbUserInfo(body);
            JSONArray comments = body.optJSONArray("comments");
            Object data = body.opt("data");
            if (comments == null && data instanceof JSONArray) {
                comments = (JSONArray) data;
            }
            if (comments == null && data instanceof JSONObject) {
                comments = firstArray((JSONObject) data, "comments", "feed_post_comments", "post_comments");
            }
            if (comments == null) {
                comments = new JSONArray();
            }

            JSONArray ids = new JSONArray();
            for (int i = 0; i < comments.length(); i++) {
                JSONObject c = comments.optJSONObject(i);
                if (c == null) continue;
                ids.put(new JSONObject().put("user_id", commentUid(c))); // resolveNames は {user_id} オブジェクト配列を要求
            }
            resolveNames(ids, "user_id");

            JSONArray out = new JSONArray();
            for (int i = 0; i < comments.length(); i++) {
                JSONObject c = comments.optJSONObject(i);
                if (c == null) continue;
                long uid = commentUid(c);
                String[] cached = nameCache.get(Long.valueOf(uid));
                JSONObject user = c.optJSONObject("user");
                if (user == null) user = c.optJSONObject("user_info");
                String name = user != null ? firstNonEmpty(user.optString("name", ""), user.optString("nickname", "")) : "";
                if (name.length() == 0) name = firstNonEmpty(c.optString("user_name", ""), c.optString("name", ""), c.optString("nickname", ""));
                if (name.length() == 0 && cached != null) {
                    name = cached[0];
                }
                if (name.length() > 0 && !hasName(uid) && uid != 0) {
                    this.nameCache.put(Long.valueOf(uid), new String[]{name, user != null ? user.optString("profile_picture_file_path", "") : ""});
                }
                if (name.length() == 0 && uid != 0) {
                    name = "user " + uid;
                }
                String icon = user != null ? user.optString("profile_picture_file_path", "") : "";
                if (icon.length() == 0 && cached != null) {
                    icon = cached[1];
                }
                JSONObject o = new JSONObject();
                o.put("id", c.opt("id"));
                o.put("user_id", uid);
                o.put("name", name);
                o.put("icon_url", iconUrl(icon));
                o.put("text", firstStr(c, "comment", "text", "description", "body"));
                o.put("created_at", firstStr(c, "created_at", "createdAt"));
                out.put(o);
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("comments", out);
            result.put("raw", raw);
            return result.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getTimelineLikers(String str) {
        HashMap<String, String> hashMap = new HashMap<String, String>();
        hashMap.put("page", "1");
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        // 公式 TimelineApi.getLikedUsers: GET /api/feed_posts/{post_id}/liked_users?page=1 (X-Auth-Token ヘッダ) → {"metadata":{...},"liked_users_info":[...]}
        Resp http = http("GET", BASE_URL2 + "/api/feed_posts/" + str + "/liked_users", hashMap, (Map<String, String>) null);
        String str2 = "[liked_users@api2 HTTP " + http.status + "] " + (http.body != null ? truncate(http.body.toString(), 300) : "(なし)");
        try {
            JSONArray users = (http.status == 200 && http.body != null) ? normalizeUserList(http.body) : new JSONArray();
            int likedCount = -1;
            String source = "api2";
            if (users.length() == 0) {
                // 空だった: 生の応答を記録して、別経路で取り直す
                dbgLog(nowStr() + "  [LIKERS] post=" + str + " api2 HTTP " + http.status + " users=0 body=" + truncate(redactLog(http.body != null ? http.body.toString() : "(null)"), 400));
                Resp r2 = http("GET", BASE_URL + "/api/feed_posts/" + str + "/liked_users", hashMap, (Map<String, String>) null);
                if (r2.status == 200 && r2.body != null) {
                    users = normalizeUserList(r2.body);
                    source = "api1";
                }
                if (users.length() == 0) {
                    dbgLog(nowStr() + "  [LIKERS] post=" + str + " api1 HTTP " + r2.status + " users=0 body=" + truncate(redactLog(r2.body != null ? r2.body.toString() : "(null)"), 300));
                    // 投稿詳細に liked_users / liked_user_ids が同梱されていれば、そこから復元する
                    HashMap<String, String> q2 = new HashMap<String, String>();
                    q2.put("version", "android_3.9.101");
                    if (authToken != null) q2.put("auth_token", authToken);
                    Resp d = http("GET", BASE_URL2 + "/api/feed_posts/" + str, q2, (Map<String, String>) null);
                    if (d.status == 200 && d.body != null) {
                        // 詳細応答は {"post_info":{...},"comment_info":[...],"user_info":[...]} の形(ログで確認)
                        JSONObject pd = d.body.optJSONObject("post_info");
                        if (pd == null) pd = d.body.optJSONObject("feed_post");
                        if (pd == null) pd = d.body.optJSONObject("data");
                        if (pd == null) pd = d.body;
                        likedCount = pd.optInt("liked_user_count", pd.optInt("good_count", pd.optInt("likes_count", -1)));
                        JSONArray a = firstArray(pd, "liked_users", "liked_users_info", "liked_user_info", "liked_user_ids", "liked_ids");
                        if (a == null && pd != d.body) a = firstArray(d.body, "liked_users", "liked_users_info", "liked_user_info", "liked_user_ids", "liked_ids", "user_info");
                        dbgLog(nowStr() + "  [LIKERS] post=" + str + " 詳細 keys=" + topKeys(pd) + " liked_count=" + likedCount + " arr=" + (a == null ? "null" : String.valueOf(a.length())));
                        if (a != null && a.length() > 0) {
                            if (a.optJSONObject(0) != null) {
                                users = normalizeUserList(new JSONObject().put("users", a));
                            } else {
                                JSONArray ids = new JSONArray();
                                for (int i = 0; i < a.length(); i++) { long id = a.optLong(i, 0); if (id != 0) ids.put(new JSONObject().put("user_id", id)); }
                                resolveNames(ids, "user_id");
                                users = new JSONArray();
                                for (int i = 0; i < ids.length(); i++) {
                                    long id = ids.optJSONObject(i).optLong("user_id");
                                    if (isBanned(id)) continue;
                                    String[] nm = this.nameCache.get(Long.valueOf(id));
                                    users.put(new JSONObject().put("user_id", id).put("name", nm != null && nm[0].length() > 0 ? nm[0] : "user " + id).put("icon_url", nm != null ? iconUrl(nm[1]) : ""));
                                }
                            }
                            source = "detail";
                        }
                    }
                }
            }
            JSONObject out = new JSONObject().put("ok", true).put("users", users).put("raw", str2).put("source", source);
            if (likedCount >= 0) out.put("liked_count", likedCount);
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 特定ユーザーの投稿を target_id スコープで取得し、user_id==target のものを outArr に post_kind付きで追加。
    // 戻り値 [rawCount, matchedCount]。nextOut!=null なら next_max_id を格納(空のときのみ)。
    private int[] scopedPostsInto(String path, String seriesKey, long target, String cursor, String kind, boolean api2, JSONArray outArr, String[] nextOut, java.util.Set<Long> seenIds) {
        int raw = 0, matched = 0;
        try {
            HashMap<String, String> q = new HashMap<String, String>();
            q.put("target_id", String.valueOf(target));
            q.put("count", "30");
            if (cursor != null && cursor.length() > 0) q.put("max_id", cursor);
            Resp resp;
            if (api2) {
                q.put("version", "android_" + APP_VERSION); // 801バージョンゲート回避
                resp = httpApi2("GET", path, q, (Map<String, String>) null);
            } else {
                resp = request("GET", path, q, (Map<String, String>) null);
            }
            if (resp.status != 200 || resp.body == null) { return new int[]{-1, 0}; }
            JSONObject o = new JSONObject(postsResult(resp, seriesKey, false));
            JSONArray posts = o.optJSONArray("posts");
            if (posts != null) {
                raw = posts.length();
                for (int i = 0; i < posts.length(); i++) {
                    JSONObject p = posts.optJSONObject(i);
                    if (p != null && p.optLong("user_id") == target) {
                        long pid = p.optLong("id", 0);
                        // 重複排除: timeline と feed が同じ投稿を返す場合、先に入った方(timeline)を優先。
                        // これにより「通話募集じゃないのに通話募集」になる誤判定を防ぐ。
                        if (pid != 0 && seenIds != null) {
                            if (seenIds.contains(Long.valueOf(pid))) continue;
                            seenIds.add(Long.valueOf(pid));
                        }
                        p.put("post_kind", kind);
                        p.put("is_talk", "talk".equals(kind)); // talk=通話募集(timeline_posts), timeline=つぶやく(feed_posts)
                        outArr.put(p);
                        matched++;
                    }
                }
            }
            if (nextOut != null && nextOut[0].length() == 0) nextOut[0] = o.optString("next_max_id", "");
        } catch (Exception ignore) {}
        dbgLog(nowStr() + "  [USERPOSTS] " + kind + (api2 ? "@api2" : "@api") + " target=" + target + " raw=" + raw + " matched=" + matched);
        return new int[]{raw, matched};
    }

    private String getUserPosts(String str, String str2) {
        long j;
        try {
            j = Long.parseLong(str);
        } catch (Exception e2) {
            return errJson(e2);
        }
        try {
            if (str2 == null) str2 = "";
            // ユーザーの投稿は2種類:「つぶやく」=通常タイムライン=/api/feed_posts と
            // 「話そう」=通話募集=/api/timeline_posts。両方を target_id スコープで取得して混ぜる。
            JSONArray merged = new JSONArray();
            java.util.Set<Long> seenFeed = new java.util.HashSet<Long>();
            java.util.Set<Long> seenTalk = new java.util.HashSet<Long>();
            // 2系列(つぶやく=feed_posts / 話そう=timeline_posts)はそれぞれ別の ID 空間なので、カーソルは
            // "F:<feed_next>|T:<talk_next>" の複合形式で持つ。片方が尽きたら(空)その系列は取得しない。
            String curF = str2, curT = str2;
            boolean doF = true, doT = true;
            if (str2.startsWith("F:")) {
                int bar = str2.indexOf("|T:");
                curF = bar >= 0 ? str2.substring(2, bar) : str2.substring(2);
                curT = bar >= 0 ? str2.substring(bar + 3) : "";
                doF = curF.length() > 0; doT = curT.length() > 0;
            }
            String[] nextF = new String[]{""}, nextT = new String[]{""};
            // 軽量化: 実ログで api(server1) の target_id 指定は無関係な投稿を30件返す(matched=0)だけで
            // 無駄な名前解決まで発生していた。公式(TimelineApi=TYPE_2)と同じく api2 のみを使い、
            // api2 が応答しなかった(raw<0)場合だけ api にフォールバックする。全体走査(200件×数ページ)は廃止。
            // つぶやき(feed)と通話募集(talk)は独立しているので同時に取りに行く。
            // 直列にすると毎回2往復ぶん待たされ、プロフィールを開くたびに体感で効いていた。
            final JSONArray fMerged = new JSONArray(), tMerged = new JSONArray();
            final long fj = j;
            final String fCurF = curF, fCurT = curT;
            final int[] rF = new int[]{0}, rT = new int[]{0};
            final java.util.Set<Long> fSeenFeed = seenFeed, fSeenTalk = seenTalk;
            final String[] fNextF = nextF, fNextT = nextT;
            Thread tf = null, tt = null;
            if (doF) {
                tf = new Thread(new Runnable() { public void run() {
                    try {
                        rF[0] = scopedPostsInto("/api/feed_posts", "feed_posts", fj, fCurF, "timeline", true, fMerged, fNextF, fSeenFeed)[0];
                        if (rF[0] < 0) scopedPostsInto("/api/feed_posts", "feed_posts", fj, fCurF, "timeline", false, fMerged, fNextF, fSeenFeed);
                    } catch (Throwable ig) {}
                }});
                tf.start();
            }
            if (doT) {
                tt = new Thread(new Runnable() { public void run() {
                    try {
                        rT[0] = scopedPostsInto("/api/timeline_posts", "timeline_posts", fj, fCurT, "talk", true, tMerged, fNextT, fSeenTalk)[0];
                        if (rT[0] < 0) scopedPostsInto("/api/timeline_posts", "timeline_posts", fj, fCurT, "talk", false, tMerged, fNextT, fSeenTalk);
                    } catch (Throwable ig) {}
                }});
                tt.start();
            }
            try { if (tf != null) tf.join(30000); } catch (Exception ig) {}
            try { if (tt != null) tt.join(30000); } catch (Exception ig) {}
            for (int i = 0; i < fMerged.length(); i++) merged.put(fMerged.opt(i));
            for (int i = 0; i < tMerged.length(); i++) merged.put(tMerged.opt(i));
            if (doF && rF[0] >= 0 && rF[0] < 30) nextF[0] = ""; // 30件未満なら終端
            if (doT && rT[0] >= 0 && rT[0] < 30) nextT[0] = "";
            String[] nextOut = new String[]{(nextF[0].length() > 0 || nextT[0].length() > 0) ? ("F:" + nextF[0] + "|T:" + nextT[0]) : ""};
            // 投稿を作成日時の新しい順に並べ替える(timeline と feed を混ぜたときのズレを防ぐ)
            merged = sortPostsByCreatedDesc(merged);
            // 診断: 先頭数件の purpose/topic を出す(通話募集判定の確認用)
            StringBuilder pdbg = new StringBuilder();
            for (int i = 0; i < merged.length() && i < 5; i++) {
                JSONObject p = merged.optJSONObject(i);
                if (p != null) pdbg.append(" id=").append(p.opt("id")).append("(talk=").append(p.opt("is_talk")).append(",kind=").append(p.opt("post_kind")).append(")");
            }
            dbgLog(nowStr() + "  [USERPOSTS] uid=" + j + " total=" + merged.length() + " next=" + nextOut[0] + pdbg);
            return new JSONObject().put("ok", true).put("posts", merged).put("next_max_id", nextOut[0]).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // JSONArray の投稿を created_at 降順に並べ替える。
    private JSONArray sortPostsByCreatedDesc(JSONArray arr) {
        java.util.ArrayList<JSONObject> list = new java.util.ArrayList<JSONObject>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) list.add(o);
        }
        java.util.Collections.sort(list, new java.util.Comparator<JSONObject>() {
            public int compare(JSONObject a, JSONObject b) {
                String ca = a.optString("created_at", "");
                String cb = b.optString("created_at", "");
                return cb.compareTo(ca); // 文字列(ISO/類似)で降順
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject o : list) out.put(o);
        return out;
    }

    // 全体タイムライン/フィードを最大4ページ走査し、user_id==target の投稿を post_kind付きで outArr に追加。
    private void globalScanPostsInto(String path, String seriesKey, long target, String cursor, String kind, JSONArray outArr) {
        try {
            String scan = cursor == null ? "" : cursor;
            int guard = 0;
            while (guard < 4) {
                HashMap<String, String> hq = new HashMap<String, String>();
                if (scan.length() > 0) hq.put("max_id", scan);
                JSONObject page = new JSONObject(postsResult(request("GET", path, hq, (Map<String, String>) null), seriesKey, false));
                JSONArray arr = page.optJSONArray("posts");
                if (!page.optBoolean("ok") || arr == null || arr.length() == 0) break;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p != null && p.optLong("user_id") == target) { p.put("post_kind", kind); p.put("is_talk", "talk".equals(kind)); outArr.put(p); }
                }
                scan = page.optString("next_max_id", "");
                if (scan.length() == 0 || outArr.length() >= 20) break;
                guard++;
            }
        } catch (Exception ignore) {}
    }

    private String getUserSettings() {
        HashMap hashMap = new HashMap();
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        // /api/account/profile は廃止(404)。/api/v3/users/{id} から取得する
        Resp http2 = request("GET", "/api/v3/users/" + userId(), (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (http2.status != 200 || http2.body == null) {
                return new JSONObject().put("ok", false).put("status", http2.status).put("raw", http2.body != null ? truncate(http2.body.toString(), 400) : "").toString();
            }
            JSONObject optJSONObject = http2.body.optJSONObject("data");
            if (optJSONObject == null) {
                optJSONObject = http2.body;
            }
            JSONObject optJSONObject2 = optJSONObject.optJSONObject("user");
            if (optJSONObject2 == null) {
                optJSONObject2 = optJSONObject;
            }
            JSONObject optJSONObject3 = optJSONObject2.optJSONObject("user_setting");
            if (optJSONObject3 == null) {
                optJSONObject3 = optJSONObject2.optJSONObject("settings");
            }
            if (optJSONObject3 == null) {
                optJSONObject3 = optJSONObject2;
            }
            JSONObject jSONObject = new JSONObject();
            for (String str : new String[]{"random_match_enabled", "is_online_status_public", "is_read_receipt_public", "is_my_age_public", "is_follow_list_public", "is_follower_list_public", "is_friend_list_public", "timeline_image_enabled"}) {
                if (optJSONObject3.has(str)) {
                    jSONObject.put(str, truthy(optJSONObject3.opt(str)));
                }
            }
            // 注意: /api/account/session を叩くとトークンが更新されて既存セッションが無効になる(403 ユーザーが見つかりません)。
            // 設定の読み取りにセッションAPIは絶対に使わない。無い場合は端末側に保存した値を返す。
            dbgLog(nowStr() + "  [SETTINGS] v3 keys=" + topKeys(optJSONObject2) + " found=" + jSONObject.length());
            if (jSONObject.length() == 0) {
                try {
                    android.content.SharedPreferences sp = appContext.getSharedPreferences("koe_usersettings", 0);
                    for (String k : new String[]{"random_match_enabled", "is_online_status_public", "is_read_receipt_public", "is_my_age_public", "is_follow_list_public", "is_follower_list_public", "is_friend_list_public", "timeline_image_enabled"}) {
                        if (sp.contains(k)) jSONObject.put(k, sp.getBoolean(k, false));
                    }
                    jSONObject.put("_local", true);
                } catch (Exception ig) {
                }
            }
            return new JSONObject().put("ok", true).put("settings", jSONObject).put("raw", truncate(http2.body.toString(), 500)).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 1/0・"1"/"0"・true/false のいずれでも真偽に解釈する(公式は設定値を int で返す)
    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        String t = String.valueOf(v).trim().toLowerCase();
        return t.equals("1") || t.equals("true") || t.equals("yes");
    }

    private static String hex(byte[] bArr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bArr.length; i++) {
            sb.append(Character.forDigit((bArr[i] >> 4) & 15, 16)).append(Character.forDigit(bArr[i] & 15, 16));
        }
        return sb.toString();
    }

    private String historyResult(Resp resp, String... strArr) {
        try {
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp.status).toString();
            }
            JSONObject optJSONObject = resp.body.optJSONObject("data");
            JSONArray firstArray = optJSONObject != null ? firstArray(optJSONObject, strArr) : null;
            if (firstArray == null) {
                firstArray = firstArray(resp.body, strArr);
            }
            if (firstArray == null) {
                firstArray = new JSONArray();
            }
            JSONArray jSONArray = new JSONArray();
            for (int i = 0; i < firstArray.length(); i++) {
                JSONObject optJSONObject2 = firstArray.optJSONObject(i);
                if (optJSONObject2 != null) {
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("amount", optJSONObject2.optInt("amount", optJSONObject2.optInt("coin_amount", optJSONObject2.optInt("point_amount", 0))));
                    jSONObject.put("title", firstNonEmpty(optJSONObject2.optString("title", ""), optJSONObject2.optString("description", ""), optJSONObject2.optString("reason", "")));
                    jSONObject.put("created_at", optJSONObject2.optString("created_at", ""));
                    jSONObject.put("expired_at", optJSONObject2.optString("expired_at", ""));
                    jSONArray.put(jSONObject);
                }
            }
            return new JSONObject().put("ok", true).put("histories", jSONArray).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private static byte[] hmac(byte[] bArr, String str) throws Exception {
        Mac instance = Mac.getInstance("HmacSHA256");
        instance.init(new SecretKeySpec(bArr, "HmacSHA256"));
        return instance.doFinal(str.getBytes("UTF-8"));
    }

    /**
     * 公式(ErrorHandlerKt.AUTH_ERROR_CODE / ResponseExtKt)と同じ「認証エラー」の判定。
     * api2 は認証が切れると HTTP 403 + {"code":1000,"message":"ユーザーが見つかりませんでした"} を返す。
     * 401 だけを見ていると、この状態を「ただの読み込み失敗」と誤認して延々ポーリングし続けてしまう。
     */
    private static boolean isAuthErrorBody(int status, JSONObject body) {
        if (status != 403 || body == null) return false;
        if (body.optInt("code", -1) == 1000) return true;
        JSONObject err = body.optJSONObject("error");
        if (err != null && err.optInt("code", -1) == 1000) return true;
        JSONObject data = body.optJSONObject("data");
        return data != null && data.optInt("code", -1) == 1000;
    }

    private Resp http(String method, String url, Map<String, String> query, Map<String, String> fields) {
        return http(method, url, query, fields, true);
    }

    // ---- ホストの遅延回避 -------------------------------------------------------------
    // api.meetscom.com(旧サーバー)は時間帯によって 1 リクエストに 10〜30 秒かかることがある。
    // 直近に遅かったホストを覚えておき、その間は同じ API を持つ api2 へ先に送る。
    // (ログイン系はホストが固定なので対象外。api2 が 404 を返した時は元のホストへ戻す)
    private static final long SLOW_HOST_MS = 8000;
    private static final long SLOW_HOST_PENALTY_MS = 120000;
    private final Map<String, Long> slowHostUntil = new java.util.concurrent.ConcurrentHashMap<String, Long>();

    private void noteHostLatency(String url, long elapsedMs) {
        if (elapsedMs < SLOW_HOST_MS) return;
        String host = hostOf(url);
        if (host.length() == 0) return;
        slowHostUntil.put(host, Long.valueOf(System.currentTimeMillis() + SLOW_HOST_PENALTY_MS));
        dbgLog(nowStr() + "  [HOST] " + host + " が遅い(" + elapsedMs + "ms) → " + (SLOW_HOST_PENALTY_MS / 1000) + "秒間は別ホストを優先");
    }

    private boolean isHostSlow(String host) {
        Long until = slowHostUntil.get(host);
        return until != null && until.longValue() > System.currentTimeMillis();
    }

    private static String hostOf(String url) {
        try {
            int i = url.indexOf("://");
            int j = url.indexOf('/', i + 3);
            return j < 0 ? url : url.substring(0, j);
        } catch (Exception e) {
            return "";
        }
    }

    /** api2 に存在しなかった(404)パス。振り替えの対象から外す。 */
    private final java.util.Set<String> api2MissingPaths = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    private static String pathOf(String url) {
        String p = url.substring(hostOf(url).length());
        int q = p.indexOf('?');
        return (q < 0 ? p : p.substring(0, q)).replaceAll("/\\d+", "/{n}");
    }

    /** server1 専用(api2 だと応答形式が異なる)のため振り替えないパス */
    private static final String[] SERVER1_ONLY_PATHS = {"/api/account/login", "/api/chats"};

    /**
     * 旧サーバーが遅い間は api2 に振り替えた URL を返す。対象外ならそのまま。
     * 振り替えるのは読み取り(GET)だけ。投稿・削除・いいね等の書き込みは公式(TimelineApiServer1 /
     * OkHttpSingleton.generateFeedPostRequest)と同じく server1 固定 — api2 に送ると 403「権限がありません」になる。
     */
    private String preferFastHost(String method, String url) {
        if (url == null || !url.startsWith(BASE_URL + "/")) return url;
        if (!"GET".equals(method)) return url;
        for (String p : SERVER1_ONLY_PATHS) if (url.startsWith(BASE_URL + p)) return url;
        if (api2MissingPaths.contains(pathOf(url))) return url;
        if (isHostSlow(BASE_URL) && !isHostSlow(BASE_URL2)) return BASE_URL2 + url.substring(BASE_URL.length());
        return url;
    }

    // sendAuth=false のときは Authorization/X-Auth-Token を付けない。
    // ログイン系(login / signup / twitter_login)を「既ログイン状態のトークン付き」で
    // 送るとサーバーが弾く(HTTP 503 リクエストエラー)ため、ログイン系だけ false で呼ぶ。
    // 端末が長時間スリープ/バックグラウンドだった後などに、使い回し(keep-alive)の接続が
    // サーバー側で既に閉じられていて、復帰直後の最初の数リクエストが
    // 「unexpected end of stream」「connection reset」「thread interrupted」で落ちることがある。
    // これらは1回だけ新しい接続で即リトライすれば通るので、リトライ可能かを判定する。
    private static boolean isRetryableNet(Throwable e) {
        if (e == null) return false;
        if (e instanceof java.io.InterruptedIOException) return true;
        if (e instanceof java.io.EOFException) return true;
        String m = (e.getMessage() != null ? e.getMessage() : e.toString()).toLowerCase();
        return m.contains("unexpected end of stream")
                || m.contains("connection reset")
                || m.contains("connection abort")
                || m.contains("econnreset")
                || m.contains("interrupted")
                || m.contains("broken pipe")
                || m.contains("stream was reset")
                || m.contains("connection closed");
    }

    private Resp http(String method, String url, Map<String, String> query, Map<String, String> fields, boolean sendAuth) {
        String fastUrl = preferFastHost(method, url);
        long started = System.currentTimeMillis();
        Resp resp = httpRaw(method, fastUrl, query, fields, sendAuth);
        noteHostLatency(fastUrl, System.currentTimeMillis() - started);
        if (!fastUrl.equals(url) && resp.status == 404 && !resp.noData) {
            // api2 に無いエンドポイントだった場合は元のホストへ(次回からは振り替えない)
            api2MissingPaths.add(pathOf(url));
            resp = httpRaw(method, url, query, fields, sendAuth);
        }
        return resp;
    }

    private Resp httpRaw(String method, String url, Map<String, String> query, Map<String, String> fields, boolean sendAuth) {
      for (int __attempt = 0; __attempt < 2; __attempt++) {
        HttpURLConnection conn = null;
        try {
            StringBuilder sb = new StringBuilder(url);
            if (query != null && !query.isEmpty()) {
                sb.append(url.contains("?") ? "&" : "?");
                boolean first = true;
                for (Map.Entry<String, String> e : query.entrySet()) {
                    if (!first) {
                        sb.append("&");
                    }
                    sb.append(enc(e.getKey())).append("=").append(enc(e.getValue()));
                    first = false;
                }
            }
            conn = (HttpURLConnection) new URL(sb.toString()).openConnection();
            // 画像/音声付き投稿(createPostWithImage等)がensureSkywayHost()経由でこのメソッドを
            // 複数回連続で呼ぶ際、旧タイムアウト(8000/20000ms)だと実機ログで20906/20942/20886ms付近の
            // -1失敗(タイムアウト)が連続発生していたため、余裕を持たせて緩和する。
            boolean isWrite = !method.equals("GET");
            conn.setConnectTimeout(10000);
            // 読み取りは待ち過ぎずに別ホストへ回す。書き込みは振り替え先が無い(server1 固定)ので長めに待つ。
            conn.setReadTimeout(isWrite ? 45000 : 20000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", UA);
            // X-App-Version は認証リクエストのみ。公式のログイン(未認証)は付けない。
            // Retrofit+OkHttp のログインヘッダーに X-App-Version は無いため、ログインでは外す。
            if (sendAuth) {
                conn.setRequestProperty("X-App-Version", "android_" + APP_VERSION);
            }
            conn.setRequestProperty("X-KOETOMO-REQUEST-ID", newRequestId());
            // 公式アプリ(Retrofit)は Accept: application/json を付ける。認証ゲートウェイが要求している可能性。
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "gzip");
            String authToken = authToken();
            if (sendAuth && authToken != null) {
                conn.setRequestProperty("X-Auth-Token", authToken);
                conn.setRequestProperty("Authorization", authToken);
            }
            if (method.equals("POST") || method.equals("PUT") || (method.equals("DELETE") && fields != null && !fields.isEmpty())) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                StringBuilder body = new StringBuilder();
                if (fields != null) {
                    boolean first = true;
                    for (Map.Entry<String, String> e : fields.entrySet()) {
                        if (!first) {
                            body.append("&");
                        }
                        body.append(enc(e.getKey())).append("=").append(enc(e.getValue()));
                        first = false;
                    }
                }
                // OkHttp(公式)は必ず Content-Length を付ける。HttpURLConnection は長さ未宣言だと
                // Transfer-Encoding: chunked になり、nginx のログイン設定が「リクエストエラー」503で弾く。
                byte[] bodyBytes = body.toString().getBytes("UTF-8");
                conn.setFixedLengthStreamingMode(bodyBytes.length);
                OutputStream os = conn.getOutputStream();
                os.write(bodyBytes);
                os.flush();
                os.close();
            }
            int status = conn.getResponseCode();
            int localVsns = -999;
            boolean localExpired = false;
            this.lastVsns = -999;
            try {
                String vsnsHeader = conn.getHeaderField("X-Vsns-Status");
                if (vsnsHeader != null && vsnsHeader.trim().length() > 0) {
                    int vsns = Integer.parseInt(vsnsHeader.trim());
                    localVsns = vsns;
                    this.lastVsns = vsns;
                    if (vsns == 101 || vsns == 102 || vsns == 119) {
                        localExpired = true;
                        this.sessionExpiredSeen = true;
                    }
                }
            } catch (Exception e) {
            }
            if (status == 401) {
                localExpired = true;
                this.sessionExpiredSeen = true;
            }
            JSONObject bodyJson = null;
            String bodyStr = readBody(conn, status);
            if (bodyStr != null && bodyStr.trim().length() > 0) {
                try {
                    String trimmed = bodyStr.trim();
                    if (trimmed.startsWith("[")) {
                        JSONObject wrap = new JSONObject();
                        wrap.put("data", new JSONArray(trimmed));
                        bodyJson = wrap;
                    } else {
                        bodyJson = new JSONObject(trimmed);
                    }
                } catch (Exception e) {
                }
            }
            try {
                String snip = "";
                if (status < 200 || status >= 300) {
                    String b = bodyStr != null ? bodyStr.trim() : "";
                    // HTMLエラーページ(巨大ノイズ)は種別だけに圧縮
                    if (b.startsWith("<") || b.toLowerCase().contains("<!doctype") || b.toLowerCase().contains("<html")) {
                        snip = b.contains("対象のデータが存在しません") ? "  ✗ (HTML:データ無し)" : "  ✗ (HTML)";
                    } else {
                        snip = "  ✗ " + truncate(redactLog(b), 240);
                    }
                    // 503等の発生元(WAF/CDNか本体か)を切り分けるためレスポンスヘッダーを付ける
                    try {
                        StringBuilder hb = new StringBuilder();
                        String[] hk = {"Server", "Via", "CF-RAY", "cf-ray", "X-Cache", "x-amz-cf-id", "X-Amzn-Trace-Id", "X-Served-By"};
                        for (String k : hk) {
                            String hv = conn.getHeaderField(k);
                            if (hv != null && hv.length() > 0) {
                                hb.append(" [").append(k).append("=").append(truncate(hv, 60)).append("]");
                            }
                        }
                        if (hb.length() > 0) snip = snip + " HDR:" + hb.toString();
                        // ログインの503は原因不明のため、全レスポンスヘッダーをダンプする
                        if (url.contains("/api/account/login")) {
                            StringBuilder ab = new StringBuilder();
                            for (int hi = 0; ; hi++) {
                                String hk2 = conn.getHeaderFieldKey(hi);
                                String hv2 = conn.getHeaderField(hi);
                                if (hk2 == null && hv2 == null) break;
                                if (hi > 25) break;
                                ab.append("\n      ").append(hk2 == null ? "(status)" : hk2).append(": ").append(truncate(hv2, 100));
                            }
                            // 認証系ヘッダーがそのまま診断ログに残らないよう伏字化を通す
                            snip = snip + " ALLHDR:" + redactLog(ab.toString());
                        }
                    } catch (Exception eh) {
                    }
                }
                dbgLog(nowStr() + "  " + method + " " + redactLog(sb.toString()) + "  → " + status + snip);
            } catch (Exception eLog) {
            }
            boolean localAuthErr = isAuthErrorBody(status, bodyJson);
            if (localAuthErr) {
                // 一瞬だけ 403 が返ることがあるので、5回続いたら本当に切れたとみなす
                if (++this.authErrStreak >= 5) {
                    localExpired = true;
                    this.sessionExpiredSeen = true;
                }
            } else if (status >= 200 && status < 300) {
                this.authErrStreak = 0;
            }
            Resp resp = new Resp(status, bodyJson);
            resp.vsns = localVsns;
            resp.sessionExpired = localExpired;
            resp.authError = localAuthErr;
            resp.noData = status == 404 && bodyStr != null && bodyStr.contains("対象のデータが存在しません");
            return resp;
        } catch (Exception e) {
            if (conn != null) { try { conn.disconnect(); } catch (Exception ig) {} }
            // 1回だけ、新しい接続で即リトライ(復帰直後の切れた接続対策)
            // 書き込みはタイムアウト後に再送しない(サーバー側で処理済みなら二重投稿になる)
            boolean timedOut = e instanceof java.net.SocketTimeoutException;
            if (__attempt == 0 && isRetryableNet(e) && !(timedOut && !method.equals("GET"))) {
                Thread.interrupted(); // pause起因の割り込みフラグをクリアしてから再試行
                try { Thread.sleep(150); } catch (Exception ig) {}
                continue;
            }
            try {
                dbgLog(nowStr() + "  " + method + " " + redactLog(url) + "  → 通信エラー: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            } catch (Exception eLog) {
            }
            JSONObject err = new JSONObject();
            try {
                err.put("error", e.getMessage() != null ? e.getMessage() : "io_error");
            } catch (Exception e2) {
            }
            return new Resp(-1, err);
        }
      }
      return new Resp(-1, (JSONObject) null); // 到達しない
    }

    private Resp httpJson(String method, String url, JSONObject bodyObj) {
      for (int __attempt = 0; __attempt < 2; __attempt++) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(35000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("X-App-Version", "android_" + APP_VERSION);
            conn.setRequestProperty("X-KOETOMO-REQUEST-ID", newRequestId());
            conn.setRequestProperty("Content-Type", "application/json");
            String authToken = authToken();
            if (authToken != null) {
                conn.setRequestProperty("X-Auth-Token", authToken);
                conn.setRequestProperty("Authorization", authToken);
            }
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(bodyObj.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
            int status = conn.getResponseCode();
            int localVsns = -999;
            boolean localExpired = false;
            this.lastVsns = -999;
            try {
                String vsnsHeader = conn.getHeaderField("X-Vsns-Status");
                if (vsnsHeader != null && vsnsHeader.trim().length() > 0) {
                    int vsns = Integer.parseInt(vsnsHeader.trim());
                    localVsns = vsns;
                    this.lastVsns = vsns;
                    if (vsns == 101 || vsns == 102 || vsns == 119) {
                        localExpired = true;
                        this.sessionExpiredSeen = true;
                    }
                }
            } catch (Exception e) {
            }
            if (status == 401) {
                localExpired = true;
                this.sessionExpiredSeen = true;
            }
            JSONObject bodyJson = null;
            String bodyStr = readBody(conn, status);
            if (bodyStr != null && bodyStr.trim().length() > 0) {
                try {
                    bodyJson = new JSONObject(bodyStr);
                } catch (Exception e) {
                }
            }
            boolean localAuthErr = isAuthErrorBody(status, bodyJson);
            if (localAuthErr) {
                // 一瞬だけ 403 が返ることがあるので、5回続いたら本当に切れたとみなす
                if (++this.authErrStreak >= 5) {
                    localExpired = true;
                    this.sessionExpiredSeen = true;
                }
            } else if (status >= 200 && status < 300) {
                this.authErrStreak = 0;
            }
            Resp resp = new Resp(status, bodyJson);
            resp.vsns = localVsns;
            resp.sessionExpired = localExpired;
            resp.authError = localAuthErr;
            return resp;
        } catch (Exception e) {
            if (conn != null) { try { conn.disconnect(); } catch (Exception ig) {} }
            // 書き込みはタイムアウト後に再送しない(サーバー側で処理済みなら二重投稿になる)
            boolean timedOut = e instanceof java.net.SocketTimeoutException;
            if (__attempt == 0 && isRetryableNet(e) && !(timedOut && !method.equals("GET"))) {
                Thread.interrupted();
                try { Thread.sleep(150); } catch (Exception ig) {}
                continue;
            }
            return new Resp(-1, (JSONObject) null);
        }
      }
      return new Resp(-1, (JSONObject) null);
    }

    private String[] httpText(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("X-App-Version", "android_" + APP_VERSION);
            conn.setRequestProperty("X-KOETOMO-REQUEST-ID", newRequestId());
            conn.setRequestProperty("Accept", "application/json");
            String authToken = authToken();
            if (authToken != null) {
                conn.setRequestProperty("X-Auth-Token", authToken);
                conn.setRequestProperty("Authorization", authToken);
            }
            int status = conn.getResponseCode();
            int localVsns = -999;
            boolean localExpired = false;
            this.lastVsns = -999;
            try {
                String vsnsHeader = conn.getHeaderField("X-Vsns-Status");
                if (vsnsHeader != null && vsnsHeader.trim().length() > 0) {
                    int vsns = Integer.parseInt(vsnsHeader.trim());
                    localVsns = vsns;
                    this.lastVsns = vsns;
                    if (vsns == 101 || vsns == 102 || vsns == 119) {
                        localExpired = true;
                        this.sessionExpiredSeen = true;
                    }
                }
            } catch (Exception e) {
            }
            if (status == 401) {
                localExpired = true;
                this.sessionExpiredSeen = true;
            }
            String body = readBody(conn, status);
            if (!localExpired && status == 403 && body != null && body.contains("\"code\"") && body.contains("1000")) {
                localExpired = true;
                this.sessionExpiredSeen = true;
            }
            String[] result = new String[]{String.valueOf(status), body != null ? body : ""};
            return result;
        } catch (Exception e) {
            String[] result = new String[]{"-1", e.getMessage() != null ? e.getMessage() : "error"};
            if (conn != null) {
                conn.disconnect();
            }
            return result;
        }
    }

    private String iconUrl(String str) {
        return (str == null || str.length() == 0) ? "" : pngServerName() + str;
    }

    private JSONObject imageS3Config() throws Exception {
        JSONObject jSONObject = null;
        ensureSkywayHost();
        if (this.clientDefines == null) {
            throw new Exception("client_defines未取得(ネットワーク確認)");
        }
        JSONObject optJSONObject = this.clientDefines.optJSONObject("client_system_params");
        JSONObject optJSONObject2 = optJSONObject != null ? optJSONObject.optJSONObject("server_name") : null;
        if (optJSONObject2 != null) {
            jSONObject = optJSONObject2.optJSONObject("image_upload");
        }
        if (jSONObject == null) {
            throw new Exception("image_upload設定が見つからない");
        }
        String optString = jSONObject.optString("identity_pool_id", "");
        String optString2 = jSONObject.optString("region", "");
        String optString3 = jSONObject.optString("bucket_name", "");
        if (optString.length() != 0 && optString2.length() != 0 && optString3.length() != 0) {
            return new JSONObject().put("pool_id", optString).put("region", optString2).put("bucket", optString3).put("path", jSONObject.optString("path", ""));
        }
        throw new Exception("image_upload設定が不完全");
    }

    private String inviteCommunityMember(String str, String str2) {
        // 公式 CommunityApi.inviteMember: POST /api/communities/{id}/invite?target_ids[]=<id>
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("target_ids[]", str2);
        Resp request = request("POST", "/api/communities/" + str + "/invite", q, new HashMap<String, String>());
        try {
            if (request.status == 200 || request.status == 201) {
                return new JSONObject().put("ok", true).toString();
            }
            return new JSONObject().put("ok", false).put("status", request.status).put("raw", request.body != null ? truncate(request.body.toString(), 200) : "").toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // /api/rooms 系の応答から枠の配列を取り出す（data配列 / data.rooms / rooms / talk_rooms / トップレベル単体 の全形式に対応）
    private JSONArray roomsFromBody(JSONObject body) {
        if (body == null) return null;
        Object data = body.opt("data");
        if (data instanceof JSONArray) return (JSONArray) data;
        if (data instanceof JSONObject) {
            JSONObject d = (JSONObject) data;
            JSONArray a = d.optJSONArray("talk_rooms");
            if (a == null) a = d.optJSONArray("rooms");
            if (a != null) return a;
            if (d.optLong("room_id", d.optLong("id", 0)) > 0 || d.optString("token", "").length() > 0) {
                JSONArray one = new JSONArray(); one.put(d); return one;
            }
        }
        JSONArray a = body.optJSONArray("talk_rooms");
        if (a == null) a = body.optJSONArray("rooms");
        if (a != null) return a;
        if (body.optLong("room_id", body.optLong("id", 0)) > 0 || body.optString("token", "").length() > 0) {
            JSONArray one = new JSONArray(); one.put(body); return one;
        }
        return null;
    }

    // 公式 getOwnTalkRoom と同じ GET api2 /api/rooms?owner_user_id=<id>
    private JSONArray roomsByOwner(String ownerId, String tag) {
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("owner_user_id", ownerId);
        Resp r = httpApi2("GET", "/api/rooms", q, (Map<String, String>) null);
        JSONArray rooms = (r != null && r.status == 200) ? roomsFromBody(r.body) : null;
        dbgLog(nowStr() + "  [ROOM] " + tag + " owner=" + ownerId + " HTTP " + (r == null ? -1 : r.status)
                + " rooms=" + (rooms == null ? "null" : String.valueOf(rooms.length()))
                + ((rooms == null || rooms.length() == 0) ? (" body=" + truncate(redactLog(r != null && r.body != null ? r.body.toString() : "(null)"), 400)) : ""));
        return rooms;
    }

    private String joinCall(String ownerIdParam) {
        try {
            boolean useOwnRoom = (ownerIdParam == null || ownerIdParam.length() == 0 || ownerIdParam.equals("null"));
            String ownerIdStr;
            if (useOwnRoom) {
                ownerIdStr = String.valueOf(userId());
            } else {
                ownerIdStr = ownerIdParam;
                if (ownerIdStr.equals(String.valueOf(userId()))) {
                    useOwnRoom = true;
                }
            }
            JSONArray rooms = roomsByOwner(ownerIdStr, "join");
            JSONObject roomObj = null;
            for (int i = 0; rooms != null && i < rooms.length(); i++) {
                JSONObject o = rooms.optJSONObject(i);
                if (o == null) continue;
                String ca = o.optString("closed_at", "");
                if (ca.length() > 0 && !"null".equals(ca)) continue; // 終了済みは無視
                roomObj = o;
                break;
            }
            return joinRoomObj(roomObj, useOwnRoom, ownerIdStr);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 通知(枠作成)などから room_id 指定で参加する。公式: GET api/rooms/{id} (TYPE_2)
    private String joinCallByRoomId(String roomId) {
        try {
            HashMap<String, String> q = new HashMap<String, String>();
            q.put("version", APP_VERSION);
            String at = authToken();
            if (at != null) q.put("auth_token", at);
            Resp resp = http("GET", BASE_URL2 + "/api/rooms/" + roomId, q, (Map<String, String>) null);
            if (resp.status != 200) resp = http("GET", BASE_URL + "/api/rooms/" + roomId, q, (Map<String, String>) null);
            dbgLog(nowStr() + "  [JOIN] room_id=" + roomId + " -> " + resp.status + " " + (resp.body != null ? truncate(redactLog(resp.body.toString()), 300) : ""));
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("error", "room_not_found").put("status", resp.status)
                        .put("message", "この枠は見つかりませんでした(終了した可能性があります)。").toString();
            }
            Object data = resp.body.opt("data");
            JSONObject roomObj = null;
            if (data instanceof JSONObject) {
                roomObj = (JSONObject) data;
                if (roomObj.optJSONObject("room") != null) roomObj = roomObj.optJSONObject("room");
            } else if (data instanceof JSONArray && ((JSONArray) data).length() > 0) {
                roomObj = ((JSONArray) data).optJSONObject(0);
            } else if (resp.body.optJSONObject("room") != null) {
                roomObj = resp.body.optJSONObject("room");
            } else if (resp.body.has("room_id") || resp.body.has("token") || resp.body.has("owner_user_id")) {
                roomObj = resp.body; // data 包みなしのトップレベル形式
            }
            // 終了済みの枠(closed_at あり)は参加できない
            if (roomObj != null) {
                String closedAt = roomObj.optString("closed_at", "");
                if (closedAt.length() > 0 && !"null".equals(closedAt)) {
                    return new JSONObject().put("ok", false).put("error", "room_closed").put("closed_at", closedAt)
                            .put("message", "この枠は終了しています。").toString();
                }
            }
            long ownerL = roomObj != null ? roomObj.optLong("owner", roomObj.optLong("owner_user_id", 0)) : 0;
            boolean own = ownerL == userId();
            return joinRoomObj(roomObj, own, String.valueOf(ownerL != 0 ? ownerL : userId()));
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 自分がオーナーで参加中の枠(アプリ終了時に閉じるため保持)
    static volatile String myOpenRoomId = null;

    private String joinRoomObj(JSONObject roomObj, boolean useOwnRoom, String ownerIdStr) {
        try {
            JSONObject room = roomObj == null ? new JSONObject() : roomObj;
            String roomToken = room.optString("token", "");
            if (roomToken.length() == 0) {
                if (useOwnRoom) {
                    return new JSONObject().put("ok", false).put("error", "no_own_room")
                            .put("message", "自分の通話ルームがまだありません。「枠を作る」から作成してください。").toString();
                } else {
                    return new JSONObject().put("ok", false).put("error", "no_target_room")
                            .put("message", "このユーザーは現在トークルームを開いていません。").toString();
                }
            }
            String member = userId() + "_" + roomToken;
            long roomIdLong = room.optLong("id", room.optLong("room_id"));
            try {
                long ownerOf = room.optLong("owner_user_id", room.optLong("owner", 0));
                if (roomIdLong != 0 && (useOwnRoom || ownerOf == userId())) {
                    myOpenRoomId = String.valueOf(roomIdLong);
                    dbgLog(nowStr() + "  [ROOM] 自分の枠として記録 room=" + myOpenRoomId);
                } else if (roomIdLong != 0) {
                    myOpenRoomId = null;
                }
            } catch (Exception e) {}
            if (roomIdLong != 0) {
                try {
                    // 公式 TalkRoomApi.join: POST api2 /api/rooms/{id}/join（本文なし・認証はヘッダー）
                    Resp joinResp = httpApi2("POST", "/api/rooms/" + roomIdLong + "/join", (Map<String, String>) null, new HashMap<String, String>());
                    dbgLog(nowStr() + "  [JOIN] POST rooms/" + roomIdLong + "/join -> " + joinResp.status
                            + (joinResp.status >= 400 && joinResp.body != null ? " " + truncate(redactLog(joinResp.body.toString()), 200) : ""));
                    if (joinResp.status == 403 || joinResp.status == 400) {
                        String jb = joinResp.body != null ? joinResp.body.toString() : "";
                        String jm = joinResp.body != null ? joinResp.body.optString("displayable_detail", joinResp.body.optString("detail", joinResp.body.optString("message", ""))) : "";
                        if (jm.length() > 0) {
                            return new JSONObject().put("ok", false).put("status", joinResp.status)
                                    .put("message", jm).put("raw", truncate(jb, 300)).toString();
                        }
                    }
                } catch (Exception e) {
                    dbgLog(nowStr() + "  [JOIN] join例外 " + e);
                }
            }
            Resp skywayResp = getSkywayToken(roomToken);
            if (skywayResp.status != 200) {
                if (skywayResp.status == 401) {
                    return new JSONObject().put("ok", false).put("status", 401)
                            .put("message", "通話サーバーへの認証に失敗しました。ログインし直してください。").toString();
                }
                return new JSONObject().put("ok", false).put("status", skywayResp.status)
                        .put("message", skywayResp.status <= 0
                                ? "通話サーバーに接続できませんでした。電波の良い場所でもう一度お試しください。"
                                : (skywayResp.status >= 500
                                    ? "通話サーバーが混み合っています。時間を置いてからお試しください。"
                                    : "通話サーバーへの接続に失敗しました（status " + skywayResp.status + "）。何度も続く場合は管理者に報告してください。")).toString();
            }
            String skywayAuthToken = skywayTokenOf(skywayResp);
            if (skywayAuthToken.length() == 0) {
                return new JSONObject().put("ok", false).put("error", "AuthTokenが取得できませんでした(応答キーを診断ログに記録)").toString();
            }

            JSONArray speakers = room.optJSONArray("speakers");
            JSONArray listeners = room.optJSONArray("listeners");
            JSONArray[] roleArrays = new JSONArray[]{speakers, listeners};

            JSONArray idsForResolve = new JSONArray();
            for (int gi = 0; gi < roleArrays.length; gi++) {
                JSONArray arr = roleArrays[gi];
                if (arr == null) {
                    continue;
                }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    long uid = o.optLong("user_id");
                    uid = o.optLong("userId", uid);
                    idsForResolve.put(new JSONObject().put("user_id", uid));
                }
            }
            resolveNames(idsForResolve, "user_id");

            JSONArray participants = new JSONArray();
            for (int gi = 0; gi < roleArrays.length; gi++) {
                JSONArray arr = roleArrays[gi];
                if (arr == null) {
                    continue;
                }
                boolean isSpeakerArray = (gi == 0);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) {
                        continue;
                    }
                    long uid = o.optLong("user_id");
                    uid = o.optLong("userId", uid);
                    String[] cached = this.nameCache.get(Long.valueOf(uid));
                    String name = o.optString("name", "");
                    if (name.length() == 0 && cached != null && cached[0] != null) {
                        name = cached[0];
                    }
                    String iconPath = (cached != null && cached[1] != null) ? cached[1] : "";
                    JSONObject p = new JSONObject();
                    p.put("user_id", uid);
                    p.put("name", name.length() > 0 ? name : ("user " + uid));
                    p.put("icon_url", iconUrl(iconPath));
                    p.put("is_owner", o.optBoolean("isOwner", o.optBoolean("is_owner", false)));
                    p.put("is_mute", o.optBoolean("isMute", o.optBoolean("is_mute", false)));
                    p.put("role", o.optString("role", isSpeakerArray ? "speaker" : "listener"));
                    participants.put(p);
                }
            }

            boolean isOwner = useOwnRoom;
            for (int i = 0; i < participants.length(); i++) {
                JSONObject p = participants.optJSONObject(i);
                if (p != null && p.optBoolean("is_owner") && p.optLong("user_id") == userId()) {
                    isOwner = true;
                }
            }

            JSONArray speakerApplicants = new JSONArray();
            JSONArray rawApplicants = room.optJSONArray("speakerApplicants");
            if (rawApplicants != null) {
                for (int i = 0; i < rawApplicants.length(); i++) {
                    JSONObject o = rawApplicants.optJSONObject(i);
                    if (o != null) {
                        speakerApplicants.put(o);
                    }
                }
            }

            String ownerName = "";
            String ownerIcon = "";
            try {
                long ownerUidForHistory = Long.parseLong(ownerIdStr);
                JSONArray idsArr = new JSONArray();
                idsArr.put(new JSONObject().put("user_id", ownerUidForHistory));
                resolveNames(idsArr, "user_id");
                String[] cached = this.nameCache.get(Long.valueOf(ownerUidForHistory));
                if (cached != null) {
                    ownerName = cached[0] != null ? cached[0] : "";
                    ownerIcon = cached[1] != null ? cached[1] : "";
                }
            } catch (Exception e) {
            }
            appendRoomHistory(ownerIdStr, roomToken, ownerName, ownerIcon, room.optString("description", room.optString("title", "")));

            if (roomIdLong != 0) {
                boolean autoRaiseHand = this.prefs.getBoolean("mod_auto_raise_hand", false);
                if (autoRaiseHand) {
                    changeRole(String.valueOf(roomIdLong), String.valueOf(userId()), "speaker_applicant");
                }
                boolean autoApprove = this.prefs.getBoolean("mod_auto_approve", false);
                boolean autoReject = this.prefs.getBoolean("mod_auto_reject", false);
                if (isOwner && (autoApprove || autoReject)) {
                    for (int i = 0; i < speakerApplicants.length(); i++) {
                        JSONObject applicant = speakerApplicants.optJSONObject(i);
                        if (applicant == null) {
                            continue;
                        }
                        long uid = applicant.optLong("user_id");
                        uid = applicant.optLong("userId", uid);
                        if (uid == 0) {
                            continue;
                        }
                        String role = autoApprove ? "speaker" : "listener";
                        changeRole(String.valueOf(roomIdLong), String.valueOf(uid), role);
                    }
                }
            }

            Object roomIdVal = roomIdLong != 0 ? (Object) Long.valueOf(roomIdLong) : JSONObject.NULL;
            long ownerUidNum = 0;
            try {
                ownerUidNum = Long.parseLong(ownerIdStr);
            } catch (Exception e) {
            }
            JSONObject call = new JSONObject();
            call.put("auth_token", skywayAuthToken);
            call.put("channel", roomToken);
            // 公式 TalkRoomViewModel: connection_type 1=SFU / それ以外=P2P。SkyWay の部屋種別を公式と揃える。
            call.put("connection_type", resolveConnectionType(room, roomIdLong));
            call.put("member", member);
            call.put("participants", participants);
            call.put("room_id", roomIdVal);
            call.put("is_owner", isOwner);
            call.put("owner_user_id", ownerUidNum);
            // 通知タップ経由の参加でも、channel(内部ルームID文字列)ではなく枠名(description/title)を画面に出す。
            call.put("title", room.optString("description", room.optString("title", "")));

            return new JSONObject()
                    .put("ok", true)
                    .put("participants", participants)
                    .put("room_id", roomIdVal)
                    .put("speaker_applicants", speakerApplicants)
                    .put("call", call)
                    .toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String joinCommunity(String str) {
        return okResultStatus(request("POST", "/api/communities/" + str + "/join", (Map<String, String>) null, new HashMap()));
    }

    private static String jsonErr(String str) {
        try {
            return new JSONObject().put("ok", false).put("error", str).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private static String jsonStatus(Resp resp) {
        try {
            return new JSONObject().put("ok", false).put("status", resp.status).toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    // koetomo 固有の新機能エンドポイント(応援トーク系ランキング/履歴/送金コイン、参加中トークルーム等)は
    // 公式アプリのリクエストが取得できず必須パラメータが不明。全候補を試しても 400 パラメータ異常値 / 500 が返る。
    // その場合はエラーをそのまま UI に出さず、空リスト + unavailable フラグで穏当に劣化させる。
    // 診断ログには実際のステータス・エラーコード・メッセージを残す。
    private static String gracefulUnavailable(Resp resp, String listKey, String label) {
        int apiCode = -1;
        String apiMsg = "";
        try {
            if (resp != null && resp.body != null) {
                apiCode = resp.body.optInt("error_code", resp.body.optInt("code", -1));
                apiMsg = firstStr(resp.body, "message", "error_message", "error", "detail");
            }
        } catch (Exception ignore) {
        }
        try {
            dbgLog(nowStr() + "  [UNAVAIL] " + label + "  status=" + (resp != null ? resp.status : -1)
                    + (apiCode >= 0 ? (" code=" + apiCode) : "") + (apiMsg.length() > 0 ? ("  " + apiMsg) : "")
                    + "  → 空表示で劣化(パラメータ不明のため利用不可)");
        } catch (Exception ignore) {
        }
        try {
            JSONObject o = new JSONObject();
            o.put("ok", true);
            o.put(listKey, new JSONArray());
            o.put("unavailable", true);
            o.put("status", resp != null ? resp.status : -1);
            if (apiCode >= 0) {
                o.put("code", apiCode);
            }
            o.put("note", "この機能は現在ご利用いただけません（公式アプリ限定の新機能のため未対応）");
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":true,\"" + listKey + "\":[],\"unavailable\":true}";
        }
    }

    private String leaveCommunity(String str) {
        // 退会は DELETE /leave が本命だが、一部環境で DELETE がプロキシ/サーバー実装により通らないことが
        // あるため、非2xxなら末尾スラッシュ有無・POST版もフォールバックで試す(いずれも公式契約書に沿う形)。
        Resp r = request("DELETE", "/api/communities/" + str + "/leave", (Map<String, String>) null, (Map<String, String>) null);
        if (!(r.status >= 200 && r.status < 300)) {
            Resp r2 = request("POST", "/api/communities/" + str + "/leave", (Map<String, String>) null, new HashMap());
            if (r2.status >= 200 && r2.status < 300) {
                r = r2;
            } else if (r.status <= 0) {
                r = r2;
            }
        }
        return okResultStatus(r);
    }

    private String listGroupRooms(String str) {
        JSONArray jSONArray;
        if (str == null || str.length() == 0) {
            str = "1";
        }
        Resp request = request("GET", "/api/rooms", q2("page", str, "order", "1"), (Map<String, String>) null);
        try {
            if (request.status != 200 || request.body == null) {
                return new JSONObject().put("ok", false).put("status", request.status).toString();
            }
            Object opt = request.body.opt("data");
            if (opt instanceof JSONArray) {
                jSONArray = (JSONArray) opt;
            } else if (opt instanceof JSONObject) {
                JSONArray optJSONArray = ((JSONObject) opt).optJSONArray("rooms");
                jSONArray = optJSONArray == null ? ((JSONObject) opt).optJSONArray("talk_rooms") : optJSONArray;
            } else {
                jSONArray = null;
            }
            JSONArray jSONArray2 = jSONArray == null ? new JSONArray() : jSONArray;
            JSONArray jSONArray3 = new JSONArray();
            for (int i = 0; i < jSONArray2.length(); i++) {
                JSONObject optJSONObject = jSONArray2.optJSONObject(i);
                if (optJSONObject != null) {
                    jSONArray3.put(new JSONObject().put("user_id", optJSONObject.optLong("owner", optJSONObject.optLong("owner_user_id"))));
                }
            }
            resolveNames(jSONArray3, "user_id");
            JSONArray jSONArray4 = new JSONArray();
            for (int i2 = 0; i2 < jSONArray2.length(); i2++) {
                JSONObject optJSONObject2 = jSONArray2.optJSONObject(i2);
                if (optJSONObject2 != null) {
                    long optLong = optJSONObject2.optLong("owner", optJSONObject2.optLong("owner_user_id"));
                    JSONArray optJSONArray2 = optJSONObject2.optJSONArray("speakers");
                    JSONArray optJSONArray3 = optJSONObject2.optJSONArray("listeners");
                    int length = optJSONArray2 == null ? 0 : optJSONArray2.length();
                    int length2 = optJSONArray3 == null ? 0 : optJSONArray3.length();
                    String[] strArr = this.nameCache.get(Long.valueOf(optLong));
                    jSONArray4.put(new JSONObject().put("owner_user_id", optLong).put("owner_name", strArr != null ? strArr[0] : "user " + optLong).put("owner_icon", strArr != null ? iconUrl(strArr[1]) : "").put("title", optJSONObject2.optString("description", "user " + optLong + " のルーム")).put("speaker_count", length).put("listener_count", length2).put("member_count", length2 + length).put("created_at", optJSONObject2.optString("created_at", optJSONObject2.optString("started_at", optJSONObject2.optString("created_time", "")))));
                }
            }
            return new JSONObject().put("ok", true).put("rooms", jSONArray4).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private JSONArray loadRoomHistoryArr() {
        try {
            return new JSONArray(this.prefs.getString("room_history", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private JSONArray loadRegulatedWordsArr() {
        try {
            return new JSONArray(this.prefs.getString("regulated_words_log", "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // is_explicit(規制対象)フラグの検出: 実APIのキー名/型が不確定なため、複数のキー候補と
    // Boolean/Number(0,1)/String("true","1")のいずれの型でも拾えるように寛容に判定する。
    private static final String[] EXPLICIT_FLAG_KEYS = {"is_explicit", "explicit", "is_regulated", "regulated", "is_nsfw", "nsfw", "is_sensitive", "sensitive", "is_caution", "is_r18", "r18"};
    private boolean optExplicitFlag(JSONObject rawPost) {
        if (rawPost == null) return false;
        for (String key : EXPLICIT_FLAG_KEYS) {
            Object v = rawPost.opt(key);
            if (v == null) continue;
            if (v instanceof Boolean) { if (((Boolean) v).booleanValue()) return true; continue; }
            if (v instanceof Number) { if (((Number) v).doubleValue() != 0) return true; continue; }
            if (v instanceof String) {
                String s = ((String) v).trim().toLowerCase();
                if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
            }
        }
        return false;
    }

    private void recordRegulatedWordIfNeeded(JSONObject rawPost, long userId, String text) {
        try {
            Object explicitVal = rawPost.opt("is_explicit");
            boolean isExplicit = optExplicitFlag(rawPost);
            if (!isExplicit) {
                return;
            }
            Object postId = rawPost.opt("id");
            if (postId == null) {
                return;
            }
            JSONArray log = loadRegulatedWordsArr();
            for (int i = 0; i < log.length(); i++) {
                JSONObject existing = log.optJSONObject(i);
                if (existing != null && existing.opt("post_id") != null && existing.opt("post_id").toString().equals(postId.toString())) {
                    return;
                }
            }
            JSONObject entry = new JSONObject();
            entry.put("post_id", postId);
            entry.put("user_id", userId);
            entry.put("text", text == null ? "" : text);
            entry.put("is_explicit_value", explicitVal != null ? explicitVal : Boolean.TRUE);
            entry.put("detected_at", nowStr());
            log.put(entry);
            while (log.length() > 200) {
                JSONArray trimmed = new JSONArray();
                for (int i2 = 1; i2 < log.length(); i2++) {
                    trimmed.put(log.get(i2));
                }
                log = trimmed;
            }
            this.prefs.edit().putString("regulated_words_log", log.toString()).apply();
        } catch (Exception e) {
        }
    }

    private String getRegulatedWords() {
        try {
            return new JSONObject().put("ok", true).put("words", loadRegulatedWordsArr()).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String login(String str, String str2) {
        // 公式(Retrofit)は facebook_id / line_id が null のときパラメータ自体を送らない。
        // 空文字で送ると Ruby では "" が真扱いになり、サーバーがFacebook/LINEログインと
        // 誤判定して即エラー(503 リクエストエラー)を返す。省略する。
        // feature も公式と同じ機能フラグ文字列、version は android_ 接頭辞付きにする。
        HashMap hashMap = new HashMap();
        hashMap.put("email", str);
        hashMap.put("password", str2);
        hashMap.put("device_uid", deviceUid());
        hashMap.put("feature", "skwmeshroom,firebase,mail_auth,reset_status,chat_pagination,speaker_applicant,p2p_room,skyway,talk_recording");
        hashMap.put("version", "android_" + APP_VERSION);
        Resp http = httpLoginPost("https://api.meetscom.com/api/account/login", hashMap);
        String truncate = http.body != null ? truncate(http.body.toString(), 500) : "(応答ボディなし HTTP " + http.status + ")";
        try {
            if (http.status != 200 || http.body == null) {
                String extractError = extractError(http.body);
                if (http.status == 503) {
                    extractError = "ログイン試行が多すぎるため一時的に制限されています。10〜30分ほど待ってから、もう一度だけお試しください(連打すると制限が延びます)";
                } else if (extractError == null) {
                    extractError = "ログインに失敗しました(HTTP " + http.status + ")";
                }
                return new JSONObject().put("ok", false).put("status", http.status).put("message", extractError).put("raw", truncate).toString();
            }
            JSONObject optJSONObject = http.body.optJSONObject("data");
            if (optJSONObject == null) {
                optJSONObject = new JSONObject();
            }
            String optString = optJSONObject.optString("auth_token", "");
            if (optString.length() == 0) {
                String extractError2 = extractError(http.body);
                if (extractError2 == null) {
                    extractError2 = "メールアドレスまたはパスワードが違います";
                }
                return new JSONObject().put("ok", false).put("status", http.status).put("vsns", this.lastVsns).put("message", extractError2).put("raw", truncate).toString();
            }
            setAuthToken(optString);
            setUserId(optJSONObject.optLong("user_id", userId()));
            setUserName(optJSONObject.optString("name", userName()));
            String loginBirthday = optJSONObject.optString("birthday", "");
            if (loginBirthday.length() == 0) {
                JSONObject nestedUser = optJSONObject.optJSONObject("user");
                if (nestedUser != null) loginBirthday = nestedUser.optString("birthday", "");
            }
            setBirthday(loginBirthday);
            if (userId() == 0) {
                long deepFindLong = deepFindLong(http.body, "user_id");
                if (deepFindLong == 0) {
                    deepFindLong = deepFindLong(http.body, "userId");
                }
                if (deepFindLong == 0) {
                    deepFindLong = deepFindLong(http.body, "uid");
                }
                if (deepFindLong == 0) {
                    deepFindLong = deepFindLong(http.body, "id");
                }
                if (deepFindLong != 0) {
                    setUserId(deepFindLong);
                }
            }
            return new JSONObject().put("ok", true).put("user_name", userName()).put("user_id", userId()).put("raw", truncate).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // トークンだけでログインする。公式の自動ログイン(api/account/session)を使い、
    // auth_token から現在のユーザー情報(user_id/name)を復元する。user_id入力が不要になる。
    private String loginWithTokenOnly(String token) {
        if (token == null || token.trim().length() == 0) {
            return jsonErr("トークンを入力してください");
        }
        final String prevToken = authToken(); // 失敗時は元のログイン状態に戻す(検証前の上書きで既存セッションを失わない)
        setAuthToken(token.trim());
        HashMap hashMap = new HashMap();
        hashMap.put("auth_token", token.trim());
        hashMap.put("version", "android_" + APP_VERSION);
        hashMap.put("feature", "skwmeshroom,firebase,mail_auth,reset_status,chat_pagination,speaker_applicant,p2p_room,skyway,talk_recording");
        hashMap.put("device_uid", deviceUid());
        Resp resp = http("POST", "https://api.meetscom.com/api/account/session", (Map<String, String>) null, hashMap);
        if (resp.status == 404 || resp.status >= 500) {
            resp = http("POST", "https://api2.meetscom.com/api/account/session", (Map<String, String>) null, hashMap);
        }
        String raw = resp.body != null ? truncate(resp.body.toString(), 400) : "(応答なし HTTP " + resp.status + ")";
        try {
            if (resp.status < 200 || resp.status >= 300 || resp.body == null) {
                setAuthToken(prevToken);
                String em = extractError(resp.body);
                if (em == null) em = "トークンでのログインに失敗しました(HTTP " + resp.status + ")。トークンが無効か期限切れの可能性があります";
                return new JSONObject().put("ok", false).put("status", resp.status).put("message", em).put("raw", raw).toString();
            }
            JSONObject data = resp.body.optJSONObject("data");
            if (data == null) data = resp.body;
            long uid = deepFindLong(data, "user_id");
            if (uid == 0) uid = deepFindLong(data, "userId");
            if (uid == 0) uid = deepFindLong(data, "id");
            if (uid == 0) uid = deepFindLong(resp.body, "user_id");
            String name = data.optString("name", "");
            if (name.length() == 0) {
                JSONObject ui = data.optJSONObject("user_info");
                if (ui == null) ui = data.optJSONObject("user");
                if (ui != null) { name = ui.optString("name", ""); if (uid == 0) uid = ui.optLong("user_id", ui.optLong("id", 0)); }
            }
            String freshToken = data.optString("auth_token", "");
            if (freshToken.length() > 0) setAuthToken(freshToken);
            if (uid == 0) {
                setAuthToken(prevToken);
                return new JSONObject().put("ok", false).put("message", "ユーザーIDを取得できませんでした。トークンが無効な可能性があります").put("raw", raw).toString();
            }
            setUserId(uid);
            if (name.length() > 0) setUserName(name);
            return new JSONObject().put("ok", true).put("user_id", uid).put("user_name", userName()).put("raw", raw).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String loginWithToken(String str, String str2) throws org.json.JSONException {
        long j = 0;
        boolean z = false;
        boolean z2;
        boolean z3 = true;
        boolean z4 = false;
        if (str == null || str.length() == 0) {
            return jsonErr("トークンを入力してください");
        }
        final String prevTok = authToken();
        setAuthToken(str.trim());
        if (str2 != null) {
            try {
                j = Long.parseLong(str2.trim());
            } catch (Exception e) {
                j = 0;
            }
        }
        long j2 = j;
        if (j2 != 0) {
            setUserId(j2);
        }
        String userName = userName();
        try {
            Resp request = request("GET", "/api/v3/users/" + (j2 != 0 ? j2 : userId()), q1("fields", ""), (Map<String, String>) null);
            if (request.status == 200 && ((this.lastVsns == -999 || this.lastVsns == 0) && request.body != null)) {
                try {
                    JSONObject optJSONObject = request.body.optJSONObject("data");
                    JSONObject optJSONObject2 = optJSONObject != null ? optJSONObject.optJSONObject("user_info") : null;
                    if (optJSONObject2 == null && optJSONObject != null) {
                        optJSONObject2 = optJSONObject.optJSONObject("userInfo");
                    }
                    if (optJSONObject2 != null) {
                        optJSONObject = optJSONObject2;
                    }
                    if (optJSONObject != null) {
                        userName = optJSONObject.optString("name", userName);
                        setUserName(userName);
                    }
                    z = false;
                    z2 = true;
                } catch (Exception e2) {
                    z2 = false;
                }
            } else if (this.lastVsns == 101 || this.lastVsns == 102 || request.status == 401) {
                z = true;
                z2 = false;
            } else {
                z = false;
                z2 = false;
            }
            z4 = z;
            z3 = z2;
        } catch (Exception e3) {
            z3 = false;
        }
        if (z3) {
            try {
                return new JSONObject().put("ok", true).put("user_name", userName).toString();
            } catch (Exception e4) {
                return errJson(e4);
            }
        } else if (!z4) {
            setAuthToken(prevTok); // 通信エラー: 検証できなかったトークンで上書きしたままにしない
            return new JSONObject().put("ok", false).put("vsns", this.lastVsns).put("message", "確認できませんでした(通信エラー)。").toString();
        } else {
            if (prevTok != null && !prevTok.equals(str.trim())) setAuthToken(prevTok); else this.prefs.edit().remove("auth_token").apply();
            return new JSONObject().put("ok", false).put("vsns", this.lastVsns).put("message", "トークンが無効です。パスワードで再ログインしてください。").toString();
        }
    }

    private String markMessageRead(String str) {
        if (str == null || str.length() == 0) {
            return jsonErr("message_id不明");
        }
        HashMap hashMap = new HashMap();
        hashMap.put("message_id", str);
        Resp request = request("PUT", "/api/chat/message", (Map<String, String>) null, hashMap);
        try {
            return (request.status == 200 || request.status == 201) ? new JSONObject().put("ok", true).toString() : new JSONObject().put("ok", false).put("status", request.status).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private static String md5Hex(byte[] bArr) throws Exception {
        return hex(MessageDigest.getInstance("MD5").digest(bArr));
    }

    private JSONArray namedList(JSONArray jSONArray) throws Exception {
        JSONArray jSONArray2 = new JSONArray();
        if (jSONArray == null) {
            return jSONArray2;
        }
        for (int i = 0; i < jSONArray.length(); i++) {
            long arrUid = arrUid(jSONArray, i);
            if (arrUid != 0) {
                String[] strArr = this.nameCache.get(Long.valueOf(arrUid));
                jSONArray2.put(new JSONObject().put("user_id", arrUid).put("name", strArr != null ? strArr[0] : "user " + arrUid).put("icon_url", strArr != null ? iconUrl(strArr[1]) : ""));
            }
        }
        return jSONArray2;
    }

    // 公式(TimelineApiServer1)は api.meetscom.com に version="android_3.9.101" で送る。
    // ここだけ "3.9.101" とapi2優先になっていて、公式と食い違っていた。
    private Resp newTimelineApi(String str, String str2) {
        HashMap hashMap = new HashMap();
        hashMap.put("version", "android_" + APP_VERSION);
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        boolean z = str.equals("POST") || str.equals("PUT");
        Resp http = z ? http(str, BASE_URL + str2, (Map<String, String>) null, hashMap) : http(str, BASE_URL + str2, hashMap, (Map<String, String>) null);
        return (http.status == 404 || http.status >= 500) ? z ? http(str, BASE_URL2 + str2, (Map<String, String>) null, hashMap) : http(str, BASE_URL2 + str2, hashMap, (Map<String, String>) null) : http;
    }

    private Resp newTimelineApiForm(String str, Map<String, String> map) {
        map.put("version", "android_" + APP_VERSION);
        String authToken = authToken();
        if (authToken != null) {
            map.put("auth_token", authToken);
        }
        Resp http = http("POST", BASE_URL + str, (Map<String, String>) null, map);
        return (http.status == 404 || http.status >= 500) ? http("POST", BASE_URL2 + str, (Map<String, String>) null, map) : http;
    }

    private static final String[] VOICE_KEYS = {"voice_file_path", "voice_url", "sound_file_path", "sound_file_url", "audio_file_path", "audio_url", "recording_file_path", "record_file_path", "file_path", "voice", "sound", "audio"};
    // 一覧に本文/音声URLが無い投稿を、詳細(GET /api/feed_posts/{id})から補完する。
    // 音声のキーが公式と違う可能性があるので、生の詳細レスポンスのキーもログに残す。
    private void fillVoiceFromDetail(long id, JSONObject out) {
        try {
            HashMap<String, String> f = new HashMap<String, String>();
            f.put("version", "android_" + APP_VERSION);
            String at = authToken();
            if (at != null) f.put("auth_token", at);
            Resp resp = http("GET", "https://api2.meetscom.com/api/feed_posts/" + id, f, (Map<String, String>) null);
            if (resp.status != 200 || resp.body == null) return;
            JSONObject b = resp.body;
            JSONObject pd = b.optJSONObject("post_info");
            if (pd == null) pd = b.optJSONObject("post");
            if (pd == null) pd = b.optJSONObject("feed_post");
            JSONObject data = b.optJSONObject("data");
            if (pd == null && data != null) {
                pd = data.optJSONObject("post_info");
                if (pd == null) pd = data.optJSONObject("post");
                if (pd == null) pd = data.optJSONObject("feed_post");
                if (pd == null) pd = data;
            }
            if (pd == null) pd = b;
            // 生のキーを記録(公式が音声をどのキーで返すか特定するため)
            dbgLog(nowStr() + "  [POST-EMPTY] 詳細キー id=" + id + " keys=" + topKeys(pd));
            String t = firstStr(pd, "description", "comment", "text", "message", "body");
            String rawVoice = firstNonEmpty(
                    pd.optString("voice_file_path", ""), pd.optString("voice_url", ""),
                    pd.optString("sound_file_path", ""), pd.optString("sound_file_url", ""),
                    pd.optString("audio_file_path", ""), pd.optString("audio_url", ""),
                    pd.optString("recording_file_path", ""), pd.optString("record_file_path", ""),
                    pd.optString("file_path", ""), pd.optString("voice", ""), pd.optString("sound", ""), pd.optString("audio", ""));
            String v = voiceUrl(rawVoice);
            String im = iconUrl(pd.optString("image_file_path", pd.optString("image", "")));
            if (t.length() > 0) out.put("text", t);
            if (v.length() > 0) { out.put("voice_url", v); out.put("has_voice", true); }
            if (im.length() > 0) out.put("image_url", im);
            if (pd.has("play_time") && !pd.isNull("play_time")) out.put("play_time", pd.opt("play_time"));
            dbgLog(nowStr() + "  [POST-EMPTY] 詳細から補完 id=" + id + " voice=" + (v.length() > 0) + " text=" + (t.length() > 0) + (rawVoice.length() > 0 ? " rawVoice=" + truncate(redactLog(rawVoice), 80) : ""));
        } catch (Throwable ig) {
        }
    }

    private JSONArray normalizePosts(JSONArray jSONArray) throws Exception {
        resolveNames(jSONArray, "user_id");
        JSONArray jSONArray2 = new JSONArray();
        for (int i = 0; i < jSONArray.length(); i++) {
            JSONObject optJSONObject = jSONArray.optJSONObject(i);
            if (optJSONObject != null) {
                long optLong = optJSONObject.optLong("user_id");
                if (isBanned(optLong)) continue; // 共有BANリストの相手は自動非表示
                String[] strArr = this.nameCache.get(Long.valueOf(optLong));
                // 本文は基本 description。空文字のキーが入っていても firstNonEmpty で別名まで見て拾う。
                String optString = firstNonEmpty(
                        optJSONObject.optString("description", ""),
                        optJSONObject.optString("comment", ""),
                        optJSONObject.optString("text", ""),
                        optJSONObject.optString("body", ""),
                        optJSONObject.optString("message", ""),
                        optJSONObject.optString("content", ""));
                JSONObject jSONObject = new JSONObject();
                jSONObject.put("id", optJSONObject.opt("id"));
                jSONObject.put("user_id", optLong);
                jSONObject.put("name", strArr != null ? strArr[0] : "user " + optLong);
                jSONObject.put("icon_url", strArr != null ? iconUrl(strArr[1]) : "");
                jSONObject.put("text", optString);
                jSONObject.put("image_url", iconUrl(optJSONObject.optString("image_file_path", "")));
                jSONObject.put("voice_url", voiceUrl(optJSONObject.optString("voice_file_path", "")));
                jSONObject.put("created_at", optJSONObject.optString("created_at", ""));
                jSONObject.put("likes", optJSONObject.optInt("good_count", optJSONObject.optInt("liked_user_count", optJSONObject.optInt("likes_count", optJSONObject.optInt("good_users_count", 0)))));
                jSONObject.put("comments", optJSONObject.optInt("comment_count", 0));
                jSONObject.put("liked", optJSONObject.optBoolean("liked", optJSONObject.optBoolean("is_liked", optJSONObject.optBoolean("is_good", false))));
                jSONObject.put("bookmarked", optJSONObject.optBoolean("bookmarked", false));
                jSONObject.put("is_explicit", optExplicitFlag(optJSONObject));
                String deco = decoCache.get(Long.valueOf(optLong));
                if (deco != null) jSONObject.put("deco_url", iconUrl(deco));
                // 通話募集(Feed)投稿を区別するためのフィールドを保持
                boolean hasPurpose = optJSONObject.has("purpose") && !optJSONObject.isNull("purpose");
                if (hasPurpose) jSONObject.put("purpose", optJSONObject.opt("purpose"));
                if (optJSONObject.has("topic") && !optJSONObject.isNull("topic")) jSONObject.put("topic", optJSONObject.opt("topic"));
                if (optJSONObject.has("post_type") && !optJSONObject.isNull("post_type")) jSONObject.put("post_type", optJSONObject.opt("post_type"));
                if (optJSONObject.has("play_time") && !optJSONObject.isNull("play_time")) jSONObject.put("play_time", optJSONObject.opt("play_time"));
                // is_talk: 通話募集(=「話そう」=timeline_posts)は purpose を持つ。通常のタイムライン(=「つぶやく」=feed_posts)は持たない。
                // 混在フィード(フォロー/友達/ブックマーク)用の自動判定。タブ側では取得元エンドポイントで上書きする。
                jSONObject.put("is_talk", hasPurpose);
                // 音声投稿の判定(一覧に音声パスが無くても play_time/play_count があれば音声投稿)
                boolean voicey = (optJSONObject.has("play_time") && !optJSONObject.isNull("play_time"))
                        || (optJSONObject.has("play_count") && !optJSONObject.isNull("play_count"));
                if (voicey) jSONObject.put("has_voice", true);
                // 本文も画像も音声も無い投稿は「見えない投稿」になる。原因を記録し、
                // 音声投稿(play_timeあり・音声パス無し)は詳細を1回だけ取りに行って埋める(まれなケース・上限つき)。
                String img0 = optJSONObject.optString("image_file_path", "");
                String voi0 = optJSONObject.optString("voice_file_path", "");
                if (optString.length() == 0 && img0.length() == 0 && voi0.length() == 0) {
                    dbgLog(nowStr() + "  [POST-EMPTY] id=" + optJSONObject.opt("id")
                            + " purpose=" + optJSONObject.opt("purpose") + " topic=" + optJSONObject.opt("topic")
                            + " keys=" + topKeys(optJSONObject));
                    // 調査結果: 詳細API(/api/feed_posts/{id})にも description/voice_file_path/image_file_path は
                    // 存在しない(音声ファイルが消えた投稿)。公式アプリも空カードを出すだけなので、
                    // 通話募集(purposeあり)以外はそもそも表示しない。
                    if (!hasPurpose) {
                        dbgLog(nowStr() + "  [POST-EMPTY] 非表示 id=" + optJSONObject.opt("id") + (voicey ? " (音声データなしの音声投稿)" : ""));
                        continue;
                    }
                }
                recordRegulatedWordIfNeeded(optJSONObject, optLong, optString);
                jSONArray2.put(jSONObject);
            }
        }
        return jSONArray2;
    }

    private static final String[] USER_LIST_KEYS = {"users", "user_info", "liked_users_info", "liked_users", "liked_user_info", "followers", "followees", "following", "followings", "friends", "friend_users", "friend_info", "mutual_users", "blocked_users", "blocked_user_info", "block_users", "recommended_users", "user_list"};

    private JSONArray normalizeUserList(JSONObject jSONObject) {
        // 形が複数ある: トップレベル {"liked_users":[...]} / {"users":[...]}、{"data":{...}}、
        // さらに旧APIは {"data":"<JSON文字列>"} で来ることがある。すべて見る。
        JSONArray jSONArray = firstNonEmptyArray(jSONObject, USER_LIST_KEYS);
        if (jSONArray == null) {
            Object opt = jSONObject.opt("data");
            if (opt instanceof JSONArray) {
                jSONArray = (JSONArray) opt;
            } else if (opt instanceof JSONObject) {
                jSONArray = firstNonEmptyArray((JSONObject) opt, USER_LIST_KEYS);
            } else if (opt instanceof String) {
                // 旧API: data が JSON文字列。パースして配列/オブジェクトを探す。
                try {
                    String ds = ((String) opt).trim();
                    if (ds.startsWith("[")) {
                        jSONArray = new JSONArray(ds);
                    } else if (ds.startsWith("{")) {
                        JSONObject dj = new JSONObject(ds);
                        jSONArray = firstArray(dj, USER_LIST_KEYS);
                    }
                } catch (Exception ignore) {}
            }
        }
        if (jSONArray == null) {
            jSONArray = new JSONArray();
        }
        JSONArray jSONArray2 = new JSONArray();
        for (int i = 0; i < jSONArray.length(); i++) {
            JSONObject item = jSONArray.optJSONObject(i);
            if (item != null) {
                // ユーザー本体がネストされている形に対応(ブロック一覧: {"target_info":{...}} など)
                JSONObject u = item;
                for (String k : new String[]{"target_info", "user_info", "user", "target_user", "followee", "follower", "userInfo"}) {
                    JSONObject nested = item.optJSONObject(k);
                    if (nested != null && (nested.has("user_id") || nested.has("id") || nested.has("name"))) { u = nested; break; }
                }
                long optLong = u.optLong("user_id", u.optLong("id", item.optLong("user_id", item.optLong("id", item.optLong("target_id", 0)))));
                if (isBanned(optLong)) continue; // 共有BANリストの相手は自動非表示(ブロック一覧を除く呼び出し元で有効)
                String firstNonEmpty = firstNonEmpty(u.optString("nickname", ""), u.optString("name", ""));
                if (firstNonEmpty.length() == 0) {
                    firstNonEmpty = "user " + optLong;
                }
                try {
                    JSONObject nu = new JSONObject().put("user_id", optLong).put("name", firstNonEmpty).put("icon_url", iconUrl(firstNonEmpty(u.optString("profile_picture_file_path", ""), u.optString("profilePictureFilePath", ""))));
                    // 最終オンライン(「3分前」等)・自己紹介・年齢・地域: 一覧のオンライン表示や並び替えに使う
                    String ls = firstNonEmpty(u.optString("login_status_with_unit", ""), u.optString("loginStatusWithUnit", ""));
                    if (ls.length() > 0) nu.put("login_status", ls);
                    int lst = loginStateOf(u);
                    if (lst < 0) lst = loginStateOf(item);
                    if (lst >= 0) nu.put("login_state", lst);
                    if (!u.isNull("age") && u.opt("age") != null) nu.put("age", u.opt("age"));
                    String ar = u.optString("area_name", u.optString("areaName", ""));
                    if (ar.length() > 0) nu.put("area_name", ar);
                    String cm = u.optString("comment", "");
                    if (cm.length() > 0) nu.put("comment", cm.length() > 80 ? cm.substring(0, 80) : cm);
                    // 一覧の右側に「フォロー中／リクエスト中／フォロー」を出すための関係情報。
                    // 声とも側は2種類の関係を持つ:
                    //   is_followee          … 実際にフォローが成立している
                    //   is_friend_requestee  … こちらから申請したが未成立(=リクエスト中)
                    boolean fo = u.optBoolean("is_followee", false) || u.optBoolean("isFollowee", false)
                            || item.optBoolean("is_followee", false);
                    boolean rq = u.optBoolean("is_friend_requestee", false) || u.optBoolean("isFriendRequestee", false)
                            || item.optBoolean("is_friend_requestee", false);
                    nu.put("is_following", fo || rq);
                    nu.put("requested", rq && !fo);
                    nu.put("is_followed", relFollowed(u) || relFollowed(item));
                    jSONArray2.put(nu);
                } catch (Exception e) {
                }
            }
        }
        return jSONArray2;
    }

    private String notifText(int i) {
        switch (i) {
            case 0:
                return "あなたの投稿にコメントしました";
            case 1:
                return "あなたの投稿にいいねしました";
            case 2:
                return "通話履歴にコメントしました";
            case 3:
                return "通話履歴にいいねしました";
            case 4:
                return "メッセージが届きました";
            case 5:
                return "フレンド申請が届きました";
            case 6:
                return "フレンドになりました";
            case 7:
                return "ギフトが届きました";
            case 9:
                return "ルームを作成しました";
            case 10:
                return "ルームに招待されました";
            case 11:
                return "コミュニティに招待されました";
            case 12:
                return "コミュニティへの参加申請が届きました";
            case 13:
                return "投稿にコメントしました";
            case 14:
                return "コミュニティに参加しました";
            case 15:
                return "参加申請が承認されました";
            case 16:
                return "参加申請が拒否されました";
            case 17:
                return "投稿にいいねしました";
            case 18:
                return "コメントにいいねしました";
            case 19:
                return "コミュニティから追放されました";
            case 20:
                return "トークルームが作成されました";
            case 21:
                return "応援通話待機をリクエストしています";
            case 22:
                return "応援通話の待機を開始しました";
            default:
                return "新しい通知";
        }
    }

    private static String nowStr() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
    }


    // koetomo は「フォロー」を友達申請として持っており、v2/v3 のユーザー情報には
    // is_following / is_followed というキーは存在しない。実際に返るのは
    //   is_friend_requestee : 自分が相手に申請した(=自分がフォローしている)
    //   is_friend_requester : 相手が自分に申請した(=相手が自分をフォローしている)
    //   is_followee / is_follower : 旧来のフォロー関係
    // 実機ログで follow → is_friend_requestee=true、解除 → false を確認済み。
    static boolean relFollowing(JSONObject u) {
        if (u == null) return false;
        return u.optBoolean("is_following", false) || u.optBoolean("isFollowing", false)
                || u.optBoolean("is_friend_requestee", false) || u.optBoolean("isFriendRequestee", false)
                || u.optBoolean("is_followee", false) || u.optBoolean("isFollowee", false);
    }

    static boolean relFollowed(JSONObject u) {
        if (u == null) return false;
        return u.optBoolean("is_followed", false) || u.optBoolean("isFollowed", false)
                || u.optBoolean("is_friend_requester", false) || u.optBoolean("isFriendRequester", false)
                || u.optBoolean("is_follower", false) || u.optBoolean("isFollower", false);
    }

    private String okResult(Resp resp) {
        try {
            JSONObject put = new JSONObject().put("ok", resp.status >= 200 && resp.status < 300 && (resp.vsns == -999 || resp.vsns == 0)).put("status", resp.status).put("vsns", resp.vsns);
            if (resp.sessionExpired) {
                put.put("session_expired", true);
            }
            if (resp.authError) {
                put.put("auth_error", true);
            }
            if (resp.body != null) {
                put.put("body", resp.body);
            }
            return put.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // HTTPステータスのみで成否を判定する版(vsnsヘッダーを見ない)。
    // コミュニティ参加/退会・トークルーム参加/退会など、成功時でも非0のX-Vsns-Statusを返す
    // エンドポイントでは okResult() だと2xx成功なのにok:falseと誤判定してしまう(いいねで既に判明済みの
    // 問題と同じ)。これらの操作系は2xxなら成功とみなす。
    private String okResultStatus(Resp resp) {
        try {
            boolean ok = resp.status >= 200 && resp.status < 300;
            JSONObject put = new JSONObject().put("ok", ok).put("status", resp.status).put("vsns", resp.vsns);
            if (resp.sessionExpired) {
                put.put("session_expired", true);
            }
            if (resp.authError) {
                put.put("auth_error", true);
            }
            if (resp.body != null) {
                put.put("body", resp.body);
            }
            return put.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String parseRecordsBody(Resp resp) {
        int optInt;
        try {
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp.status).put("raw", resp.body != null ? truncate(resp.body.toString(), 1200) : "").toString();
            }
            JSONObject optJSONObject = resp.body.optJSONObject("data");
            JSONArray jSONArray = null;
            if (optJSONObject != null) {
                jSONArray = optJSONObject.optJSONArray("call_records");
                if (jSONArray == null) {
                    jSONArray = optJSONObject.optJSONArray("records");
                }
                if (jSONArray == null) {
                    jSONArray = optJSONObject.optJSONArray("data");
                }
            }
            if (jSONArray == null) {
                jSONArray = resp.body.optJSONArray("call_records");
            }
            if (jSONArray == null) {
                jSONArray = resp.body.optJSONArray("records");
            }
            if (jSONArray == null) {
                jSONArray = resp.body.optJSONArray("data");
            }
            JSONArray jSONArray2 = jSONArray == null ? new JSONArray() : jSONArray;
            JSONArray jSONArray3 = new JSONArray();
            for (int i = 0; i < jSONArray2.length(); i++) {
                JSONObject optJSONObject2 = jSONArray2.optJSONObject(i);
                if (optJSONObject2 != null) {
                    long optLong = optJSONObject2.optLong("caller_id", 0);
                    long optLong2 = optJSONObject2.optLong("callee_id", 0);
                    long j = optLong2 != 0 ? optLong2 : optLong;
                    if (j != optLong2) {
                        optLong = optLong2;
                    }
                    if (j == 0) {
                        JSONObject optJSONObject3 = optJSONObject2.optJSONObject("user");
                        j = optJSONObject2.optLong("user_id", 0);
                        if (j == 0 && optJSONObject3 != null) {
                            j = optJSONObject3.optLong("id", 0);
                        }
                    }
                    String voiceUrl = voiceUrl(firstNonEmpty(optJSONObject2.optString("file_path", ""), optJSONObject2.optString("voice_file_path", ""), optJSONObject2.optString("sound_file_url", ""), optJSONObject2.optString("record_url", ""), optJSONObject2.optString("audio_url", ""), optJSONObject2.optString("url", "")));
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("id", optJSONObject2.opt("id"));
                    jSONObject.put("user_id", j);
                    jSONObject.put("other_id", optLong);
                    jSONObject.put("voice_url", voiceUrl);
                    jSONObject.put("text", firstNonEmpty(optJSONObject2.optString("callee_description", ""), optJSONObject2.optString("caller_description", ""), optJSONObject2.optString("description", ""), optJSONObject2.optString("comment", "")));
                    jSONObject.put("likes", optJSONObject2.optInt("liked_count", optJSONObject2.optInt("good_count", optJSONObject2.optInt("liked_user_count", 0))));
                    jSONObject.put("comments", optJSONObject2.optInt("comment_count", 0));
                    jSONObject.put("liked", optJSONObject2.optBoolean("liked", optJSONObject2.optBoolean("is_liked", false)));
                    jSONObject.put("created_at", firstNonEmpty(optJSONObject2.optString("talked_at", ""), optJSONObject2.optString("created_at", "")));
                    jSONObject.put("play_time", optJSONObject2.optInt("call_duration", optJSONObject2.optInt("play_time", 0)));
                    jSONObject.put("play_count", optJSONObject2.optInt("play_count", 0));
                    jSONArray3.put(jSONObject);
                }
            }
            JSONArray jSONArray4 = new JSONArray();
            for (int i2 = 0; i2 < jSONArray3.length(); i2++) {
                JSONObject optJSONObject4 = jSONArray3.optJSONObject(i2);
                if (optJSONObject4 != null && optJSONObject4.optLong("user_id", 0) > 0) {
                    jSONArray4.put(optJSONObject4);
                }
            }
            if (jSONArray4.length() > 0) {
                resolveNames(jSONArray4, "user_id");
            }
            JSONArray jSONArray5 = new JSONArray();
            for (int i3 = 0; i3 < jSONArray3.length(); i3++) {
                JSONObject optJSONObject5 = jSONArray3.optJSONObject(i3);
                if (optJSONObject5 != null && optJSONObject5.optLong("other_id", 0) > 0) {
                    jSONArray5.put(new JSONObject().put("user_id", optJSONObject5.optLong("other_id", 0)).put("__i", i3));
                }
            }
            if (jSONArray5.length() > 0) {
                resolveNames(jSONArray5, "user_id");
                for (int i4 = 0; i4 < jSONArray5.length(); i4++) {
                    JSONObject optJSONObject6 = jSONArray5.optJSONObject(i4);
                    if (!(optJSONObject6 == null || (optInt = optJSONObject6.optInt("__i", -1)) < 0 || jSONArray3.optJSONObject(optInt) == null)) {
                        jSONArray3.optJSONObject(optInt).put("other_name", optJSONObject6.optString("name", ""));
                    }
                }
            }
            return new JSONObject().put("ok", true).put("records", jSONArray3).put("raw", truncate(resp.body.toString(), 800)).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String pngServerName() {
        JSONObject jSONObject = null;
        if (this.pngServer != null) {
            return this.pngServer;
        }
        ensureSkywayHost();
        try {
            JSONObject optJSONObject = this.clientDefines != null ? this.clientDefines.optJSONObject("client_system_params") : null;
            if (optJSONObject != null) {
                jSONObject = optJSONObject.optJSONObject("server_name");
            }
            String optString = jSONObject != null ? jSONObject.optString("png", "") : "";
            if (optString.length() > 0) {
                if (!optString.startsWith("http")) {
                    optString = "https://" + optString;
                }
                if (!optString.endsWith("/")) {
                    optString = optString + "/";
                }
                this.pngServer = optString;
            }
        } catch (Exception e) {
        }
        if (this.pngServer == null) {
            this.pngServer = PNG_FALLBACK;
        }
        return this.pngServer;
    }

    private String pointExchangeUrl() {
        try {
            String authToken = authToken();
            StringBuilder append = new StringBuilder().append("https://api2.meetscom.com/api/dmoney/product_list?auth_token=");
            if (authToken == null) {
                authToken = "";
            }
            return new JSONObject().put("ok", true).put("url", append.append(authToken).append("&version=android_").append(APP_VERSION).toString()).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String postRecordComment(String str, String str2) {
        HashMap hashMap = new HashMap();
        if (str2 == null) {
            str2 = "";
        }
        hashMap.put("text", str2);
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        String str3 = "/api/call_records/" + str + "/comments";
        Resp http = http("POST", BASE_URL2 + str3, (Map<String, String>) null, hashMap);
        if (http.status == 404 || http.status >= 500) {
            http = http("POST", BASE_URL + str3, (Map<String, String>) null, hashMap);
        }
        return okResult(http);
    }

    // 空文字のページングパラメータは送らない(空のmax_id=はサーバが0件で返すため)
    private HashMap<String, String> qOpt(String key, String val) {
        HashMap<String, String> m = new HashMap<String, String>();
        if (val != null && val.trim().length() > 0) m.put(key, val);
        return m;
    }

    /** JSON を深さ優先で辿り、指定キーのうち最初に見つかった非空文字列/数値を返す(配列は先頭要素のみ) */
    private static String deepFindStr(Object node, String[] keys, int depth) {
        if (node == null || depth > 6) return "";
        try {
            if (node instanceof JSONObject) {
                JSONObject o = (JSONObject) node;
                for (String k : keys) {
                    Object v = o.opt(k);
                    if (v != null && !(v instanceof JSONObject) && !(v instanceof JSONArray)) {
                        String sv = String.valueOf(v);
                        if (sv.length() > 0 && !"null".equals(sv) && !"0".equals(sv)) return sv;
                    }
                }
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    Object v = o.opt(it.next());
                    if (v instanceof JSONObject || v instanceof JSONArray) {
                        String r = deepFindStr(v, keys, depth + 1);
                        if (r.length() > 0) return r;
                    }
                }
            } else if (node instanceof JSONArray) {
                JSONArray a = (JSONArray) node;
                if (a.length() > 0) return deepFindStr(a.opt(0), keys, depth + 1);
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String topKeys(JSONObject o) {
        try {
            StringBuilder sb = new StringBuilder();
            java.util.Iterator<String> it = o.keys();
            while (it.hasNext()) { sb.append(it.next()).append(","); }
            JSONObject d = o.optJSONObject("data");
            if (d != null) { sb.append("data:{"); java.util.Iterator<String> it2 = d.keys(); while (it2.hasNext()) sb.append(it2.next()).append(","); sb.append("}"); }
            return sb.toString();
        } catch (Exception e) { return "?"; }
    }

    private static void collectIdList(JSONObject src, String key, java.util.Set<Long> out) {
        if (src == null) return;
        JSONArray a = src.optJSONArray(key);
        if (a == null) return;
        for (int i = 0; i < a.length(); i++) {
            long v = a.optLong(i, -1);
            if (v > 0) out.add(Long.valueOf(v));
        }
    }

    private String postsResult(Resp resp, String str, boolean z) {
        JSONObject optJSONObject;
        try {
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp.status).toString();
            }
            // 応答の形が2通りある: トップレベル {"timeline_posts":[...]} と {"data":{"following_posts":[...]}}。両方見る。
            JSONArray jSONArray = firstArray(resp.body, str, "timeline_posts", "following_posts", "friend_posts", "feed_posts", "posts", "bookmark_posts");
            if (jSONArray == null) {
                JSONObject optJSONObject2 = resp.body.optJSONObject("data");
                if (optJSONObject2 != null) {
                    jSONArray = firstArray(optJSONObject2, str, "timeline_posts", "following_posts", "friend_posts", "feed_posts", "posts", "bookmark_posts");
                    if (jSONArray == null && optJSONObject2.opt("data") instanceof JSONArray) {
                        jSONArray = optJSONObject2.optJSONArray("data");
                    }
                }
            }
            if (jSONArray == null) {
                jSONArray = new JSONArray();
            }
            JSONArray normalizePosts = normalizePosts(jSONArray);
            // 公式(FeedListResponse/TimelinePostsResponse)はブックマーク状態を投稿ごとの bookmarked ではなく
            // 応答トップレベル(or data配下)の bookmark_ids / feed_bookmark_ids / timeline_bookmark_ids で返す。
            java.util.HashSet<Long> bmIds = new java.util.HashSet<Long>();
            collectIdList(resp.body, "bookmark_ids", bmIds);
            collectIdList(resp.body, "feed_bookmark_ids", bmIds);
            collectIdList(resp.body, "timeline_bookmark_ids", bmIds);
            JSONObject dataObj = resp.body.optJSONObject("data");
            if (dataObj != null) {
                collectIdList(dataObj, "bookmark_ids", bmIds);
                collectIdList(dataObj, "feed_bookmark_ids", bmIds);
                collectIdList(dataObj, "timeline_bookmark_ids", bmIds);
            }
            for (int i = 0; i < normalizePosts.length(); i++) {
                JSONObject np = normalizePosts.optJSONObject(i);
                if (np == null) continue;
                if (z || bmIds.contains(Long.valueOf(np.optLong("id", -1)))) {
                    np.put("bookmarked", true);
                }
            }
            String str2 = "";
            if (jSONArray.length() > 0 && (optJSONObject = jSONArray.optJSONObject(jSONArray.length() - 1)) != null) {
                str2 = String.valueOf(optJSONObject.optLong("id"));
                // 公式 TimelineApi: following_posts / friend_posts のページングは max_id ではなく
                // max_created_at(最後の投稿の created_at)。id を渡すと同じ10件が返り続けて無限ループになる。
                if ("following_posts".equals(str) || "friend_posts".equals(str) || (z && "posts".equals(str))) {
                    String ca = optJSONObject.optString("created_at", optJSONObject.optString("createdAt", ""));
                    if (ca.length() > 0) str2 = ca;
                }
            }
            dbgLog(nowStr() + "  [POSTS] " + str + " count=" + normalizePosts.length() + " bookmark_ids=" + bmIds.size() + " keys=" + topKeys(resp.body) + " next_max_id=" + str2 + (normalizePosts.length() == 0 ? " body=" + truncate(redactLog(resp.body.toString()), 300) : ""));
            return new JSONObject().put("ok", true).put("posts", normalizePosts).put("next_max_id", str2).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String profileResult(Resp resp, long j, boolean z) {
        String truncate = resp.body != null ? truncate(resp.body.toString(), 600) : "(応答ボディなし HTTP " + resp.status + ")";
        try {
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp.status).put("raw", truncate).toString();
            }
            JSONObject optJSONObject = resp.body.optJSONObject("data");
            if (optJSONObject == null) {
                optJSONObject = new JSONObject();
            }
            JSONObject optJSONObject2 = optJSONObject.optJSONObject("user_info");
            if (optJSONObject2 == null) {
                optJSONObject2 = optJSONObject.optJSONObject("userInfo");
            }
            if (optJSONObject2 != null) {
                optJSONObject = optJSONObject2;
            }
            JSONObject jSONObject = new JSONObject();
            jSONObject.put("user_id", optJSONObject.optLong("user_id", optJSONObject.optLong("userId", j)));
            jSONObject.put("name", optJSONObject.optString("name", ""));
            jSONObject.put("comment", optJSONObject.optString("comment", ""));
            jSONObject.put("icon_url", iconUrl(optJSONObject.optString("profile_picture_file_path", optJSONObject.optString("profilePictureFilePath", ""))));
            jSONObject.put("follower_count", optJSONObject.optInt("follower_count", optJSONObject.optInt("followerCount", 0)));
            jSONObject.put("followee_count", optJSONObject.optInt("followee_count", optJSONObject.optInt("followeeCount", 0)));
            if (!optJSONObject.isNull("age")) {
                jSONObject.put("age", optJSONObject.opt("age"));
            }
            if (z) {
                jSONObject.put("header_url", iconUrl(optJSONObject.optString("header_image_file_path", optJSONObject.optString("headerImageFilePath", ""))));
                jSONObject.put("is_following", relFollowing(optJSONObject));
                jSONObject.put("is_followed", relFollowed(optJSONObject));
                jSONObject.put("is_blocked", optJSONObject.optBoolean("is_blocked", optJSONObject.optBoolean("isBlocked", false)));
                jSONObject.put("friend_count", optJSONObject.optInt("friend_count", optJSONObject.optInt("friendCount", 0)));
                jSONObject.put("area_name", optJSONObject.optString("area_name", optJSONObject.optString("areaName", "")));
                jSONObject.put("login_status", optJSONObject.optString("login_status_with_unit", optJSONObject.optString("loginStatusWithUnit", "")));
                int lst2 = loginStateOf(optJSONObject);
                if (lst2 >= 0) jSONObject.put("login_state", lst2);
            }
            return new JSONObject().put("ok", true).put("profile", jSONObject).put("raw", truncate).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private static Map<String, String> q1(String str, String str2) {
        HashMap hashMap = new HashMap();
        if (str2 == null) {
            str2 = "";
        }
        hashMap.put(str, str2);
        return hashMap;
    }

    private static Map<String, String> q2(String str, String str2, String str3, String str4) {
        Map<String, String> q1 = q1(str, str2);
        if (str4 == null) {
            str4 = "";
        }
        q1.put(str3, str4);
        return q1;
    }

    // 可変長のキー/値ペアからクエリマップを生成 ("k1","v1","k2","v2",...)
    private static Map<String, String> qN(String... kv) {
        HashMap<String, String> m = new HashMap<String, String>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1] == null ? "" : kv[i + 1]);
        }
        return m;
    }

    private static String readBody(HttpURLConnection httpURLConnection, int i) {
        InputStream inputStream;
        if (i < 200 || i >= 400) {
            inputStream = httpURLConnection.getErrorStream();
        } else {
            try {
                inputStream = httpURLConnection.getInputStream();
            } catch (Exception e) {
                inputStream = null;
            }
        }
        if (inputStream == null) {
            return "";
        }
        // gzip 応答の展開(Accept-Encoding: gzip を明示しているため。JSON は 5〜10 倍縮むので通信量・時間を削減)
        try {
            String ce = httpURLConnection.getContentEncoding();
            if (ce != null && ce.toLowerCase().contains("gzip")) inputStream = new java.util.zip.GZIPInputStream(inputStream);
        } catch (Exception ignored) {}
        String str = "";
        try {
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"), 16384);
            StringBuilder sb = new StringBuilder();
            while (true) {
                String readLine = bufferedReader.readLine();
                if (readLine == null) {
                    break;
                }
                sb.append(readLine);
            }
            bufferedReader.close();
            str = sb.toString();
        } catch (Exception e2) {
        } finally {
            try {
                inputStream.close();
            } catch (Exception e3) {
            }
        }
        return str;
    }

    private String refreshRoomState(String str) throws org.json.JSONException {
        return refreshRoomState(str, null);
    }

    // room_id が分かっている場合は公式(OkHttpSingleton generatePureUrl "api/rooms/{id}", TYPE_2)と同じく
    // api2 の /api/rooms/{id} を見る(一覧APIより鮮度が高く、枠名の変更も取れる)。無ければ owner 一覧で探す。
    private String refreshRoomState(String str, String roomId) throws org.json.JSONException {
        JSONObject jSONObject = null;
        boolean z = true;
        if (!(str == null || str.length() == 0 || str.equals("null"))) {
            z = false;
        }
        if (z) {
            str = String.valueOf(userId());
        }
        try {
            if (roomId != null && roomId.length() > 0 && !roomId.equals("null") && !roomId.equals("0")) {
                HashMap<String, String> q = new HashMap<String, String>();
                q.put("version", APP_VERSION);
                String at = authToken();
                if (at != null) q.put("auth_token", at);
                Resp r1 = http("GET", BASE_URL2 + "/api/rooms/" + roomId, q, (Map<String, String>) null);
                if (r1.status == 200 && r1.body != null) {
                    Object d = r1.body.opt("data");
                    if (d instanceof JSONObject) {
                        jSONObject = (JSONObject) d;
                        if (jSONObject.optJSONObject("room") != null) jSONObject = jSONObject.optJSONObject("room");
                    } else if (d instanceof JSONArray && ((JSONArray) d).length() > 0) {
                        jSONObject = ((JSONArray) d).optJSONObject(0);
                    } else if (r1.body.has("room_id") || r1.body.has("owner_user_id") || r1.body.has("speakers")) {
                        // 参加APIと同じく data 包みなしのトップレベル形式
                        jSONObject = r1.body;
                    } else if (r1.body.optJSONObject("room") != null) {
                        jSONObject = r1.body.optJSONObject("room");
                    }
                    if (jSONObject == null) {
                        JSONArray rr = roomsFromBody(r1.body);
                        if (rr != null && rr.length() > 0) jSONObject = rr.optJSONObject(0);
                        if (jSONObject == null) dbgLog(nowStr() + "  [ROOM] state 解析できず room=" + roomId + " body=" + truncate(redactLog(r1.body.toString()), 300));
                    }
                    if (jSONObject != null && !this.roomStateLogged) {
                        this.roomStateLogged = true;
                        dbgLog(nowStr() + "  [ROOM] /api/rooms/" + roomId + " keys=" + topKeys(jSONObject));
                    }
                } else if (r1.status == 404) {
                    // 枠が閉じられた
                    return new JSONObject().put("ok", true).put("room_id", JSONObject.NULL).put("owner_user_id", 0).put("speaker_applicants", new JSONArray()).put("speakers", new JSONArray()).put("listeners", new JSONArray()).put("speaker_count", 0).put("listener_count", 0).toString();
                }
            }
            if (jSONObject == null) {
                JSONArray rooms = roomsByOwner(str, "state");
                for (int i = 0; rooms != null && i < rooms.length(); i++) {
                    JSONObject o = rooms.optJSONObject(i);
                    if (o != null) { jSONObject = o; break; }
                }
            }
            JSONObject jSONObject2 = jSONObject == null ? new JSONObject() : jSONObject;
            JSONArray optJSONArray = jSONObject2.optJSONArray("speakerApplicants");
            JSONArray optJSONArray2 = optJSONArray == null ? jSONObject2.optJSONArray("speaker_applicants") : optJSONArray;
            JSONArray optJSONArray3 = jSONObject2.optJSONArray("speakers");
            JSONArray optJSONArray4 = jSONObject2.optJSONArray("listeners");
            long optLong = jSONObject2.optLong("owner", jSONObject2.optLong("owner_user_id"));
            JSONArray jSONArray2 = new JSONArray();
            collectUids(jSONArray2, optJSONArray2);
            collectUids(jSONArray2, optJSONArray3);
            collectUids(jSONArray2, optJSONArray4);
            if (optLong != 0) {
                jSONArray2.put(new JSONObject().put("user_id", optLong));
            }
            resolveNames(jSONArray2, "user_id");
            long optLong2 = jSONObject2.optLong("id", jSONObject2.optLong("room_id"));
            return new JSONObject().put("ok", true).put("room_id", optLong2 == 0 ? JSONObject.NULL : Long.valueOf(optLong2)).put("owner_user_id", optLong)
                    .put("title", jSONObject2.optString("description", jSONObject2.optString("title", "")))
                    .put("comment_enabled", jSONObject2.has("comment_enabled") ? truthy(jSONObject2.opt("comment_enabled")) : JSONObject.NULL)
                    .put("close_at", jSONObject2.optString("close_at", "")).put("opened_at", jSONObject2.optString("opened_at", ""))
                    .put("is_public", jSONObject2.has("is_public") ? truthy(jSONObject2.opt("is_public")) : JSONObject.NULL)
                    .put("speaker_applicants", namedList(optJSONArray2)).put("speakers", namedList(optJSONArray3)).put("listeners", namedList(optJSONArray4)).put("speaker_count", optJSONArray3 == null ? 0 : optJSONArray3.length()).put("listener_count", optJSONArray4 == null ? 0 : optJSONArray4.length()).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private boolean roomStateLogged = false;

    private String replyTimelinePost(String str, String str2) {
        HashMap hashMap = new HashMap();
        hashMap.put("text", str2);
        Resp r = newTimelineApiForm("/api/feed_posts/" + str + "/comments", hashMap);
        dbgLog(nowStr() + "  [REPLY] post=" + str + " HTTP " + r.status + " vsns=" + r.vsns + " body=" + truncate(redactLog(r.body != null ? r.body.toString() : "(null)"), 300));
        return okResult(r);
    }

    // クエリ作成の小ヘルパー（2つ目は値が空なら付けない）
    private HashMap<String, String> cQ(String k1, String v1, String k2, String v2) {
        HashMap<String, String> m = new HashMap<String, String>();
        m.put(k1, v1);
        if (v2 != null && v2.length() > 0 && !"null".equals(v2)) m.put(k2, v2);
        return m;
    }

    private String reportTimelinePost(String str, String str2) {
        if (str == null || str.length() == 0) {
            return jsonErr("通報対象が不明です");
        }
        // 公式: POST /api/relation/user_report(全てクエリ)。referer_id は必須int(空だと弾かれる)なので 0 を送る。
        // community_id / community_talk_room_id は nullable のためユーザー通報では送らない。
        // 公式 OkHttpSingleton.generateUserReportsRequest:
        //   POST api/relation/user_report  フォーム: auth_token, version, target_id, diagnostics_info, content
        //   referer_id は画面種別が特定できるときだけ付く(不明時は送らない)
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("target_id", str);
        q.put("content", (str2 == null || str2.length() == 0) ? "通報" : str2);
        q.put("diagnostics_info", "");
        // 公式 ReportScreen: -1=不明 0=Feed 1=Timeline 2=声とも 3=フォロー 7=相手プロフィール …
        // 公式は必ず referer_id を送る。当方はプロフィールからの通報なので 7(PeopleProfile)。
        q.put("referer_id", "7");
        q.put("version", "android_" + APP_VERSION);
        String atR = authToken();
        if (atR != null) q.put("auth_token", atR);
        // 公式 RelationApi.report は @Query（target_id / content / referer_id / diagnostics_info など）
        Resp r = http("POST", BASE_URL + "/api/relation/user_report", q, (Map<String, String>) null);
        dbgLog(nowStr() + "  [REPORT] target=" + str + " HTTP " + r.status + (r.body != null ? " " + truncate(redactLog(r.body.toString()), 200) : ""));
        boolean ok = r.status >= 200 && r.status < 300;
        try {
            JSONObject out = new JSONObject().put("ok", ok).put("status", r.status);
            if (r.body != null) out.put("body", r.body);
            if (r.sessionExpired) out.put("session_expired", true);
            if (r.authError) out.put("auth_error", true);
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 公式の TimelineApi/BookmarkApi は TYPE_2(api2) を使う。api(server1) が 200 を返しても
    // bookmark_ids 等が含まれないことがあるため、投稿一覧系は api2 を先に試す。
    private Resp request2(String method, String path, Map<String, String> query, Map<String, String> fields) {
        HashMap<String, String> q = new HashMap<String, String>();
        if (query != null) {
            for (Map.Entry<String, String> e : query.entrySet()) {
                if ((e.getValue() != null && e.getValue().length() > 0) || emptyAllowed(e.getKey())) q.put(e.getKey(), e.getValue());
            }
        }
        if (!q.containsKey("version")) q.put("version", "android_" + APP_VERSION);
        String at = authToken();
        if (at != null && !q.containsKey("auth_token")) q.put("auth_token", at);
        Resp r = http(method, BASE_URL2 + path, q, fields);
        if (r.status >= 200 && r.status < 300) return r;
        if (r.noData) return r; // データが無いだけ(コメント 0 件など)。旧サーバーに聞き直さない
        // 4xx(認証・検証エラー等)は別ホストでも同じなので再送しない。タイムアウトは GET のみ再送
        boolean retry = r.status == 404 || r.status >= 500 || (r.status <= 0 && "GET".equals(method));
        if (!retry) return r;
        Resp r1 = http(method, BASE_URL + path, q, fields);
        return (r1.status >= 200 && r1.status < 300) ? r1 : r;
    }

    private Resp request(String str, String str2, Map<String, String> map, Map<String, String> map2) {
        HashMap hashMap = new HashMap();
        if (map != null) {
            for (Map.Entry next : map.entrySet()) {
                if ((next.getValue() != null && ((String) next.getValue()).length() > 0) || emptyAllowed((String) next.getKey())) {
                    hashMap.put((String) next.getKey(), (String) next.getValue());
                }
            }
        }
        if (!hashMap.containsKey("version")) {
            // cheering_talk 系は公式アプリと同じ "android_3.9.101" 形式でないと
            // パラメータ異常値で弾かれる。該当エンドポイントのみ厳密版を送る。
            hashMap.put("version", (str2 != null && str2.startsWith("/api/cheering_talk/")) ? ("android_" + APP_VERSION) : APP_VERSION);
        }
        String authToken = authToken();
        if (authToken != null && !hashMap.containsKey("auth_token")) {
            hashMap.put("auth_token", authToken);
        }
        // ホスト学習のキーは ID 部分を正規化(/api/v3/users/123 → /api/v3/users/{n})。
        // ユーザーごとに毎回 旧サーバー(500)→api2 と無駄打ちしていたのを防ぐ
        final String ck = (str == null ? "" : str) + " " + (str2 == null ? "" : str2.replaceAll("/\\d+", "/{n}"));
        String str3 = this.hostCache.containsKey(ck) ? this.hostCache.get(ck) : BASE_URL;
        final String pathKey = pathOf(BASE_URL + (str2 == null ? "" : str2));
        // 旧サーバーが遅い間は読み取り(GET)だけ api2 から始める。書き込みはホストで権限判定が異なる(api2 は 403)ため動かさない
        if ("GET".equals(str) && BASE_URL.equals(str3) && isHostSlow(BASE_URL) && !isHostSlow(BASE_URL2) && !api2MissingPaths.contains(pathKey)) str3 = BASE_URL2;
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        linkedHashSet.add(str3);
        linkedHashSet.add(BASE_URL2);
        linkedHashSet.add(BASE_URL);
        if (api2MissingPaths.contains(pathKey) && !BASE_URL2.equals(str3)) linkedHashSet.remove(BASE_URL2);
        Resp resp = new Resp(0, (JSONObject) null);
        Iterator it = linkedHashSet.iterator();
        while (it.hasNext()) {
            String str4 = (String) it.next();
            Resp http = http(str, str4 + str2, hashMap, map2);
            if (http.status == 200) {
                String prevHost = this.hostCache.get(ck);
                if (prevHost == null || !prevHost.equals(str4)) {
                    this.hostCache.put(ck, str4);
                    saveHostCache();
                }
                return http;
            } else if (http.noData) {
                return http; // データが無いだけ。別ホストに同じものを聞き直さない
            } else if (http.status == 404 || http.status <= 0 || http.status >= 500) {
                if (http.status == 404 && BASE_URL2.equals(str4)) api2MissingPaths.add(pathKey); // api2 に無い API は次から飛ばす
                resp = http;
                // 書き込み系(POST/PUT/DELETE)がタイムアウト(応答なし)した場合は、サーバー側で処理済みの可能性が
                // あるため別ホストへ再送しない(二重投稿・二重送金の防止)
                if (http.status <= 0 && !"GET".equals(str)) return http;
            } else {
                return http;
            }
        }
        return resp;
    }

    /** 名前が実際に入っているキャッシュだけを「解決済み」とみなす(空文字のエントリは未解決扱い) */
    private boolean hasName(long uid) {
        String[] e = this.nameCache.get(Long.valueOf(uid));
        return e != null && e.length > 0 && e[0] != null && e[0].length() > 0;
    }

    /** レスポンス上位の user_info 配列(feed_posts / comments 等が同梱してくる)を名前キャッシュに吸収する */
    private void absorbUserInfo(JSONObject body) {
        if (body == null) return;
        JSONArray arr = body.optJSONArray("user_info");
        if (arr == null && body.optJSONObject("data") != null) arr = body.optJSONObject("data").optJSONArray("user_info");
        if (arr == null) arr = body.optJSONArray("users");
        if (arr == null) return;
        boolean changed = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject u = arr.optJSONObject(i);
            if (u == null) continue;
            long uid = u.optLong("user_id", u.optLong("id", 0));
            String nm = firstNonEmpty(u.optString("name", ""), u.optString("nickname", ""), u.optString("user_name", ""));
            if (uid == 0 || nm.length() == 0) continue;
            String[] prev = this.nameCache.get(Long.valueOf(uid));
            String icon = u.optString("profile_picture_file_path", "");
            if (icon.length() == 0 && prev != null && prev.length > 1) icon = prev[1];
            this.nameCache.put(Long.valueOf(uid), new String[]{nm, icon});
            changed = true;
        }
        if (changed) saveNameCache();
    }

    private void resolveNames(JSONArray jSONArray, String str) {
        JSONArray optJSONArray;
        ArrayList arrayList = new ArrayList();
        for (int i = 0; i < jSONArray.length(); i++) {
            JSONObject optJSONObject = jSONArray.optJSONObject(i);
            long optLong = optJSONObject != null ? optJSONObject.optLong(str) : jSONArray.optLong(i, 0); // 素の数値配列も許容
            {
                boolean needName = !hasName(optLong);
                boolean needDeco = !decoChecked.contains(Long.valueOf(optLong));
                if (optLong != 0 && (needName || needDeco) && !arrayList.contains(Long.valueOf(optLong))) {
                    arrayList.add(Long.valueOf(optLong));
                }
            }
        }
        if (!arrayList.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i2 = 0; i2 < arrayList.size(); i2++) {
                if (i2 > 0) {
                    sb.append(",");
                }
                sb.append(arrayList.get(i2));
            }
            Resp request = request("GET", "/api/v2/users", q1("ids", sb.toString()), (Map<String, String>) null);
            for (int k = 0; k < arrayList.size(); k++) decoChecked.add((Long) arrayList.get(k));
            if (request.body != null && (optJSONArray = request.body.optJSONArray("user_info")) != null) {
                boolean changed = false;
                for (int i3 = 0; i3 < optJSONArray.length(); i3++) {
                    JSONObject optJSONObject2 = optJSONArray.optJSONObject(i3);
                    if (optJSONObject2 != null) {
                        long optLong2 = optJSONObject2.optLong("user_id", optJSONObject2.optLong("id", 0));
                        String nm2 = firstNonEmpty(optJSONObject2.optString("name", ""), optJSONObject2.optString("nickname", ""), optJSONObject2.optString("user_name", ""));
                        // 名前が空のまま覚えると「user 1234567」表示が永久に固定されるので、空は覚えない(あとで個別に取り直す)
                        if (optLong2 != 0 && nm2.length() > 0) {
                            this.nameCache.put(Long.valueOf(optLong2), new String[]{nm2, optJSONObject2.optString("profile_picture_file_path", "")});
                            rememberDecoration(optLong2, optJSONObject2);
                            changed = true;
                        }
                    }
                }
                if (changed) {
                    saveNameCache();
                }
            }
            // まとめ取得(v2/users?ids=)は、退会・停止・非公開などの相手を黙って返さないことがある。
            // 「user 4093414」のようなIDだけの表示になるので、取り残した分だけ個別に取り直す。
            boolean extra = false;
            int tried = 0;
            for (int i4 = 0; i4 < arrayList.size() && tried < 5; i4++) {
                Long id = (Long) arrayList.get(i4);
                if (hasName(id.longValue())) continue;
                tried++;
                try {
                    Resp one = request("GET", "/api/v3/users/" + id, q1("fields", "core"), (Map<String, String>) null);
                    if (one.status != 200 || one.body == null) continue;
                    JSONObject u = one.body.optJSONObject("user_info");
                    if (u == null) {
                        JSONArray ua = one.body.optJSONArray("user_info");
                        if (ua != null && ua.length() > 0) u = ua.optJSONObject(0);
                    }
                    if (u == null) u = one.body;
                    String nm = firstNonEmpty(u.optString("name", ""), u.optString("nickname", ""));
                    if (nm.length() == 0) continue;
                    this.nameCache.put(id, new String[]{nm, u.optString("profile_picture_file_path", "")});
                    rememberDecoration(id.longValue(), u);
                    extra = true;
                } catch (Exception ig) {
                }
            }
            if (extra) saveNameCache();
        }
    }

    // 公式 OkHttpSingleton.generateChatMessageDeleteRequest:
    //   DELETE api.meetscom.com/api/chat/message?message_id=…&auth_token=…&version=android_…
    private String deleteChatMessage(String messageId) {
        return deleteChatMessage(messageId, "", "");
    }

    // 公式 generateChatMessageDeleteRequest は DELETE api/chat/message?message_id=… だが、
    // それだけでは消えない端末/相手があったので、経路を順に試して「実際に消えたか」で判定する。
    private String deleteChatMessage(String messageId, String chatId, String targetId) {
        if (messageId == null || messageId.length() == 0) return jsonErr("メッセージが不明です");
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("message_id", messageId);
        q.put("version", "android_" + APP_VERSION);
        if (chatId != null && chatId.length() > 0) q.put("chat_id", chatId);
        if (targetId != null && targetId.length() > 0) q.put("target_id", targetId);
        String at = authToken();
        if (at != null) q.put("auth_token", at);

        String[][] tries = new String[][]{
            {"DELETE", BASE_URL + "/api/chat/message"},
            {"DELETE", BASE_URL2 + "/api/chat/message"},
            {"DELETE", BASE_URL + "/api/chat/messages/" + messageId},
            {"DELETE", BASE_URL2 + "/api/chat/messages/" + messageId}
        };
        Resp last = new Resp(0, (JSONObject) null);
        for (int i = 0; i < tries.length; i++) {
            Resp r = http(tries[i][0], tries[i][1], q, (Map<String, String>) null);
            last = r;
            dbgLog(nowStr() + "  [MSG-DEL] " + tries[i][1] + " message=" + messageId + " -> " + r.status
                    + (r.status >= 200 && r.status < 300 ? "" : " " + truncate(redactLog(r.body != null ? r.body.toString() : ""), 140)));
            if (r.status >= 200 && r.status < 300) return okResultStatus(r);
        }
        try {
            String raw = last.body != null ? truncate(redactLog(last.body.toString()), 200) : "";
            return new JSONObject().put("ok", false).put("status", last.status)
                    .put("message", "サーバーがメッセージの削除を受け付けませんでした" + (last.status > 0 ? " (HTTP " + last.status + ")" : ""))
                    .put("raw", raw).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 相互フォロー(=お互いにフォローしている人)の一覧。
    // koetomo の friend_count は「友達申請が成立した人数」で相互フォローとは別物のため、
    // 数と中身が食い違わないよう、ここで数えたものをそのまま使う。
    private String getMutuals() {
        try {
            ensureRelationSets();
            java.util.Set<Long> fe, fr;
            synchronized (this) { fe = myFolloweeIds; fr = myFollowerIds; }
            if (fe == null || fr == null) return new JSONObject().put("ok", false).put("error", "relation_unavailable").toString();
            java.util.HashSet<Long> both = new java.util.HashSet<Long>(fe);
            both.retainAll(fr);
            JSONArray out = new JSONArray();
            if (!both.isEmpty()) {
                // 名前・アイコンは自分のフォロー一覧の応答から拾う(追加の往復を増やさない)
                long me = userId();
                java.util.HashSet<Long> seen = new java.util.HashSet<Long>();
                for (int page = 1; page <= 5 && seen.size() < both.size(); page++) {
                    Resp r = httpApi2("GET", "/api/v2/users/" + me + "/followees", q1("page", String.valueOf(page)), (Map<String, String>) null);
                    if (r == null || r.status != 200 || r.body == null) break;
                    JSONArray arr = normalizeUserList(r.body);
                    if (arr == null || arr.length() == 0) break;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject u = arr.optJSONObject(i);
                        if (u == null) continue;
                        Long id = Long.valueOf(u.optLong("user_id", 0));
                        if (!both.contains(id) || !seen.add(id)) continue;
                        u.put("is_following", true);
                        u.put("is_followed", true);
                        u.put("requested", false);
                        out.put(u);
                    }
                    if (arr.length() < 20) break;
                }
            }
            dbgLog(nowStr() + "  [MUTUAL] count=" + out.length());
            return new JSONObject().put("ok", true).put("users", out).put("count", out.length()).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String resolveUsers(String str) throws org.json.JSONException {
        JSONArray optJSONArray;
        if (str != null) {
            try {
                if (str.length() != 0) {
                    Resp request = request("GET", "/api/v2/users", q1("ids", str), (Map<String, String>) null);
                    JSONArray jSONArray = new JSONArray();
                    if (!(request.body == null || (optJSONArray = request.body.optJSONArray("user_info")) == null)) {
                        for (int i = 0; i < optJSONArray.length(); i++) {
                            JSONObject optJSONObject = optJSONArray.optJSONObject(i);
                            if (optJSONObject != null) {
                                jSONArray.put(new JSONObject().put("user_id", optJSONObject.optLong("user_id")).put("name", optJSONObject.optString("name", "")).put("icon_url", iconUrl(optJSONObject.optString("profile_picture_file_path", ""))));
                            }
                        }
                    }
                    return new JSONObject().put("ok", true).put("users", jSONArray).toString();
                }
            } catch (Exception e) {
                return errJson(e);
            }
        }
        return new JSONObject().put("ok", true).put("users", new JSONArray()).toString();
    }

    private String roomClose(String str) {
        return (str == null || str.length() == 0) ? jsonErr("room_id不明") : okResult(request("DELETE", "/api/rooms/" + str, (Map<String, String>) null, (Map<String, String>) null));
    }

    private String roomLeave(String str) {
        if (str == null || str.length() == 0) return jsonErr("room_id不明");
        // 公式 TalkRoomApi.leaveTalkRoom: DELETE api2 /api/rooms/{id}/leave (POST は 404)
        Resp r = request2("DELETE", "/api/rooms/" + str + "/leave", (Map<String, String>) null, (Map<String, String>) null);
        if (r.status == 404 || r.status == 405) {
            r = request2("POST", "/api/rooms/" + str + "/leave", (Map<String, String>) null, new HashMap<String, String>());
        }
        dbgLog(nowStr() + "  [ROOM] leave " + str + " -> " + r.status + (r.status >= 400 && r.body != null ? " " + truncate(redactLog(r.body.toString()), 200) : ""));
        // 自分が枠主なら退出だけでは枠が残り、次回の枠作成が「作成超過」で弾かれる。
        // 誰も残っていなければ枠自体を閉じる（公式も枠主の終了で DELETE /api/rooms/{id}）。
        try {
            long uid = userId();
            Resp st = httpApi2("GET", "/api/rooms/" + str, (Map<String, String>) null, (Map<String, String>) null);
            if (st != null && st.status == 200 && st.body != null) {
                JSONObject ro = st.body.optJSONObject("data") != null ? st.body.optJSONObject("data") : st.body;
                long owner = ro.optLong("owner_user_id", ro.optLong("owner", 0));
                if (owner != 0 && owner == uid) {
                    // 公式(TalkRoomFragment.leaveRoom → closeRoom)と同じく、枠主の退出は枠の終了。
                    // 閉じないと枠が残り続け、次の作成が「ルーム作成超過」で拒否される。
                    closeRoomById(str);
                    myOpenRoomId = null;
                }
            }
        } catch (Exception e) {
        }
        return okResult(r);
    }

    private String roomUpdateTitle(String str, String str2) {
        return (str == null || str.length() == 0) ? jsonErr("room_id不明") : okResult(request("PUT", "/api/rooms/" + str, q1("description", str2), (Map<String, String>) null));
    }

    private String roomKickUser(String str, String str2) {
        if (str == null || str.length() == 0) {
            return jsonErr("room_id不明");
        }
        if (str2 == null || str2.length() == 0) {
            return jsonErr("target_id不明");
        }
        // 公式 TalkRoomApi.kickRoomUser: POST /api/rooms/{id}/kick （FORM: target_id）
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("target_id", str2);
        return okResult(request("POST", "/api/rooms/" + str + "/kick", (Map<String, String>) null, fields));
    }

    private String roomSwitchCommentEnabled(String str, boolean z) {
        if (str == null || str.length() == 0) {
            return jsonErr("room_id不明");
        }
        // 公式 TalkRoomApi.setCommentEnabled は comment_enabled を 1/0(int)・version=android_3.9.101 で送る。
        // "true"/"false" だとサーバーが「パラメータ異常値」(400/2004)を返す。
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("comment_enabled", z ? "1" : "0");
        q.put("version", "android_" + APP_VERSION);
        Resp r = request("PUT", "/api/rooms/" + str + "/switch_comment_enabled", q, (Map<String, String>) null);
        if (r.status == 400) {
            // クエリで弾かれた場合はフォーム本文で再送
            HashMap<String, String> f = new HashMap<String, String>();
            f.put("comment_enabled", z ? "1" : "0");
            f.put("version", "android_" + APP_VERSION);
            r = request("PUT", "/api/rooms/" + str + "/switch_comment_enabled", (Map<String, String>) null, f);
        }
        return okResult(r);
    }

    /**
     * 枠の SkyWay 接続種別(公式 JoinTrialResponse.Room.connectionType)を返す。
     * 1=SFU(公式の既定) / 2=P2P / 0=不明。枠オブジェクトに無ければ join_trial から取る。
     */
    private int resolveConnectionType(JSONObject room, long roomId) {
        int type = room.optInt("connection_type", room.optInt("connectionType", 0));
        if (type != 0 || roomId == 0) return type;
        try {
            JSONObject res = new JSONObject(roomJoinTrial(String.valueOf(roomId)));
            JSONObject body = res.optJSONObject("body");
            JSONObject data = body != null ? body.optJSONObject("data") : null;
            JSONObject r = data != null ? data.optJSONObject("room") : (body != null ? body.optJSONObject("room") : null);
            if (r != null) type = r.optInt("connection_type", r.optInt("connectionType", 0));
        } catch (Exception ignored) {
        }
        return type;
    }

    /**
     * 公式サーバー上の「タイムライン背景画像」(他ユーザーの投稿一覧にも表示される)を外す。
     * 公式 OkHttpSingleton.generateTimelineBackgroundUpdateRequest と同じ PUT を空パスで送り、
     * それでも残る場合は完全透明の画像で上書きする。ユーザー設定(表示スイッチ)には触らない。
     */
    /** 内部専用の画像種別。dispatch("upload_account_image") では受け付けない。 */
    private static final String TIMELINE_IMAGE_KIND = "timeline_internal";

    /** 完全透明の小さな PNG(Base64)。公開背景を「見えない画像」で上書きする時に使う。 */
    private static String transparentPngBase64() {
        Bitmap bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(0x00000000);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
        bmp.recycle();
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    /** 自分のアカウントに設定されている公開タイムライン背景のパス(無ければ "")。 */
    private String myTimelineImagePath() {
        try {
            Resp r = request("GET", "/api/v2/users", q1("ids", String.valueOf(userId())), (Map<String, String>) null);
            JSONArray arr = r.body != null ? r.body.optJSONArray("user_info") : null;
            JSONObject me = arr != null && arr.length() > 0 ? arr.optJSONObject(0) : null;
            return me != null ? me.optString("timeline_image_file_path", "") : "";
        } catch (Exception e) {
            return "";
        }
    }

    private String clearPublicTimelineImage() {
        try {
            if (myTimelineImagePath().length() == 0) {
                return new JSONObject().put("ok", true).put("already_clear", true).toString();
            }
            HashMap<String, String> form = new HashMap<String, String>();
            form.put("timeline_image_file_path", "");
            form.put("version", "android_" + APP_VERSION);
            String authToken = authToken();
            if (authToken != null) form.put("auth_token", authToken);
            Resp img = http("PUT", BASE_URL + "/api/account/timeline_images", (Map<String, String>) null, form);
            if (img.status == 404 || img.status >= 500) {
                img = http("PUT", BASE_URL2 + "/api/account/timeline_images", (Map<String, String>) null, form);
            }
            dbgLog(nowStr() + "  [TLIMG] clear image=" + img.status);
            // 空パスをサーバーが受け付けなかった場合は、完全透明の 1x1 画像で上書きして見えなくする
            String remaining = myTimelineImagePath();
            String overwrite = null;
            if (remaining.length() > 0 && !"null".equals(remaining)) {
                overwrite = uploadAccountImage(transparentPngBase64(), TIMELINE_IMAGE_KIND);
                dbgLog(nowStr() + "  [TLIMG] transparent overwrite -> " + truncate(overwrite, 120));
            }
            boolean cleared = remaining.length() == 0 || "null".equals(remaining)
                    || (overwrite != null && new JSONObject(overwrite).optBoolean("ok"));
            if (cleared) decoCache.remove(Long.valueOf(userId()));
            return new JSONObject().put("ok", cleared).put("image_status", img.status)
                    .put("overwritten", overwrite != null).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String roomJoinTrial(String str) {
        // 公式 TalkRoomApi.joinTrial: POST /api/rooms/join_trial?room_id=<id>
        HashMap<String, String> q = new HashMap<String, String>();
        if (str != null && str.length() > 0) q.put("room_id", str);
        return okResult(request("POST", "/api/rooms/join_trial", q, new HashMap<String, String>()));
    }

    private String roomInvite(String str, String str2) {
        if (str == null || str.length() == 0) {
            return jsonErr("room_id不明");
        }
        if (str2 == null || str2.length() == 0) {
            return jsonErr("target_id不明");
        }
        // 公式 TalkRoomApi.invite: POST /api/rooms/{id}/invite?target_ids=<カンマ区切り>
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("target_ids", str2);
        return okResult(request("POST", "/api/rooms/" + str + "/invite", q, new HashMap<String, String>()));
    }

    private String getParticipatingCommunityTalkRooms() {
        // 公式 TalkRoomApi.getMyCommunityTalkRoomList は order / page を必ず送る
        Resp resp = request("GET", "/api/communities/participating_talk_rooms", q2("page", "1", "order", "1"), (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return gracefulUnavailable(resp, "talk_rooms", "communities participating_talk_rooms");
            }
            JSONArray rooms;
            Object data = resp.body.opt("data");
            if (data instanceof JSONArray) {
                rooms = (JSONArray) data;
            } else if (data instanceof JSONObject) {
                JSONArray t = ((JSONObject) data).optJSONArray("talk_rooms");
                rooms = t == null ? ((JSONObject) data).optJSONArray("rooms") : t;
            } else {
                JSONArray t = resp.body.optJSONArray("talk_rooms");
                rooms = t == null ? resp.body.optJSONArray("rooms") : t;
            }
            if (rooms == null) {
                rooms = new JSONArray();
            }
            JSONArray ownerIds = new JSONArray();
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject r = rooms.optJSONObject(i);
                if (r != null) {
                    ownerIds.put(new JSONObject().put("user_id", r.optLong("owner", r.optLong("owner_user_id"))));
                }
            }
            resolveNames(ownerIds, "user_id");
            JSONArray out = new JSONArray();
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject r = rooms.optJSONObject(i);
                if (r == null) continue;
                long ownerId = r.optLong("owner", r.optLong("owner_user_id"));
                String[] cached = this.nameCache.get(Long.valueOf(ownerId));
                JSONArray speakers = r.optJSONArray("speakers");
                JSONArray listeners = r.optJSONArray("listeners");
                int speakerCount = speakers == null ? 0 : speakers.length();
                int listenerCount = listeners == null ? 0 : listeners.length();
                JSONObject community = r.optJSONObject("community");
                JSONObject o = new JSONObject();
                o.put("id", r.opt("id"));
                o.put("community_id", community != null ? community.opt("id") : r.opt("community_id"));
                o.put("community_name", community != null ? community.optString("name", "") : r.optString("community_name", ""));
                o.put("owner_user_id", ownerId);
                o.put("owner_name", cached != null ? cached[0] : "user " + ownerId);
                o.put("owner_icon", cached != null ? iconUrl(cached[1]) : "");
                o.put("title", r.optString("description", r.optString("title", "")));
                o.put("speaker_count", speakerCount);
                o.put("listener_count", listenerCount);
                o.put("member_count", speakerCount + listenerCount);
                out.put(o);
            }
            return new JSONObject().put("ok", true).put("talk_rooms", out).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCommunityTalkRooms(String communityId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        Resp resp = request("GET", "/api/communities/" + communityId + "/talk_rooms", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            JSONArray rooms;
            Object data = resp.body.opt("data");
            if (data instanceof JSONArray) {
                rooms = (JSONArray) data;
            } else if (data instanceof JSONObject) {
                JSONArray t = ((JSONObject) data).optJSONArray("talk_rooms");
                rooms = t == null ? ((JSONObject) data).optJSONArray("rooms") : t;
            } else {
                JSONArray t = resp.body.optJSONArray("talk_rooms");
                rooms = t == null ? resp.body.optJSONArray("rooms") : t;
            }
            if (rooms == null) {
                rooms = new JSONArray();
            }
            JSONArray ownerIds = new JSONArray();
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject r = rooms.optJSONObject(i);
                if (r != null) {
                    ownerIds.put(new JSONObject().put("user_id", r.optLong("owner", r.optLong("owner_user_id"))));
                }
            }
            resolveNames(ownerIds, "user_id");
            JSONArray out = new JSONArray();
            for (int i = 0; i < rooms.length(); i++) {
                JSONObject r = rooms.optJSONObject(i);
                if (r == null) continue;
                long ownerId = r.optLong("owner", r.optLong("owner_user_id"));
                String[] cached = this.nameCache.get(Long.valueOf(ownerId));
                JSONArray speakers = r.optJSONArray("speakers");
                JSONArray listeners = r.optJSONArray("listeners");
                int speakerCount = speakers == null ? 0 : speakers.length();
                int listenerCount = listeners == null ? 0 : listeners.length();
                JSONObject o = new JSONObject();
                o.put("id", r.opt("id"));
                o.put("owner_user_id", ownerId);
                o.put("owner_name", cached != null ? cached[0] : "user " + ownerId);
                o.put("owner_icon", cached != null ? iconUrl(cached[1]) : "");
                o.put("title", r.optString("description", r.optString("title", "")));
                o.put("speaker_count", speakerCount);
                o.put("listener_count", listenerCount);
                o.put("member_count", speakerCount + listenerCount);
                o.put("comment_enabled", r.optBoolean("comment_enabled", true));
                o.put("created_at", r.optString("created_at", r.optString("started_at", "")));
                out.put(o);
            }
            return new JSONObject().put("ok", true).put("talk_rooms", out).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCommunityTalkRoom(String communityId, String roomId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        if (roomId == null || roomId.length() == 0) {
            return jsonErr("room_id不明");
        }
        Resp resp = request("GET", "/api/communities/" + communityId + "/talk_rooms/" + roomId, (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            JSONObject room = resp.body.optJSONObject("data");
            if (room == null) {
                room = resp.body.optJSONObject("talk_room");
            }
            if (room == null) {
                room = resp.body;
            }
            return new JSONObject().put("ok", true).put("room", room).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String joinCommunityTalkRoom(String communityId, String roomId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        if (roomId == null || roomId.length() == 0) {
            return jsonErr("room_id不明");
        }
        return okResultStatus(request("POST", "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/join", (Map<String, String>) null, new HashMap()));
    }

    private String leaveCommunityTalkRoom(String communityId, String roomId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        if (roomId == null || roomId.length() == 0) {
            return jsonErr("room_id不明");
        }
        Resp r = request2("DELETE", "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/leave", (Map<String, String>) null, (Map<String, String>) null);
        if (r.status == 404 || r.status == 405) r = request2("POST", "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/leave", (Map<String, String>) null, new HashMap<String, String>());
        return okResultStatus(r);
    }

    private String kickCommunityTalkRoomUser(String communityId, String roomId, String targetId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        if (roomId == null || roomId.length() == 0) {
            return jsonErr("room_id不明");
        }
        if (targetId == null || targetId.length() == 0) {
            return jsonErr("target_id不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("target_id", targetId);
        return okResult(request("POST", "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/kick", fields, (Map<String, String>) null));
    }

    private String changeCommunityTalkRoomRole(String communityId, String roomId, String targetId, String role) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        if (roomId == null || roomId.length() == 0) {
            return jsonErr("room_id不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("role", role);
        fields.put("target_id", targetId);
        return okResult(request("PUT", "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/change_role", fields, (Map<String, String>) null));
    }

    private String switchCommunityTalkRoomCommentEnabled(String communityId, String roomId, boolean enabled) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        if (roomId == null || roomId.length() == 0) {
            return jsonErr("room_id不明");
        }
        // 公式 TalkRoomApi.setCommentEnabledForCommunity は @Body JSON {"comment_enabled":true/false}
        // (枠側の /api/rooms/{id}/switch_comment_enabled はクエリで、コミュニティ側だけ本文JSON)
        try {
            JSONObject body = new JSONObject().put("comment_enabled", enabled);
            Resp rr = httpJson("PUT", BASE_URL + "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/switch_comment_enabled", body);
            if (rr.status == 404 || rr.status >= 500) {
                rr = httpJson("PUT", BASE_URL2 + "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/switch_comment_enabled", body);
            }
            return okResult(rr);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCommunityTalkRoomComments(String communityId, String roomId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        if (roomId == null || roomId.length() == 0) {
            return jsonErr("room_id不明");
        }
        Resp resp = request("GET", "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/comments", q1("page", "1"), (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            JSONArray comments = resp.body.optJSONArray("comments");
            Object data = resp.body.opt("data");
            if (comments == null && data instanceof JSONArray) {
                comments = (JSONArray) data;
            }
            if (comments == null && data instanceof JSONObject) {
                comments = firstArray((JSONObject) data, "comments");
            }
            if (comments == null) {
                comments = new JSONArray();
            }
            JSONArray ids = new JSONArray();
            for (int i = 0; i < comments.length(); i++) {
                JSONObject c = comments.optJSONObject(i);
                if (c != null) {
                    ids.put(new JSONObject().put("user_id", commentUid(c))); // resolveNames は {user_id} オブジェクト配列を要求
                }
            }
            resolveNames(ids, "user_id");
            JSONArray out = new JSONArray();
            for (int i = 0; i < comments.length(); i++) {
                JSONObject c = comments.optJSONObject(i);
                if (c == null) continue;
                long uid = commentUid(c);
                String[] cached = this.nameCache.get(Long.valueOf(uid));
                JSONObject o = new JSONObject();
                o.put("user_id", uid);
                o.put("name", cached != null ? cached[0] : "user " + uid);
                o.put("icon_url", cached != null ? iconUrl(cached[1]) : "");
                o.put("text", firstStr(c, "comment", "text", "description", "body"));
                o.put("created_at", firstStr(c, "created_at", "createdAt"));
                out.put(o);
            }
            return new JSONObject().put("ok", true).put("comments", out).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // チャットの画像/音声は非公開バケットにあり、公式は S3 の署名付きURL(5分)で表示する。
    // ここでも Cognito の一時認証情報で GET の presigned URL を作る。
    private int countAttach(JSONArray arr) {
        int n = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && (o.optString("image_url", "").length() > 0 || o.optString("voice_url", "").length() > 0)) n++;
        }
        return n;
    }

    private String s3PresignGet(JSONObject cfg, JSONObject creds, String key, int expireSec) {
        try {
            String region = cfg.getString("region");
            String bucket = cfg.getString("bucket");
            String ak = creds.getString("AccessKeyId");
            String sk = creds.getString("SecretKey");
            String st = creds.getString("SessionToken");
            String host = "s3." + region + ".amazonaws.com";
            SimpleDateFormat f1 = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
            f1.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat f2 = new SimpleDateFormat("yyyyMMdd", Locale.US);
            f2.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date now = new Date();
            String amzDate = f1.format(now), day = f2.format(now);
            String scope = day + "/" + region + "/s3/aws4_request";
            String canonKey = "/" + bucket + "/" + key;
            String q = "X-Amz-Algorithm=AWS4-HMAC-SHA256"
                    + "&X-Amz-Credential=" + urlEnc(ak + "/" + scope)
                    + "&X-Amz-Date=" + amzDate
                    + "&X-Amz-Expires=" + (expireSec > 0 ? expireSec : 300)
                    + "&X-Amz-Security-Token=" + urlEnc(st)
                    + "&X-Amz-SignedHeaders=host";
            String canonical = "GET\n" + canonKey + "\n" + q + "\nhost:" + host + "\n\nhost\nUNSIGNED-PAYLOAD";
            String sts = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonical.getBytes("UTF-8"));
            String sig = hex(hmac(hmac(hmac(hmac(hmac(("AWS4" + sk).getBytes("UTF-8"), day), region), "s3"), "aws4_request"), sts));
            return "https://" + host + canonKey + "?" + q + "&X-Amz-Signature=" + sig;
        } catch (Exception e) {
            dbgLog(nowStr() + "  [S3] presign失敗 " + e);
            return "";
        }
    }

    private String urlEnc(String s) {
        try {
            String e = java.net.URLEncoder.encode(s, "UTF-8");
            return e.replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
        } catch (Exception ex) { return s; }
    }

    private String s3PutBytes(JSONObject jSONObject, JSONObject jSONObject2, byte[] bArr, String str, String str2) {
        try {
            String string = jSONObject.getString("region");
            String string2 = jSONObject.getString("bucket");
            String string3 = jSONObject2.getString("AccessKeyId");
            String string4 = jSONObject2.getString("SecretKey");
            String string5 = jSONObject2.getString("SessionToken");
            String str3 = "s3." + string + ".amazonaws.com";
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
            simpleDateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat simpleDateFormat2 = new SimpleDateFormat("yyyyMMdd", Locale.US);
            simpleDateFormat2.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = new Date();
            String format = simpleDateFormat.format(date);
            String format2 = simpleDateFormat2.format(date);
            String sha256Hex = sha256Hex(bArr);
            String str4 = "/" + string2 + "/" + str;
            String str5 = "PUT\n" + str4 + "\n\n" + ("content-type:" + str2 + "\nhost:" + str3 + "\nx-amz-content-sha256:" + sha256Hex + "\nx-amz-date:" + format + "\nx-amz-security-token:" + string5 + "\n") + "\n" + "content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token" + "\n" + sha256Hex;
            String str6 = format2 + "/" + string + "/" + "s3" + "/aws4_request";
            String str7 = "AWS4-HMAC-SHA256 Credential=" + string3 + "/" + str6 + ", SignedHeaders=" + "content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token" + ", Signature=" + hex(hmac(hmac(hmac(hmac(hmac(("AWS4" + string4).getBytes("UTF-8"), format2), string), "s3"), "aws4_request"), "AWS4-HMAC-SHA256\n" + format + "\n" + str6 + "\n" + sha256Hex(str5.getBytes("UTF-8"))));
            HttpURLConnection httpURLConnection = (HttpURLConnection) new URL("https://" + str3 + str4).openConnection();
            httpURLConnection.setConnectTimeout(15000);
            httpURLConnection.setReadTimeout(30000);
            httpURLConnection.setRequestMethod("PUT");
            httpURLConnection.setDoOutput(true);
            httpURLConnection.setRequestProperty("Content-Type", str2);
            httpURLConnection.setRequestProperty("x-amz-content-sha256", sha256Hex);
            httpURLConnection.setRequestProperty("x-amz-date", format);
            httpURLConnection.setRequestProperty("x-amz-security-token", string5);
            httpURLConnection.setRequestProperty("Authorization", str7);
            httpURLConnection.setFixedLengthStreamingMode(bArr.length);
            OutputStream outputStream = httpURLConnection.getOutputStream();
            outputStream.write(bArr);
            outputStream.flush();
            outputStream.close();
            int responseCode = httpURLConnection.getResponseCode();
            String str8 = (responseCode < 200 || responseCode >= 300) ? "S3 PUT失敗 HTTP " + responseCode + ": " + truncate(readBody(httpURLConnection, responseCode), 300) : null;
            httpURLConnection.disconnect();
            return str8;
        } catch (Exception e) {
            return "S3 PUT例外: " + e.getMessage();
        }
    }

    private String s3PutPng(JSONObject jSONObject, JSONObject jSONObject2, byte[] bArr, String str) {
        return s3PutBytes(jSONObject, jSONObject2, bArr, str, "image/png");
    }

    private String searchCommunities(String str, String str2) {
        HashMap hashMap = new HashMap();
        hashMap.put("page", "1");
        hashMap.put("count", "20");
        if (str != null && str.length() > 0) {
            hashMap.put("keyword", str);
        }
        if (str2 != null && str2.length() > 0 && !str2.equals("0") && !str2.equals("all")) {
            hashMap.put("category_id", str2);
        }
        return communitiesResult(request("GET", "/api/communities/search", hashMap, (Map<String, String>) null));
    }

    private String searchUsers(String str, String str2) {
        JSONArray jSONArray = null;
        try {
            HashMap hashMap = new HashMap();
            // 公式 generateSearchUserRequest はクエリ名が "name"。"user_name" では絞り込まれない。
            if (str != null && str.length() > 0) {
                hashMap.put("name", str);
                hashMap.put("consistency", "true");
            }
            if (str2 == null || str2.length() == 0) {
                str2 = "1";
            }
            hashMap.put("page", str2);
            Resp request = request("GET", "/api/v2/users/search", hashMap, (Map<String, String>) null);
            if (request.status != 200 || request.body == null) {
                return new JSONObject().put("ok", false).put("status", request.status).toString();
            }
            JSONObject optJSONObject = request.body.optJSONObject("data");
            if (optJSONObject != null) {
                jSONArray = firstArray(optJSONObject, "users", "user_info", "data");
            }
            if (jSONArray == null) {
                jSONArray = request.body.optJSONArray("users");
            }
            if (jSONArray == null) {
                jSONArray = new JSONArray();
            }
            JSONArray jSONArray2 = new JSONArray();
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject2 = jSONArray.optJSONObject(i);
                if (optJSONObject2 != null) {
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("user_id", optJSONObject2.optLong("id", optJSONObject2.optLong("user_id", 0)));
                    jSONObject.put("name", firstNonEmpty(optJSONObject2.optString("nickname", ""), optJSONObject2.optString("name", "")));
                    jSONObject.put("icon_url", iconUrl(optJSONObject2.optString("profile_picture_file_path", optJSONObject2.optString("profilePictureFilePath", ""))));
                    if (!optJSONObject2.isNull("age")) {
                        jSONObject.put("age", optJSONObject2.opt("age"));
                    }
                    jSONArray2.put(jSONObject);
                }
            }
            return new JSONObject().put("ok", true).put("users", jSONArray2).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String sendMessage(String str, String str2, String str3) {
        HashMap hashMap = new HashMap();
        hashMap.put("target_id", str2);
        hashMap.put("chat_id", str);
        hashMap.put("uid", String.valueOf(userId()));
        hashMap.put("text_message", str3);
        hashMap.put("message_type", "1");
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        // メッセージ送信も2xx成功時に非0のX-Vsns-Statusを返すことがある(いいね/コミュニティ退会と同じ罠)ため、
        // HTTPステータスのみで成否判定するokResultStatus()を使う(okResult()だと誤ってok=falseになる)。
        return okResultStatus(http("POST", "https://api.meetscom.com/api/chat/messages", (Map<String, String>) null, hashMap));
    }

    // チャットに画像を送る。投稿画像と同じS3アップロード経路を流用し、image_file_path で送信。
    private String sendImageMessage(String chatId, String targetId, String dataUrl) {
        if (dataUrl == null || dataUrl.length() == 0) return jsonErr("画像がありません");
        try {
            String b64 = dataUrl;
            int comma = b64.indexOf(44);
            if (b64.startsWith("data:") && comma >= 0) b64 = b64.substring(comma + 1);
            byte[] decode = Base64.decode(b64, 0);
            if (decode.length == 0) return jsonErr("画像データが空です");
            Bitmap bmp = BitmapFactory.decodeByteArray(decode, 0, decode.length);
            if (bmp == null) return jsonErr("画像を読み込めませんでした");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            byte[] bytes = bos.toByteArray();
            JSONObject cfg = imageS3Config();
            JSONObject creds = cognitoCredentials(cfg);
            String bareName = UUID.randomUUID().toString().replace("-", "") + ".png";
            String uploadKey = bareName;
            String path = cfg.optString("path", "");
            if (path != null && path.length() > 0) uploadKey = path.replaceAll("^/+", "").replaceAll("/+$", "") + "/" + bareName;
            String err = s3PutPng(cfg, creds, bytes, uploadKey);
            if (err != null) return jsonErr(err);
            String md5 = md5Hex(bytes);
            HashMap hashMap = new HashMap();
            hashMap.put("target_id", targetId);
            hashMap.put("chat_id", chatId);
            hashMap.put("uid", String.valueOf(userId()));
            hashMap.put("message_type", "2");
            // 公式 generateImageFileTransmissionRequest は binary_file_path（ファイル名だけ）。
            // 旧実装は image_file_path で送っており、サーバー側で画像が付かなかった。
            hashMap.put("binary_file_path", bareName);
            hashMap.put("md5", md5);
            hashMap.put("version", "android_3.9.101");
            String authToken = authToken();
            if (authToken != null) hashMap.put("auth_token", authToken);
            return okResultStatus(http("POST", "https://api.meetscom.com/api/chat/messages", (Map<String, String>) null, hashMap));
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // チャットに音声を送る（公式 generateVoiceFileTransmissionRequest:
    //   POST api/chat/messages FORM target_id, chat_id, binary_file_path, play_time, uid, message_type=3）
    private String sendVoiceMessage(String chatId, String targetId, String dataUrl, String ext, String mime, String playTimeSec) {
        if (dataUrl == null || dataUrl.length() == 0) return jsonErr("音声がありません");
        try {
            String b64 = dataUrl;
            int comma = b64.indexOf(44);
            if (b64.startsWith("data:") && comma >= 0) b64 = b64.substring(comma + 1);
            byte[] bytes = Base64.decode(b64, 0);
            if (bytes.length == 0) return jsonErr("音声データが空です");
            if (ext == null || ext.length() == 0) ext = "webm";
            String type = (mime == null || mime.length() == 0) ? "audio/webm" : mime;
            JSONObject cfg = imageS3Config();
            JSONObject creds = cognitoCredentials(cfg);
            String bareName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            String uploadKey = bareName;
            String path = cfg.optString("path", "");
            if (path != null && path.length() > 0) uploadKey = path.replaceAll("^/+", "").replaceAll("/+$", "") + "/" + bareName;
            String err = s3PutBytes(cfg, creds, bytes, uploadKey, type);
            if (err != null) return jsonErr(err);
            HashMap<String, String> hashMap = new HashMap<String, String>();
            hashMap.put("target_id", targetId);
            hashMap.put("chat_id", chatId);
            hashMap.put("uid", String.valueOf(userId()));
            hashMap.put("message_type", "3");
            hashMap.put("binary_file_path", bareName);
            hashMap.put("play_time", (playTimeSec == null || playTimeSec.length() == 0) ? "0" : playTimeSec);
            hashMap.put("version", "android_3.9.101");
            String authToken = authToken();
            if (authToken != null) hashMap.put("auth_token", authToken);
            Resp r = http("POST", "https://api.meetscom.com/api/chat/messages", (Map<String, String>) null, hashMap);
            dbgLog(nowStr() + "  [CHAT] voice送信 chat=" + chatId + " -> " + r.status + (r.status >= 400 && r.body != null ? " " + truncate(redactLog(r.body.toString()), 200) : ""));
            return okResultStatus(r);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String sendRoomComment(String str, String str2) {
        if (str == null || str.length() == 0) {
            return jsonErr("room_id不明");
        }
        HashMap<String, String> hashMap = new HashMap<String, String>();
        hashMap.put("room_id", str);
        hashMap.put("comment", str2);
        // 公式 TalkRoomApi.postRoomComment: POST api2 /api/room_comments (FORM room_id, comment)。
        // 旧サーバー(api)に投げると 200 が返っても Firebase(RTDB) に配信されず、自分の発言が誰にも表示されない。
        Resp r = request2("POST", "/api/room_comments", (Map<String, String>) null, hashMap);
        dbgLog(nowStr() + "  [CHAT] send room=" + str + " -> " + r.status + (r.body != null ? " " + truncate(redactLog(r.body.toString()), 200) : ""));
        return okResult(r);
    }

    public boolean hasAuthToken() { try { String t = authToken(); return t != null && t.length() > 0; } catch (Exception e) { return false; } }

    private void setAuthToken(String str) {
        this.prefs.edit().putString("auth_token", str == null ? null : secure().encrypt(str)).apply();
    }

    private String setModerationSettings(boolean z, boolean z2, boolean z3) {
        this.prefs.edit().putBoolean("mod_auto_approve", z).putBoolean("mod_auto_reject", z2).putBoolean("mod_auto_raise_hand", z3).apply();
        try {
            return new JSONObject().put("ok", true).put("settings", new JSONObject().put("auto_approve", z).put("auto_reject", z2).put("auto_raise_hand", z3)).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private void setUserId(long j) {
        this.prefs.edit().putLong("user_id", j).apply();
    }

    private void setUserName(String str) {
        this.prefs.edit().putString("user_name", str).apply();
    }

    private String setUserSettings(String str) {
        try {
            if (str == null || str.length() == 0) {
                str = "{}";
            }
            JSONObject jSONObject = new JSONObject(str);
            HashMap hashMap = new HashMap();
            Iterator<String> keys = jSONObject.keys();
            while (keys.hasNext()) {
                String next = keys.next();
                Object opt = jSONObject.opt(next);
                if (opt instanceof Boolean) {
                    hashMap.put(next, ((Boolean) opt).booleanValue() ? "1" : "0");
                } else {
                    hashMap.put(next, String.valueOf(opt));
                }
            }
            hashMap.put("version", "android_3.9.101");
            String authToken = authToken();
            if (authToken != null) {
                hashMap.put("auth_token", authToken);
            }
            Resp http = http("PUT", "https://api.meetscom.com/api/account/user_settings", (Map<String, String>) null, hashMap);
            if (http.status == 404 || http.status >= 500) {
                http = http("PUT", "https://api2.meetscom.com/api/account/user_settings", (Map<String, String>) null, hashMap);
            }
            dbgLog(nowStr() + "  [SETTINGS] update " + str + " -> " + http.status + " vsns=" + http.vsns);
            if (http.status >= 200 && http.status < 300) {
                try {
                    android.content.SharedPreferences.Editor ed = appContext.getSharedPreferences("koe_usersettings", 0).edit();
                    Iterator<String> ks = jSONObject.keys();
                    while (ks.hasNext()) { String k = ks.next(); ed.putBoolean(k, truthy(jSONObject.opt(k))); }
                    ed.apply();
                } catch (Exception ig) {
                }
            }
            return okResultStatus(http);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private static String sha256Hex(byte[] bArr) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(bArr));
    }

    private String signup(String str, String str2, String str3, String str4, String str5) {
        HashMap hashMap = new HashMap();
        if (str == null) {
            str = "";
        }
        hashMap.put("email", str);
        if (str2 == null) {
            str2 = "";
        }
        hashMap.put("password", str2);
        hashMap.put("name", str3 == null ? "" : str3);
        if (str4 == null) {
            str4 = "";
        }
        hashMap.put("sex", str4);
        if (str5 == null) {
            str5 = "";
        }
        hashMap.put("birthday", str5);
        hashMap.put("device_uid", deviceUid());
        hashMap.put("version", APP_VERSION);
        hashMap.put("etat2", "");
        hashMap.put("vt2", "");
        hashMap.put("gt2", "");
        hashMap.put("birthday_input_error", "");
        Resp http = httpLoginPost("https://api.meetscom.com/api/account/signup", hashMap);
        String truncate = http.body != null ? truncate(http.body.toString(), 500) : "(応答なし HTTP " + http.status + ")";
        try {
            if ((http.status == 200 || http.status == 201) && http.body != null) {
                JSONObject optJSONObject = http.body.optJSONObject("data");
                if (optJSONObject == null) {
                    optJSONObject = new JSONObject();
                }
                String optString = optJSONObject.optString("auth_token", "");
                if (optString.length() > 0) {
                    setAuthToken(optString);
                }
                setUserId(optJSONObject.optLong("user_id", userId()));
                setUserName(optJSONObject.optString("name", str3));
                setBirthday(str5);
                if (userId() == 0) {
                    long deepFindLong = deepFindLong(http.body, "user_id");
                    if (deepFindLong == 0) {
                        deepFindLong = deepFindLong(http.body, "id");
                    }
                    if (deepFindLong != 0) {
                        setUserId(deepFindLong);
                    }
                }
                return (authToken() == null || authToken().length() <= 0) ? new JSONObject().put("ok", false).put("message", "登録応答にトークンがありません。サーバーの不正検知で弾かれた可能性があります。").put("raw", truncate).toString() : new JSONObject().put("ok", true).put("user_name", userName()).put("user_id", userId()).put("raw", truncate).toString();
            }
            String extractError = extractError(http.body);
            if (http.status == 503) {
                extractError = "試行が多すぎるため一時的に制限されています。10〜30分ほど待ってから、もう一度だけお試しください(連打すると制限が延びます)";
            } else if (extractError == null) {
                extractError = "登録に失敗しました(HTTP " + http.status + ")。サーバーの不正検知で弾かれた可能性があります。";
            }
            return new JSONObject().put("ok", false).put("status", http.status).put("message", extractError).put("raw", truncate).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String soundServerName() {
        JSONObject jSONObject = null;
        ensureSkywayHost();
        try {
            JSONObject optJSONObject = this.clientDefines != null ? this.clientDefines.optJSONObject("client_system_params") : null;
            if (optJSONObject != null) {
                jSONObject = optJSONObject.optJSONObject("server_name");
            }
            if (jSONObject != null) {
                String firstNonEmpty = firstNonEmpty(jSONObject.optString("sound", ""), jSONObject.optString("talk", ""), jSONObject.optString("voice", ""), jSONObject.optString("cloud_front", ""), jSONObject.optString("cloudfront", ""), jSONObject.optString("movie", ""), jSONObject.optString("video", ""));
                if (firstNonEmpty.length() > 0) {
                    if (!firstNonEmpty.startsWith("http")) {
                        firstNonEmpty = "https://" + firstNonEmpty;
                    }
                    return !firstNonEmpty.endsWith("/") ? firstNonEmpty + "/" : firstNonEmpty;
                }
            }
        } catch (Exception e) {
        }
        return pngServerName();
    }

    private String sendSmsAuthCode(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.length() == 0) {
            return jsonErr("電話番号不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("phone_number", phoneNumber);
        return okResult(request("POST", "/api/account/send_sms_auth_code", (Map<String, String>) null, fields));
    }

    private String authenticateSmsAuthCode(String phoneNumber, String code) {
        if (phoneNumber == null || phoneNumber.length() == 0) {
            return jsonErr("電話番号不明");
        }
        if (code == null || code.length() == 0) {
            return jsonErr("認証コード不明");
        }
        // 公式 SmsVerificationApi.authenticateCode: FORM sms_auth_code のみ
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("sms_auth_code", code);
        if (phoneNumber != null && phoneNumber.length() > 0) fields.put("phone_number", phoneNumber);
        return okResult(request("POST", "/api/account/authenticate_sms_auth_code", (Map<String, String>) null, fields));
    }

    private String checkEnteredEmail(String email) {
        if (email == null || email.length() == 0) {
            return jsonErr("メールアドレス不明");
        }
        return okResult(request("POST", "/api/account/entered_email", (Map<String, String>) null, q1("email", email)));
    }

    private String checkFacebookExist(String facebookId) {
        if (facebookId == null || facebookId.length() == 0) {
            return jsonErr("facebook_id不明");
        }
        return okResult(request("POST", "/api/account/facebook_exist", (Map<String, String>) null, q1("facebook_id", facebookId)));
    }

    private String checkLineExist(String lineId) {
        if (lineId == null || lineId.length() == 0) {
            return jsonErr("line_id不明");
        }
        return okResult(request("POST", "/api/account/line_exist", (Map<String, String>) null, q1("line_id", lineId)));
    }

    // ==== 公式 KoetomoEncryptor と同一: Xアクセストークンを AES-GCM で暗号化して etat/vt/gt を作る ====
    // etat = 暗号文(Base64) / vt = IV(Base64) / gt = 認証タグ(Base64)。
    // 鍵はリポジトリに置かない。ビルド時に tools/gen_secrets.py が keystore.properties の
    // KOETOMO_ENC_KEY から Secrets.java(git-ignored)を生成し、そこから取得する。
    private String[] encryptForKoetomo(String plain) {
        try {
            String keyB64 = Secrets.aesKey();
            if (keyB64 == null || keyB64.length() == 0) {
                dbgLog(nowStr() + "  [ENC] 暗号鍵が未設定です(keystore.properties の KOETOMO_ENC_KEY)。X ログイン/画像変更は使えません");
                return null;
            }
            byte[] key = android.util.Base64.decode(keyB64, android.util.Base64.NO_WRAP);
            byte[] iv = new byte[12];
            new java.security.SecureRandom().nextBytes(iv);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, iv));
            byte[] out = c.doFinal(plain.getBytes("UTF-8"));
            int tagLen = 16;
            byte[] data = new byte[out.length - tagLen];
            byte[] tag = new byte[tagLen];
            System.arraycopy(out, 0, data, 0, data.length);
            System.arraycopy(out, data.length, tag, 0, tagLen);
            return new String[]{
                android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP),
                android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP),
                android.util.Base64.encodeToString(tag, android.util.Base64.NO_WRAP)
            };
        } catch (Exception e) {
            return null;
        }
    }

    /*
     * X(Twitter)ログイン。公式の LoginViewModel.twitterLogin と同一手順:
     *   1) POST api/account/twitter_exist で登録済みか確認
     *   2) Xアクセストークンを AES-GCM 暗号化 -> etat/vt/gt
     *   3) POST api/v2/account/login に twitter_id + etat/vt/gt + device_uid + feature + version
     *      (本番では平文 twitter_access_token は送らない)
     */
    private String twitterLogin(String twitterId, String accessToken) {
        if (twitterId == null || twitterId.length() == 0) {
            return jsonErr("twitter_id不明");
        }
        if (accessToken == null || accessToken.length() == 0) {
            return jsonErr("Xのアクセストークンが取得できませんでした");
        }
        // 1) 登録済み確認
        try {
            HashMap ex = new HashMap();
            ex.put("twitter_id", twitterId);
            ex.put("version", "android_" + APP_VERSION);
            Resp er = http("POST", "https://api.meetscom.com/api/account/twitter_exist", (Map<String, String>) null, ex, false);
            if (er.status >= 200 && er.status < 300 && er.body != null) {
                String st = er.body.optString("twitter_exist", "");
                if (st.length() == 0) {
                    JSONObject d = er.body.optJSONObject("data");
                    if (d != null) st = d.optString("twitter_exist", "");
                }
                if (st.length() > 0 && !"exist".equals(st)) {
                    return new JSONObject().put("ok", false).put("message", "このXアカウントは声ともに登録されていません。公式アプリでXアカウントを使って登録してからお試しください").put("raw", truncate(er.body.toString(), 300)).toString();
                }
            }
        } catch (Exception ignore) {
        }
        // 2) 暗号化
        String[] enc = encryptForKoetomo(accessToken);
        if (enc == null) {
            return jsonErr("トークンの暗号化に失敗しました");
        }
        // 3) v2 ログイン
        HashMap hashMap = new HashMap();
        hashMap.put("twitter_id", twitterId);
        hashMap.put("etat", enc[0]);
        hashMap.put("vt", enc[1]);
        hashMap.put("gt", enc[2]);
        hashMap.put("device_uid", deviceUid());
        hashMap.put("feature", "skwmeshroom,firebase,mail_auth,reset_status,chat_pagination,speaker_applicant,p2p_room,skyway,talk_recording");
        hashMap.put("version", "android_" + APP_VERSION);
        Resp http = httpLoginPost("https://api.meetscom.com/api/v2/account/login", hashMap);
        String truncate = http.body != null ? truncate(http.body.toString(), 500) : "(応答ボディなし HTTP " + http.status + ")";
        try {
            if (http.status != 200 || http.body == null) {
                String extractError = extractError(http.body);
                if (extractError == null) {
                    extractError = "Xログインに失敗しました(HTTP " + http.status + ")";
                }
                return new JSONObject().put("ok", false).put("status", http.status).put("message", extractError).put("raw", truncate).toString();
            }
            JSONObject optJSONObject = http.body.optJSONObject("data");
            if (optJSONObject == null) {
                optJSONObject = new JSONObject();
            }
            String optString = optJSONObject.optString("auth_token", "");
            if (optString.length() == 0) {
                String extractError2 = extractError(http.body);
                if (extractError2 == null) {
                    extractError2 = "このXアカウントに紐づく声ともアカウントが見つかりませんでした";
                }
                return new JSONObject().put("ok", false).put("status", http.status).put("message", extractError2).put("raw", truncate).toString();
            }
            setAuthToken(optString);
            setUserId(optJSONObject.optLong("user_id", userId()));
            setUserName(optJSONObject.optString("name", userName()));
            String birthday = optJSONObject.optString("birthday", "");
            if (birthday.length() == 0) {
                JSONObject nested = optJSONObject.optJSONObject("user");
                if (nested != null) birthday = nested.optString("birthday", "");
            }
            setBirthday(birthday);
            if (userId() == 0) {
                long found = deepFindLong(http.body, "user_id");
                if (found == 0) found = deepFindLong(http.body, "id");
                if (found != 0) setUserId(found);
            }
            return new JSONObject().put("ok", true).put("user_name", userName()).put("user_id", userId()).put("raw", truncate).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // LINE / Facebook ログイン。公式と同じく api/account/login に line_id / facebook_id を送る
    // (暗号化不要・メールログインと同構造)。ID は利用者が公式アプリ等から取得して入力する。
    private String socialIdLogin(String field, String id) {
        if (id == null || id.trim().length() == 0) {
            return jsonErr(("line_id".equals(field) ? "LINE ID" : "Facebook ID") + "を入力してください");
        }
        HashMap hashMap = new HashMap();
        hashMap.put(field, id.trim());
        hashMap.put("device_uid", deviceUid());
        hashMap.put("feature", "skwmeshroom,firebase,mail_auth,reset_status,chat_pagination,speaker_applicant,p2p_room,skyway,talk_recording");
        hashMap.put("version", "android_" + APP_VERSION);
        Resp http = httpLoginPost("https://api.meetscom.com/api/account/login", hashMap);
        String raw = http.body != null ? truncate(http.body.toString(), 500) : "(応答ボディなし HTTP " + http.status + ")";
        try {
            if (http.status != 200 || http.body == null) {
                String em = extractError(http.body);
                if (em == null) em = "ログインに失敗しました(HTTP " + http.status + ")";
                return new JSONObject().put("ok", false).put("status", http.status).put("message", em).put("raw", raw).toString();
            }
            JSONObject data = http.body.optJSONObject("data");
            if (data == null) data = new JSONObject();
            String at = data.optString("auth_token", "");
            if (at.length() == 0) {
                String em = extractError(http.body);
                if (em == null) em = "この" + ("line_id".equals(field) ? "LINE" : "Facebook") + "アカウントに紐づく声ともアカウントが見つかりませんでした";
                return new JSONObject().put("ok", false).put("status", http.status).put("message", em).put("raw", raw).toString();
            }
            setAuthToken(at);
            setUserId(data.optLong("user_id", userId()));
            setUserName(data.optString("name", userName()));
            String birthday = data.optString("birthday", "");
            if (birthday.length() == 0) { JSONObject nu = data.optJSONObject("user"); if (nu != null) birthday = nu.optString("birthday", ""); }
            setBirthday(birthday);
            if (userId() == 0) {
                long found = deepFindLong(http.body, "user_id");
                if (found == 0) found = deepFindLong(http.body, "id");
                if (found != 0) setUserId(found);
            }
            return new JSONObject().put("ok", true).put("user_name", userName()).put("user_id", userId()).put("raw", raw).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String lineLogin(String lineId) { return socialIdLogin("line_id", lineId); }
    private String facebookLogin(String facebookId) { return socialIdLogin("facebook_id", facebookId); }

        private String checkTwitterExist(String twitterId) {
        if (twitterId == null || twitterId.length() == 0) {
            return jsonErr("twitter_id不明");
        }
        return okResult(request("POST", "/api/account/twitter_exist", (Map<String, String>) null, q1("twitter_id", twitterId)));
    }

    private String sendEmailToken(String email) {
        if (email == null || email.length() == 0) {
            return jsonErr("メールアドレス不明");
        }
        return okResult(request("POST", "/api/send_email_token", (Map<String, String>) null, q1("email", email)));
    }

    private String checkEmailToken(String email, String token) {
        if (email == null || email.length() == 0) {
            return jsonErr("メールアドレス不明");
        }
        if (token == null || token.length() == 0) {
            return jsonErr("トークン不明");
        }
        // 公式 MailVerificationApi.checkVerificationCode: FORM email_token
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("email_token", token);
        if (email != null && email.length() > 0) fields.put("email", email);
        return okResult(request("POST", "/api/check_email_token", (Map<String, String>) null, fields));
    }

    // 公式アプリの実装(ChatType1Api / BulkDeleteRequest の注釈から確定):
    //   POST /api/chat/chats_bulk_delete   ※api.meetscom.com(TYPE_1)
    //   Content-Type: application/json
    //   {"chat_ids":[…], "uid":<自分>, "auth_token":"…", "version":"android_3.9.101"}
    // フォーム送信では 200 が返るのに1件も消えなかったのは、本文がJSONでないと
    // サーバーが chat_ids を読めていなかったため。
    private String bulkDeleteChats(String chatIdsCsv) {
        if (chatIdsCsv == null || chatIdsCsv.length() == 0) {
            return jsonErr("chat_ids不明");
        }
        JSONArray idArr = new JSONArray();
        String[] ids = chatIdsCsv.split(",");
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i].trim();
            if (id.length() == 0) continue;
            try { idArr.put(Long.parseLong(id)); } catch (Exception e) { idArr.put(id); }
        }
        if (idArr.length() == 0) {
            return jsonErr("有効なchat_idがありません");
        }
        java.util.List<String> before = chatIdList();
        Resp r;
        try {
            JSONObject body = new JSONObject();
            body.put("chat_ids", idArr);
            body.put("uid", userId());
            String at = authToken();
            body.put("auth_token", at == null ? "" : at);
            body.put("version", "android_" + APP_VERSION);
            r = httpJson("POST", BASE_URL + "/api/chat/chats_bulk_delete", body);
            dbgLog(nowStr() + "  [CHAT-DEL] POST(JSON) /api/chat/chats_bulk_delete ids=" + idArr.length() + " -> " + r.status
                    + (r.status >= 200 && r.status < 300 ? "" : " " + truncate(redactLog(r.body != null ? r.body.toString() : ""), 140)));
            if (r.status < 200 || r.status >= 300) {
                r = httpJson("POST", BASE_URL2 + "/api/chat/chats_bulk_delete", body);
                dbgLog(nowStr() + "  [CHAT-DEL] api2 -> " + r.status);
            }
        } catch (Exception e) {
            return errJson(e);
        }
        java.util.List<String> after = chatIdList();
        int gone = 0;
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i].trim();
            if (id.length() == 0) continue;
            if (before.contains(id) && !after.contains(id)) gone++;
        }
        dbgLog(nowStr() + "  [CHAT-DEL] 消えた件数=" + gone + " / 指定=" + idArr.length());
        try {
            if (gone > 0) return new JSONObject().put("ok", true).put("deleted", gone).toString();
            return new JSONObject().put("ok", false).put("status", r.status)
                    .put("message", "サーバーが会話の削除を受け付けませんでした" + (r.status > 0 ? " (HTTP " + r.status + ")" : "")).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }
    /** いまの会話一覧の chat_id を並べて返す(削除が効いたかの確認用)。 */
    private java.util.List<String> chatIdList() {
        java.util.ArrayList<String> out = new java.util.ArrayList<String>();
        try {
            HashMap<String, String> q = new HashMap<String, String>();
            q.put("uid", String.valueOf(userId()));
            q.put("offset", "0");
            q.put("count", "20");
            Resp r = request("GET", "/api/chats", q, (Map<String, String>) null);
            if (r.status != 200 || r.body == null) return out;
            JSONObject d = r.body.optJSONObject("data");
            JSONArray arr = (d != null) ? d.optJSONArray("chats") : r.body.optJSONArray("chats");
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject c = arr.optJSONObject(i);
                if (c != null && c.opt("id") != null) out.add(String.valueOf(c.opt("id")));
            }
        } catch (Exception e) {
        }
        return out;
    }

    private String checkRecordingDisabledUsers(String userIdsCsv) {
        if (userIdsCsv == null || userIdsCsv.length() == 0) {
            return jsonErr("user_ids不明");
        }
        // 公式 TalkRecordingApi.getRecordingDisabledUsers:
        //   POST /api/recording_disabled_users/check  JSON {"user_ids":[…]}
        //   (kotlinx serialization + snake_case 命名。GET+CSVは当方の推測だった)
        try {
            JSONArray ids = new JSONArray();
            String[] parts = userIdsCsv.split(",");
            for (int i = 0; i < parts.length; i++) {
                String v = parts[i].trim();
                if (v.length() == 0) continue;
                try { ids.put(Long.parseLong(v)); } catch (Exception ex) { ids.put(v); }
            }
            JSONObject body = new JSONObject().put("user_ids", ids);
            Resp rr = httpJson("POST", BASE_URL + "/api/recording_disabled_users/check", body);
            if (rr.status == 404 || rr.status >= 500) {
                rr = httpJson("POST", BASE_URL2 + "/api/recording_disabled_users/check", body);
            }
            return okResult(rr);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getTalkRecordingAgreements() {
        Resp resp = request("GET", "/api/talk_recording_agreements", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("data", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 公式 TalkRecordingApi.talkRecordingAgreements:
    //   POST /api/talk_recording_agreements
    //   JSON {"terms_agreed":true,"agreement_text":"…","promotional_banner_image":null}
    // room_id をフォームで送るのは当方の推測だった。
    private String agreeTalkRecording(String roomId) {
        try {
            JSONObject body = new JSONObject();
            body.put("terms_agreed", true);
            body.put("agreement_text", "");
            body.put("promotional_banner_image", JSONObject.NULL);
            Resp rr = httpJson("POST", BASE_URL + "/api/talk_recording_agreements", body);
            if (rr.status == 404 || rr.status >= 500) {
                rr = httpJson("POST", BASE_URL2 + "/api/talk_recording_agreements", body);
            }
            return okResult(rr);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getTrialListenings() {
        Resp resp = request("GET", "/api/trial_listenings/", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return gracefulUnavailable(resp, "trial_listenings", "trial_listenings");
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("trial_listenings");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("trial_listenings", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getUserCampaigns() {
        Resp resp = request("GET", "/api/user_campaigns/", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("user_campaigns");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("user_campaigns", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getServerTime() {
        Resp resp = request("GET", "/api/system/server_times", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("data", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getMypageDecoration() {
        Resp resp = request("GET", "/api/mypage_decoration", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("data", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String startCheeringCall(String receiverId, String targetUserId, String name) {
        if (receiverId == null || receiverId.length() == 0) {
            return jsonErr("receiver_id不明");
        }
        // 公式仕様: POST @Body {origin:int, target_receiver_id:int} + ヘッダ認証
        int rid = 0; try { rid = Integer.parseInt(receiverId.trim()); } catch (Exception ig) {}
        JSONObject body = new JSONObject();
        try { body.put("origin", 0); body.put("target_receiver_id", rid); } catch (Exception ig) {}
        Resp resp = httpJsonApi2("POST", "/api/cheering_talk/requests", body);
        dbgLog(nowStr() + "  [CHEER] requests HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 400));
        try {
            if (resp.status != 200 && resp.status != 201) {
                String msg = extractError(resp.body);
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("status", resp.status);
                err.put("error", msg != null ? msg : ("HTTP " + resp.status));
                return err.toString();
            }
            JSONObject data = resp.body != null ? resp.body.optJSONObject("data") : null;
            if (data == null) {
                data = resp.body;
            }
            JSONObject out = new JSONObject();
            out.put("ok", true);
            if (data != null) {
                String tk = firstStr(data, "token", "skyway_token", "call_token");
                String ch = firstStr(data, "channel", "skyway_channel", "channel_name");
                // 公式は CallResponse.data.token をそのまま SkyWay のルーム名(=channel)に使う
                if (ch == null || ch.length() == 0) ch = tk;
                out.put("channel", ch);
                out.put("token", tk);
                // target_id は以降の confirm / disconnect / SkyWay 接続で使う「相手のユーザーID」。
                // receiver_id(応援トーク受け手レコードのID)とは別物なので取り違えない。
                out.put("target_id", (targetUserId != null && targetUserId.length() > 0) ? targetUserId : receiverId);
                out.put("receiver_id", receiverId);
                out.put("raw", data);
            }
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /**
     * 公式 DefaultCheeringTalkRealtimeDatabase.isAccepted 相当。
     * api/cheering_talk/request_connections/{token}/confirm_status が 1 になったら相手が応答。
     * 発信側は request_checks(受け手用API)ではなくこちらを見る。
     */
    private String cheeringConfirmStatus(String token) {
        if (token == null || token.length() == 0) return jsonErr("token不明");
        try {
            int[] st = new int[]{0};
            String raw = rtdbGet("api/cheering_talk/request_connections/" + token + "/confirm_status.json", st);
            String v = raw == null ? "" : raw.trim();
            boolean accepted = "1".equals(v);
            boolean refused = "0".equals(v) || "2".equals(v);
            dbgLog(nowStr() + "  [CHEER] confirm_status " + token + " HTTP " + st[0] + " = " + (v.length() == 0 ? "(なし)" : v));
            return new JSONObject().put("ok", true).put("rtdb", st[0] == 200 ? "ok" : "error")
                    .put("value", v).put("accepted", accepted).put("refused", refused).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 公式 isReceiverConnected 相当。相手が実際に音声へ入ったか(receiver_connect_status)。 */
    private String cheeringReceiverConnected(String token) {
        if (token == null || token.length() == 0) return jsonErr("token不明");
        try {
            int[] st = new int[]{0};
            String raw = rtdbGet("api/cheering_talk/request_connections/" + token + "/receiver_connect_status.json", st);
            String v = raw == null ? "" : raw.trim().replace("\"", "");
            return new JSONObject().put("ok", true).put("value", v)
                    .put("connected", "connected".equals(v)).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String checkCheeringCall() {
        Resp resp = httpApi2("GET", "/api/cheering_talk/request_checks", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                JSONObject out = new JSONObject();
                out.put("ok", false);
                out.put("status", resp.status);
                return out.toString();
            }
            JSONObject data = resp.body.optJSONObject("data");
            if (data == null) {
                data = resp.body;
            }
            // 公式 RequestChecksResponse:
            //   data { token, call_method, is_blocking, requester_info { id, name, comment, age, ... } }
            // 「channel」「target_id」は公式には無い名前だったので、token / requester_info を先に読む。
            JSONObject req = data.optJSONObject("requester_info");
            if (req == null) req = data.optJSONObject("requesterInfo");
            String token = firstStr(data, "token", "channel", "skyway_channel", "channel_name");
            String tid = (req != null) ? firstStr(req, "id", "user_id") : "";
            if (tid == null || tid.length() == 0) tid = firstStr(data, "target_id", "receiver_id", "id");
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("token", token);
            out.put("channel", token);
            out.put("target_id", tid);
            out.put("call_method", firstStr(data, "call_method", "callMethod"));
            out.put("is_blocking", data.optBoolean("is_blocking", data.optBoolean("isBlocking", false)));
            if (req != null) {
                out.put("requester_name", firstStr(req, "name"));
                out.put("requester_icon", firstStr(req, "profile_picture_file_path", "profilePictureFilePath"));
            }
            out.put("status", data.optString("status", ""));
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String confirmAndOpenCheeringCall(String targetId, String channel, String name) {
        if (targetId == null || targetId.length() == 0) {
            return jsonErr("target_id不明");
        }
        // 公式仕様: FORM field target_id のみ + ヘッダ認証
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("target_id", targetId);
        Resp resp = httpApi2("POST", "/api/cheering_talk/request_confirms", (Map<String, String>) null, fields);
        dbgLog(nowStr() + "  [CHEER] request_confirms HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 300));
        try {
            if (resp.status != 200 && resp.status != 201) {
                String msg = extractError(resp.body);
                JSONObject err = new JSONObject();
                err.put("ok", false);
                err.put("status", resp.status);
                err.put("error", msg != null ? msg : ("HTTP " + resp.status));
                return err.toString();
            }
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String cancelCheeringCall(String targetId) {
        if (targetId == null || targetId.length() == 0) {
            return jsonErr("target_id不明");
        }
        return okResult(httpApi2("POST", "/api/cheering_talk/request_cancels", (Map<String, String>) null, q1("target_id", targetId)));
    }

    private String disconnectCheeringCall(String targetId) {
        if (targetId == null || targetId.length() == 0) {
            return jsonErr("target_id不明");
        }
        return okResult(httpApi2("POST", "/api/cheering_talk/request_disconnects", (Map<String, String>) null, q1("target_id", targetId)));
    }

    // 応援トーク受け手レスポンスから配列を取り出し out に追記(user_idで重複排除)。返り値=追加件数。
    private int parseCheeringReceiversInto(JSONObject body, JSONArray out, java.util.Set<Long> seen) throws Exception {
        if (body == null) return 0;
        JSONArray raw = null;
        Object dataObj = body.opt("data");
        if (dataObj instanceof JSONArray) {
            raw = (JSONArray) dataObj;
        } else if (dataObj instanceof JSONObject) {
            raw = firstArray((JSONObject) dataObj, "recommended_users", "receiver_users", "ranked_users", "users");
        }
        if (raw == null) raw = firstArray(body, "recommended_users", "receiver_users", "ranked_users", "users");
        if (raw == null) return 0;
        int added = 0;
        for (int i = 0; i < raw.length(); i++) {
            JSONObject r = raw.optJSONObject(i);
            if (r == null) continue;
            JSONObject user = r.optJSONObject("user");
            long userId = user != null ? user.optLong("id", user.optLong("user_id")) : r.optLong("user_id", r.optLong("id"));
            if (userId != 0 && seen != null) {
                if (seen.contains(Long.valueOf(userId))) continue;
                seen.add(Long.valueOf(userId));
            }
            String name = user != null ? user.optString("name", "") : r.optString("name", "");
            String icon = user != null ? user.optString("profile_picture_file_path", "") : r.optString("profile_picture_file_path", "");
            JSONObject o = new JSONObject();
            o.put("receiver_id", r.opt("receiver_id") != null ? r.opt("receiver_id") : (r.opt("id") != null ? r.opt("id") : userId));
            o.put("user_id", userId);
            o.put("name", name.length() > 0 ? name : ("user " + userId));
            o.put("icon_url", iconUrl(icon));
            String statusText = firstStr(r, "status_text", "message", "introduction", "comment");
            // ランキング用の評価情報があれば保持し、順位を status_text 先頭に付ける(既存カードUIで表示される)
            if (r.has("rating_count") && !r.isNull("rating_count")) o.put("rating_count", r.opt("rating_count"));
            if (r.has("total_rating_point") && !r.isNull("total_rating_point")) o.put("total_rating_point", r.opt("total_rating_point"));
            if (r.has("total_coin") && !r.isNull("total_coin")) o.put("total_coin", r.opt("total_coin"));
            int rankVal = r.optInt("rank", 0);
            if (rankVal > 0) {
                o.put("rank", rankVal);
                String pts = "";
                if (r.has("total_rating_point") && !r.isNull("total_rating_point")) pts = " ・ " + r.optInt("total_rating_point") + "pt";
                statusText = "第" + rankVal + "位" + pts + (statusText.length() > 0 ? " ・ " + statusText : "");
            }
            o.put("status_text", statusText);
            out.put(o);
            added++;
        }
        return added;
    }

    private String getCheeringReceivers(String kind) {
        // 公式仕様(api2 / X-App-Version・X-Auth-Token ヘッダ認証)。
        try {
            if (kind != null && kind.startsWith("rankings")) {
                // /api/cheering_talk/receiver_users/rankings : rating_type(1-6), page, filter_type(1週/2月/3全期間)
                // kind 形式: "rankings" もしくは "rankings:<rating_type>:<filter_type>"
                String ratingType = "1", filterType = "2";
                if (kind.contains(":")) {
                    String[] parts = kind.split(":");
                    if (parts.length >= 2 && parts[1].trim().length() > 0) ratingType = parts[1].trim();
                    if (parts.length >= 3 && parts[2].trim().length() > 0) filterType = parts[2].trim();
                }
                HashMap<String, String> q = new HashMap<String, String>();
                q.put("rating_type", ratingType); q.put("page", "1"); q.put("filter_type", filterType);
                Resp resp = httpApi2("GET", "/api/cheering_talk/receiver_users/rankings", q, (Map<String, String>) null);
                dbgLog(nowStr() + "  [CHEER] receivers/rankings HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 400));
                if (resp.status != 200 || resp.body == null) return gracefulUnavailable(resp, "receivers", "cheering receivers/rankings");
                JSONArray out = new JSONArray();
                parseCheeringReceiversInto(resp.body, out, new java.util.HashSet<Long>());
                return new JSONObject().put("ok", true).put("receivers", out).toString();
            }
            if ("recommended".equals(kind)) {
                Resp resp = httpApi2("GET", "/api/cheering_talk/receiver_users/recommended_users", (Map<String, String>) null, (Map<String, String>) null);
                dbgLog(nowStr() + "  [CHEER] receivers/recommended HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 400));
                if (resp.status != 200 || resp.body == null) return gracefulUnavailable(resp, "receivers", "cheering receivers/recommended");
                JSONArray out = new JSONArray();
                parseCheeringReceiversInto(resp.body, out, new java.util.HashSet<Long>());
                return new JSONObject().put("ok", true).put("receivers", out).toString();
            }
            // 一覧(全て): /api/cheering_talk/receiver_users に status/order/direction/page が必須。
            // status は active/online/offline の3種。全部を取得して結合(user_idで重複排除)。
            JSONArray out = new JSONArray();
            java.util.Set<Long> seen = new java.util.HashSet<Long>();
            String[] statuses = { "active", "online", "offline" };
            int lastStatus = 0;
            for (String st : statuses) {
                HashMap<String, String> q = new HashMap<String, String>();
                q.put("status", st);
                q.put("order", "created_at");
                q.put("direction", "desc");
                q.put("page", "1");
                Resp resp = httpApi2("GET", "/api/cheering_talk/receiver_users", q, (Map<String, String>) null);
                lastStatus = resp.status;
                dbgLog(nowStr() + "  [CHEER] receivers/list(" + st + ") HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 300));
                if (resp.status == 200 && resp.body != null) {
                    parseCheeringReceiversInto(resp.body, out, seen);
                }
            }
            if (out.length() == 0 && lastStatus != 200) {
                return gracefulUnavailable(new Resp(lastStatus, null), "receivers", "cheering receivers/list");
            }
            return new JSONObject().put("ok", true).put("receivers", out).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 応援トークのランキングは API リストではなく Web ページ(r.koetomo.fun/ranking/cheering_talk_YYYYMM)。
    // 公開設定ファイル client_defines.json から実URLを抽出して返す(月が変わっても自動追従)。無ければ当月を構築。
    private String getCheeringRankingUrl() {
        String url = "";
        try {
            String[] r = httpText("https://api.meetscom.com/config/client_defines.json");
            String body = (r != null && r.length > 1 && r[1] != null) ? r[1] : "";
            int p = body.indexOf("r.koetomo.fun/ranking/cheering_talk");
            if (p >= 0) {
                int start = body.lastIndexOf('"', p);
                int end = body.indexOf('"', p);
                if (start >= 0 && end > p) {
                    url = body.substring(start + 1, end).trim();
                }
            }
        } catch (Exception e) {
        }
        if (url == null || url.length() == 0) {
            url = "https://r.koetomo.fun/ranking/cheering_talk_" + ymNow();
        }
        if (url.startsWith("//")) {
            url = "https:" + url;
        } else if (!url.startsWith("http")) {
            url = "https://" + url;
        }
        try {
            return new JSONObject().put("ok", true).put("url", url).toString();
        } catch (Exception e) {
            return "{\"ok\":true,\"url\":\"" + url + "\"}";
        }
    }

    private String getCheeringTalkHistories(String page) {
        HashMap<String, String> hq = new HashMap<String, String>();
        hq.put("page", (page == null || page.length() == 0) ? "1" : page);
        hq.put("filter_type", "0");
        Resp resp = httpApi2("GET", "/api/cheering_talk/talk_histories", hq, (Map<String, String>) null);
        dbgLog(nowStr() + "  [CHEER] talk_histories HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 400));
        try {
            if (resp.status != 200 || resp.body == null) {
                return gracefulUnavailable(resp, "histories", "cheering talk_histories");
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("talk_histories");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("histories", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String rateCheeringCall(String targetId, String rating, String comment) {
        if (targetId == null || targetId.length() == 0) {
            return jsonErr("target_id不明");
        }
        // 公式仕様: POST @Body {rating_type:int, target_receiver_id:int, token:string} + ヘッダ認証
        int tid = 0; try { tid = Integer.parseInt(targetId.trim()); } catch (Exception ig) {}
        int rt = 0; try { rt = Integer.parseInt((rating == null ? "0" : rating).trim()); } catch (Exception ig) {}
        JSONObject body = new JSONObject();
        try {
            body.put("rating_type", rt);
            body.put("target_receiver_id", tid);
            if (comment != null && comment.length() > 0) body.put("token", comment); // 呼び出し側が通話tokenを渡せる場合
        } catch (Exception ig) {}
        return okResult(httpJsonApi2("POST", "/api/cheering_talk/ratings", body));
    }

    private String inspectFeedPost(String postId) {
        if (postId == null || postId.length() == 0) {
            return jsonErr("feed_post_id不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("version", "android_" + APP_VERSION);
        String authToken = authToken();
        if (authToken != null) {
            fields.put("auth_token", authToken);
        }
        Resp resp = http("GET", "https://api2.meetscom.com/api/feed_posts/" + postId, fields, (Map<String, String>) null);
        JSONObject out = new JSONObject();
        try {
            out.put("ok", resp.status == 200);
            out.put("status", resp.status);
            out.put("body", resp.body != null ? resp.body : JSONObject.NULL);
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getFeedPostLikedUsers(String postId) {
        if (postId == null || postId.length() == 0) {
            return jsonErr("feed_post_id不明");
        }
        // 公式: GET /api/feed_posts/{post_id}/liked_users?page= (ヘッダ認証 / 応答 {"liked_users_info":[...]})
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("page", "1");
        Resp resp = httpApi2("GET", "/api/feed_posts/" + postId + "/liked_users", q, (Map<String, String>) null);
        dbgLog(nowStr() + "  [LIKERS] feed_post " + postId + " HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 300));
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            JSONArray users = normalizeUserList(resp.body);
            return new JSONObject().put("ok", true).put("users", users).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String feedPostBadVote(String postId, String reason) {
        if (postId == null || postId.length() == 0) {
            return jsonErr("feed_post_id不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        if (reason != null && reason.length() > 0) {
            fields.put("reason", reason);
        }
        return okResult(newTimelineApiForm("/api/feed_posts/" + postId + "/bad_vote", fields));
    }

    private String getSystemParams() {
        // 公式 generateSystemParamsRequest は POST(フォーム、キーなし)
        Resp resp = request("POST", "/api/master/system_params", (Map<String, String>) null, new HashMap<String, String>());
        if (resp.status == 404 || resp.status == 405) {
            resp = request("GET", "/api/master/system_params", (Map<String, String>) null, (Map<String, String>) null);
        }
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("params", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCommunityJoinRequests(String communityId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        // 公式 CommunityApi.getJoinRequests は page を送る
        Resp resp = request("GET", "/api/communities/" + communityId + "/join-requests", q1("page", "1"), (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            JSONArray users = normalizeUserList(resp.body);
            return new JSONObject().put("ok", true).put("requests", users).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String approveCommunityJoinRequest(String communityId, String userId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        if (userId == null || userId.length() == 0) {
            return jsonErr("user_id不明");
        }
        // 公式 CommunityApi.approveJoinRequests: POST .../join-requests/approve?target_ids[]=<id>
        return okResult(request("POST", "/api/communities/" + communityId + "/join-requests/approve", q1("target_ids[]", userId), new HashMap<String, String>()));
    }

    private String cancelCommunityJoinRequest(String communityId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        return okResult(request("POST", "/api/communities/" + communityId + "/join-requests/cancel", (Map<String, String>) null, new HashMap()));
    }

    private String denyCommunityJoinRequest(String communityId, String userId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        if (userId == null || userId.length() == 0) {
            return jsonErr("user_id不明");
        }
        // 公式 CommunityApi.denyJoinRequests: POST .../join-requests/deny?target_ids[]=<id>
        return okResult(request("POST", "/api/communities/" + communityId + "/join-requests/deny", q1("target_ids[]", userId), new HashMap<String, String>()));
    }

    private String getCommunityPost(String communityId, String postId) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        if (postId == null || postId.length() == 0) {
            return jsonErr("post_id不明");
        }
        Resp resp = request("GET", "/api/communities/" + communityId + "/posts/" + postId, (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("post", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String reportCommunity(String communityId, String reason) {
        if (communityId == null || communityId.length() == 0) {
            return jsonErr("community_id不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        if (reason != null && reason.length() > 0) {
            fields.put("reason", reason);
        }
        // 公式 CommunityApi.report は @Body JSON {"description":…, "referer_id":…}
        try {
            JSONObject rb = new JSONObject();
            rb.put("description", fields.containsKey("reason") ? fields.get("reason") : "");
            rb.put("referer_id", 0);
            Resp rr = httpJson("POST", BASE_URL + "/api/communities/" + communityId + "/report", rb);
            if (rr.status == 404 || rr.status >= 500) {
                rr = httpJson("POST", BASE_URL2 + "/api/communities/" + communityId + "/report", rb);
            }
            return okResult(rr);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String toggleCommunityCommentLike(String communityId, String postId, String commentId, boolean unlike) {
        if (communityId == null || communityId.length() == 0 || postId == null || postId.length() == 0 || commentId == null || commentId.length() == 0) {
            return jsonErr("パラメータ不明");
        }
        return okResult(request(unlike ? "DELETE" : "POST", "/api/communities/" + communityId + "/posts/" + postId + "/comments/" + commentId + "/liked", (Map<String, String>) null, unlike ? null : new HashMap()));
    }

    private String deleteCommunityComment(String communityId, String postId, String commentId) {
        if (communityId == null || communityId.length() == 0 || postId == null || postId.length() == 0 || commentId == null || commentId.length() == 0) {
            return jsonErr("パラメータ不明");
        }
        return okResult(request("DELETE", "/api/communities/" + communityId + "/posts/" + postId + "/comments/" + commentId, (Map<String, String>) null, (Map<String, String>) null));
    }

    private String setDisplayBadge(String badgeId) {
        // 公式 BadgeApi.setDisplayBadge は @Body JSON {"badge_id":…}
        // (バッジを外すときは DELETE /api/users/{id}/display-badge)
        try {
            String path = "/api/users/" + userId() + "/display-badge";
            if (badgeId == null || badgeId.length() == 0) {
                return okResult(request("DELETE", path, (Map<String, String>) null, (Map<String, String>) null));
            }
            JSONObject bb = new JSONObject();
            try { bb.put("badge_id", Long.parseLong(badgeId)); } catch (Exception ex) { bb.put("badge_id", badgeId); }
            Resp rr = httpJson("PUT", BASE_URL + path, bb);
            if (rr.status == 404 || rr.status >= 500) rr = httpJson("PUT", BASE_URL2 + path, bb);
            return okResult(rr);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String markUserCampaignAsRead(String campaignId) {
        if (campaignId == null || campaignId.length() == 0) {
            return jsonErr("campaign_id不明");
        }
        return okResult(request("PUT", "/api/user_campaigns/" + campaignId + "/mark_as_read", (Map<String, String>) null, new HashMap()));
    }

    private String cheeringSendCoins(String targetId, String coinAmount) {
        if (targetId == null || targetId.length() == 0) {
            return jsonErr("target_id不明");
        }
        if (coinAmount == null || coinAmount.length() == 0) {
            return jsonErr("coin_amount不明");
        }
        // 公式仕様: FORM target_receiver_id, token(= コイン識別トークン)
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("target_receiver_id", targetId);
        fields.put("token", coinAmount);
        return okResult(httpApi2("POST", "/api/cheering_talk/send_coins", (Map<String, String>) null, fields));
    }

    private String cheeringSkywayConnect(String targetId, String channel) {
        if (targetId == null || targetId.length() == 0) {
            return jsonErr("target_id不明");
        }
        // 公式仕様: FORM token(= skyway接続トークン)
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("token", channel != null ? channel : "");
        return okResult(httpApi2("POST", "/api/cheering_talk/skyway_connections", (Map<String, String>) null, fields));
    }

    private String cheeringSkywayDisconnect(String targetId, String channel) {
        if (targetId == null || targetId.length() == 0) {
            return jsonErr("target_id不明");
        }
        // 公式仕様: FORM token, call_duration
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("token", channel != null ? channel : "");
        fields.put("call_duration", "0");
        return okResult(httpApi2("POST", "/api/cheering_talk/skyway_disconnections", (Map<String, String>) null, fields));
    }

    private String cheeringDataResult(Resp resp, String key) {
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            Object data = resp.body.opt("data");
            if (data instanceof JSONArray) {
                return new JSONObject().put("ok", true).put(key, data).toString();
            }
            if (data instanceof JSONObject) {
                return new JSONObject().put("ok", true).put(key, data).toString();
            }
            JSONArray arr = resp.body.optJSONArray(key);
            if (arr != null) {
                return new JSONObject().put("ok", true).put(key, arr).toString();
            }
            return new JSONObject().put("ok", true).put(key, resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCheeringReceiverDetail(String receiverId) {
        if (receiverId == null || receiverId.length() == 0) {
            return jsonErr("receiver_id不明");
        }
        return cheeringDataResult(httpApi2("GET", "/api/cheering_talk/receiver_users/" + receiverId + "/user_detail", (Map<String, String>) null, (Map<String, String>) null), "detail");
    }

    private String getCheeringReceiverCoinList(String receiverId) {
        if (receiverId == null || receiverId.length() == 0) {
            return jsonErr("receiver_id不明");
        }
        return cheeringDataResult(httpApi2("GET", "/api/cheering_talk/receiver_users/" + receiverId + "/coin_list", (Map<String, String>) null, (Map<String, String>) null), "coins");
    }

    private String getCheeringStandbyRequests(String receiverId) {
        if (receiverId == null || receiverId.length() == 0) {
            return jsonErr("receiver_id不明");
        }
        return cheeringDataResult(httpApi2("GET", "/api/cheering_talk/receiver_users/" + receiverId + "/standby_requests", (Map<String, String>) null, (Map<String, String>) null), "requests");
    }

    private String updateCheeringReceiverStatus(String receiverId, String status) {
        if (receiverId == null || receiverId.length() == 0) {
            return jsonErr("receiver_id不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        if (status != null && status.length() > 0) {
            fields.put("status", status);
        }
        // 公式 CheeringTalkApi.updateStatus は @Body JSON (ProfileEditRequest, snake_case)
        try {
            JSONObject sb = new JSONObject().put("status", status == null ? "" : status);
            return okResult(httpJsonApi2("PUT", "/api/cheering_talk/receiver_users/" + receiverId + "/update_status", sb));
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 公式には「受けたリクエストの一覧」APIは無く、着信の確認は GET /api/cheering_talk/request_checks。
    private String getCheeringRequestReceives() {
        Resp resp = httpApi2("GET", "/api/cheering_talk/request_checks", (Map<String, String>) null, (Map<String, String>) null);
        dbgLog(nowStr() + "  [CHEER] request_checks HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 400));
        return cheeringDataResult(resp, "requests");
    }

    /**
     * 応援通話の着信に answer を返す。
     * 公式 CallApi.answer: POST api2 /api/cheering_talk/request_receives
     *   JSON {"token":"…","answer":<数値>}   answer: 1=受ける / 0=断る
     */
    private String answerCheeringCall(String token, String answer) {
        if (token == null || token.length() == 0) {
            return jsonErr("token不明");
        }
        try {
            int a = 1;
            try { a = Integer.parseInt(answer); } catch (Exception ex) { a = "0".equals(answer) ? 0 : 1; }
            JSONObject body = new JSONObject().put("token", token).put("answer", a);
            Resp resp = httpJsonApi2("POST", "/api/cheering_talk/request_receives", body);
            dbgLog(nowStr() + "  [CHEER] answer(" + a + ") HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 300));
            return okResult(resp);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getCheeringSentCoins(String page) {
        HashMap<String, String> scq = new HashMap<String, String>(); scq.put("receiver_id", "0");
        Resp resp = httpApi2("GET", "/api/cheering_talk/sent_coins", scq, (Map<String, String>) null);
        dbgLog(nowStr() + "  [CHEER] sent_coins HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 400));
        if (resp.status != 200 || resp.body == null) {
            return gracefulUnavailable(resp, "sent_coins", "cheering sent_coins");
        }
        return cheeringDataResult(resp, "sent_coins");
    }

    // ===== v60: 公式APK契約どおりに未実装機能を実装 =====
    // components系(cheering等)は api2.meetscom.com。api2優先で叩き、404/通信不可なら api にフォールバック。
    private Resp httpApi2(String method, String path, Map<String, String> query, Map<String, String> fields) {
        Resp r = http(method, BASE_URL2 + path, query, fields);
        if (r == null || r.status == 404 || r.status >= 500 || (r.status <= 0 && "GET".equals(method))) {
            Resp r2 = http(method, BASE_URL + path, query, fields);
            if (r == null) return r2;
            if (r2 != null && (r2.status == 200 || r2.status == 201 || r2.status < 400)) return r2;
        }
        return r;
    }
    private Resp httpJsonApi2(String method, String path, JSONObject body) {
        Resp r = httpJson(method, BASE_URL2 + path, body);
        if (r == null || r.status == 404 || r.status >= 500 || (r.status <= 0 && "GET".equals(method))) {
            Resp r2 = httpJson(method, BASE_URL + path, body);
            if (r == null) return r2;
            if (r2 != null && (r2.status == 200 || r2.status == 201 || r2.status < 400)) return r2;
        }
        return r;
    }

    private String okList(Resp resp, String outKey, String... arrKeys) {
        try {
            if (resp == null || resp.status < 200 || resp.status >= 300 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp == null ? 0 : resp.status)
                        .put("raw", resp != null && resp.body != null ? truncate(resp.body.toString(), 300) : "").toString();
            }
            JSONArray arr = null;
            Object d = resp.body.opt("data");
            if (d instanceof JSONArray) arr = (JSONArray) d;
            if (arr == null) {
                for (int i = 0; i < arrKeys.length; i++) { JSONArray a = resp.body.optJSONArray(arrKeys[i]); if (a != null) { arr = a; break; } }
            }
            if (arr == null && resp.body.optJSONObject("data") != null) {
                JSONObject dd = resp.body.optJSONObject("data");
                for (int i = 0; i < arrKeys.length; i++) { JSONArray a = dd.optJSONArray(arrKeys[i]); if (a != null) { arr = a; break; } }
            }
            if (arr == null) arr = new JSONArray();
            return new JSONObject().put("ok", true).put(outKey, arr).put("raw", truncate(resp.body.toString(), 300)).toString();
        } catch (Exception e) { return errJson(e); }
    }

    private String getFriendPosts(String maxCreatedAt) {
        HashMap<String, String> q = new HashMap<String, String>();
        if (maxCreatedAt != null && maxCreatedAt.length() > 0) q.put("max_created_at", maxCreatedAt);
        return postsResult(request("GET", "/api/friend_posts", q, (Map<String, String>) null), "friend_posts", false);
    }

    private String getUnreadNotifCount() {
        Resp resp = request("GET", "/api/user_notifications/unread_count", (Map<String, String>) null, (Map<String, String>) null);
        try {
            long c = 0;
            if (resp.body != null) {
                JSONObject data = resp.body.optJSONObject("data");
                JSONObject src = data != null ? data : resp.body;
                c = src.optLong("unread_count", src.optLong("count", src.optLong("unread", 0)));
            }
            return new JSONObject().put("ok", resp.status == 200).put("count", c).toString();
        } catch (Exception e) { return errJson(e); }
    }

    private String getRecordingEntries(String page) {
        return okList(request("GET", "/api/recording_entries", q1("page", page == null || page.length() == 0 ? "1" : page), (Map<String, String>) null), "recordings", "recording_entries", "recordings");
    }
    private String getDailyPointHistories(String page) {
        // 公式 generateGetDailyPointHistoryRequest は page / user_id / order の3つを送る
        HashMap<String, String> dq = new HashMap<String, String>();
        dq.put("page", page == null || page.length() == 0 ? "1" : page);
        dq.put("user_id", String.valueOf(userId()));
        dq.put("order", "desc");
        return okList(request("GET", "/api/v2/daily_point_histories", dq, (Map<String, String>) null), "histories", "daily_point_histories", "histories", "point_histories");
    }
    private String getItemHistories(String page) {
        // 公式 generateGetGiftHistoryRequest は絞り込みが無いとき type_ids=1,2 を送る
        return okList(request("GET", "/api/item_histories", q2("page", page == null || page.length() == 0 ? "1" : page, "type_ids", "1,2"), (Map<String, String>) null), "items", "item_histories", "items");
    }
    private String getCoinPacks() {
        Resp resp = request("GET", "/api/v2/coin_packs", (Map<String, String>) null, (Map<String, String>) null);
        if (resp.status != 200) resp = request("GET", "/api/coin_packs", (Map<String, String>) null, (Map<String, String>) null);
        return okList(resp, "coin_packs", "coin_packs", "coinPacks");
    }
    private String getSubscriptionMenus() {
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("on_sale", "false");
        return okList(httpApi2("GET", "/api/subscription_menus", q, (Map<String, String>) null), "menus", "subscription_menus", "menus");
    }
    private String getOwnedCommunities() {
        return okList(request("GET", "/api/communities/owned_communities", (Map<String, String>) null, (Map<String, String>) null), "communities", "communities", "owned_communities");
    }

    private String getFollowRelList(String userId, String kind, String page) {
        String sub = "followers".equals(kind) ? "followers" : "followees";
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("page", page == null || page.length() == 0 ? "1" : page);
        return okList(httpApi2("GET", "/api/v2/users/" + userId + "/" + sub, q, (Map<String, String>) null), "users", "users", sub);
    }

    private String resetUserStatus() {
        return okResult(request("PUT", "/api/users/reset_status", (Map<String, String>) null, new HashMap()));
    }

    private String leaveTrialRoom(String trialId) {
        HashMap<String, String> q = new HashMap<String, String>();
        if (trialId != null && trialId.length() > 0) q.put("trial_listening_id", trialId);
        return okResult(httpApi2("DELETE", "/api/rooms/leave_trial", q, (Map<String, String>) null));
    }

    private String canSendChat(String targetId) {
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("target_id", targetId == null ? "" : targetId);
        Resp resp = httpApi2("GET", "/api/chats/can_send_2", q, (Map<String, String>) null);
        try {
            boolean can = resp.status == 200;
            if (resp.body != null) {
                JSONObject data = resp.body.optJSONObject("data");
                JSONObject src = data != null ? data : resp.body;
                can = src.optBoolean("can_send", src.optBoolean("sendable", can));
            }
            return new JSONObject().put("ok", resp.status == 200).put("can_send", can).put("raw", resp.body != null ? truncate(resp.body.toString(), 200) : "").toString();
        } catch (Exception e) { return errJson(e); }
    }

    private String checkNameAvailability(String name) {
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("name", name == null ? "" : name);
        Resp resp = request("GET", "/api/account/name_availability", q, (Map<String, String>) null);
        try {
            boolean avail = resp.status == 200;
            if (resp.body != null) {
                JSONObject data = resp.body.optJSONObject("data");
                JSONObject src = data != null ? data : resp.body;
                avail = src.optBoolean("available", src.optBoolean("is_available", avail));
            }
            return new JSONObject().put("ok", resp.status == 200).put("available", avail).toString();
        } catch (Exception e) { return errJson(e); }
    }

    private String deleteAllPosts(String kind) {
        String path = "timeline".equals(kind) ? "/api/timeline/timeline_posts" : "/api/feed/feed_posts";
        return okResult(request("DELETE", path, (Map<String, String>) null, (Map<String, String>) null));
    }

    // ===================== ランダムマッチング / ランダム通話 =====================
    // 公式(RandomMatchActivity)と同じ流れ:
    //   1) POST /api/matching (group_id)          … 待ち行列に入る
    //   2) RTDB users/{me}/matching_info を監視     … current_id / {id}/{entry_id,target_id,matched_at}
    //   3) PUT  /api/matchings/{m}/entries/{e}/accept|refuse
    //   4) 双方OKで mutual_accepted_at が入る → id の小さい側が POST /api/dive/requests
    //   5) 発信側: RTDB request_connections/{token}/confirm_status を待って POST /api/dive/request_confirms
    //      着信側: RTDB users/{me}/is_incoming → POST /api/v2/dive/request_checks → POST /api/dive/request_receives
    //   6) token を SkyWay のチャンネル名として双方が参加して通話
    private static final String KOE_RTDB_BASE = "https://koetomo-bb8bb.firebaseio.com/";

    /** Firebase RTDB の REST 読み取り。値がそのまま(文字列/真偽/数値)返るので生文字列で扱う。 */
    private String rtdbGet(String path) {
        return rtdbGet(path, null);
    }

    private String rtdbGet(String path, int[] stOut) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(KOE_RTDB_BASE + path).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            int st = conn.getResponseCode();
            if (stOut != null && stOut.length > 0) stOut[0] = st;
            if (st != 200) return "";
            java.io.InputStream is = conn.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "";
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ig) {}
        }
    }

    /** RTDB へ書き込む(公式アプリの sendRoomData 相当: api/rooms/{id}/room_data) */
    private String rtdbPut(String path, String json) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(KOE_RTDB_BASE + path).openConnection();
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] b = json.getBytes("UTF-8");
            conn.setFixedLengthStreamingMode(b.length);
            conn.getOutputStream().write(b);
            conn.getOutputStream().flush();
            int st = conn.getResponseCode();
            dbgLog(nowStr() + "  [ROOMDATA] PUT " + path + " → " + st);
            return new JSONObject().put("ok", st >= 200 && st < 300).put("status", st).toString();
        } catch (Exception e) {
            return errJson(e);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ig) {}
        }
    }

    // 公式の RoomData: {"command":<1..6>,"args":{...}}
    //   3=発言を依頼 / 4=承諾 / 5=辞退  args={"requestee_id":<uid>}
    private String roomDataSend(String roomId, String command, String requesteeId) {
        if (roomId == null || roomId.length() == 0) return jsonErr("room_id不明");
        try {
            int cmd = Integer.parseInt(command);
            long uid = Long.parseLong(requesteeId);
            JSONObject body = new JSONObject().put("command", cmd)
                    .put("args", new JSONObject().put("requestee_id", uid));
            return rtdbPut("api/rooms/" + roomId + "/room_data.json", body.toString());
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** ISO / "yyyy-MM-dd HH:mm:ss" の時刻から経過秒。読めなければ -1。 */
    private static long ageSecOf(String s) {
        long t = botParseTime(s);
        if (t <= 0) return -1;
        return (System.currentTimeMillis() - t) / 1000L;
    }

    // 公式(TimeUtil.localDateTimeFromString → LocalDateTime)と同じく、タイムゾーン表記は無視して
    // 端末のローカル時刻として解釈する。matched_at の 15 秒判定を公式と同じ結果にするため。
    private static long naiveLocalMillis(String s) {
        if (s == null) return 0;
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?").matcher(s);
            if (!m.find()) return 0;
            java.util.Calendar c = java.util.Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)) - 1, Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            return c.getTimeInMillis();
        } catch (Exception e) {
            return 0;
        }
    }

    // 公式(kotlinx Instant.parse)と同じ ISO-8601(UTC/オフセット付き)の解釈。
    private static long instantMillis(String s) {
        if (s == null || s.length() < 10) return 0;
        String v = s.trim();
        try {
            String frac = "";
            int dot = v.indexOf('.');
            if (dot > 0) {
                int e = dot + 1;
                while (e < v.length() && Character.isDigit(v.charAt(e))) e++;
                frac = ".SSS";
                String d3 = v.substring(dot + 1, Math.min(e, dot + 4));
                while (d3.length() < 3) d3 = d3 + "0";
                v = v.substring(0, dot) + "." + d3 + v.substring(e);
            }
            boolean z = v.endsWith("Z");
            String pat = "yyyy-MM-dd'T'HH:mm:ss" + frac + (z ? "'Z'" : "Z");
            if (!z) v = v.replaceAll("([+-]\\d{2}):(\\d{2})$", "$1$2");
            SimpleDateFormat f = new SimpleDateFormat(pat, Locale.US);
            if (z) f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
            return f.parse(v).getTime();
        } catch (Exception e) {
            return 0; // 公式(Instant.parse 失敗時)と同じく「無効」として扱う
        }
    }

    /**
     * 公式アプリ(OkHttpSingleton)とまったく同じ形で書き込みリクエストを送る。
     *   POST / PUT … URL にクエリを付けず、auth_token と version=android_x.y.z を「本文」に入れる
     *   DELETE     … 本文なし、auth_token と version=android_x.y.z を「クエリ」に入れる
     * 共通の request() は version を "3.9.101"(android_ 無し)でクエリに載せるため、
     * 旧サーバー(api.meetscom.com)の /api/matching 系・/api/dive/ 系では
     * HTTP 200 が返るのに待ち行列へ入らない(＝いつまでもマッチしない)ことがある。
     * ランダム通話まわりは必ずこちらを使う。
     */
    private Resp requestOfficial(String method, String path, Map<String, String> fields) {
        boolean isDelete = "DELETE".equals(method);
        HashMap<String, String> body = new HashMap<String, String>();
        HashMap<String, String> query = new HashMap<String, String>();
        if (fields != null) {
            for (Map.Entry<String, String> e : fields.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) body.put(e.getKey(), e.getValue());
            }
        }
        HashMap<String, String> target = isDelete ? query : body;
        String token = authToken();
        if (token != null && token.length() > 0) target.put("auth_token", token);
        target.put("version", "android_" + APP_VERSION);
        final String ck = method + " " + path.replaceAll("/\\d+", "/{n}") + " #official";
        String firstHost = this.hostCache.containsKey(ck) ? this.hostCache.get(ck) : BASE_URL;
        LinkedHashSet<String> hosts = new LinkedHashSet<String>();
        hosts.add(firstHost);
        hosts.add(BASE_URL);
        hosts.add(BASE_URL2);
        Resp last = new Resp(0, (JSONObject) null);
        for (String h : hosts) {
            Resp r = http(method, h + path, query, isDelete ? null : (Map<String, String>) body);
            if (r.status >= 200 && r.status < 300) {
                String prev = this.hostCache.get(ck);
                if (prev == null || !prev.equals(h)) { this.hostCache.put(ck, h); saveHostCache(); }
                return r;
            }
            if (r.status == 404 || r.status <= 0 || r.status >= 500) {
                last = r;
                // 応答なし(タイムアウト)はサーバー側で処理済みの可能性があるので別ホストへ再送しない
                if (r.status <= 0) return r;
            } else {
                return r;
            }
        }
        return last;
    }

    private String matchingStart(String groupId) {
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("group_id", (groupId == null || groupId.length() == 0) ? "1" : groupId);
        Resp r = requestOfficial("POST", "/api/matching", f);
        dbgLog(nowStr() + "  [MATCH] start HTTP " + r.status + " vsns=" + r.vsns + " " + truncate(redactLog(r.body != null ? r.body.toString() : "(null)"), 300));
        return okResultStatus(r);
    }

    private String matchingCancel() {
        Resp r = requestOfficial("DELETE", "/api/matching", (Map<String, String>) null);
        dbgLog(nowStr() + "  [MATCH] cancel HTTP " + r.status + " vsns=" + r.vsns);
        return okResultStatus(r);
    }

    private String matchingAccept(String matchingId, String entryId) {
        Resp r = requestOfficial("PUT", "/api/matchings/" + matchingId + "/entries/" + entryId + "/accept", (Map<String, String>) null);
        dbgLog(nowStr() + "  [MATCH] accept " + matchingId + "/" + entryId + " HTTP " + r.status + " vsns=" + r.vsns);
        return okResultStatus(r);
    }

    private String matchingRefuse(String matchingId, String entryId) {
        Resp r = requestOfficial("PUT", "/api/matchings/" + matchingId + "/entries/" + entryId + "/refuse", (Map<String, String>) null);
        dbgLog(nowStr() + "  [MATCH] refuse " + matchingId + "/" + entryId + " HTTP " + r.status + " vsns=" + r.vsns);
        return okResultStatus(r);
    }

    /** 自分の matching_info を読んで、待機中 / マッチ成立 / 相互OK を判定して返す。 */
    private String matchingState() {
        long me = userId();
        if (me == 0) return jsonErr("ログインが必要です");
        try {
            JSONObject out = new JSONObject().put("ok", true).put("my_user_id", me);
            int[] st0 = new int[]{0};
            String raw = rtdbGet("api/users/" + me + "/matching_info.json", st0);
            out.put("rtdb", st0[0] == 200 ? "ok" : ((st0[0] == 401 || st0[0] == 403) ? "denied" : "error"));
            dbgLog(nowStr() + "  [MATCH] rtdb HTTP " + st0[0] + " " + truncate(redactLog(raw), 160));
            if (raw.length() == 0 || "null".equals(raw.trim())) return out.put("state", "waiting").toString();
            JSONObject o;
            try { o = new JSONObject(raw); } catch (Exception e) { return out.put("state", "waiting").toString(); }
            int cur = o.optInt("current_id", 0);
            out.put("matching_id", cur);
            JSONObject e = cur != 0 ? o.optJSONObject(String.valueOf(cur)) : null;
            if (e == null) return out.put("state", "waiting").toString();
            out.put("entry_id", e.optInt("entry_id", 0));
            out.put("target_id", e.optInt("target_id", 0));
            String matchedAt = e.isNull("matched_at") ? "" : e.optString("matched_at", "");
            String mutualAt = e.isNull("mutual_accepted_at") ? "" : e.optString("mutual_accepted_at", "");
            out.put("matched_at", matchedAt).put("mutual_accepted_at", mutualAt);
            out.put("matched_age", ageSecOf(matchedAt)).put("mutual_age", ageSecOf(mutualAt));
            // 公式と同じ判定:
            //  matched_at        … now < matched_at + 15秒 (ローカル時刻として解釈)
            //  mutual_accepted_at… |now - 時刻| <= 15秒 (ISO-8601)
            long nowMs = System.currentTimeMillis();
            long mAt = naiveLocalMillis(matchedAt);
            out.put("matched_fresh", mAt != 0 && nowMs < mAt + 15000L);
            long uAt = instantMillis(mutualAt);
            out.put("mutual_fresh", uAt != 0 && Math.abs(nowMs - uAt) <= 15000L);
            // 公式は Firebase の push で即座に気づくので 15 秒で足りるが、こちらは定期確認なので
            // 端末がスリープ/バックグラウンドで JS が止まると 15 秒窓を跨いで取りこぼす。
            // 取りこぼし救済用に「何秒前か」も渡す(公式と同じローカル時刻解釈で計算)。
            out.put("matched_age_local", mAt != 0 ? (nowMs - mAt) / 1000L : -1L);
            out.put("mutual_age_local", uAt != 0 ? (nowMs - uAt) / 1000L : -1L);
            out.put("state", mutualAt.length() > 0 ? "mutual" : (matchedAt.length() > 0 ? "matched" : "waiting"));
            return out.toString();
        } catch (Exception ex) {
            return errJson(ex);
        }
    }

    /** 相手が断ったか(相手側 matching_info の refused_at)。 */
    private String matchingRefused(String targetId, String matchingId) {
        try {
            String raw = rtdbGet("api/users/" + targetId + "/matching_info/" + matchingId + "/refused_at.json");
            boolean refused = raw.length() > 0 && !"null".equals(raw.trim());
            return new JSONObject().put("ok", true).put("refused", refused).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 発信: POST /api/dive/requests → 接続トークン(= SkyWay チャンネル名)。 */
    private String diveRequest(String targetId) {
        if (targetId == null || targetId.length() == 0) return jsonErr("target_id不明");
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("target_id", targetId);
        f.put("call_method", "skyway");
        f.put("origin", "10");
        Resp r = requestOfficial("POST", "/api/dive/requests", f);
        dbgLog(nowStr() + "  [DIVE] request target=" + targetId + " HTTP " + r.status + " " + truncate(redactLog(r.body != null ? r.body.toString() : "(null)"), 200));
        try {
            if (r.status != 200 && r.status != 201) {
                String msg = extractError(r.body);
                return new JSONObject().put("ok", false).put("status", r.status).put("error", msg != null ? msg : ("HTTP " + r.status)).toString();
            }
            String token = diveTokenOf(r.body);
            return new JSONObject().put("ok", true).put("token", token).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String diveTokenOf(JSONObject body) {
        if (body == null) return "";
        String t = body.optString("token", "");
        if (t.length() == 0) {
            JSONObject d = body.optJSONObject("data");
            if (d != null) {
                t = d.optString("token", "");
                if (t.length() == 0) t = d.optString("connection_id", "");
            }
        }
        if (t.length() == 0) t = body.optString("connection_id", "");
        return t;
    }

    /** 発信側: 相手が出たか(RTDB request_connections/{token}/confirm_status)。 */
    private String diveConfirmStatus(String token) {
        if (token == null || token.length() == 0) return jsonErr("token不明");
        try {
            String raw = rtdbGet("api/request_connections/" + token + "/confirm_status.json");
            boolean ready = raw.length() > 0 && !"null".equals(raw.trim());
            return new JSONObject().put("ok", true).put("ready", ready).put("value", raw).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 公式: request_confirms の応答に入っている token が通話ルーム名になる(無ければ1秒後に再試行)
    private String diveConfirm(String targetId) {
        if (targetId == null || targetId.length() == 0) return jsonErr("target_id不明");
        Resp r = request("POST", "/api/dive/request_confirms", (Map<String, String>) null, q1("target_id", targetId));
        dbgLog(nowStr() + "  [DIVE] confirm target=" + targetId + " HTTP " + r.status + " " + truncate(redactLog(r.body != null ? r.body.toString() : "(null)"), 200));
        try {
            if (r.status != 200 && r.status != 201) {
                String msg = extractError(r.body);
                return new JSONObject().put("ok", false).put("status", r.status).put("error", msg != null ? msg : ("HTTP " + r.status)).toString();
            }
            return new JSONObject().put("ok", true).put("token", diveTokenOf(r.body)).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String diveRequestCancel() {
        return diveRequestCancel(null);
    }

    /** 発信の取り消し。公式は target_id を必ず載せるので、分かっていれば付ける。 */
    private String diveRequestCancel(String targetId) {
        HashMap<String, String> f = new HashMap<String, String>();
        if (targetId != null && targetId.length() > 0) f.put("target_id", targetId);
        Resp r = requestOfficial("POST", "/api/dive/request_cancels", f);
        dbgLog(nowStr() + "  [DIVE] cancel target=" + (targetId == null ? "-" : targetId) + " HTTP " + r.status + " vsns=" + r.vsns);
        return okResultStatus(r);
    }

    /** グッドトークを出せる最短の通話秒数(公式: client_system_params.dive.min_good_talk_second)。 */
    private String getGoodTalkMin() {
        int sec = 60;
        try {
            JSONObject sys = this.clientDefines != null ? this.clientDefines.optJSONObject("client_system_params") : null;
            JSONObject dive = sys != null ? sys.optJSONObject("dive") : null;
            if (dive != null) sec = dive.optInt("min_good_talk_second", 60);
        } catch (Exception e) {
        }
        if (sec <= 0) sec = 60;
        try {
            return new JSONObject().put("ok", true).put("min_second", sec).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /**
     * グッドトーク(通話後の高評価)。公式: PUT /api/dive/like に connection_id を送る。
     * connection_id は通話ルーム名(dive_confirm / dive_receive が返す token)と同じもの。
     */
    private String diveLike(String connectionId) {
        if (connectionId == null || connectionId.length() == 0) return jsonErr("通話が特定できません");
        Resp r = request("PUT", "/api/dive/like", (Map<String, String>) null, q1("connection_id", connectionId));
        dbgLog(nowStr() + "  [DIVE] good_talk conn=" + truncate(connectionId, 24) + " HTTP " + r.status);
        try {
            if (r.status != 200 && r.status != 201 && r.status != 204) {
                String msg = extractError(r.body);
                return new JSONObject().put("ok", false).put("status", r.status).put("error", msg != null ? msg : ("HTTP " + r.status)).toString();
            }
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 着信フラグ(RTDB users/{me}/is_incoming)。 */
    private String diveIncoming() {
        long me = userId();
        if (me == 0) return jsonErr("ログインが必要です");
        try {
            String raw = rtdbGet("api/users/" + me + "/is_incoming.json");
            boolean inc = "true".equals(raw.trim());
            return new JSONObject().put("ok", true).put("incoming", inc).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 着信内容の取得。相手の名前・アイコン等を返す。 */
    private String diveCheck() {
        Resp r = request("POST", "/api/v2/dive/request_checks", (Map<String, String>) null, new HashMap<String, String>());
        try {
            if (r.status != 200 || r.body == null) return new JSONObject().put("ok", false).put("status", r.status).toString();
            JSONObject req = r.body.optJSONObject("request");
            if (req == null) {
                JSONObject d = r.body.optJSONObject("data");
                if (d != null) req = d.optJSONObject("request");
            }
            JSONObject out = new JSONObject().put("ok", true);
            if (req == null) return out.put("has_request", false).toString();
            JSONObject ti = req.optJSONObject("target_info");
            out.put("has_request", true);
            out.put("call_method", req.optString("call_method", "skyway"));
            if (ti != null) {
                out.put("target_id", ti.opt("user_id"));
                out.put("name", ti.optString("name", ""));
                out.put("age", ti.opt("age"));
                out.put("sex", ti.opt("sex"));
                out.put("comment", ti.isNull("comment") ? "" : ti.optString("comment", ""));
                out.put("liked_count", ti.opt("liked_count"));
                out.put("icon_url", iconUrl(ti.optString("profile_picture_file_path", "")));
            }
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /** 着信への応答。answer=1 で受ける(応答に token = SkyWay チャンネル名が入る)。 */
    private String diveReceive(String targetId, String answer) {
        if (targetId == null || targetId.length() == 0) return jsonErr("target_id不明");
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("target_id", targetId);
        f.put("answer", (answer == null || answer.length() == 0) ? "1" : answer);
        Resp r = request("POST", "/api/dive/request_receives", (Map<String, String>) null, f);
        dbgLog(nowStr() + "  [DIVE] receive target=" + targetId + " answer=" + answer + " HTTP " + r.status);
        try {
            if (r.status != 200 && r.status != 201) {
                String msg = extractError(r.body);
                return new JSONObject().put("ok", false).put("status", r.status).put("error", msg != null ? msg : ("HTTP " + r.status)).toString();
            }
            return new JSONObject().put("ok", true).put("token", diveTokenOf(r.body)).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String diveRequestDisconnect(String targetId, String errDesc) {
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("target_id", targetId == null ? "" : targetId);
        f.put("error_description", errDesc == null ? "" : errDesc);
        return okResult(request("POST", "/api/dive/request_disconnects", (Map<String, String>) null, f));
    }

    private String getCommunityPostsList(String communityId, String page) {
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("page", page == null || page.length() == 0 ? "1" : page);
        return okList(httpApi2("GET", "/api/communities/" + communityId + "/posts", q, (Map<String, String>) null), "posts", "posts", "community_posts");
    }
    private String getCommunityPostDetail(String communityId, String postId) {
        Resp resp = httpApi2("GET", "/api/communities/" + communityId + "/posts/" + postId, (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) return new JSONObject().put("ok", false).put("status", resp.status).toString();
            JSONObject data = resp.body.optJSONObject("data");
            return new JSONObject().put("ok", true).put("post", data != null ? data : resp.body).toString();
        } catch (Exception e) { return errJson(e); }
    }
    private String banCommunityMember(String communityId, String userId) {
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("user_id", userId == null ? "" : userId);
        f.put("target_id", userId == null ? "" : userId);
        return okResult(httpApi2("POST", "/api/communities/" + communityId + "/members/ban", (Map<String, String>) null, f));
    }

    private String setDisplayBadge(String userId, String badgeId) {
        if (badgeId == null || badgeId.length() == 0) {
            return okResult(httpApi2("DELETE", "/api/users/" + userId + "/display-badge", (Map<String, String>) null, (Map<String, String>) null));
        }
        JSONObject body = new JSONObject();
        try { body.put("badge_id", Integer.parseInt(badgeId.trim())); } catch (Exception ig) { try { body.put("badge_id", badgeId); } catch (Exception ig2) {} }
        return okResult(httpJsonApi2("PUT", "/api/users/" + userId + "/display-badge", body));
    }

    // ===== v61: 残りの未実装機能を全実装 =====
    private String cpBase(String cid, String pid) { return BASE_URL + "/api/communities/" + cid + "/posts/" + pid; }
    private String likeCommunityPost(String cid, String pid, boolean like) {
        return okResult(http(like ? "POST" : "DELETE", cpBase(cid, pid) + "/liked", (Map<String, String>) null, (Map<String, String>) null));
    }
    private String bookmarkCommunityPost(String cid, String pid, boolean add) {
        return okResult(http(add ? "POST" : "DELETE", cpBase(cid, pid) + "/bookmark", (Map<String, String>) null, (Map<String, String>) null));
    }
    private String deleteCommunityPost(String cid, String pid) {
        return okResult(http("DELETE", cpBase(cid, pid), (Map<String, String>) null, (Map<String, String>) null));
    }
    private String getCommunityPostComments(String cid, String pid, String page) {
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("page", page == null || page.length() == 0 ? "1" : page);
        return okList(http("GET", cpBase(cid, pid) + "/comments", q, (Map<String, String>) null), "comments", "comments", "post_comments");
    }
    private String commentCommunityPost(String cid, String pid, String desc) {
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("description", desc == null ? "" : desc);
        f.put("image_file_path", ""); f.put("voice_file_path", ""); f.put("md5", "");
        return okResult(http("POST", cpBase(cid, pid) + "/comments", (Map<String, String>) null, f));
    }
    private String likeCommunityComment(String cid, String pid, String commentId, boolean like) {
        return okResult(http(like ? "POST" : "DELETE", cpBase(cid, pid) + "/comments/" + commentId + "/liked", (Map<String, String>) null, (Map<String, String>) null));
    }
    // 投げ銭
    private String getReceiveTippings(String targetId) {
        HashMap<String, String> q = new HashMap<String, String>();
        if (targetId != null && targetId.length() > 0) q.put("target_id", targetId);
        return okList(httpApi2("GET", "/api/receive_tippings", q, (Map<String, String>) null), "tippings", "tippings", "receive_tippings");
    }
    private String openAllTippings() {
        return okResult(httpApi2("PUT", "/api/tippings/all/open", (Map<String, String>) null, (Map<String, String>) null));
    }
    private String openTipping(String tippingId) {
        return okResult(httpApi2("PUT", "/api/tippings/" + tippingId + "/open", (Map<String, String>) null, (Map<String, String>) null));
    }
    // トークルーム(コミュニティ)
    private String postTalkRoomComment(String communityId, String roomId, String comment) {
        // 公式 TalkRoomApi.commentForCommunity は @Body JSON {"description":…}
        try {
            JSONObject cb = new JSONObject().put("description", comment == null ? "" : comment);
            Resp rr = httpJsonApi2("POST", "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/comments", cb);
            return okResult(rr);
        } catch (Exception e) {
            return errJson(e);
        }
    }
    private String switchTalkRoomComment(String communityId, String roomId, boolean enabled) {
        JSONObject body = new JSONObject();
        try { body.put("comment_enabled", enabled); } catch (Exception ig) {}
        return okResult(httpJsonApi2("PUT", "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/switch_comment_enabled", body));
    }
    // キャンペーン進捗
    private String campaignChallengeProgress(String campaignId, String challengeId) {
        return okResult(httpJson("POST", BASE_URL + "/api/user_campaigns/" + campaignId + "/challenges/" + challengeId + "/progress", new JSONObject()));
    }
    // 以下は旧API/用途不明のため best-effort（クエリ認証）。ダメなら診断ログで詰める。
    private String getDiveTargetFriends() {
        // 公式 generateTargetFriendsListRequest: POST api/v2/dive/target_friends (フォーム include_blocked_user)
        HashMap<String, String> tff = new HashMap<String, String>();
        tff.put("include_blocked_user", "false");
        return okList(request("POST", "/api/v2/dive/target_friends", (Map<String, String>) null, tff), "friends", "target_friends", "friends", "users");
    }
    private String getBadgeUsers() {
        return okList(request("GET", "/api/badge_users", (Map<String, String>) null, (Map<String, String>) null), "users", "badge_users", "users");
    }
    private String getCallRecordLikedUsers(String recordId) {
        String path = (recordId == null || recordId.length() == 0) ? "/api/call_record_liked_users" : "/api/call_record_liked_users/" + recordId;
        return okList(request("GET", path, (Map<String, String>) null, (Map<String, String>) null), "users", "liked_users", "users");
    }
    private String getExpirationDate() {
        Resp resp = request("GET", "/api/expiration_date", (Map<String, String>) null, (Map<String, String>) null);
        try {
            JSONObject data = resp.body != null ? resp.body.optJSONObject("data") : null;
            return new JSONObject().put("ok", resp.status == 200).put("data", data != null ? data : (resp.body != null ? resp.body : new JSONObject())).toString();
        } catch (Exception e) { return errJson(e); }
    }
    private String getMatching() {
        Resp resp = request("GET", "/api/matchings", (Map<String, String>) null, (Map<String, String>) null);
        if (resp.status != 200) resp = request("GET", "/api/matching", (Map<String, String>) null, (Map<String, String>) null);
        return okList(resp, "matchings", "matchings", "matches", "users");
    }
    private String giveCoin(String targetId, String amount) {
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("target_id", targetId == null ? "" : targetId);
        f.put("amount", amount == null ? "" : amount);
        f.put("coin", amount == null ? "" : amount);
        return okResult(request("POST", "/api/give_coin", (Map<String, String>) null, f));
    }
    private String passclearRequest(String email) {
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("email", email == null ? "" : email);
        return okResult(request("POST", "/api/account/passclear_request", (Map<String, String>) null, f));
    }

    private String getCommunityRule(String communityId, String ruleId) {
        if (communityId == null || communityId.length() == 0 || ruleId == null || ruleId.length() == 0) {
            return jsonErr("パラメータ不明");
        }
        Resp resp = request("GET", "/api/communities/" + communityId + "/rules/" + ruleId, (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("rule", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String deleteCommunityRule(String communityId, String ruleId) {
        if (communityId == null || communityId.length() == 0 || ruleId == null || ruleId.length() == 0) {
            return jsonErr("パラメータ不明");
        }
        return okResult(request("DELETE", "/api/communities/" + communityId + "/rules/" + ruleId, (Map<String, String>) null, (Map<String, String>) null));
    }

    private String getUserCampaign(String campaignId) {
        if (campaignId == null || campaignId.length() == 0) {
            return jsonErr("campaign_id不明");
        }
        Resp resp = request("GET", "/api/campaigns/" + campaignId + "/user_campaign", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("user_campaign", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String joinCampaign(String campaignId) {
        if (campaignId == null || campaignId.length() == 0) {
            return jsonErr("campaign_id不明");
        }
        // 公式 CampaignApi.getUserCampaign は GET。POSTでは受け付けられない。
        return okResult(request("GET", "/api/campaigns/" + campaignId + "/user_campaign", (Map<String, String>) null, (Map<String, String>) null));
    }

    private String recoverUserCampaign(String campaignId) {
        if (campaignId == null || campaignId.length() == 0) {
            return jsonErr("campaign_id不明");
        }
        return okResult(request("POST", "/api/campaigns/" + campaignId + "/user_campaign/recovery", (Map<String, String>) null, new HashMap()));
    }

    private String getCampaignChallengeProgress(String userCampaignId, String challengeId) {
        if (userCampaignId == null || userCampaignId.length() == 0 || challengeId == null || challengeId.length() == 0) {
            return jsonErr("パラメータ不明");
        }
        Resp resp = request("GET", "/api/user_campaigns/" + userCampaignId + "/challenges/" + challengeId + "/progress", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("progress", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getSubscriptionIntroductionSchedules() {
        Resp resp = request("GET", "/api/subscription_introduction_schedules", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("subscription_introduction_schedules");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("schedules", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 公式 EnqueteApi.enqueteTracking: POST /api/enquete_tracking（すべてクエリ）
    //   is_complete / exit_question_display_number / exit_question_id / next_action
    private String trackEnquete(String enqueteId, String event) {
        return trackEnquete(enqueteId, event, null, null, null);
    }

    private String trackEnquete(String enqueteId, String isComplete, String exitNo, String exitQuestionId, String nextAction) {
        HashMap<String, String> q = new HashMap<String, String>();
        if (enqueteId != null && enqueteId.length() > 0) q.put("enquete_id", enqueteId);
        if (isComplete != null && isComplete.length() > 0) q.put("is_complete", isComplete);
        if (exitNo != null && exitNo.length() > 0) q.put("exit_question_display_number", exitNo);
        if (exitQuestionId != null && exitQuestionId.length() > 0) q.put("exit_question_id", exitQuestionId);
        if (nextAction != null && nextAction.length() > 0) q.put("next_action", nextAction);
        Resp r = request("POST", "/api/enquete_tracking", q, new HashMap<String, String>());
        dbgLog(nowStr() + "  [ENQUETE] tracking -> " + r.status);
        return okResult(r);
    }

    // 通話後アンケートの回答送信（公式 generateSurveyResultRequest:
    //   POST api2 /api/answers  FORM token / talk_history_id / choice_id[]（複数可））
    private String sendEnqueteAnswer(String token, String talkHistoryId, String choiceIdsCsv) {
        HashMap<String, String> fields = new HashMap<String, String>();
        if (token != null && token.length() > 0) fields.put("token", token);
        if (talkHistoryId != null && talkHistoryId.length() > 0) fields.put("talk_history_id", talkHistoryId);
        if (choiceIdsCsv != null && choiceIdsCsv.length() > 0) {
            // FORM は同名キーを複数送れないため、単一/複数どちらでも動くよう連結して送る
            fields.put("choice_id[]", choiceIdsCsv.replace(" ", ""));
        }
        Resp r = request2("POST", "/api/answers", (Map<String, String>) null, fields);
        dbgLog(nowStr() + "  [ENQUETE] answer -> " + r.status + (r.status >= 400 && r.body != null ? " " + truncate(redactLog(r.body.toString()), 160) : ""));
        return okResult(r);
    }

    // アンケート回答（公式 EnqueteApi.sendAnswer: POST /api/enquete_answer）
    private String sendEnqueteAnswerV2(String bodyJson) {
        try {
            JSONObject body = (bodyJson == null || bodyJson.length() == 0) ? new JSONObject() : new JSONObject(bodyJson);
            Resp r = httpJsonApi2("POST", "/api/enquete_answer", body);
            dbgLog(nowStr() + "  [ENQUETE] enquete_answer -> " + r.status);
            return okResult(r);
        } catch (Exception e) { return errJson(e); }
    }

    // 起動時ピング（公式 generateSystemArrivalRequest: POST api/system/arrival）
    // 端末に保存した「最後に見た時刻」をサーバーへ知らせる。未読の判定精度が上がる。
    /** 起動時の到着通知。統計用の ping なので結果を待たず、別スレッドで送って即 ok を返す。 */
    private String systemArrival() {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try { systemArrivalBlocking(); } catch (Throwable ignored) {}
            }
        }, "koe-arrival");
        t.setDaemon(true);
        t.start();
        return "{\"ok\":true,\"async\":true}";
    }

    private String systemArrivalBlocking() {
        HashMap<String, String> fields = new HashMap<String, String>();
        String[][] keys = {
            {"last_info_at", "arr_info"}, {"last_talking_requests_at", "arr_talk_req"},
            {"last_message_received_at", "arr_msg"}, {"last_read_notice_at", "arr_notice"},
            {"last_all_feed_at", "arr_feed"}, {"last_all_timeline_at", "arr_timeline"},
            {"last_friend_timeline_at", "arr_friend_tl"}
        };
        for (int i = 0; i < keys.length; i++) {
            String v = this.prefs.getString(keys[i][1], "");
            if (v != null && v.length() > 0) fields.put(keys[i][0], v);
        }
        Resp r = request("POST", "/api/system/arrival", (Map<String, String>) null, fields);
        dbgLog(nowStr() + "  [ARRIVAL] -> " + r.status);
        // 次回用に現在時刻を記録
        try {
            String now = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).format(new java.util.Date());
            android.content.SharedPreferences.Editor ed = this.prefs.edit();
            for (int i = 0; i < keys.length; i++) ed.putString(keys[i][1], now);
            ed.apply();
        } catch (Exception e) {}
        return okResult(r);
    }

    // FCM のプッシュ登録ID をサーバーへ送る（公式 generateRegistrationIdTransmissionRequest）
    // ※ KoeTomo+ は FCM を持たないため通常は使わない。明示的に呼ばれたときだけ送る。
    private String sendRegistrationId(String registrationId) {
        if (registrationId == null || registrationId.length() == 0) return jsonErr("registration_id不明");
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("registration_id", registrationId);
        Resp r = request("POST", "/api/account/registration_id", (Map<String, String>) null, fields);
        dbgLog(nowStr() + "  [PUSH] registration_id 送信 -> " + r.status);
        return okResult(r);
    }

    // 通話録音（公式 SkyWayAuthApi）: join → start → token → stop
    private String recordingChannel(String action, String channelName) {
        if (channelName == null || channelName.length() == 0) return jsonErr("channel不明");
        String host = ensureSkywayHost();
        String enc = channelName;
        String url;
        String method;
        if ("start".equals(action)) { url = host + "/channels/" + enc + "/start"; method = "POST"; }
        else if ("stop".equals(action)) { url = host + "/channels/" + enc + "/stop"; method = "DELETE"; }
        else if ("token".equals(action)) { url = host + "/channels/" + enc + "/token"; method = "GET"; }
        else { url = host + "/channels/" + enc + "/join"; method = "POST"; }
        Resp r = http(method, url, (Map<String, String>) null, "GET".equals(method) ? (Map<String, String>) null : new HashMap<String, String>());
        dbgLog(nowStr() + "  [REC] " + action + " ch=" + channelName + " -> " + r.status
                + (r.status >= 400 && r.body != null ? " " + truncate(redactLog(r.body.toString()), 160) : ""));
        try {
            JSONObject out = new JSONObject().put("ok", r.status >= 200 && r.status < 300).put("status", r.status);
            if (r.body != null) out.put("body", r.body);
            return out.toString();
        } catch (Exception e) { return errJson(e); }
    }

    private String sendSkywayLog(String logJson) {
        HashMap<String, String> fields = new HashMap<String, String>();
        if (logJson != null && logJson.length() > 0) {
            fields.put("log", logJson);
        }
        return okResult(request("POST", "/api/skyway/logs", (Map<String, String>) null, fields));
    }

    private String getTiktokEventInfo() {
        Resp resp = request("GET", "/api/tiktok_event/entry_info", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("info", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getTiktokEventStatus() {
        Resp resp = request("GET", "/api/tiktok_event/entry_status", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("status", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String sendTiktokEventEntry(String eventType) {
        HashMap<String, String> fields = new HashMap<String, String>();
        if (eventType != null && eventType.length() > 0) {
            fields.put("event", eventType);
        }
        return okResult(request("POST", "/api/tiktok_event/entry", (Map<String, String>) null, fields));
    }

    private String getOfficialLinks() {
        try {
            JSONArray links = new JSONArray();
            links.put(new JSONObject().put("category", "サポート").put("title", "応援トークについて").put("url", "https://r.koetomo.fun/support/cheering_talk_info"));
            links.put(new JSONObject().put("category", "サポート").put("title", "応援トークの使い方ガイド").put("url", "https://r.koetomo.fun/support/guide_for_cheering_talk"));
            links.put(new JSONObject().put("category", "ヘルプ").put("title", "定期購入の解約方法").put("url", "https://r.koetomo.fun/help/cancel_subscription"));
            links.put(new JSONObject().put("category", "ヘルプ").put("title", "定期購入の再送").put("url", "https://r.koetomo.fun/help/retransmission_subscription"));
            links.put(new JSONObject().put("category", "インフォメーション").put("title", "コイン獲得について").put("url", "https://r.koetomo.fun/contents/coin_get_2022"));
            links.put(new JSONObject().put("category", "インフォメーション").put("title", "通話録音キャンペーン").put("url", "https://r.koetomo.fun/info/call_recording_campaign_202602"));
            return new JSONObject().put("ok", true).put("links", links).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /**
     * 公式アプリの「advertisement」設定のうち、koetomo自身の宣伝(subscription=定期購入の自社バナー)だけを返す。
     * AdMob / AdGeneration などの第三者広告ネットワークは、非公式アプリから koetomo の広告枠IDで
     * 読み込むと koetomo の広告アカウントに不正インプレッションが乗る(各社の規約違反)ため一切扱わない。
     * ここで返すのは koetomo が自前で配信する宣伝画像＋遷移先だけ。
     */
    private String getOfficialAd() {
        try {
            JSONObject sys = this.clientDefines != null ? this.clientDefines.optJSONObject("client_system_params") : null;
            JSONObject adv = sys != null ? sys.optJSONObject("advertisement") : null;
            JSONObject cfg = adv != null ? adv.optJSONObject("config") : null;
            JSONObject andr = cfg != null ? cfg.optJSONObject("android_for_g") : null;
            String url = andr != null ? andr.optString("url", "") : "";
            if (url.length() == 0) return new JSONObject().put("ok", true).put("has", false).toString();
            Resp r = http("GET", url, (Map<String, String>) null, (Map<String, String>) null);
            JSONObject body = r.body;
            if (body == null) return new JSONObject().put("ok", true).put("has", false).toString();
            JSONObject data = body.optJSONObject("data");
            JSONObject root = data != null ? data : body;
            // koetomo 自社宣伝(定期購入バナー)だけ取り出す
            JSONObject sub = root.optJSONObject("subscription");
            if (sub == null) return new JSONObject().put("ok", true).put("has", false).toString();
            int enabled = sub.optInt("enabled", 0);
            String img = firstStr(sub, "image_url", "imageUrl", "banner_image_url", "image");
            String link = firstStr(sub, "link_url", "linkUrl", "url", "redirect_url");
            if (link == null || link.length() == 0) link = "https://r.koetomo.fun/contents/coin_get_2022";
            boolean has = enabled == 1 && img != null && img.length() > 0;
            return new JSONObject().put("ok", true).put("has", has)
                    .put("image_url", img == null ? "" : img).put("link_url", link)
                    .put("label", "PR").toString();
        } catch (Exception e) {
            try { return new JSONObject().put("ok", true).put("has", false).toString(); } catch (Exception ig) { return errJson(e); }
        }
    }

    private String getSkyflagOfferWallUrl() {
        Resp resp = request("GET", "/api/skyflag/ow_url", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("url", firstStr(resp.body, "url", "ow_url", "offer_wall_url")).put("raw", resp.body).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String toggleFeedPostBookmark(String str, boolean z) {
        return toggleBookmark(str, z, false);
    }

    private String getCommunityBookmarks(String page) { return getCommunityBookmarks(page, null); }

    // cursor(= 前ページ最後の bookmarked_at)で続きを取得（公式 max_bookmarked_at）
    private String getCommunityBookmarks(String page, String cursor) {
        // 正しくは count パラメータが必須(無しだと 400 code4200)。レスポンスは data.bookmarks。
        Resp resp = request("GET", "/api/communities/bookmarks", cQ("count", "20", "max_bookmarked_at", cursor), (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return gracefulUnavailable(resp, "communities", "community_bookmarks");
            }
            JSONArray src = null;
            Object data = resp.body.opt("data");
            if (data instanceof JSONObject) {
                src = ((JSONObject) data).optJSONArray("bookmarks");
            } else if (data instanceof JSONArray) {
                src = (JSONArray) data;
            }
            if (src == null) {
                src = new JSONArray();
            }
            JSONArray out = new JSONArray();
            for (int i = 0; i < src.length(); i++) {
                JSONObject c = src.optJSONObject(i);
                if (c == null) continue;
                JSONObject comm = c.optJSONObject("community");
                if (comm == null) comm = c;
                out.put(new JSONObject().put("id", comm.opt("id"))
                        .put("name", comm.optString("name", ""))
                        .put("description", comm.optString("description", ""))
                        .put("icon_url", iconUrl(comm.optString("image_file_path", "")))
                        .put("participant_count", comm.optInt("participant_count", 0)));
            }
            return new JSONObject().put("ok", true).put("communities", out).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getOwnedItems() {
        Resp resp = request("GET", "/api/owned_items", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("owned_items");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("items", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getDecorationItems() {
        // 公式 DecorationApi.getDecorationItems は on_sale を必ず送る
        Resp resp = request("GET", "/api/decoration_items", q1("on_sale", "true"), (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("decoration_items");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("items", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String purchaseDecorationItem(String itemId) {
        if (itemId == null || itemId.length() == 0) {
            return jsonErr("item_id不明");
        }
        // 公式 DecorationApi.purchaseDecorationItems は @Query("item_pack_id")。
        // フォームで送っていたためサーバー側で品物が読めていなかった。
        return okResult(request("POST", "/api/decoration_items/purchase", q1("item_pack_id", itemId), (Map<String, String>) null));
    }

    private String getVoiceProfiles() {
        Resp resp = request("GET", "/api/v2/voice_profiles", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                resp = request("GET", "/api/voice_profiles", (Map<String, String>) null, (Map<String, String>) null);
            }
            if (resp.status != 200 || resp.body == null) {
                return gracefulUnavailable(resp, "voice_profiles", "voice_profiles");
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("voice_profiles");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("voice_profiles", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getSubscriptionHistories() {
        // 公式 SubscriptionApi.getSubscriptionHistories は order / page / type_ids を送る
        HashMap<String, String> shq = new HashMap<String, String>();
        shq.put("page", "1"); shq.put("order", "desc"); shq.put("type_ids", "1,2");
        Resp resp = request("GET", "/api/subscription_histories", shq, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("subscription_histories");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("subscription_histories", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getEnqueteQuestions(String enqueteId) {
        if (enqueteId == null || enqueteId.length() == 0) {
            return jsonErr("enquete_id不明");
        }
        Resp resp = request("GET", "/api/enquete_questions", q1("enquete_id", enqueteId), (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("enquete_questions");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("questions", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getSubscriptions() {
        Resp resp = request("GET", "/api/subscriptions", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("subscriptions");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("subscriptions", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String estimatePointExchange(String points) {
        if (points == null || points.length() == 0) {
            return jsonErr("points不明");
        }
        // 公式 CurrencyExchangeApi.estimatePointExchange も @Query("amount")
        return okResult(request("GET", "/api/estimate_point_exchange", q1("amount", points), (Map<String, String>) null));
    }

    private String executePointExchange(String points) {
        if (points == null || points.length() == 0) {
            return jsonErr("points不明");
        }
        // 公式 CurrencyExchangeApi.pointExchange は @Query("amount")
        return okResult(request("POST", "/api/point_exchange", q1("amount", points), (Map<String, String>) null));
    }

    private String getRoomSettings() {
        Resp resp = request("GET", "/api/room_settings", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            JSONObject d = resp.body.optJSONObject("data");
            JSONObject st = (d != null ? d : resp.body).optJSONObject("settings");
            if (st == null) st = (d != null ? d : resp.body);
            JSONObject out = new JSONObject().put("ok", true).put("settings", resp.body);
            out.put("sfu_max", st.optInt("sfu_room_max_member", 0));
            out.put("p2p_max", st.optInt("p2p_room_max_member", 0));
            out.put("sfu_title", st.optString("sfu_room_title", ""));
            out.put("p2p_title", st.optString("p2p_room_title", ""));
            out.put("sfu_desc", st.optString("sfu_room_description", ""));
            out.put("p2p_desc", st.optString("p2p_room_description", ""));
            dbgLog(nowStr() + "  [ROOMSET] sfu=" + out.optInt("sfu_max") + "人 p2p=" + out.optInt("p2p_max") + "人");
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String getEnquetes() {
        Resp resp = request("GET", "/api/enquetes", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            Object data = resp.body.opt("data");
            JSONArray items = data instanceof JSONArray ? (JSONArray) data : resp.body.optJSONArray("enquetes");
            if (items == null) {
                items = new JSONArray();
            }
            return new JSONObject().put("ok", true).put("enquetes", items).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String answerEnquete(String enqueteId, String questionId, String answer) {
        if (enqueteId == null || enqueteId.length() == 0) {
            return jsonErr("enquete_id不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("enquete_id", enqueteId);
        if (questionId != null && questionId.length() > 0) {
            fields.put("question_id", questionId);
        }
        if (answer != null) {
            fields.put("answer", answer);
        }
        return okResult(request("POST", "/api/enquete_answer", (Map<String, String>) null, fields));
    }

    private String toggleBookmark(String str, boolean z) {
        return toggleBookmark(str, z, true);
    }

    // 公式(BookmarkApi, TYPE_2=api2) は POST /api/bookmark_timeline_post?timeline_post_id= /
    // DELETE /api/delete_timeline_post_bookmark、つぶやくは feed_post_id 版。version=android_3.9.101、
    // 成功時も vsns!=0 が返るため HTTP ステータスで判定する。種別が不明な場合は両方試す。
    private String toggleBookmark(String str, boolean removing, boolean isTalk) {
        String[][] kinds = isTalk
            ? new String[][]{{"/api/bookmark_timeline_post", "/api/delete_timeline_post_bookmark", "timeline_post_id"}, {"/api/bookmark_feed_post", "/api/delete_feed_post_bookmark", "feed_post_id"}}
            : new String[][]{{"/api/bookmark_feed_post", "/api/delete_feed_post_bookmark", "feed_post_id"}, {"/api/bookmark_timeline_post", "/api/delete_timeline_post_bookmark", "timeline_post_id"}};
        String[] hosts = new String[]{BASE_URL2, BASE_URL};
        Resp last = new Resp(0, (JSONObject) null);
        for (String[] k : kinds) {
            for (String host : hosts) {
                HashMap<String, String> q = new HashMap<>();
                q.put(k[2], str);
                q.put("version", "android_" + APP_VERSION);
                String authToken = authToken();
                if (authToken != null) q.put("auth_token", authToken);
                Resp r = removing
                    ? http("DELETE", host + k[1], q, (Map<String, String>) null)
                    : http("POST", host + k[0], q, new HashMap<String, String>());
                dbgLog(nowStr() + "  [BOOKMARK] " + (removing ? "DEL " + k[1] : "POST " + k[0]) + " @" + host + " " + k[2] + "=" + str + " -> " + r.status + " vsns=" + r.vsns + (r.body != null ? " " + truncate(redactLog(r.body.toString()), 200) : ""));
                if (r.status >= 200 && r.status < 300) {
                    return okResultStatus(r);
                }
                last = r;
            }
        }
        return okResultStatus(last);
    }

    private String toggleCommunityLike(String str, String str2, boolean z) {
        return okResult(request(z ? "DELETE" : "POST", "/api/communities/" + str + "/posts/" + str2 + "/liked", (Map<String, String>) null, z ? null : new HashMap()));
    }

    private Boolean likePrefersServer1Cache = null;
    private boolean likePrefersServer1() {
        if (likePrefersServer1Cache == null) {
            try { likePrefersServer1Cache = Boolean.valueOf(this.prefs.getBoolean("like_prefers_server1", false)); }
            catch (Exception e) { likePrefersServer1Cache = Boolean.FALSE; }
        }
        return likePrefersServer1Cache.booleanValue();
    }
    private void setLikePrefersServer1(boolean v) {
        if (likePrefersServer1Cache != null && likePrefersServer1Cache.booleanValue() == v) return;
        likePrefersServer1Cache = Boolean.valueOf(v);
        try { this.prefs.edit().putBoolean("like_prefers_server1", v).apply(); } catch (Exception e) {}
    }
    private String toggleLike(String str, boolean z) {
        // いいねは feed_posts/{id}/like のみ(タイムライン投稿も共通)。2xxなら成功扱い(vsnsで誤判定して
        // フロントが取り消すのを防ぐ)。失敗時は api2→api1 も試す。診断用にHTTP状況を記録。
        Resp r;
        if (likePrefersServer1()) {
            // 前回 api2 が 404 で旧サーバーが成功した → 以後は旧サーバーを先に(無駄打ち防止)
            r = (z ? http("DELETE", "https://api.meetscom.com/api/feed_posts/" + str + "/like", likeFields(), (Map<String, String>) null) : http("POST", "https://api.meetscom.com/api/feed_posts/" + str + "/like", (Map<String, String>) null, likeFields()));
            if (!(r.status >= 200 && r.status < 300)) { setLikePrefersServer1(false); r = newTimelineApi(z ? "DELETE" : "POST", "/api/feed_posts/" + str + "/like"); }
        } else {
            r = newTimelineApi(z ? "DELETE" : "POST", "/api/feed_posts/" + str + "/like");
            if (!(r.status >= 200 && r.status < 300)) {
                Resp r2 = (z ? http("DELETE", "https://api.meetscom.com/api/feed_posts/" + str + "/like", likeFields(), (Map<String, String>) null) : http("POST", "https://api.meetscom.com/api/feed_posts/" + str + "/like", (Map<String, String>) null, likeFields()));
                if (r2.status >= 200 && r2.status < 300) { r = r2; setLikePrefersServer1(true); }
            }
        }
        dbgLog(nowStr() + "  [LIKE] " + (z ? "un" : "") + "like post=" + str + " HTTP " + r.status + " vsns=" + r.vsns);
        boolean ok = r.status >= 200 && r.status < 300;
        try {
            JSONObject out = new JSONObject().put("ok", ok).put("status", r.status);
            if (r.sessionExpired) out.put("session_expired", true);
            if (r.authError) out.put("auth_error", true);
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private Map<String, String> likeFields() {
        HashMap<String, String> m = new HashMap<String, String>();
        m.put("version", "android_" + APP_VERSION);
        String t = authToken();
        if (t != null) m.put("auth_token", t);
        return m;
    }

    private String toggleRecordLike(String str, boolean z) {
        HashMap hashMap = new HashMap();
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        String str2 = "/api/call_records/" + str + "/like";
        Resp http = http(z ? "DELETE" : "POST", BASE_URL2 + str2, (Map<String, String>) null, hashMap);
        if (http.status == 404 || http.status >= 500) {
            http = http(z ? "DELETE" : "POST", BASE_URL + str2, (Map<String, String>) null, hashMap);
        }
        return okResult(http);
    }

    private static String truncate(String str, int i) {
        return str == null ? "" : str.length() > i ? str.substring(0, i) : str;
    }

    private String unblockUser(String str) {
        HashMap hashMap = new HashMap();
        hashMap.put("target_id", str);
        hashMap.put("version", "android_" + APP_VERSION);
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        return okResult(request("POST", "/api/relation/block/deletes", (Map<String, String>) null, hashMap));
    }

    /** 相手をいま自分がフォローしているか、サーバーに聞き直す(解除の実効を確かめるため)。 */
    private boolean stillFollowing(String targetId) {
        try {
            Resp r = request("GET", "/api/v3/users/" + targetId, q1("fields", "follow"), (Map<String, String>) null);
            if (r.status != 200 || r.body == null) return true; // 分からないときは「まだ」とみなして次を試す
            JSONObject u = r.body.optJSONObject("user_info");
            if (u == null) {
                JSONArray a = r.body.optJSONArray("user_info");
                if (a != null && a.length() > 0) u = a.optJSONObject(0);
            }
            if (u == null) u = r.body;
            return relFollowing(u);
        } catch (Exception e) {
            return true;
        }
    }

    // koetomo にはフォロー解除にあたるエンドポイントが複数あり、関係の種類で効くものが違う。
    //   DELETE /api/relation/follows    … 自分が出した申請の取り消し(is_friend_requestee 系)
    //   DELETE /api/relation/followers  … 旧来のフォロー関係(is_followee 系)
    //   POST   /api/relation/unfollow   … 公式アプリのコードにはあるがサーバーには無い(404)
    // どれも 200 を返しうるのに関係が消えていないことがあるため、
    // 1つ試すたびにサーバーへ状態を確認し、実際に外れるまで次を試す。
    private String unfollowUser(String str) {
        // 公式 OkHttpSingleton.generateFollowCancellationRequest:
        //   DELETE api/followings/{user_id}   ← タイムラインのフォロー(is_followee)を外す本命
        // (対になる フォロー は POST api/followings + target_id)
        // 公式には2系統ある: generateFollowCancellationRequest(DELETE api/followings/{id}) と
        // TimelineApiServer1.unFollow(DELETE api/relation/new_follow/following?target_id=…)。
        // /api/relation/follows は「友達申請の取り消し」で別物なので後ろに置く。
        // /api/relation/follows は「自分が出した友達申請の取り消し」、
        // /api/relation/followers は「来ている友達申請を断る」で、どちらもフォロー解除ではない。
        // 巻き添えで友達関係を壊すので、フォロー解除の経路からは外す。
        String[] attempts = new String[]{"DELETE /api/followings/" + str, "DELETE /api/relation/new_follow/following"};
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("target_id", str);
        q.put("version", "android_" + APP_VERSION);
        String at = authToken();
        if (at != null) q.put("auth_token", at);
        Resp last = new Resp(0, (JSONObject) null);
        for (int i = 0; i < attempts.length; i++) {
            int sp = attempts[i].indexOf(' ');
            String method = attempts[i].substring(0, sp);
            String path = attempts[i].substring(sp + 1);
            Resp r;
            if ("POST".equals(method)) {
                r = http("POST", BASE_URL + path, (Map<String, String>) null, q);
            } else {
                r = http(method, BASE_URL + path, q, (Map<String, String>) null);
            }
            last = r;
            dbgLog(nowStr() + "  [UNFOLLOW] " + method + " " + path + " target=" + str + " -> " + r.status
                    + (r.status >= 200 && r.status < 300 ? "" : " " + truncate(redactLog(r.body != null ? r.body.toString() : ""), 160)));
            if (r.status < 200 || r.status >= 300) continue;
            clearRelationSets();
            if (!stillFollowing(str)) {
                dbgLog(nowStr() + "  [UNFOLLOW] 解除を確認しました (" + path + ")");
                return okResultStatus(r);
            }
            dbgLog(nowStr() + "  [UNFOLLOW] " + path + " は200だが関係が残っているため次を試します");
        }
        try {
            return new JSONObject().put("ok", false).put("status", last.status)
                    .put("error", "unfollow_not_applied")
                    .put("message", "サーバー側でフォロー解除が反映されませんでした").toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String updateProfile(String str, String str2, String str3) {
        HashMap hashMap = new HashMap();
        if (str == null) {
            str = "";
        }
        hashMap.put("name", str);
        hashMap.put("email", "");
        if (str3 == null) {
            str3 = "";
        }
        hashMap.put("birthday", str3);
        if (str2 == null) {
            str2 = "";
        }
        hashMap.put("comment", str2);
        hashMap.put("referer_name", "");
        hashMap.put("birthday_input_error", "");
        Resp resp = request("POST", "/api/account/profile_update", (Map<String, String>) null, hashMap);
        // 保存成功時、送信した生年月日をキャッシュしておく(次回以降の保存で「生年月日エラー」が
        // 再発しないようにするため。/api/v2/users, /api/v3/users/{id} はbirthdayを返さない非公開項目)。
        if (resp.status >= 200 && resp.status < 300 && str3.length() > 0) {
            setBirthday(str3);
        }
        return okResult(resp);
    }

    private String uploadAccountImage(String str, String str2) {
        Bitmap createScaledBitmap;
        String str3;
        String str4;
        boolean equals = "profile".equals(str2);
        if (str != null) {
            try {
                if (str.length() != 0) {
                    int indexOf = str.indexOf(44);
                    if (str.startsWith("data:") && indexOf >= 0) {
                        str = str.substring(indexOf + 1);
                    }
                    byte[] decode = Base64.decode(str, 0);
                    Bitmap decodeByteArray = BitmapFactory.decodeByteArray(decode, 0, decode.length);
                    if (decodeByteArray == null) {
                        return jsonErr("画像を読み込めませんでした");
                    }
                    if (!equals) {
                        int width = decodeByteArray.getWidth();
                        int height = decodeByteArray.getHeight();
                        int min = Math.min(width, 1080);
                        if (width > 0) {
                            height = (int) ((((long) height) * ((long) min)) / ((long) width));
                        }
                        createScaledBitmap = Bitmap.createScaledBitmap(decodeByteArray, Math.max(min, 1), Math.max(height, 1), true);
                    } else {
                        int width2 = decodeByteArray.getWidth();
                        int height2 = decodeByteArray.getHeight();
                        int min2 = Math.min(width2, height2);
                        Bitmap createBitmap = Bitmap.createBitmap(decodeByteArray, (width2 - min2) / 2, (height2 - min2) / 2, min2, min2);
                        int min3 = Math.min(min2, 720);
                        createScaledBitmap = Bitmap.createScaledBitmap(createBitmap, min3, min3, true);
                    }
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    createScaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, byteArrayOutputStream);
                    byte[] byteArray = byteArrayOutputStream.toByteArray();
                    JSONObject imageS3Config = imageS3Config();
                    JSONObject cognitoCredentials = cognitoCredentials(imageS3Config);
                    String str5 = UUID.randomUUID().toString().replace("-", "") + ".png";
                    String optString = imageS3Config.optString("path", "");
                    String str6 = (optString == null || optString.length() <= 0) ? str5 : optString.replaceAll("^/+", "").replaceAll("/+$", "") + "/" + str5;
                    String s3PutPng = s3PutPng(imageS3Config, cognitoCredentials, byteArray, str6);
                    if (s3PutPng != null) {
                        return jsonErr(s3PutPng);
                    }
                    String md5Hex = md5Hex(byteArray);
                    HashMap hashMap = new HashMap();
                    String v2Path = null;
                    if ("header".equals(str2)) {
                        str3 = "header_image_file_path";
                        str4 = "/api/account/header_images";
                        v2Path = "/api/v2/account/header_images";
                    } else if ("background".equals(str2) || TIMELINE_IMAGE_KIND.equals(str2)) {
                        // 投稿デコレーション(公式の timeline_images)。全ユーザーに公開されるため、
                        // JS 側は必ず「このように表示されます」の確認を経てから呼ぶ。
                        str3 = "timeline_image_file_path";
                        str4 = "/api/account/timeline_images";
                    } else {
                        str3 = "profile_picture_file_path";
                        str4 = "/api/account/profile_pictures";
                        v2Path = "/api/v2/account/profile_pictures";
                    }
                    // S3キーは str6(=images/<uuid>.png)、送信する *_file_path はファイル名だけ str5
                    // (公式 S3FileManager.uploadFile は "<uuid>.png" を返し、それをそのまま送る)
                    hashMap.put(str3, str5);
                    hashMap.put("md5", md5Hex);
                    hashMap.put("version", "android_" + APP_VERSION);
                    String authToken = authToken();
                    if (authToken != null) {
                        hashMap.put("auth_token", authToken);
                    }
                    Resp http;
                    if (v2Path != null) {
                        // 公式(ProfileImageUploadApi)は PUT /api/v2/account/{header_images|profile_pictures} を
                        // server1 に送り、{file_path, md5, auth_token, version} の JSON を AES-GCM で暗号化した
                        // payload/vt/gt を同時に付ける。v1 は 200 を返しても反映されない。
                        HashMap<String, String> v2Fields = new HashMap<>(hashMap);
                        try {
                            JSONObject pj = new JSONObject();
                            pj.put(str3, str5);
                            pj.put("md5", md5Hex);
                            if (authToken != null) pj.put("auth_token", authToken);
                            pj.put("version", "android_" + APP_VERSION);
                            String[] enc3 = encryptForKoetomo(pj.toString());
                            v2Fields.put("payload", enc3[0]);
                            v2Fields.put("vt", enc3[1]);
                            v2Fields.put("gt", enc3[2]);
                        } catch (Exception e) {
                            dbgLog(nowStr() + "  [UPLOAD] payload encrypt failed: " + e);
                        }
                        http = http("PUT", BASE_URL + v2Path, (Map<String, String>) null, v2Fields);
                        if (http.status == 404 || http.status >= 500) {
                            http = http("PUT", BASE_URL2 + v2Path, (Map<String, String>) null, v2Fields);
                        }
                        if (http.status < 200 || http.status >= 300) {
                            dbgLog(nowStr() + "  [UPLOAD] v2 " + v2Path + " -> " + http.status + " ; fallback v1");
                            http = http("PUT", BASE_URL + str4, (Map<String, String>) null, hashMap);
                            if (http.status == 404 || http.status >= 500) {
                                http = http("PUT", BASE_URL2 + str4, (Map<String, String>) null, hashMap);
                            }
                        }
                    } else {
                        http = http("PUT", BASE_URL + str4, (Map<String, String>) null, hashMap);
                        if (http.status == 404 || http.status >= 500) {
                            http = http("PUT", BASE_URL2 + str4, (Map<String, String>) null, hashMap);
                        }
                    }
                    if (http.status >= 200 && http.status < 300) {
                        return new JSONObject().put("ok", true).put("image_file_path", str6).toString();
                    }
                    return new JSONObject().put("ok", false).put("status", http.status).put("raw", http.body != null ? truncate(http.body.toString(), 300) : "").toString();
                }
            } catch (Exception e) {
                return errJson(e);
            }
        }
        return jsonErr("画像がありません");
    }

    private String userHistoryResult(Resp resp, String... strArr) {
        try {
            if (resp.status != 200 || resp.body == null) {
                return new JSONObject().put("ok", false).put("status", resp.status).toString();
            }
            JSONObject optJSONObject = resp.body.optJSONObject("data");
            JSONArray firstArray = optJSONObject != null ? firstArray(optJSONObject, strArr) : null;
            if (firstArray == null) {
                firstArray = firstArray(resp.body, strArr);
            }
            JSONArray jSONArray = firstArray == null ? new JSONArray() : firstArray;
            JSONArray jSONArray2 = new JSONArray();
            for (int i = 0; i < jSONArray.length(); i++) {
                JSONObject optJSONObject2 = jSONArray.optJSONObject(i);
                if (optJSONObject2 != null) {
                    JSONObject optJSONObject3 = optJSONObject2.optJSONObject("opponent");
                    if (optJSONObject3 == null) {
                        optJSONObject3 = optJSONObject2.optJSONObject("caller");
                    }
                    if (optJSONObject3 == null) {
                        optJSONObject3 = optJSONObject2.optJSONObject("from_user");
                    }
                    if (optJSONObject3 == null) {
                        optJSONObject3 = optJSONObject2.optJSONObject("sender");
                    }
                    if (optJSONObject3 == null) {
                        optJSONObject3 = optJSONObject2.optJSONObject("requester");
                    }
                    JSONObject optJSONObject4 = optJSONObject3 == null ? optJSONObject2.optJSONObject("user") : optJSONObject3;
                    long j = 0;
                    if (optJSONObject4 != null) {
                        j = optJSONObject4.optLong("id", optJSONObject4.optLong("user_id", 0));
                    }
                    if (j == 0) {
                        j = optJSONObject2.optLong("opponent_id", optJSONObject2.optLong("caller_id", optJSONObject2.optLong("target_id", optJSONObject2.optLong("user_id", 0))));
                    }
                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("user_id", j);
                    jSONObject.put("name", optJSONObject4 != null ? firstNonEmpty(optJSONObject4.optString("nickname", ""), optJSONObject4.optString("name", "")) : "");
                    jSONObject.put("icon_url", optJSONObject4 != null ? iconUrl(optJSONObject4.optString("profile_picture_file_path", optJSONObject4.optString("profilePictureFilePath", ""))) : "");
                    jSONObject.put("created_at", firstNonEmpty(optJSONObject2.optString("created_at", ""), optJSONObject2.optString("talked_at", ""), optJSONObject2.optString("requested_at", "")));
                    jSONArray2.put(jSONObject);
                }
            }
            JSONArray jSONArray3 = new JSONArray();
            for (int i2 = 0; i2 < jSONArray2.length(); i2++) {
                JSONObject optJSONObject5 = jSONArray2.optJSONObject(i2);
                if (optJSONObject5 != null && optJSONObject5.optString("name", "").length() == 0 && optJSONObject5.optLong("user_id", 0) > 0) {
                    jSONArray3.put(optJSONObject5);
                }
            }
            if (jSONArray3.length() > 0) {
                resolveNames(jSONArray3, "user_id");
            }
            for (int i3 = 0; i3 < jSONArray2.length(); i3++) {
                JSONObject optJSONObject6 = jSONArray2.optJSONObject(i3);
                if (optJSONObject6 != null) {
                    long optLong = optJSONObject6.optLong("user_id", 0);
                    if (optJSONObject6.optString("name", "").length() == 0) {
                        String[] strArr2 = this.nameCache.get(Long.valueOf(optLong));
                        optJSONObject6.put("name", strArr2 != null ? strArr2[0] : optLong > 0 ? "user " + optLong : "?");
                        if (optJSONObject6.optString("icon_url", "").length() == 0 && strArr2 != null) {
                            optJSONObject6.put("icon_url", iconUrl(strArr2[1]));
                        }
                    }
                }
            }
            return new JSONObject().put("ok", true).put("items", jSONArray2).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private long userId() {
        return this.prefs.getLong("user_id", 0);
    }

    private String userName() {
        return this.prefs.getString("user_name", "");
    }

    // 生年月日(YYYYMMDD)をログイン応答/プロフィール更新成功時にキャッシュしておく。
    // koetomo APIの公開プロフィール取得系エンドポイント(/api/v2/users, /api/v3/users/{id})は
    // birthdayを返さない(本人限定の非公開フィールドのため)。公式アプリもログイン応答から取得した
    // 値をローカルに保持して編集画面にプリフィルする方式のため、同じ方式を採用する。
    private void setBirthday(String str) {
        if (str == null || str.length() == 0) return;
        this.prefs.edit().putString("birthday", str).apply();
    }

    private String birthday() {
        return this.prefs.getString("birthday", "");
    }

    // ===== 共有BANリスト連携(モデレーション) =====
    // 端末内フィルタ用のBAN判定。koetomo本体のブロックには一切触れない。
    private boolean isBanned(long uid) {
        return uid != 0 && this.bannedUids.contains(Long.valueOf(uid));
    }

    private String modBase(String url) {
        if (url == null) return "";
        String u = url.trim();
        if (u.length() == 0) return "";
        if (!u.startsWith("http")) u = "https://" + u;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    private String modReadStream(java.io.InputStream in) throws Exception {
        if (in == null) return "";
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new String(bo.toByteArray(), "UTF-8");
    }

    // GET /api/bl/list を取得して bannedUids を置き換える。304なら現状維持。
    private String moderationBanlist(String url, String etag) {
        String base = modBase(url);
        if (base.length() == 0) return jsonErr("BANリストURL未設定");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(base + "/api/bl/list").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", UA);
            if (etag != null && etag.length() > 0) conn.setRequestProperty("If-None-Match", etag);
            int status = conn.getResponseCode();
            String newEtag = conn.getHeaderField("ETag");
            if (status == 304) {
                return new JSONObject().put("ok", true).put("not_modified", true).put("etag", etag == null ? "" : etag).put("count", this.bannedUids.size()).toString();
            }
            if (status != 200) {
                return new JSONObject().put("ok", false).put("status", status).toString();
            }
            String body = modReadStream(conn.getInputStream());
            JSONObject bj = new JSONObject(body);
            JSONArray arr = bj.optJSONArray("banned");
            java.util.HashSet<Long> fresh = new java.util.HashSet<Long>();
            JSONArray outBanned = new JSONArray();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    long uid = 0;
                    try { uid = Long.parseLong(o.optString("uid", "0").trim()); } catch (Exception ig) {}
                    if (uid != 0) fresh.add(Long.valueOf(uid));
                    outBanned.put(o);
                }
            }
            this.bannedUids.clear();
            this.bannedUids.addAll(fresh);
            dbgLog(nowStr() + "  [BANLIST] synced count=" + this.bannedUids.size() + " version=" + bj.opt("version"));
            return new JSONObject().put("ok", true).put("not_modified", false).put("etag", newEtag == null ? "" : newEtag)
                    .put("version", bj.opt("version")).put("count", this.bannedUids.size()).put("banned", outBanned).toString();
        } catch (Exception e) {
            return errJson(e);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ig) {}
        }
    }

    private String modPostJson(String url, JSONObject body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
            int status = conn.getResponseCode();
            String resp = modReadStream(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            JSONObject out = new JSONObject();
            out.put("ok", status >= 200 && status < 300).put("status", status);
            if (resp != null && resp.length() > 0) {
                try {
                    JSONObject rj = new JSONObject(resp);
                    if (rj.has("error")) out.put("error", rj.opt("error"));
                    if (rj.has("duplicate")) out.put("duplicate", rj.opt("duplicate"));
                    if (rj.has("lane")) out.put("lane", rj.opt("lane"));
                    if (rj.has("verified")) out.put("verified", rj.opt("verified"));
                    if (rj.has("verify_reason")) out.put("verify_reason", rj.opt("verify_reason"));
                } catch (Exception ig) { out.put("raw", truncate(resp, 200)); }
            }
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ig) {}
        }
    }

    // ===================== 業者(bot)自動判定 =====================
    // 判定はすべてネイティブ側で行い、材料は「その場で API から取り直した生の値」だけを使う。
    // JS から渡された値は一切採点に使わないので、WebView 側を書き換えても判定・証拠は偽造できない。
    //
    //  A: 必須条件(4つ全部を満たさないと自動申請しない)
    //    A1 アイコンのファイル名が 16文字ランダム英数
    //    A2 followee / follower / friend / liked すべて 0
    //    A3 自己紹介が空
    //    A4 年齢確認なし(age_verification_status = 0)
    //  B: 加点
    //    B1 名前が「単語+半角3桁数字」            +3
    //    B2 既知 bot と user_id が近接連番(±20)   +3
    //    B3 feature 文字列が既知 bot と完全一致    +2
    //    B4 ランダムマッチON かつ A2 成立          +1.5
    //    B5 直近1時間に5件以上投稿                 +2
    //    B6 直近ログイン(1時間以内)                +0.5
    //  A全成立 かつ B合計 >= 6.0 → 自動申請 / 3.0以上 → 画面上の「⚠ 業者?」表示のみ
    private static final long BOT_APP_START_MS = System.currentTimeMillis();
    /* ===== 業者判定ルールの文字列難読化 =====
       正規表現や判定理由をそのまま定数に置くと、APK を展開して strings を眺めるだけで
       「どう避ければ検知されないか」が分かってしまう。実行時に組み立てて復元する。 */
    private static final byte[] BK = new byte[]{107,84,43,98,111,116,47,50,48,50,54};
    private static String dx(String b64) {
        try {
            byte[] a = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP);
            byte[] o = new byte[a.length];
            for (int i = 0; i < a.length; i++) o[i] = (byte) (a[i] ^ BK[i % BK.length]);
            return new String(o, "UTF-8");
        } catch (Exception e) { return ""; }
    }
    private static final String R_ICON = dx("NQ9qTzUVAkgAHw82LxpUEigBGkBcURc+WwdQE1NFVVBGQnA=");
    private static final String R_NAME = dx("NQ91PhwpVAMcAAYWDxtPVilUAU0W");
    private static final String R_DIGIT = dx("NQ8bT1YpBBY=");
    private static final String L_ON1 = dx("WrKy4IbivNaLl9Pt0Q==");
    private static final String L_ON2 = dx("jtythtTRyrS1");
    private static final String L_ON3 = dx("iNaBgezHzLGZ0bTPt6jR");
    private static final String N_ICON = dx("gtOkhfvXyqy70bTJt6nGjPac0bOB0/vZ");
    private static final String N_NOICON = dx("iNaJge3QzLCD0bXYsrfIh9yC156o");
    private static final String N_ZERO = dx("j+6PhNr1H9Gzid7s/s7V3pObi9SJvYjVgYHu48yxi9eP372WwIjWldqav9Xq/sjj+A==");
    private static final String N_NAME = dx("jsSmh+b5zLO817vzvIH8REfJk7HUo9uxhvU=");
    private static final String N_NEAR = dx("jcOJhfDRTV1E0bfDHW+L79fIp5o=");
    private static final String N_FEAT = dx("jcOJhfDRTV1E0bfDsbvui8yvVFVTQh4mTg==");
    private static final String N_RM = dx("iNeCgezHzLGw0bXLt6j8jPes0bOzeSV/z9jLkpqzAA==");
    private static final String N_LOGIN = dx("jM+fitDlzLGd0bTbt6nGjPec");
    private static final String N_POST = dx("jM+fitDlHtSpsN/9x8jjxA==");
    private static final String N_POST2 = dx("j++dhOXhyJqP");
    private static final String R_BIO = dx("QzxfFh8HEAgfHUoHPUUHE5esm9OwkojXmB6AyaPdjbvZ1trE3+oIzLCb0bTAt6nIEx9OWVFdSh89QBYAH1PRs7TV6ffI4eyXrZ3Tsb6I16iB7dtT17md0M75V4fm28q9vtezzijMytOXrqBM1Z7Xt6rsE5Klp9iBsRcyUx6Lz4HUs4Hf687D0McIzLGj0bXot6jqjPac0bKW1ejnV4Hs5MywlNG14beoyBOXrKPTsaeN4JAeh9eg166QSo7wjIbVzsyznk7T4ubI4OWRp7XTsLwXt6j5ivyKTt+PsoTpph4LGcuKu9G3/req5hMQQtGxvdXq9Mjj+peutkxybQp5UVJCTXBvSwEaFihCBjROwI6qb0qN8LeF29bMs6fRt819");
    private static final String N_BIO = dx("g9OBh9jFyIaJ1o3gt6rJiv+I2pqq1ejvw8j3kZ+807OYg/61h+DR");
    private static final String N_NEARZERO = dx("j+6PhNr1zLOL0bfXZA==");
    private static final String N_NAMECLUSTER = dx("jsSnh/H/zLOe16bmsaLvR5GiqtiYqEBnzcPuXcyzvHtyg+u6hOHRzLOX2pLssr7S");
    private static final String N_CORE3 = dx("gtOkhfvXyqy70bfFs6Lbisqb0bG+BYjVj4bU0cuKug==");

    private static final double BOT_AUTO_SCORE = 6.0;
    private static final double BOT_MARK_SCORE = 3.0;

    private JSONArray botPrefArr(String key) {
        try { return new JSONArray(this.prefs.getString(key, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void botPrefPut(String key, JSONArray a, int cap) {
        try {
            while (a.length() > cap) a.remove(0);
            this.prefs.edit().putString(key, a.toString()).apply();
        } catch (Exception e) {}
    }

    // A を満たした相手を「候補」として端末内に控える。B2/B3 の照合に使うだけで、申請はしない。
    private void botRemember(long uid, String feature) {
        try {
            JSONArray a = botPrefArr("bot_cands");
            String fh = (feature == null || feature.length() == 0) ? "" : String.valueOf(feature.hashCode());
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null && o.optLong("u") == uid) { o.put("f", fh); o.put("t", System.currentTimeMillis()); botPrefPut("bot_cands", a, 300); return; }
            }
            a.put(new JSONObject().put("u", uid).put("f", fh).put("t", System.currentTimeMillis()));
            botPrefPut("bot_cands", a, 300);
        } catch (Exception e) {}
    }

    /* 「単語+3桁数字」型の名前を見かけた ID を覚え、同型の名前が ID 近接(±100)で他に 2 人以上いれば
       量産アカウント群とみなす(例: 点キーケース074 / マカロニストール194 / アヒルなす713 が連番で出現)。 */
    private void botRememberNameHit(long uid) {
        try {
            JSONArray a = botPrefArr("bot_namehits");
            for (int i = 0; i < a.length(); i++) if (a.optLong(i, 0) == uid) return;
            a.put(uid);
            botPrefPut("bot_namehits", a, 500);
        } catch (Exception e) {}
    }

    private int botNameHitNeighbors(long uid) {
        int n = 0;
        try {
            JSONArray a = botPrefArr("bot_namehits");
            for (int i = 0; i < a.length(); i++) {
                long v = a.optLong(i, 0);
                if (v != 0 && v != uid && Math.abs(v - uid) <= 100) n++;
            }
        } catch (Exception e) {}
        return n;
    }

    private boolean botKnownNear(long uid) {
        try {
            JSONArray a = botPrefArr("bot_cands");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                long v = o.optLong("u", 0);
                if (v != 0 && v != uid && Math.abs(v - uid) <= 20) return true;
            }
        } catch (Exception e) {}
        return false;
    }

    private boolean botKnownFeature(long uid, String feature) {
        if (feature == null || feature.length() == 0) return false;
        try {
            String fh = String.valueOf(feature.hashCode());
            JSONArray a = botPrefArr("bot_cands");
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (o.optLong("u", 0) != uid && fh.equals(o.optString("f", ""))) return true;
            }
        } catch (Exception e) {}
        return false;
    }

    // 直近 windowMs 以内の投稿件数。1回の取得(先頭ページ)だけで数える。
    private int botRecentPosts(long uid, long windowMs) {
        try {
            JSONObject r = new JSONObject(getUserPosts(String.valueOf(uid), ""));
            JSONArray ps = r.optJSONArray("posts");
            if (ps == null) return -1;
            long now = System.currentTimeMillis();
            int n = 0;
            for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.optJSONObject(i);
                if (p == null) continue;
                long t = botParseTime(p.optString("created_at", ""));
                if (t > 0 && now - t <= windowMs) n++;
            }
            return n;
        } catch (Exception e) { return -1; }
    }

    private static long botParseTime(String s) {
        if (s == null || s.length() < 10) return 0;
        String[] fmts = new String[]{"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd HH:mm:ss", "yyyy/MM/dd HH:mm:ss"};
        for (int i = 0; i < fmts.length; i++) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(fmts[i], Locale.US);
                if (i <= 1) f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                return f.parse(s.replace("+09:00", "+0900").replace("+00:00", "Z")).getTime();
            } catch (Exception e) {}
        }
        return 0;
    }

    // 判定本体。u は API から取り直した生のユーザー情報。
    /**
     * 画面表示用の業者判定。ルールは全てネイティブ側にあり、通信もしない。
     * app.js に判定式を置くと APK を展開しただけで回避方法が分かるため、JS からはこれを呼ぶだけにする。
     */
    private String botEvalLocal(String rawJson, String uidStr) {
        try {
            long uid = 0;
            try { uid = Long.parseLong(String.valueOf(uidStr).trim()); } catch (Exception ig) {}
            JSONObject u = (rawJson == null || rawJson.length() == 0) ? new JSONObject() : new JSONObject(rawJson);
            JSONObject r = botEval(u, uid, false);
            return new JSONObject().put("ok", true)
                    .put("level", r.optString("level", ""))
                    .put("hard", r.optBoolean("hard", false))
                    .put("reasons", r.optJSONArray("reasons") == null ? new JSONArray() : r.optJSONArray("reasons"))
                    .toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private JSONObject botEval(JSONObject u, long uid, boolean allowPostFetch) {
        JSONObject out = new JSONObject();
        try {
            JSONArray rs = new JSONArray();
            JSONObject ev = new JSONObject();

            String icon = u.optString("profile_picture_file_path", "");
            if (icon.length() == 0) icon = u.optString("icon_url", "");
            String fn = icon;
            int sl = fn.lastIndexOf('/'); if (sl >= 0) fn = fn.substring(sl + 1);
            int qm = fn.indexOf('?'); if (qm >= 0) fn = fn.substring(0, qm);
            /* アイコンが「16文字の自動生成名」だけでなく「未設定」も同じ扱いにする。
               アイコンを付けないだけで検知を抜けられていたため。 */
            boolean genIcon = fn.matches(R_ICON);
            boolean noIcon = (icon.trim().length() == 0);
            boolean a1 = genIcon || noIcon;

            int fol = u.optInt("follower_count", -1), fee = u.optInt("followee_count", -1), fr = u.optInt("friend_count", -1);
            long liked = u.optLong("liked_count", -1);
            boolean a2 = (fol == 0 && fee == 0 && fr == 0 && liked == 0);
            // 「ほぼ 0」も同じ扱い(1〜2 件だけ交流を作って検知を抜ける量産アカウントがいるため)
            boolean a2near = !a2 && fol >= 0 && fee >= 0 && fr >= 0 && liked >= 0 && fol <= 2 && fee <= 2 && fr == 0 && liked <= 2;

            String cm = u.isNull("comment") ? "" : u.optString("comment", "");
            boolean a3 = (cm.trim().length() == 0);
            // 自己紹介があっても、勧誘・外部誘導の語句なら「怪しい自己紹介」として同等に扱う
            boolean a3bio = !a3 && cm.toLowerCase(Locale.ROOT).replaceAll("\\s+", "").matches("(?s).*" + R_BIO + ".*");

            int av = (u.has("age_verification_status") && !u.isNull("age_verification_status")) ? u.optInt("age_verification_status", -1) : -1;
            boolean a4 = (av == 0);

            String nm = u.optString("name", "");
            boolean nameHit = nm.matches(R_NAME) && !nm.matches(R_DIGIT);
            String feat = u.isNull("feature") ? "" : u.optString("feature", "");
            boolean near = botKnownNear(uid);
            boolean knownFeat = botKnownFeature(uid, feat);

            /* 量産型の 4 特徴(アイコン・交流・自己紹介・年齢確認)のうち幾つ当たるか。
               以前は 4 つ全部が必要で、アイコンか自己紹介を 1 つ付けるだけで抜けられていた。
               いまは 3 つ + 補強材料(名前の型・既知 bot との連番/同一端末・勧誘文)でも量産型とみなす。 */
            int core = (a1 ? 1 : 0) + ((a2 || a2near) ? 1 : 0) + ((a3 || a3bio) ? 1 : 0) + (a4 ? 1 : 0);
            if (nameHit) botRememberNameHit(uid);
            int nameCluster = nameHit ? botNameHitNeighbors(uid) : 0;
            boolean hard = core >= 4
                    || (core >= 3 && (nameHit || near || knownFeat || a3bio))
                    || (core >= 2 && nameHit) // 「単語+3桁」の名前 + 量産型の特徴 2 つ
                    || (nameHit && nameCluster >= 2); // 同型の名前が ID 近接で複数
            if (hard) {
                rs.put(genIcon ? N_ICON : (noIcon ? N_NOICON : N_CORE3));
                if (a2 && a3 && a4) rs.put(N_ZERO);
                else if (a2near) rs.put(N_NEARZERO);
                if (a3bio) rs.put(N_BIO);
            }
            ev.put("icon_file", fn).put("follower_count", fol).put("followee_count", fee)
              .put("friend_count", fr).put("liked_count", liked).put("comment_empty", a3).put("comment_suspicious", a3bio)
              .put("age_verification_status", av).put("core_hits", core)
              .put("A1_icon16", a1).put("A2_all_zero", a2).put("A2_near_zero", a2near).put("A3_no_bio", a3).put("A4_no_age_verify", a4)
              .put("icon_kind", genIcon ? "generated" : (noIcon ? "none" : "normal"));

            double sc = 0;
            ev.put("name", nm);
            if (nameHit) { sc += 3; rs.put(N_NAME); }
            if (hard && noIcon) { sc += 1; }
            if (hard && a3bio) { sc += 2; }
            if (hard && near) { sc += 3; rs.put(N_NEAR); }
            ev.put("feature", feat.length() > 120 ? feat.substring(0, 120) : feat);
            if (hard && knownFeat) { sc += 3; rs.put(N_FEAT); }
            if (hard && nameCluster >= 2) { sc += 3; rs.put(N_NAMECLUSTER); }
            ev.put("name_cluster", nameCluster);
            boolean rm = truthy(u.opt("random_match_enabled"));
            JSONObject st = u.optJSONObject("settings");
            if (!rm && st != null) rm = truthy(st.opt("random_match_enabled"));
            ev.put("random_match_enabled", rm);
            if (rm && (a2 || a2near)) { sc += 1.5; rs.put(N_RM); }
            String ls = u.optString("login_status_with_unit", "");
            ev.put("login_status", ls);
            if (ls.indexOf(L_ON1) >= 0 || ls.indexOf(L_ON2) >= 0 || ls.indexOf(L_ON3) >= 0) { sc += 0.5; rs.put(N_LOGIN); }
            // B5 は通信が増えるので、結果を左右するとき(3.0〜6.0)だけ数えに行く
            int recent = -1;
            if (hard && allowPostFetch && sc >= BOT_MARK_SCORE && sc < BOT_AUTO_SCORE) {
                recent = botRecentPosts(uid, 3600000L);
                ev.put("posts_last_hour", recent);
                if (recent >= 5) { sc += 2; rs.put(N_POST + recent + N_POST2); }
            }

            String level = hard ? (sc >= BOT_AUTO_SCORE ? "high" : (sc >= BOT_MARK_SCORE ? "mid" : "")) : "";
            ev.put("score", sc).put("level", level).put("checked_at", nowStr()).put("checked_by", "KoeTomo+ auto");
            if (hard) botRemember(uid, feat);
            out.put("hard", hard).put("score", sc).put("level", level).put("reasons", rs).put("ev", ev);
        } catch (Exception e) {
            try { out.put("hard", false).put("score", 0).put("level", "").put("reasons", new JSONArray()).put("ev", new JSONObject()); } catch (Exception ig) {}
        }
        return out;
    }

    // 自動申請の暴走防止(端末内・ネイティブ側で管理)。
    //  ・同一 user_id は生涯1回まで  ・30秒間隔  ・1時間3件 / 1日10件  ・起動から10秒は動かさない
    private String botAutoGate(long uid) {
        try {
            if (System.currentTimeMillis() - BOT_APP_START_MS < 10000) return "起動直後は判定しません";
            JSONArray done = botPrefArr("bot_auto_done");
            for (int i = 0; i < done.length(); i++) if (done.optLong(i, 0) == uid) return "この相手は申請済みです";
            long now = System.currentTimeMillis();
            JSONArray log = botPrefArr("bot_auto_log");
            long last = 0; int inHour = 0, inDay = 0;
            for (int i = 0; i < log.length(); i++) {
                long t = log.optLong(i, 0);
                if (now - t < 86400000L) { inDay++; if (now - t < 3600000L) inHour++; if (t > last) last = t; }
            }
            if (last > 0 && now - last < 30000) return "自動申請の間隔制限中";
            if (inHour >= 3) return "自動申請は1時間3件までです";
            if (inDay >= 10) return "自動申請は1日10件までです";
            return "";
        } catch (Exception e) { return ""; }
    }

    private void botAutoMark(long uid) {
        try {
            JSONArray log = botPrefArr("bot_auto_log");
            long now = System.currentTimeMillis();
            JSONArray keep = new JSONArray();
            for (int i = 0; i < log.length(); i++) { long t = log.optLong(i, 0); if (now - t < 86400000L) keep.put(t); }
            keep.put(now);
            botPrefPut("bot_auto_log", keep, 60);
            JSONArray done = botPrefArr("bot_auto_done");
            done.put(uid);
            botPrefPut("bot_auto_done", done, 3000);
        } catch (Exception e) {}
    }

    // JS から「この相手を見た」と呼ばれる入口。判定・制限・申請まですべてネイティブ側で完結する。
    // 条件を満たさない・制限中なら何もせず理由だけ返す。ブロックは一切しない(申請のみ)。
    private String moderationAutoSpam(String url, String target) {
        long uid;
        try { uid = Long.parseLong(String.valueOf(target).trim()); } catch (Exception e) { return jsonErr("対象不明"); }
        try {
            if (uid == userId()) return new JSONObject().put("ok", true).put("applied", false).put("skip", "self").toString();
            String gate = botAutoGate(uid);
            if (gate.length() > 0) return new JSONObject().put("ok", true).put("applied", false).put("skip", gate).toString();
            // 直近7日に見た相手は取り直さない(通信とキャッシュの節約)
            JSONArray seen = botPrefArr("bot_auto_seen");
            long now = System.currentTimeMillis();
            JSONArray keep = new JSONArray();
            for (int i = 0; i < seen.length(); i++) {
                JSONObject o = seen.optJSONObject(i);
                if (o == null) continue;
                if (now - o.optLong("t", 0) > 604800000L) continue;
                if (o.optLong("u", 0) == uid) return new JSONObject().put("ok", true).put("applied", false).put("skip", "確認済み").toString();
                keep.put(o);
            }
            keep.put(new JSONObject().put("u", uid).put("t", now));
            botPrefPut("bot_auto_seen", keep, 500);

            Resp r = request("GET", "/api/v3/users/" + uid, q1("fields", "core,chat,friend,follow,block"), (Map<String, String>) null);
            if (r.status != 200 || r.body == null) return new JSONObject().put("ok", false).put("applied", false).put("error", "user_fetch_failed").toString();
            JSONObject u = botUserOf(r.body);
            JSONObject ev = botEval(u, uid, true);
            double sc = ev.optDouble("score", 0);
            String level = ev.optString("level", "");
            if (!"high".equals(level)) {
                return new JSONObject().put("ok", true).put("applied", false).put("score", sc).put("level", level).put("skip", "条件未達").toString();
            }
            JSONArray rs = ev.optJSONArray("reasons");
            StringBuilder detail = new StringBuilder("[KoeTomo+ 業者自動判定(自動申請) score=" + sc + "] ");
            for (int i = 0; rs != null && i < rs.length(); i++) { if (i > 0) detail.append("・"); detail.append(rs.optString(i)); }
            JSONObject body = botReportBody(uid, ev, u);
            String base = modBase(url);
            if (base.length() == 0) return jsonErr("BANリストURL未設定");
            dbgLog(nowStr() + "  [BOTAUTO] apply uid=" + uid + " score=" + sc + " confidence=" + body.optString("confidence") + " reasons=" + rs);
            String res = modPostJson(base + "/api/bl/report", body);
            JSONObject o;
            try { o = new JSONObject(res); } catch (Exception e) { o = new JSONObject(); }
            int st = o.optInt("status", 0);
            dbgLog(nowStr() + "  [BOTAUTO] server -> " + st + " lane=" + o.optString("lane", "-") + " verified=" + o.optString("verified", "-") + (o.has("verify_reason") ? " " + truncate(o.optString("verify_reason"), 80) : ""));
            if (o.optBoolean("ok", false) || (st >= 400 && st < 500 && st != 429)) {
                botAutoMark(uid); // 受理された / 内容の問題で拒否された → もう送らない
            } else if ("confirmed".equals(body.optString("confidence"))) {
                // 確定 bot は流量制限(429)やサーバー障害で落とさず、あとで再送する
                botQueueRetry(base, body);
            }
            botFlushRetry(base);
            try {
                o.put("applied", o.optBoolean("ok", false)).put("score", sc).put("reasons", rs).put("name", u.optString("name", ""))
                 .put("confidence", body.optString("confidence")).put("queued", !o.optBoolean("ok", false) && "confirmed".equals(body.optString("confidence")));
                return o.toString();
            } catch (Exception e) { return res; }
        } catch (Exception e) {
            return errJson(e);
        }
    }

    /**
     * BAN サーバーへ送る申請本文。
     * confidence: "confirmed" = 量産アカウント群としての確証(既知 bot との連番/同一端末、同型名の ID 近接)がある。
     *             サーバー側はこの値を信用せず、evidence の生値と自前の再取得で検証する前提(仕様書 docs/banlist-confirmed.md)。
     * evidence.verify: サーバーが koetomo API から取り直して照合するための材料(対象 ID・近接 ID・判定時刻)。
     */
    private JSONObject botReportBody(long uid, JSONObject ev, JSONObject u) throws Exception {
        double sc = ev.optDouble("score", 0);
        JSONArray rs = ev.optJSONArray("reasons");
        JSONObject e = ev.optJSONObject("ev") != null ? ev.optJSONObject("ev") : new JSONObject();
        boolean cluster = e.optInt("name_cluster", 0) >= 2;
        boolean nearKnown = false, sameFeat = false;
        for (int i = 0; rs != null && i < rs.length(); i++) {
            String r = rs.optString(i);
            if (r.equals(N_NEAR)) nearKnown = true;
            if (r.equals(N_FEAT)) sameFeat = true;
        }
        String confidence = (cluster || nearKnown || sameFeat) && sc >= BOT_AUTO_SCORE ? "confirmed" : "high";
        JSONArray neighbors = new JSONArray();
        try {
            JSONArray a = botPrefArr("bot_namehits");
            for (int i = 0; i < a.length(); i++) { long v = a.optLong(i, 0); if (v != 0 && v != uid && Math.abs(v - uid) <= 100) neighbors.put(v); }
            a = botPrefArr("bot_cands");
            for (int i = 0; i < a.length(); i++) { JSONObject o = a.optJSONObject(i); long v = o == null ? 0 : o.optLong("u", 0); if (v != 0 && v != uid && Math.abs(v - uid) <= 20) neighbors.put(v); }
        } catch (Exception ig) {}
        e.put("verify", new JSONObject().put("target_uid", uid).put("neighbor_uids", neighbors).put("checked_at_ms", System.currentTimeMillis())
                .put("rules", "core>=4 | core>=3+aux | core>=2+namepattern | namepattern+cluster>=2"));
        StringBuilder detail = new StringBuilder("[KoeTomo+ 業者自動判定(自動申請) score=" + sc + (confidence.equals("confirmed") ? " 確定" : "") + "] ");
        for (int i = 0; rs != null && i < rs.length(); i++) { if (i > 0) detail.append("・"); detail.append(rs.optString(i)); }
        JSONObject body = new JSONObject();
        body.put("target_uid", String.valueOf(uid));
        body.put("reason_code", "bot");
        body.put("detail", detail.toString());
        body.put("evidence", e.toString());
        body.put("reporter_uid", String.valueOf(userId()));
        body.put("auto", true);
        body.put("confidence", confidence);
        body.put("client", "koetomoplus-android/" + appVersionName());
        // 管理画面で ID ではなく名前で見られるように(サーバーは reporter_name / target_name を保存する)
        body.put("reporter_name", this.prefs.getString("user_name", ""));
        body.put("target_name", u.optString("name", ""));
        return body;
    }

    private String appVersionName() {
        try {
            android.content.Context c = this.appContext;
            if (c == null) return "?";
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) { return "?"; }
    }

    /* 確定 bot の申請が 429/5xx で通らなかった時の再送キュー(端末内)。1 分以上あけて 1 件ずつ。 */
    private void botQueueRetry(String base, JSONObject body) {
        try {
            JSONArray q = botPrefArr("bot_retry");
            String uid = body.optString("target_uid");
            for (int i = 0; i < q.length(); i++) { JSONObject o = q.optJSONObject(i); if (o != null && uid.equals(o.optString("target_uid"))) return; }
            body.put("queued_at", System.currentTimeMillis());
            q.put(body);
            botPrefPut("bot_retry", q, 50);
            dbgLog(nowStr() + "  [BOTAUTO] 再送キューへ uid=" + uid + " (" + q.length() + "件)");
        } catch (Exception e) {}
    }

    private long botRetryLastAt = 0;

    private void botFlushRetry(String base) {
        try {
            JSONArray q = botPrefArr("bot_retry");
            if (q.length() == 0) return;
            long now = System.currentTimeMillis();
            if (now - botRetryLastAt < 60000) return;
            botRetryLastAt = now;
            JSONObject body = q.optJSONObject(0);
            JSONArray rest = new JSONArray();
            for (int i = 1; i < q.length(); i++) rest.put(q.opt(i));
            if (body == null) { botPrefPut("bot_retry", rest, 50); return; }
            body.put("retry", true);
            String res = modPostJson(base + "/api/bl/report", body);
            JSONObject o = new JSONObject(res);
            int st = o.optInt("status", 0);
            if (o.optBoolean("ok", false) || (st >= 400 && st < 500 && st != 429)) {
                botAutoMark(body.optLong("target_uid", 0));
                dbgLog(nowStr() + "  [BOTAUTO] 再送 uid=" + body.optString("target_uid") + " -> " + st);
            } else if (now - body.optLong("queued_at", now) < 7L * 86400000L) {
                rest.put(body); // まだ通らない → 末尾に戻す(7 日で諦める)
            }
            botPrefPut("bot_retry", rest, 50);
        } catch (Exception e) {}
    }

    private JSONObject botUserOf(JSONObject body) {
        JSONObject d = body.optJSONObject("data");
        JSONObject u = d != null ? d.optJSONObject("user_info") : null;
        if (u == null && d != null) u = d.optJSONObject("userInfo");
        if (u == null) u = body.optJSONObject("user_info");
        if (u == null) u = (d != null ? d : body);
        return u;
    }

    // 業者(量産アカウント)としての申請。理由・証拠は JS から受け取らず、ネイティブ側でその場で API から
    // ユーザー情報を取得して判定する(偽造不可)。判定条件を満たさない相手は申請できない。
    private String moderationReportSpam(String url, String target) {
        String base = modBase(url);
        if (base.length() == 0) return jsonErr("BANリストURL未設定");
        long me = userId();
        if (me == 0) return jsonErr("ログインが必要です");
        long t;
        try { t = Long.parseLong(String.valueOf(target).trim()); } catch (Exception e) { return jsonErr("対象不明"); }
        if (t == me) { try { return new JSONObject().put("ok", false).put("error", "cannot_report_self").toString(); } catch (Exception e) { return errJson(e); } }
        try {
            Resp r = request("GET", "/api/v3/users/" + t, q1("fields", "core,chat,friend,follow,block"), (Map<String, String>) null);
            if (r.status != 200 || r.body == null) return new JSONObject().put("ok", false).put("error", "user_fetch_failed").put("status", r.status).put("message", "相手の情報を取得できませんでした").toString();
            JSONObject u = botUserOf(r.body);
            // ---- 判定(自動申請と同じ規則。材料はいま API から取り直した値だけ) ----
            JSONObject res0 = botEval(u, t, true);
            double sc = res0.optDouble("score", 0);
            String level = res0.optString("level", "");
            JSONArray reasons = res0.optJSONArray("reasons"); if (reasons == null) reasons = new JSONArray();
            JSONObject ev = res0.optJSONObject("ev"); if (ev == null) ev = new JSONObject();
            if (level.length() == 0) {
                String why = res0.optBoolean("hard", false)
                        ? ("業者判定の条件を満たしていません(スコア " + sc + ")。通常の通報をご利用ください")
                        : "業者判定の必須条件(量産型アイコン名・フォロー等すべて0・自己紹介なし・年齢確認なし)を満たしていません。通常の通報をご利用ください";
                return new JSONObject().put("ok", false).put("error", "not_spam_like").put("message", why).toString();
            }
            StringBuilder detail = new StringBuilder("[KoeTomo+ 業者自動判定 score=" + sc + "] ");
            for (int i = 0; i < reasons.length(); i++) { if (i > 0) detail.append("・"); detail.append(reasons.optString(i)); }
            JSONObject body = new JSONObject();
            body.put("target_uid", String.valueOf(t));
            body.put("reason_code", "bot");
            body.put("detail", detail.toString());
            body.put("evidence", ev.toString()); // アプリが API から取得した生の判定材料(ユーザー入力なし)
            body.put("reporter_uid", String.valueOf(me));
            body.put("reporter_name", this.prefs.getString("user_name", ""));
            body.put("target_name", u.optString("name", ""));
            dbgLog(nowStr() + "  [MODREPORT] spam target=" + t + " score=" + sc + " reasons=" + reasons);
            String res = modPostJson(base + "/api/bl/report", body);
            try { JSONObject o = new JSONObject(res); o.put("reasons", reasons).put("score", sc); return o.toString(); } catch (Exception e) { return res; }
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 通報。reporter_uid は必ずログイン中の自分のuid(改ざん不可)。
    private String moderationReport(String url, String target, String code, String detail, String evidence, String evidenceImage, String evidenceUrl, String contact) {
        String base = modBase(url);
        if (base.length() == 0) return jsonErr("BANリストURL未設定");
        long me = userId();
        if (me == 0) return jsonErr("ログインが必要です");
        if (target == null || target.trim().length() == 0) return jsonErr("対象不明");
        try {
            JSONObject body = new JSONObject();
            body.put("target_uid", target.trim());
            body.put("reason_code", (code == null || code.length() == 0) ? "other" : code);
            if (detail != null && detail.length() > 0) body.put("detail", detail);
            if (evidence != null && evidence.length() > 0) body.put("evidence", evidence);
            if (evidenceImage != null && evidenceImage.length() > 0) body.put("evidence_image", evidenceImage); // 圧縮済みbase64スクショ
            if (evidenceUrl != null && evidenceUrl.length() > 0) body.put("evidence_url", evidenceUrl);         // YouTube限定公開リンク等
            if (contact != null && contact.trim().length() > 0) body.put("reporter_contact", contact.trim());   // 返信希望者の声ともID(任意)
            body.put("reporter_uid", String.valueOf(me));
            body.put("reporter_name", this.prefs.getString("user_name", ""));
            dbgLog(nowStr() + "  [MODREPORT] target=" + target + " code=" + code + " img=" + (evidenceImage != null && evidenceImage.length() > 0) + " url=" + (evidenceUrl != null && evidenceUrl.length() > 0));
            return modPostJson(base + "/api/bl/report", body);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String moderationAppeal(String url, String target, String message, String evidenceImage, String evidenceUrl) {
        String base = modBase(url);
        if (base.length() == 0) return jsonErr("BANリストURL未設定");
        try {
            JSONObject body = new JSONObject();
            body.put("target_uid", target == null ? "" : target.trim());
            body.put("message", message == null ? "" : message);
            if (evidenceImage != null && evidenceImage.length() > 0) body.put("evidence_image", evidenceImage);
            if (evidenceUrl != null && evidenceUrl.length() > 0) body.put("evidence_url", evidenceUrl);
            return modPostJson(base + "/api/bl/appeal", body);
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String moderationMyUid() {
        try {
            return new JSONObject().put("ok", true).put("uid", userId()).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    private String viewUserProfile(String str) {
        long j = 0;
        try {
            j = Long.parseLong(str);
        } catch (Exception e) {
        }
        return buildProfile(j, true);
    }

    private String voiceUrl(String str) {
        if (str == null || str.length() == 0) {
            return "";
        }
        if (str.startsWith("http")) {
            return str;
        }
        try {
            JSONObject optJSONObject = this.clientDefines != null ? this.clientDefines.optJSONObject("client_system_params") : null;
            JSONObject optJSONObject2 = optJSONObject != null ? optJSONObject.optJSONObject("server_name") : null;
            if (optJSONObject2 != null) {
                JSONObject optJSONObject3 = optJSONObject2.optJSONObject("audio_download");
                if (optJSONObject3 == null) {
                    optJSONObject3 = optJSONObject2.optJSONObject("audio_upload");
                }
                String optString = optJSONObject3 != null ? optJSONObject3.optString("path", "audio") : "audio";
                String optString2 = optJSONObject2.optString("png", "");
                String str2 = "";
                if (optString2.startsWith("http")) {
                    int indexOf = optString2.indexOf(47, optString2.indexOf("://") + 3);
                    if (indexOf > 0) {
                        optString2 = optString2.substring(0, indexOf + 1);
                    } else if (!optString2.endsWith("/")) {
                        optString2 = optString2 + "/";
                    }
                    str2 = optString2;
                }
                if (str2.length() > 0) {
                    return str2 + optString + "/" + (str.startsWith("/") ? str.substring(1) : str);
                }
            }
        } catch (Exception e) {
        }
        return soundServerName() + str;
    }

    private String withdrawAccount(String str) {
        try {
            long userId = userId();
            HashMap hashMap = new HashMap();
            if (str == null) {
                str = "";
            }
            hashMap.put("reason", str);
            hashMap.put("uid", String.valueOf(userId));
            hashMap.put("version", "android_3.9.101");
            String authToken = authToken();
            if (authToken != null) {
                hashMap.put("auth_token", authToken);
            }
            Resp http = http("POST", "https://api.meetscom.com/api/account/withdrawal", (Map<String, String>) null, hashMap);
            if (http.status == 404 || http.status >= 500) {
                http = http("POST", "https://api2.meetscom.com/api/account/withdrawal", (Map<String, String>) null, hashMap);
            }
            if (http.status < 200 || http.status >= 300) {
                return new JSONObject().put("ok", false).put("status", http.status).put("raw", http.body != null ? truncate(http.body.toString(), 300) : "").toString();
            }
            this.prefs.edit().clear().apply();
            return new JSONObject().put("ok", true).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    public boolean consumeSessionExpired() {
        boolean z = this.sessionExpiredSeen;
        this.sessionExpiredSeen = false;
        return z;
    }

    public String dispatch(String str, JSONArray jSONArray) {
        boolean z = true;
        try {
            if (str.equals("is_logged_in")) {
                JSONObject put = new JSONObject().put("ok", true);
                if (authToken() == null) {
                    z = false;
                }
                return put.put("logged_in", z).put("user_name", userName()).put("user_id", userId()).toString();
            } else if (str.equals("logout")) {
                this.prefs.edit().remove("auth_token").remove("user_id").remove("user_name").apply();
                return new JSONObject().put("ok", true).toString();
            } else if (str.equals("get_feed_post")) {
                return getFeedPost(jSONArray.optString(0));
            } else {
                if (str.equals("approve_speaker")) {
                    return changeRole(jSONArray.optString(0), jSONArray.optString(1), "speaker");
                }
                if (str.equals("block_user")) {
                    return blockUser(jSONArray.optString(0));
                }
                if (str.equals("get_block_list")) {
                    return getBlockList();
                }
                if (str.equals("unblock_user")) {
                    return unblockUser(jSONArray.optString(0));
                }
                if (str.equals("moderation_banlist")) {
                    return moderationBanlist(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("moderation_auto_spam")) {
                    return moderationAutoSpam(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("bot_eval_local")) {
                    return botEvalLocal(jSONArray.optString(0, ""), jSONArray.optString(1, ""));
                }
                if (str.equals("moderation_report_spam")) {
                    return moderationReportSpam(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("moderation_report")) {
                    return moderationReport(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optString(3, ""), jSONArray.optString(4, ""), jSONArray.optString(5, ""), jSONArray.optString(6, ""), jSONArray.optString(7, ""));
                }
                if (str.equals("moderation_appeal")) {
                    return moderationAppeal(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optString(3, ""), jSONArray.optString(4, ""));
                }
                if (str.equals("moderation_my_uid")) {
                    return moderationMyUid();
                }
                if (str.equals("get_account_balance")) {
                    return getAccountBalance();
                }
                if (str.equals("search_users")) {
                    return searchUsers(jSONArray.optString(0), jSONArray.optString(1, "1"));
                }
                if (str.equals("get_coin_history")) {
                    return getCoinHistory();
                }
                if (str.equals("get_point_history")) {
                    return getPointHistory();
                }
                if (str.equals("point_exchange_url")) {
                    return pointExchangeUrl();
                }
                if (str.equals("get_item_packs")) {
                    return getItemPacks();
                }
                if (str.equals("do_tipping")) {
                    return doTipping(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2, ""));
                }
                if (str.equals("get_missed_calls")) {
                    return getMissedCalls();
                }
                if (str.equals("get_talk_requests")) {
                    return getTalkRequestHistory();
                }
                if (str.equals("get_user_posts")) {
                    return getUserPosts(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("get_user_settings")) {
                    return getUserSettings();
                }
                if (str.equals("set_user_settings")) {
                    return setUserSettings(jSONArray.optString(0));
                }
                if (str.equals("create_community")) {
                    return createCommunity(jSONArray.optString(0), jSONArray.optString(1, ""), jSONArray.optBoolean(2, true));
                }
                if (str.equals("create_community_comment")) {
                    return createCommunityComment(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("create_community_post")) {
                    return createCommunityPost(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("create_room")) {
                    return createRoom(jSONArray.optString(0), jSONArray.optBoolean(1, true), jSONArray.optBoolean(2, true), jSONArray.optInt(3, 1));
                }
                // 公式のエンドポイント名は意味と逆になっている(逆コンパイルで確認):
                //   /api/feed_posts     = 「つぶやく」 = 通常のタイムライン投稿
                //   /api/timeline_posts = 「話そう」   = 通話募集投稿(purpose/topicを持つ)
                // したがって タイムライン→feed_posts、通話募集→timeline_posts に振り分ける。
                if (str.equals("create_timeline_post")) {
                    return createPost("/api/feed_posts", jSONArray.optString(0), "0");
                }
                if (str.equals("create_timeline_post_with_image")) {
                    return createPostWithImage("/api/feed_posts", jSONArray.optString(0), "0", jSONArray.optString(2));
                }
                if (str.equals("create_timeline_post_with_voice")) {
                    return createPostWithVoice("/api/feed_posts", jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optString(3), "0", jSONArray.optString(4, "0"));
                }
                if (str.equals("create_feed_post")) {
                    return createPost("/api/timeline_posts", jSONArray.optString(0), "0");
                }
                if (str.equals("create_feed_post_with_image")) {
                    return createPostWithImage("/api/timeline_posts", jSONArray.optString(0), "0", jSONArray.optString(2));
                }
                if (str.equals("create_feed_post_with_voice")) {
                    return createPostWithVoice("/api/timeline_posts", jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optString(3), "0", jSONArray.optString(4, "0"));
                }
                if (str.equals("upload_account_image")) {
                    String kind = jSONArray.optString(1, "profile");
                    if (!"profile".equals(kind) && !"header".equals(kind)) {
                        // 投稿デコレーション(background)は公開機能のため現在は無効化中(要望時に有効化)
                        return jsonErr("この画像種別はアップロードできません: " + kind);
                    }
                    return uploadAccountImage(jSONArray.optString(0), kind);
                }
                if (str.equals("change_password")) {
                    return changePassword(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("withdraw_account")) {
                    return withdrawAccount(jSONArray.optString(0));
                }
                if (str.equals("get_gift_history")) {
                    return getGiftHistory();
                }
                if (str.equals("get_call_records")) {
                    return getCallRecords();
                }
                if (str.equals("toggle_record_like")) {
                    return toggleRecordLike(jSONArray.optString(0), jSONArray.optBoolean(1, false));
                }
                if (str.equals("get_my_call_records")) {
                    return getMyCallRecords();
                }
                if (str.equals("get_record_comments")) {
                    return getRecordComments(jSONArray.optString(0));
                }
                if (str.equals("post_record_comment")) {
                    return postRecordComment(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("send_friend_request")) {
                    return sendFriendRequest(jSONArray.optString(0));
                }
                if (str.equals("cancel_friend_request")) {
                    return cancelFriendRequest(jSONArray.optString(0));
                }
                if (str.equals("deny_friend_request")) {
                    return denyFriendRequest(jSONArray.optString(0));
                }
                if (str.equals("remove_friend")) {
                    return removeFriend(jSONArray.optString(0));
                }
                if (str.equals("get_friend_requests_in")) {
                    return getFriendRequestsIn();
                }
                if (str.equals("get_friend_requests_out")) {
                    return getFriendRequestsOut();
                }
                if (str.equals("skyway_connect_log")) {
                    return skywayConnectLog(jSONArray.optString(0), jSONArray.optString(1, "0"), jSONArray.optString(2, "0"), jSONArray.optString(3, "0"), jSONArray.optString(4, ""));
                }
                if (str.equals("skyway_disconnect_log")) {
                    return skywayDisconnectLog(jSONArray.optString(0), jSONArray.optString(1, "0"));
                }
                if (str.equals("get_aborted_purchase_token")) {
                    return getAbortedPurchaseToken();
                }
                if (str.equals("add_purchase_token")) {
                    return addPurchaseToken(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("subscribe")) {
                    return subscribe(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("signup")) {
                    return signup(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optString(3, "0"), jSONArray.optString(4, ""), jSONArray.optString(5, ""));
                }
                if (str.equals("signup_auth")) {
                    return signupAuth(jSONArray.optString(0));
                }
                if (str.equals("remove_follower")) {
                    return removeFollower(jSONArray.optString(0));
                }
                if (str.equals("get_dive_talk_histories")) {
                    return getDiveTalkHistories(jSONArray.optString(0, "1"));
                }
                if (str.equals("get_system_info")) {
                    return getSystemInfo(jSONArray.optString(0, "0"), jSONArray.optString(1, "1"));
                }
                if (str.equals("server_logout")) {
                    return serverLogout();
                }
                if (str.equals("bookmark_community_comment")) {
                    return bookmarkCommunityComment(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optBoolean(3, true));
                }
                if (str.equals("send_trial_time")) {
                    return sendTrialTime(jSONArray.optString(0), jSONArray.optString(1, "0"));
                }
                if (str.equals("get_friends_list")) {
                    return getFriendsList(jSONArray.optString(0, "1"));
                }
                if (str.equals("delete_own_timeline_post")) {
                    return deleteOwnPost(jSONArray.optString(0), jSONArray.optInt(1, 0) == 1);
                }
                if (str.equals("export_token")) {
                    return exportToken();
                }
                if (str.equals("follow_user")) {
                    return followUser(jSONArray.optString(0));
                }
                if (str.equals("get_activity_heatmap")) {
                    return getActivityHeatmap();
                }
                if (str.equals("get_announcements")) {
                    return getAnnouncements();
                }
                if (str.equals("get_badges")) {
                    return getBadges(jSONArray.optString(0, "null"));
                }
                if (str.equals("get_bookmarks")) {
                    return getBookmarks(jSONArray.optString(0, ""));
                }
                if (str.equals("get_chats")) {
                    return getChats();
                }
                if (str.equals("get_friend_posts")) {
                    return getFriendPosts(jSONArray.optString(0, ""));
                }
                if (str.equals("get_unread_notif_count")) {
                    return getUnreadNotifCount();
                }
                if (str.equals("get_recording_entries")) {
                    return getRecordingEntries(jSONArray.optString(0, "1"));
                }
                if (str.equals("get_daily_point_histories")) {
                    return getDailyPointHistories(jSONArray.optString(0, "1"));
                }
                if (str.equals("get_item_histories")) {
                    return getItemHistories(jSONArray.optString(0, "1"));
                }
                if (str.equals("get_coin_packs")) {
                    return getCoinPacks();
                }
                if (str.equals("get_subscription_menus")) {
                    return getSubscriptionMenus();
                }
                if (str.equals("get_owned_communities")) {
                    return getOwnedCommunities();
                }
                if (str.equals("get_follow_list")) {
                    return getFollowRelList(jSONArray.optString(0), jSONArray.optString(1, "followees"), jSONArray.optString(2, "1"));
                }
                if (str.equals("reset_user_status")) {
                    return resetUserStatus();
                }
                if (str.equals("leave_trial_room")) {
                    return leaveTrialRoom(jSONArray.optString(0, ""));
                }
                if (str.equals("can_send_chat")) {
                    return canSendChat(jSONArray.optString(0));
                }
                if (str.equals("check_name_availability")) {
                    return checkNameAvailability(jSONArray.optString(0));
                }
                if (str.equals("delete_all_posts")) {
                    return deleteAllPosts(jSONArray.optString(0, "feed"));
                }
                if (str.equals("room_data_send")) {
                    return roomDataSend(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("matching_start")) {
                    return matchingStart(jSONArray.optString(0, "1"));
                }
                if (str.equals("matching_cancel")) {
                    return matchingCancel();
                }
                if (str.equals("matching_state")) {
                    return matchingState();
                }
                if (str.equals("matching_accept")) {
                    return matchingAccept(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("matching_refuse")) {
                    return matchingRefuse(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("matching_refused")) {
                    return matchingRefused(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("dive_request")) {
                    return diveRequest(jSONArray.optString(0));
                }
                if (str.equals("dive_confirm_status")) {
                    return diveConfirmStatus(jSONArray.optString(0));
                }
                if (str.equals("dive_confirm")) {
                    return diveConfirm(jSONArray.optString(0));
                }
                if (str.equals("dive_request_cancel")) {
                    return diveRequestCancel(jSONArray.optString(0, ""));
                }
                if (str.equals("dive_incoming")) {
                    return diveIncoming();
                }
                if (str.equals("dive_check")) {
                    return diveCheck();
                }
                if (str.equals("dive_receive")) {
                    return diveReceive(jSONArray.optString(0), jSONArray.optString(1, "1"));
                }
                if (str.equals("dive_request_disconnect")) {
                    return diveRequestDisconnect(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("dive_like")) {
                    return diveLike(jSONArray.optString(0));
                }
                if (str.equals("get_good_talk_min")) {
                    return getGoodTalkMin();
                }
                if (str.equals("get_community_posts_list")) {
                    return getCommunityPostsList(jSONArray.optString(0), jSONArray.optString(1, "1"));
                }
                if (str.equals("get_community_post_detail")) {
                    return getCommunityPostDetail(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("ban_community_member")) {
                    return banCommunityMember(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("set_display_badge")) {
                    return setDisplayBadge(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("like_community_post")) {
                    return likeCommunityPost(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optBoolean(2, true));
                }
                if (str.equals("bookmark_community_post")) {
                    return bookmarkCommunityPost(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optBoolean(2, true));
                }
                if (str.equals("delete_community_post")) {
                    return deleteCommunityPost(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("get_community_post_comments")) {
                    return getCommunityPostComments(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2, "1"));
                }
                if (str.equals("comment_community_post")) {
                    return commentCommunityPost(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("like_community_comment")) {
                    return likeCommunityComment(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optBoolean(3, true));
                }
                if (str.equals("get_receive_tippings")) {
                    return getReceiveTippings(jSONArray.optString(0, ""));
                }
                if (str.equals("open_all_tippings")) {
                    return openAllTippings();
                }
                if (str.equals("open_tipping")) {
                    return openTipping(jSONArray.optString(0));
                }
                if (str.equals("post_talk_room_comment")) {
                    return postTalkRoomComment(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("switch_talk_room_comment")) {
                    return switchTalkRoomComment(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optBoolean(2, true));
                }
                if (str.equals("campaign_challenge_progress")) {
                    return campaignChallengeProgress(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("get_dive_target_friends")) {
                    return getDiveTargetFriends();
                }
                if (str.equals("get_badge_users")) {
                    return getBadgeUsers();
                }
                if (str.equals("get_call_record_liked_users")) {
                    return getCallRecordLikedUsers(jSONArray.optString(0, ""));
                }
                if (str.equals("get_expiration_date")) {
                    return getExpirationDate();
                }
                if (str.equals("get_matching")) {
                    return getMatching();
                }
                if (str.equals("give_coin")) {
                    return giveCoin(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("passclear_request")) {
                    return passclearRequest(jSONArray.optString(0));
                }
                if (str.equals("get_communities_feed")) {
                    return getCommunitiesFeed(jSONArray.optString(0, "1"));
                }
                if (str.equals("get_community_categories")) {
                    return getCommunityCategories();
                }
                if (str.equals("get_community_comments")) {
                    return getCommunityComments(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("get_community_info")) {
                    return getCommunityInfo(jSONArray.optString(0));
                }
                if (str.equals("get_community_members")) {
                    return getCommunityMembers(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("get_community_posts")) {
                    return getCommunityPosts(jSONArray.optString(0));
                }
                if (str.equals("get_community_rules")) {
                    return getCommunityRules(jSONArray.optString(0));
                }
                if (str.equals("get_followee_ids")) {
                    return getFolloweeIds();
                }
                if (str.equals("get_followees")) {
                    return followList(jSONArray.optString(0), jSONArray.optString(1, "1"), "followees");
                }
                if (str.equals("get_followers")) {
                    return followList(jSONArray.optString(0), jSONArray.optString(1, "1"), "followers");
                }
                if (str.equals("get_recommended_users")) {
                    return getRecommendedUsers(jSONArray.optString(0, "1"));
                }
                if (str.equals("get_hima_users")) {
                    return getHimaUsers(jSONArray.optString(0, "1"));
                }
                if (str.equals("get_birthday_users")) {
                    return getBirthdayUsers();
                }
                if (str.equals("get_follow_requests")) {
                    return getFollowRequests();
                }
                if (str.equals("get_following_timeline")) {
                    return getFollowingTimeline(jSONArray.optString(0));
                }
                if (str.equals("get_live_pulse")) {
                    return getLivePulse();
                }
                if (str.equals("get_messages")) {
                    return getMessages(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("get_moderation_settings")) {
                    return getModerationSettings();
                }
                if (str.equals("get_my_communities")) {
                    return getMyCommunities();
                }
                if (str.equals("get_my_profile")) {
                    return getMyProfile();
                }
                if (str.equals("get_icon_base")) {
                    return new JSONObject().put("ok", true).put("base", pngServerName()).toString();
                }
                if (str.equals("join_room_by_id")) {
                    return joinCallByRoomId(jSONArray.optString(0));
                }
                if (str.equals("delete_chat_message")) {
                    return deleteChatMessage(jSONArray.optString(0), jSONArray.optString(1, ""), jSONArray.optString(2, ""));
                }
                if (str.equals("get_mutuals")) {
                    return getMutuals();
                }
                if (str.equals("delete_feed_post_comment")) {
                    return deleteFeedPostComment(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("get_notifications")) {
                    return getNotifications(jSONArray.optString(0, "normal"), jSONArray.optString(1, "1"));
                }
                if (str.equals("get_follow_activity")) {
                    return getFollowActivity();
                }
                if (str.equals("get_room_history")) {
                    return getRoomHistory();
                }
                if (str.equals("get_timeline")) {
                    return getTimeline(jSONArray.optString(0));
                }
                if (str.equals("get_feed_timeline")) {
                    return getFeedTimeline(jSONArray.optString(0));
                }
                if (str.equals("get_timeline_comments")) {
                    return getTimelineComments(jSONArray.optString(0), jSONArray.optString(1, "1"));
                }
                if (str.equals("get_timeline_likers")) {
                    return getTimelineLikers(jSONArray.optString(0));
                }
                if (str.equals("invite_community_member")) {
                    return inviteCommunityMember(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("join_call")) {
                    return joinCall(jSONArray.optString(0, "null"));
                }
                if (str.equals("join_community")) {
                    return joinCommunity(jSONArray.optString(0));
                }
                if (str.equals("leave_community")) {
                    return leaveCommunity(jSONArray.optString(0));
                }
                if (str.equals("list_group_rooms")) {
                    return listGroupRooms(jSONArray.optString(0, "1"));
                }
                if (str.equals("login")) {
                    return login(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("login_token_only")) {
                    return loginWithTokenOnly(jSONArray.optString(0));
                }
                if (str.equals("login_with_token")) {
                    return loginWithToken(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("raise_hand")) {
                    return changeRole(jSONArray.optString(0), String.valueOf(userId()), "speaker_applicant");
                }
                if (str.equals("lower_hand")) {
                    return changeRole(jSONArray.optString(0), String.valueOf(userId()), "listener");
                }
                if (str.equals("refresh_room_state")) {
                    return refreshRoomState(jSONArray.optString(0, "null"), jSONArray.optString(1, ""));
                }
                if (str.equals("reject_speaker")) {
                    return changeRole(jSONArray.optString(0), jSONArray.optString(1), "listener");
                }
                if (str.equals("reply_timeline_post")) {
                    return replyTimelinePost(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("report_timeline_post")) {
                    return reportTimelinePost(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("resolve_users")) {
                    return resolveUsers(jSONArray.optString(0));
                }
                if (str.equals("room_close")) {
                    return roomClose(jSONArray.optString(0));
                }
                if (str.equals("get_enquete_questions")) {
                    return getEnqueteQuestions(jSONArray.optString(0));
                }
                if (str.equals("send_enquete_answer")) {
                    return sendEnqueteAnswer(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("send_enquete_answer_v2")) {
                    return sendEnqueteAnswerV2(jSONArray.optString(0));
                }
                if (str.equals("get_pusher_config")) {
                    return getPusherConfig();
                }
                if (str.equals("system_arrival")) {
                    return systemArrival();
                }
                if (str.equals("send_registration_id")) {
                    return sendRegistrationId(jSONArray.optString(0));
                }
                if (str.equals("recording_channel")) {
                    return recordingChannel(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("send_voice_message")) {
                    return sendVoiceMessage(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optString(3), jSONArray.optString(4), jSONArray.optString(5, "0"));
                }
                if (str.equals("close_room")) {
                    String rid = jSONArray.optString(0);
                    if (rid == null || rid.length() == 0 || "null".equals(rid)) rid = myOpenRoomId;
                    if (rid == null || rid.length() == 0) return jsonErr("room_id不明");
                    int st = closeRoomById(rid);
                    myOpenRoomId = null;
                    return new JSONObject().put("ok", st >= 200 && st < 300).put("status", st)
                            .put("message", (st >= 200 && st < 300) ? "枠を終了しました" :
                                    (st == 404 ? "この枠はすでに終了しています" : "枠を終了できませんでした（status " + st + "）。"))
                            .toString();
                }
                if (str.equals("room_leave")) {
                    return roomLeave(jSONArray.optString(0));
                }
                if (str.equals("room_update_title")) {
                    return roomUpdateTitle(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("room_kick_user")) {
                    return roomKickUser(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("room_switch_comment_enabled")) {
                    return roomSwitchCommentEnabled(jSONArray.optString(0), jSONArray.optBoolean(1, true));
                }
                if (str.equals("room_join_trial")) {
                    return roomJoinTrial(jSONArray.optString(0, ""));
                }
                if (str.equals("clear_public_timeline_image")) {
                    return clearPublicTimelineImage();
                }
                if (str.equals("room_invite")) {
                    return roomInvite(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("get_community_talk_rooms")) {
                    return getCommunityTalkRooms(jSONArray.optString(0));
                }
                if (str.equals("get_participating_community_talk_rooms")) {
                    return getParticipatingCommunityTalkRooms();
                }
                if (str.equals("toggle_feed_post_bookmark")) {
                    return toggleFeedPostBookmark(jSONArray.optString(0), jSONArray.optBoolean(1));
                }
                if (str.equals("get_community_bookmarks")) {
                    return getCommunityBookmarks(jSONArray.optString(0, "1"), jSONArray.optString(1, ""));
                }
                if (str.equals("get_owned_items")) {
                    return getOwnedItems();
                }
                if (str.equals("get_decoration_items")) {
                    return getDecorationItems();
                }
                if (str.equals("purchase_decoration_item")) {
                    return purchaseDecorationItem(jSONArray.optString(0));
                }
                if (str.equals("get_voice_profiles")) {
                    return getVoiceProfiles();
                }
                if (str.equals("get_subscriptions")) {
                    return getSubscriptions();
                }
                if (str.equals("estimate_point_exchange")) {
                    return estimatePointExchange(jSONArray.optString(0));
                }
                if (str.equals("execute_point_exchange")) {
                    return executePointExchange(jSONArray.optString(0));
                }
                if (str.equals("get_room_settings")) {
                    return getRoomSettings();
                }
                if (str.equals("get_enquetes")) {
                    return getEnquetes();
                }
                if (str.equals("answer_enquete")) {
                    return answerEnquete(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("send_sms_auth_code")) {
                    return sendSmsAuthCode(jSONArray.optString(0));
                }
                if (str.equals("authenticate_sms_auth_code")) {
                    return authenticateSmsAuthCode(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("check_entered_email")) {
                    return checkEnteredEmail(jSONArray.optString(0));
                }
                if (str.equals("check_facebook_exist")) {
                    return checkFacebookExist(jSONArray.optString(0));
                }
                if (str.equals("check_line_exist")) {
                    return checkLineExist(jSONArray.optString(0));
                }
                if (str.equals("line_login")) {
                    return lineLogin(jSONArray.optString(0));
                }
                if (str.equals("facebook_login")) {
                    return facebookLogin(jSONArray.optString(0));
                }
                if (str.equals("twitter_login")) {
                    return twitterLogin(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("check_twitter_exist")) {
                    return checkTwitterExist(jSONArray.optString(0));
                }
                if (str.equals("send_email_token")) {
                    return sendEmailToken(jSONArray.optString(0));
                }
                if (str.equals("check_email_token")) {
                    return checkEmailToken(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("bulk_delete_chats")) {
                    return bulkDeleteChats(jSONArray.optString(0));
                }
                if (str.equals("check_recording_disabled_users")) {
                    return checkRecordingDisabledUsers(jSONArray.optString(0));
                }
                if (str.equals("get_talk_recording_agreements")) {
                    return getTalkRecordingAgreements();
                }
                if (str.equals("agree_talk_recording")) {
                    return agreeTalkRecording(jSONArray.optString(0, ""));
                }
                if (str.equals("get_trial_listenings")) {
                    return getTrialListenings();
                }
                if (str.equals("get_user_campaigns")) {
                    return getUserCampaigns();
                }
                if (str.equals("get_server_time")) {
                    return getServerTime();
                }
                if (str.equals("get_mypage_decoration")) {
                    return getMypageDecoration();
                }
                if (str.equals("get_cheering_voice_call")) {
                    return getCheeringVoiceCall(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("get_native_log")) {
                    return getNativeLog();
                }
                if (str.equals("probe_endpoints")) {
                    return probeEndpoints();
                }
                if (str.equals("clear_native_log")) {
                    return clearNativeLog();
                }
                if (str.equals("set_debug_log_enabled")) {
                    return setDebugLogEnabled(jSONArray.optBoolean(0, true));
                }
                if (str.equals("start_cheering_call")) {
                    return startCheeringCall(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("cheering_confirm_status")) {
                    return cheeringConfirmStatus(jSONArray.optString(0));
                }
                if (str.equals("cheering_receiver_connected")) {
                    return cheeringReceiverConnected(jSONArray.optString(0));
                }
                if (str.equals("check_cheering_call")) {
                    return checkCheeringCall();
                }
                if (str.equals("confirm_and_open_cheering_call")) {
                    return confirmAndOpenCheeringCall(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("cancel_cheering_call")) {
                    return cancelCheeringCall(jSONArray.optString(0));
                }
                if (str.equals("disconnect_cheering_call")) {
                    return disconnectCheeringCall(jSONArray.optString(0));
                }
                if (str.equals("get_cheering_receivers")) {
                    return getCheeringReceivers(jSONArray.optString(0, "1"));
                }
                if (str.equals("get_receivers")) {
                    return getCheeringReceivers(jSONArray.optString(0, ""));
                }
                if (str.equals("get_cheering_ranking_url")) {
                    return getCheeringRankingUrl();
                }
                if (str.equals("inspect_feed_post")) {
                    return inspectFeedPost(jSONArray.optString(0));
                }
                if (str.equals("get_regulated_words")) {
                    return getRegulatedWords();
                }
                if (str.equals("get_feed_post_liked_users")) {
                    return getFeedPostLikedUsers(jSONArray.optString(0));
                }
                if (str.equals("feed_post_bad_vote")) {
                    return feedPostBadVote(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("get_system_params")) {
                    return getSystemParams();
                }
                if (str.equals("get_community_join_requests")) {
                    return getCommunityJoinRequests(jSONArray.optString(0));
                }
                if (str.equals("approve_community_join_request")) {
                    return approveCommunityJoinRequest(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("cancel_community_join_request")) {
                    return cancelCommunityJoinRequest(jSONArray.optString(0));
                }
                if (str.equals("deny_community_join_request")) {
                    return denyCommunityJoinRequest(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("get_community_post")) {
                    return getCommunityPost(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("report_community")) {
                    return reportCommunity(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("toggle_community_comment_like")) {
                    return toggleCommunityCommentLike(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optBoolean(3));
                }
                if (str.equals("delete_community_comment")) {
                    return deleteCommunityComment(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("set_display_badge")) {
                    return setDisplayBadge(jSONArray.optString(0, ""));
                }
                if (str.equals("mark_user_campaign_as_read")) {
                    return markUserCampaignAsRead(jSONArray.optString(0));
                }
                if (str.equals("cheering_send_coins")) {
                    return cheeringSendCoins(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("cheering_skyway_connect")) {
                    return cheeringSkywayConnect(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("cheering_skyway_disconnect")) {
                    return cheeringSkywayDisconnect(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("get_cheering_receiver_detail")) {
                    return getCheeringReceiverDetail(jSONArray.optString(0));
                }
                if (str.equals("get_cheering_receiver_coin_list")) {
                    return getCheeringReceiverCoinList(jSONArray.optString(0));
                }
                if (str.equals("get_cheering_standby_requests")) {
                    return getCheeringStandbyRequests(jSONArray.optString(0));
                }
                if (str.equals("update_cheering_receiver_status")) {
                    return updateCheeringReceiverStatus(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("answer_cheering_call")) {
                    return answerCheeringCall(jSONArray.optString(0), jSONArray.optString(1, "1"));
                }
                if (str.equals("get_cheering_request_receives")) {
                    return getCheeringRequestReceives();
                }
                if (str.equals("get_cheering_sent_coins")) {
                    return getCheeringSentCoins(jSONArray.optString(0, "1"));
                }
                if (str.equals("get_community_rule")) {
                    return getCommunityRule(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("delete_community_rule")) {
                    return deleteCommunityRule(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("get_user_campaign")) {
                    return getUserCampaign(jSONArray.optString(0));
                }
                if (str.equals("join_campaign")) {
                    return joinCampaign(jSONArray.optString(0));
                }
                if (str.equals("recover_user_campaign")) {
                    return recoverUserCampaign(jSONArray.optString(0));
                }
                if (str.equals("get_campaign_challenge_progress")) {
                    return getCampaignChallengeProgress(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("get_subscription_introduction_schedules")) {
                    return getSubscriptionIntroductionSchedules();
                }
                if (str.equals("track_enquete")) {
                    return trackEnquete(jSONArray.optString(0), jSONArray.optString(1, ""));
                }
                if (str.equals("get_skyflag_offer_wall_url")) {
                    return getSkyflagOfferWallUrl();
                }
                if (str.equals("get_official_ad")) {
                    return getOfficialAd();
                }
                if (str.equals("get_official_links")) {
                    return getOfficialLinks();
                }
                if (str.equals("send_skyway_log")) {
                    return sendSkywayLog(jSONArray.optString(0, ""));
                }
                if (str.equals("get_tiktok_event_info")) {
                    return getTiktokEventInfo();
                }
                if (str.equals("get_tiktok_event_status")) {
                    return getTiktokEventStatus();
                }
                if (str.equals("send_tiktok_event_entry")) {
                    return sendTiktokEventEntry(jSONArray.optString(0, ""));
                }
                if (str.equals("get_cheering_talk_histories")) {
                    return getCheeringTalkHistories(jSONArray.optString(0, "1"));
                }
                if (str.equals("rate_cheering_call")) {
                    return rateCheeringCall(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2, ""));
                }
                if (str.equals("get_subscription_histories")) {
                    return getSubscriptionHistories();
                }
                if (str.equals("get_enquete_questions")) {
                    return getEnqueteQuestions(jSONArray.optString(0));
                }
                if (str.equals("get_community_talk_room")) {
                    return getCommunityTalkRoom(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("join_community_talk_room")) {
                    return joinCommunityTalkRoom(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("leave_community_talk_room")) {
                    return leaveCommunityTalkRoom(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("kick_community_talk_room_user")) {
                    return kickCommunityTalkRoomUser(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("change_community_talk_room_role")) {
                    return changeCommunityTalkRoomRole(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optString(3));
                }
                if (str.equals("switch_community_talk_room_comment_enabled")) {
                    return switchCommunityTalkRoomCommentEnabled(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optBoolean(2, true));
                }
                if (str.equals("get_community_talk_room_comments")) {
                    return getCommunityTalkRoomComments(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("search_communities")) {
                    return searchCommunities(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("send_message")) {
                    return sendMessage(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("send_image_message")) {
                    return sendImageMessage(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2));
                }
                if (str.equals("mark_message_read")) {
                    return markMessageRead(jSONArray.optString(0));
                }
                if (str.equals("get_room_comments")) {
                    return getRoomComments(jSONArray.optString(0));
                }
                if (str.equals("js_diag_log")) {
                    dbgLog(nowStr() + "  " + redactLog(jSONArray.optString(0, "")));
                    return "{\"ok\":true}";
                }
                if (str.equals("send_room_comment")) {
                    return sendRoomComment(jSONArray.optString(0), jSONArray.optString(1));
                }
                if (str.equals("set_moderation_settings")) {
                    return setModerationSettings(jSONArray.optBoolean(0), jSONArray.optBoolean(1), jSONArray.optBoolean(2));
                }
                if (!str.equals("signup")) {
                    return str.equals("toggle_community_like") ? toggleCommunityLike(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optBoolean(2)) : str.equals("toggle_timeline_bookmark") ? toggleBookmark(jSONArray.optString(0), jSONArray.optBoolean(1), jSONArray.optInt(2, 1) == 1) : str.equals("toggle_timeline_like") ? toggleLike(jSONArray.optString(0), jSONArray.optBoolean(1)) : str.equals("unfollow_user") ? unfollowUser(jSONArray.optString(0)) : str.equals("update_profile") ? updateProfile(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2, "")) : str.equals("view_user_profile") ? viewUserProfile(jSONArray.optString(0)) : new JSONObject().put("ok", false).put("error", "not_ported").put("message", "「" + str + "」は未移植です。").toString();
                }
                return signup(jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optString(3), jSONArray.optString(4));
            }
        } catch (Exception e) {
            return errJson(e);
        }
    }
}
