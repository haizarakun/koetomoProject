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
    static final String PNG_FALLBACK = "https://d34we8vh702akg.cloudfront.net/";
    static final String UA = "okhttp/4.12.0";  // 公式アプリと同一(OkHttpデフォルトUA)。ログインWAFが独自UAを弾くため一致させる。
    private JSONObject clientDefines = null;
    private final Map<String, String> hostCache = new java.util.concurrent.ConcurrentHashMap<String, String>();
    // 共有BANリストで自動非表示にするUID集合(端末内フィルタ。koetomo本体のブロックには触れない)。
    private final java.util.Set<Long> bannedUids = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
    private volatile int lastVsns = -999;
    // メモリ軽量化: 名前キャッシュはアクセス順LinkedHashMapで最大1500件に制限し、古いものから自動破棄する
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
                            this.nameCache.put(Long.valueOf(Long.parseLong(k)), new String[]{arr.optString(0, ""), arr.optString(1, "")});
                        } catch (Exception e) {
                        }
                    }
                }
            }
        } catch (Exception e) {
        }
    }

    private void saveNameCache() {
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
        hashMap.put("version", APP_VERSION);
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
        if (!(request.body == null || (optJSONArray = request.body.optJSONArray("user_info")) == null || optJSONArray.length() <= 0 || (optJSONObject = optJSONArray.optJSONObject(0)) == null)) {
            str = optJSONObject.optString("name", "");
            str2 = optJSONObject.optString("profile_picture_file_path", "");
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
            jSONObject2.put("comment", jSONObject != null ? jSONObject.optString("comment", "") : "");
            jSONObject2.put("icon_url", iconUrl(str2));
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
                jSONObject2.put("is_following", jSONObject != null && jSONObject.optBoolean("is_following", jSONObject.optBoolean("isFollowing", false)));
                jSONObject2.put("is_followed", jSONObject != null && jSONObject.optBoolean("is_followed", jSONObject.optBoolean("isFollowed", false)));
                jSONObject2.put("friend_count", jSONObject != null ? jSONObject.optInt("friend_count", jSONObject.optInt("friendCount", 0)) : 0);
                jSONObject2.put("area_name", jSONObject != null ? jSONObject.optString("area_name", jSONObject.optString("areaName", "")) : "");
                jSONObject2.put("login_status", jSONObject != null ? jSONObject.optString("login_status_with_unit", jSONObject.optString("loginStatusWithUnit", "")) : "");
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
                try { dbgLog(nowStr() + "  [PROFILE] uid=" + j + " created_at=" + (ca.length() > 0 ? ca : "(なし)") + " user_info=" + (jSONObject != null ? truncate(redactLog(jSONObject.toString()), 1600) : "-")); } catch (Exception ignored) {}
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

    private String changeRole(String str, String str2, String str3) {
        HashMap hashMap = new HashMap();
        hashMap.put("role", str3);
        hashMap.put("target_id", str2);
        return okResult(request("PUT", "/api/rooms/" + str + "/change_role", hashMap, (Map<String, String>) null));
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
            String authToken = authToken();
            return okResult(httpJson("POST", "https://api.meetscom.com/api/communities?version=" + enc(APP_VERSION) + (authToken != null ? "&auth_token=" + enc(authToken) : ""), put2));
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

    private String createRoom(String str, boolean z, boolean z2) {
        HashMap hashMap = new HashMap();
        if (str == null) {
            str = "";
        }
        hashMap.put("description", str);
        Resp request = request("POST", "/api/rooms", (Map<String, String>) null, hashMap);
        try {
            if (request.status >= 200 && request.status < 300) {
                return joinCall("null");
            }
            return new JSONObject().put("ok", false).put("status", request.status).put("message", (request.status == 400 || request.status == 401 || request.status == 403) ? "枠の作成がサーバーに拒否されました(status " + request.status + ")。アカウントが年齢確認/SMS認証未完了だと、サーバー側の制限で作成できないことがあります。" : request.status == 404 ? "枠作成APIが見つかりませんでした(404)。" : request.status >= 500 ? "サーバーエラーで枠を作成できませんでした(status " + request.status + ")。時間を置いて再試行してください。" : "枠を作成できませんでした(status " + request.status + ")。").put("raw", request.body != null ? truncate(request.body.toString(), 300) : "").toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // 投稿の共通POST。
    //   /api/timeline_posts = 「話そう」= 通話募集 → purpose/topic を付与
    //   /api/feed_posts     = 「つぶやく」= 通常のタイムライン → play_time を付ける(purpose/topicなし)
    // ※公式はエンドポイント名と意味が逆。api→api2 フォールバック。
    private Resp postToSeries(String endpoint, String description, String purpose, String imagePath, String voicePath, String md5) {
        HashMap<String, String> hashMap = new HashMap<String, String>();
        hashMap.put("version", "android_3.9.101");
        boolean isFeed = endpoint.contains("feed_posts");
        if (!isFeed) {
            hashMap.put("purpose", (purpose == null || purpose.length() == 0) ? "0" : purpose);
            hashMap.put("topic", "0");
        } else {
            hashMap.put("play_time", "0");
        }
        if (description != null && description.length() > 0) hashMap.put("description", description);
        if (imagePath != null && imagePath.length() > 0) hashMap.put("image_file_path", imagePath);
        if (voicePath != null && voicePath.length() > 0) hashMap.put("voice_file_path", voicePath);
        if (md5 != null && md5.length() > 0) hashMap.put("md5", md5);
        String authToken = authToken();
        if (authToken != null) hashMap.put("auth_token", authToken);
        Resp http = http("POST", "https://api.meetscom.com" + endpoint, (Map<String, String>) null, hashMap);
        if (http.status == 404 || http.status >= 500) {
            http = http("POST", "https://api2.meetscom.com" + endpoint, (Map<String, String>) null, hashMap);
        }
        dbgLog(nowStr() + "  [POST] " + endpoint + " HTTP " + http.status + (http.status != 200 && http.status != 201 && http.body != null ? " " + truncate(redactLog(http.body.toString()), 200) : ""));
        return http;
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
        return createPostWithVoice(endpoint, str, str2, str3, str4, "0");
    }

    private String createPostWithVoice(String endpoint, String str, String str2, String str3, String str4, String purpose) {
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
                    return okResult(postToSeries(endpoint, str4, (purpose == null || purpose.length() == 0) ? "0" : purpose, null, bareVoice, md5Hex(decode)));
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

    // 公式(TimelineApiServer1)は server1(api.meetscom.com) に DELETE、version=android_3.9.101 で送る。
    // api2 に送ると 403「権限がありません」が返るので server1 を優先し、両ホスト×両種別を順に試す。
    private String deleteOwnPost(String str, boolean isTalk) {
        String[] paths = isTalk ? new String[]{"/api/timeline_posts/", "/api/feed_posts/"} : new String[]{"/api/feed_posts/", "/api/timeline_posts/"};
        String[] hosts = new String[]{BASE_URL, BASE_URL2};
        Resp last = new Resp(0, (JSONObject) null);
        for (String path : paths) {
            for (String host : hosts) {
                HashMap<String, String> q = new HashMap<>();
                q.put("version", "android_" + APP_VERSION);
                String authToken = authToken();
                if (authToken != null) q.put("auth_token", authToken);
                Resp r = http("DELETE", host + path + str, q, (Map<String, String>) null);
                if (r.status >= 200 && r.status < 300) {
                    try { return new JSONObject().put("ok", true).put("kind", path.contains("timeline") ? "talk" : "feed").toString(); } catch (Exception e) { return "{\"ok\":true}"; }
                }
                last = r;
            }
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
            HashMap hashMap = new HashMap();
            hashMap.put("item_pack_id", str);
            hashMap.put("referer_id", str2);
            if (str3 == null || str3.length() == 0) {
                str3 = "0";
            }
            hashMap.put("room_id", str3);
            hashMap.put("screen", "timeline");
            Resp request = request("POST", "/api/tippings", (Map<String, String>) null, hashMap);
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
                    Resp http = http("GET", "https://api.meetscom.com/config/release/3.9.101.json", (Map<String, String>) null, (Map<String, String>) null);
                    JSONObject optJSONObject = http.body != null ? http.body.optJSONObject("data") : null;
                    JSONObject optJSONObject2 = optJSONObject != null ? optJSONObject.optJSONObject("client_defines") : null;
                    String optString = optJSONObject2 != null ? optJSONObject2.optString("url", "") : "";
                    if (optString.length() > 0) {
                        Resp http2 = http("GET", optString, (Map<String, String>) null, (Map<String, String>) null);
                        JSONObject optJSONObject3 = http2.body != null ? http2.body.optJSONObject("data") : null;
                        if (optJSONObject3 == null) {
                            optJSONObject3 = http2.body;
                        }
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

    private static String firstStr(JSONObject jSONObject, String... strArr) {
        for (String optString : strArr) {
            String optString2 = jSONObject.optString(optString, "");
            if (optString2.length() > 0) {
                return optString2;
            }
        }
        return "";
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
                return new JSONObject().put("ok", true).put("users", normalizeUserList(resp.body)).toString();
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

    private String getMyQrCode() {
        try {
            Resp r = request("GET", "/api/users/qr_codes", (Map<String, String>) null, (Map<String, String>) null);
            if (r.status != 200 || r.body == null) return gracefulUnavailable(r, "qr", "qr_codes");
            JSONObject b = r.body;
            JSONObject d = b.optJSONObject("data");
            String url = "";
            if (d != null) url = firstStr(d, "url", "qr_code_url", "image_url", "qr_url", "file_path", "qr");
            if (url.length() == 0) url = firstStr(b, "url", "qr_code_url", "image_url", "qr_url", "file_path", "qr");
            JSONObject out = new JSONObject().put("ok", true);
            if (url.length() > 0) out.put("url", url);
            out.put("raw", d != null ? d : b);
            return out.toString();
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
        return okResult(http("POST", "https://api.meetscom.com/api/relation/new_follow/following", hashMap, (Map<String, String>) null));
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
        Resp request = request("GET", "/api/chats", hashMap, (Map<String, String>) null);
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
            JSONObject optJSONObject = request.body.optJSONObject("data");
            JSONObject jSONObject = optJSONObject == null ? new JSONObject() : optJSONObject;
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
            JSONArray jSONArray2 = new JSONArray();
            final java.util.List<String[]> toFetch = new java.util.ArrayList<String[]>();
            final java.util.List<Integer> fetchIdx = new java.util.ArrayList<Integer>();
            for (int i2 = 0; i2 < jSONArray.length(); i2++) {
                JSONObject optJSONObject3 = jSONArray.optJSONObject(i2);
                if (optJSONObject3 != null) {
                    long optLong = optJSONObject3.optLong("user_id");
                    JSONObject jSONObject2 = (JSONObject) hashMap2.get(Long.valueOf(optLong));
                    String preview = chatLastMessage(optJSONObject3);
                    int idx = jSONArray2.length();
                    jSONArray2.put(new JSONObject().put("chat_id", optJSONObject3.opt("id")).put("target_id", optLong).put("name", jSONObject2 != null ? jSONObject2.optString("name", "user " + optLong) : "user " + optLong).put("icon_url", jSONObject2 != null ? iconUrl(jSONObject2.optString("profile_picture_file_path", "")) : "").put("last_sent_at", optJSONObject3.optString("last_sent_at", "")).put("last_message", preview));
                    // リスト応答に本文が無い場合は、後で /api/messages から取得する
                    if (preview.length() == 0) {
                        String cid = optJSONObject3.optString("id", "");
                        if (cid.length() == 0 && optJSONObject3.opt("id") != null) cid = String.valueOf(optJSONObject3.opt("id"));
                        if (cid.length() > 0) { toFetch.add(new String[]{cid, String.valueOf(optLong)}); fetchIdx.add(Integer.valueOf(idx)); }
                    }
                }
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
                        try { jSONArray2.getJSONObject(fetchIdx.get(k).intValue()).put("last_message", results[k]); } catch (Exception ignore) {}
                    }
                }
            }
            return new JSONObject().put("ok", true).put("rooms", jSONArray2).put("raw", str).toString();
        }
    }

    private String getCoinHistory() {
        return historyResult(request("GET", "/api/v2/coin_histories", q1("page", "1"), (Map<String, String>) null), "coin_histories", "histories", "data");
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

    private String getCommunityMembers(String str) {
        Resp request = request("GET", "/api/communities/" + str + "/members", q1("count", "20"), (Map<String, String>) null);
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

    private String getFriendsList(String str) {
        if (str == null || str.length() == 0) {
            str = "1";
        }
        try {
            // 1) GET /api/relation/friends
            Resp r = request("GET", "/api/relation/friends", q1("page", str), (Map<String, String>) null);
            JSONArray users = (r.status == 200 && r.body != null) ? normalizeUserList(r.body) : new JSONArray();
            dbgLog(nowStr() + "  [FRIENDS] GET relation/friends HTTP " + r.status + " users=" + users.length() + " " + (r.body != null ? truncate(redactLog(r.body.toString()), 160) : ""));
            // 2) だめなら POST /api/relation/friends
            if (users.length() == 0) {
                Resp rp = request("POST", "/api/relation/friends", q1("page", str), (Map<String, String>) null);
                if (rp.status == 200 && rp.body != null) users = normalizeUserList(rp.body);
                dbgLog(nowStr() + "  [FRIENDS] POST relation/friends HTTP " + rp.status + " users=" + users.length());
            }
            // 3) それでも空なら 相互フォロー = フォロー中 ∩ フォロワー を自前計算
            if (users.length() == 0) {
                users = computeMutualFollows(str);
                dbgLog(nowStr() + "  [FRIENDS] computed mutual users=" + users.length());
            }
            return new JSONObject().put("ok", true).put("users", users).toString();
        } catch (Exception e) {
            return errJson(e);
        }
    }

    // フォロー中(followees)とフォロワー(followers)の積集合 = 相互フォロー を計算。
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
            for (int length = jSONArray.length() - 1; length >= 0; length--) {
                JSONObject optJSONObject2 = jSONArray.optJSONObject(length);
                if (optJSONObject2 != null) {
                    String imgPath = firstStr(optJSONObject2, "image_file_path", "imageFilePath", "image_url", "imageUrl", "image");
                    jSONArray2.put(new JSONObject().put("id", optJSONObject2.opt("id")).put("user_id", optJSONObject2.optLong("user_id", optJSONObject2.optLong("userId"))).put("text", firstStr(optJSONObject2, "text_message", "text", "message", "content", "body")).put("image_url", imgPath.length() > 0 ? iconUrl(imgPath) : "").put("is_read", optJSONObject2.optInt("is_read", 0) == 1 || optJSONObject2.optBoolean("is_read", false)).put("sent_at", firstStr(optJSONObject2, "sent_at", "sentAt", "created_at", "createdAt")));
                }
            }
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
        return communitiesResult(request("GET", "/api/communities/participating", q1("count", "20"), (Map<String, String>) null));
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

    private String getNotifications(String kind) {
        return getNotifications(kind, "1");
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
        return http("POST", ensureSkywayHost + "/authenticate", (Map<String, String>) null, hashMap);
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
            call.put("is_owner", false);
            call.put("owner_user_id", targetUid);
            JSONArray participants = new JSONArray();
            if (targetUid != 0) {
                String[] cached = this.nameCache.get(Long.valueOf(targetUid));
                participants.put(new JSONObject().put("user_id", targetUid)
                        .put("name", cached != null ? cached[0] : ("user " + targetUid))
                        .put("icon_url", cached != null ? iconUrl(cached[1]) : ""));
            }
            call.put("participants", participants);
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
        return userHistoryResult(request("GET", "/api/dive/talking_requests", q1("page", "1"), (Map<String, String>) null), "talking_requests", "data");
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
                    if (p != null) p.put("is_talk", isTalk);
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
                ids.put(commentUid(c));
            }
            resolveNames(ids, "user_id");

            JSONArray out = new JSONArray();
            for (int i = 0; i < comments.length(); i++) {
                JSONObject c = comments.optJSONObject(i);
                if (c == null) continue;
                long uid = commentUid(c);
                String[] cached = nameCache.get(Long.valueOf(uid));
                JSONObject user = c.optJSONObject("user");
                String name = user != null ? user.optString("name", "") : "";
                if (name.length() == 0 && cached != null) {
                    name = cached[0];
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
        HashMap hashMap = new HashMap();
        hashMap.put("page", "1");
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        Resp http = http("GET", "https://api2.meetscom.com/api/feed_posts/" + str + "/liked_users", hashMap, (Map<String, String>) null);
        String str2 = "[liked_users@api2 HTTP " + http.status + "] " + (http.body != null ? truncate(http.body.toString(), 300) : "(なし)");
        try {
            return (http.status != 200 || http.body == null) ? new JSONObject().put("ok", false).put("status", http.status).put("raw", str2).toString() : new JSONObject().put("ok", true).put("users", normalizeUserList(http.body)).put("raw", str2).toString();
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
            if (doF) {
                int[] rF = scopedPostsInto("/api/feed_posts", "feed_posts", j, curF, "timeline", true, merged, nextF, seenFeed);
                if (rF[0] < 0) scopedPostsInto("/api/feed_posts", "feed_posts", j, curF, "timeline", false, merged, nextF, seenFeed);
                if (rF[0] >= 0 && rF[0] < 30) nextF[0] = ""; // 30件未満なら終端
            }
            if (doT) {
                int[] rT = scopedPostsInto("/api/timeline_posts", "timeline_posts", j, curT, "talk", true, merged, nextT, seenTalk);
                if (rT[0] < 0) scopedPostsInto("/api/timeline_posts", "timeline_posts", j, curT, "talk", false, merged, nextT, seenTalk);
                if (rT[0] >= 0 && rT[0] < 30) nextT[0] = "";
            }
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

    private Resp http(String method, String url, Map<String, String> query, Map<String, String> fields) {
        return http(method, url, query, fields, true);
    }

    // sendAuth=false のときは Authorization/X-Auth-Token を付けない。
    // ログイン系(login / signup / twitter_login)を「既ログイン状態のトークン付き」で
    // 送るとサーバーが弾く(HTTP 503 リクエストエラー)ため、ログイン系だけ false で呼ぶ。
    private Resp http(String method, String url, Map<String, String> query, Map<String, String> fields, boolean sendAuth) {
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
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(35000);
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
                            snip = snip + " ALLHDR:" + ab.toString();
                        }
                    } catch (Exception eh) {
                    }
                }
                dbgLog(nowStr() + "  " + method + " " + redactLog(sb.toString()) + "  → " + status + snip);
            } catch (Exception eLog) {
            }
            Resp resp = new Resp(status, bodyJson);
            resp.vsns = localVsns;
            resp.sessionExpired = localExpired;
            return resp;
        } catch (Exception e) {
            try {
                dbgLog(nowStr() + "  " + method + " " + redactLog(url) + "  → 通信エラー: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            } catch (Exception eLog) {
            }
            JSONObject err = new JSONObject();
            try {
                err.put("error", e.getMessage() != null ? e.getMessage() : "io_error");
            } catch (Exception e2) {
            }
            Resp resp = new Resp(-1, err);
            if (conn != null) {
                conn.disconnect();
            }
            return resp;
        }
    }

    private Resp httpJson(String method, String url, JSONObject bodyObj) {
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
            Resp resp = new Resp(status, bodyJson);
            resp.vsns = localVsns;
            resp.sessionExpired = localExpired;
            return resp;
        } catch (Exception e) {
            Resp resp = new Resp(-1, null);
            if (conn != null) {
                conn.disconnect();
            }
            return resp;
        }
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
        HashMap hashMap = new HashMap();
        hashMap.put("target_ids[]", str2);
        Resp request = request("POST", "/api/communities/" + str + "/invite", (Map<String, String>) null, hashMap);
        try {
            if (request.status == 200 || request.status == 201) {
                return new JSONObject().put("ok", true).toString();
            }
            return new JSONObject().put("ok", false).put("status", request.status).put("raw", request.body != null ? truncate(request.body.toString(), 200) : "").toString();
        } catch (Exception e) {
            return errJson(e);
        }
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
            Resp resp = request("GET", "/api/rooms", q1("owner_user_id", ownerIdStr), (Map<String, String>) null);
            if (resp.status != 200) {
                return new JSONObject().put("ok", false).put("error", "room_not_found").put("status", resp.status)
                        .put("message", "この枠は見つかりませんでした(終了した可能性があります)。一覧を更新してください。").toString();
            }
            JSONObject roomObj = null;
            Object data = resp.body != null ? resp.body.opt("data") : null;
            if (data instanceof JSONArray) {
                JSONArray arr = (JSONArray) data;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        roomObj = o;
                        break;
                    }
                }
            } else if (data instanceof JSONObject) {
                roomObj = (JSONObject) data;
            }
            if (roomObj == null || roomObj.optString("token", "").length() == 0) {
                dbgLog(nowStr() + "  [JOIN] owner=" + ownerIdStr + " rooms body=" + (resp.body != null ? truncate(redactLog(resp.body.toString()), 300) : "(none)"));
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
            if (roomIdLong != 0) {
                try {
                    JSONObject joinBody = new JSONObject();
                    joinBody.put("version", APP_VERSION);
                    String authToken = authToken();
                    if (authToken != null) {
                        joinBody.put("auth_token", authToken);
                    }
                    Resp joinResp = httpJson("POST", "https://api.meetscom.com/api/rooms/" + roomIdLong + "/join", joinBody);
                    if (joinResp.status == 404) {
                        httpJson("POST", "https://api2.meetscom.com/api/rooms/" + roomIdLong + "/join", joinBody);
                    }
                } catch (Exception e) {
                }
            }
            Resp skywayResp = getSkywayToken(roomToken);
            if (skywayResp.status != 200) {
                if (skywayResp.status == 401) {
                    return new JSONObject().put("ok", false).put("status", 401)
                            .put("message", "通話サーバーへの認証に失敗しました。ログインし直してください。").toString();
                }
                return new JSONObject().put("ok", false).put("status", skywayResp.status).toString();
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

    private Resp newTimelineApi(String str, String str2) {
        HashMap hashMap = new HashMap();
        hashMap.put("version", APP_VERSION);
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        boolean z = str.equals("POST") || str.equals("PUT");
        Resp http = z ? http(str, BASE_URL2 + str2, (Map<String, String>) null, hashMap) : http(str, BASE_URL2 + str2, hashMap, (Map<String, String>) null);
        return (http.status == 404 || http.status >= 500) ? z ? http(str, BASE_URL + str2, (Map<String, String>) null, hashMap) : http(str, BASE_URL + str2, hashMap, (Map<String, String>) null) : http;
    }

    private Resp newTimelineApiForm(String str, Map<String, String> map) {
        map.put("version", APP_VERSION);
        String authToken = authToken();
        if (authToken != null) {
            map.put("auth_token", authToken);
        }
        Resp http = http("POST", BASE_URL2 + str, (Map<String, String>) null, map);
        return (http.status == 404 || http.status >= 500) ? http("POST", BASE_URL + str, (Map<String, String>) null, map) : http;
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
                String optString = optJSONObject.optString("comment", optJSONObject.optString("description", ""));
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
                // 通話募集(Feed)投稿を区別するためのフィールドを保持
                boolean hasPurpose = optJSONObject.has("purpose") && !optJSONObject.isNull("purpose");
                if (hasPurpose) jSONObject.put("purpose", optJSONObject.opt("purpose"));
                if (optJSONObject.has("topic") && !optJSONObject.isNull("topic")) jSONObject.put("topic", optJSONObject.opt("topic"));
                if (optJSONObject.has("post_type") && !optJSONObject.isNull("post_type")) jSONObject.put("post_type", optJSONObject.opt("post_type"));
                if (optJSONObject.has("play_time") && !optJSONObject.isNull("play_time")) jSONObject.put("play_time", optJSONObject.opt("play_time"));
                // is_talk: 通話募集(=「話そう」=timeline_posts)は purpose を持つ。通常のタイムライン(=「つぶやく」=feed_posts)は持たない。
                // 混在フィード(フォロー/友達/ブックマーク)用の自動判定。タブ側では取得元エンドポイントで上書きする。
                jSONObject.put("is_talk", hasPurpose);
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
        JSONArray jSONArray = firstArray(jSONObject, USER_LIST_KEYS);
        if (jSONArray == null) {
            Object opt = jSONObject.opt("data");
            if (opt instanceof JSONArray) {
                jSONArray = (JSONArray) opt;
            } else if (opt instanceof JSONObject) {
                jSONArray = firstArray((JSONObject) opt, USER_LIST_KEYS);
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
                    String ls = firstNonEmpty(u.optString("login_status_with_unit", ""), u.optString("loginStatusWithUnit", ""), u.optString("login_status", ""));
                    if (ls.length() > 0) nu.put("login_status", ls);
                    if (!u.isNull("age") && u.opt("age") != null) nu.put("age", u.opt("age"));
                    String ar = u.optString("area_name", u.optString("areaName", ""));
                    if (ar.length() > 0) nu.put("area_name", ar);
                    String cm = u.optString("comment", "");
                    if (cm.length() > 0) nu.put("comment", cm.length() > 80 ? cm.substring(0, 80) : cm);
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

    private String okResult(Resp resp) {
        try {
            JSONObject put = new JSONObject().put("ok", resp.status >= 200 && resp.status < 300 && (resp.vsns == -999 || resp.vsns == 0)).put("status", resp.status).put("vsns", resp.vsns);
            if (resp.sessionExpired) {
                put.put("session_expired", true);
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
                jSONObject.put("is_following", optJSONObject.optBoolean("is_following", optJSONObject.optBoolean("isFollowing", false)));
                jSONObject.put("is_followed", optJSONObject.optBoolean("is_followed", optJSONObject.optBoolean("isFollowed", false)));
                jSONObject.put("is_blocked", optJSONObject.optBoolean("is_blocked", optJSONObject.optBoolean("isBlocked", false)));
                jSONObject.put("friend_count", optJSONObject.optInt("friend_count", optJSONObject.optInt("friendCount", 0)));
                jSONObject.put("area_name", optJSONObject.optString("area_name", optJSONObject.optString("areaName", "")));
                jSONObject.put("login_status", optJSONObject.optString("login_status_with_unit", optJSONObject.optString("loginStatusWithUnit", "")));
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
                Resp request = request("GET", "/api/rooms", q1("owner_user_id", str), (Map<String, String>) null);
                if (request.status != 200 || request.body == null) {
                    return jsonStatus(request);
                }
                Object opt = request.body.opt("data");
                if (opt instanceof JSONArray) {
                    JSONArray jSONArray = (JSONArray) opt;
                    for (int i = 0; i < jSONArray.length(); i++) {
                        JSONObject optJSONObject = jSONArray.optJSONObject(i);
                        if (optJSONObject != null) { jSONObject = optJSONObject; break; }
                    }
                } else if (opt instanceof JSONObject) {
                    jSONObject = (JSONObject) opt;
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
        return okResult(newTimelineApiForm("/api/feed_posts/" + str + "/comments", hashMap));
    }

    private String reportTimelinePost(String str, String str2) {
        if (str == null || str.length() == 0) {
            return jsonErr("通報対象が不明です");
        }
        // 公式: POST /api/relation/user_report(全てクエリ)。referer_id は必須int(空だと弾かれる)なので 0 を送る。
        // community_id / community_talk_room_id は nullable のためユーザー通報では送らない。
        HashMap<String, String> q = new HashMap<String, String>();
        q.put("target_id", str);
        q.put("content", (str2 == null || str2.length() == 0) ? "通報" : str2);
        q.put("referer_id", "0");
        Resp r = request("POST", "/api/relation/user_report", q, (Map<String, String>) null);
        dbgLog(nowStr() + "  [REPORT] target=" + str + " HTTP " + r.status + (r.body != null ? " " + truncate(redactLog(r.body.toString()), 200) : ""));
        boolean ok = r.status >= 200 && r.status < 300;
        try {
            JSONObject out = new JSONObject().put("ok", ok).put("status", r.status);
            if (r.body != null) out.put("body", r.body);
            if (r.sessionExpired) out.put("session_expired", true);
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
        final String ck = str2 == null ? "" : str2.replaceAll("/\\d+", "/{n}");
        String str3 = this.hostCache.containsKey(ck) ? this.hostCache.get(ck) : BASE_URL;
        LinkedHashSet linkedHashSet = new LinkedHashSet();
        linkedHashSet.add(str3);
        linkedHashSet.add(BASE_URL2);
        linkedHashSet.add(BASE_URL);
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
            } else if (http.status == 404 || http.status <= 0 || http.status >= 500) {
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

    private void resolveNames(JSONArray jSONArray, String str) {
        JSONArray optJSONArray;
        ArrayList arrayList = new ArrayList();
        for (int i = 0; i < jSONArray.length(); i++) {
            JSONObject optJSONObject = jSONArray.optJSONObject(i);
            if (optJSONObject != null) {
                long optLong = optJSONObject.optLong(str);
                if (optLong != 0 && !this.nameCache.containsKey(Long.valueOf(optLong)) && !arrayList.contains(Long.valueOf(optLong))) {
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
            if (request.body != null && (optJSONArray = request.body.optJSONArray("user_info")) != null) {
                boolean changed = false;
                for (int i3 = 0; i3 < optJSONArray.length(); i3++) {
                    JSONObject optJSONObject2 = optJSONArray.optJSONObject(i3);
                    if (optJSONObject2 != null) {
                        long optLong2 = optJSONObject2.optLong("user_id");
                        if (optLong2 != 0) {
                            this.nameCache.put(Long.valueOf(optLong2), new String[]{optJSONObject2.optString("name"), optJSONObject2.optString("profile_picture_file_path")});
                            changed = true;
                        }
                    }
                }
                if (changed) {
                    saveNameCache();
                }
            }
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
        dbgLog(nowStr() + "  [ROOM] leave " + str + " -> " + r.status);
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
        HashMap hashMap = new HashMap();
        hashMap.put("target_id", str2);
        return okResult(request("POST", "/api/rooms/" + str + "/kick", hashMap, (Map<String, String>) null));
    }

    private String roomSwitchCommentEnabled(String str, boolean z) {
        if (str == null || str.length() == 0) {
            return jsonErr("room_id不明");
        }
        HashMap hashMap = new HashMap();
        hashMap.put("comment_enabled", z ? "true" : "false");
        return okResult(request("PUT", "/api/rooms/" + str + "/switch_comment_enabled", hashMap, (Map<String, String>) null));
    }

    private String roomJoinTrial(String str) {
        HashMap hashMap = new HashMap();
        if (str != null && str.length() > 0) {
            hashMap.put("room_id", str);
        }
        return okResult(request("POST", "/api/rooms/join_trial", (Map<String, String>) null, hashMap));
    }

    private String roomInvite(String str, String str2) {
        if (str == null || str.length() == 0) {
            return jsonErr("room_id不明");
        }
        if (str2 == null || str2.length() == 0) {
            return jsonErr("target_id不明");
        }
        HashMap hashMap = new HashMap();
        hashMap.put("target_id", str2);
        return okResult(request("POST", "/api/rooms/" + str + "/invite", (Map<String, String>) null, hashMap));
    }

    private String getParticipatingCommunityTalkRooms() {
        Resp resp = request("GET", "/api/communities/participating_talk_rooms", (Map<String, String>) null, (Map<String, String>) null);
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
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("comment_enabled", enabled ? "true" : "false");
        return okResult(request("PUT", "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/switch_comment_enabled", fields, (Map<String, String>) null));
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
                    ids.put(commentUid(c));
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
            if (str != null && str.length() > 0) {
                hashMap.put("user_name", str);
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
            // image_file_path はファイル名だけ（サーバがフォルダを前置する）
            hashMap.put("image_file_path", bareName);
            hashMap.put("md5", md5);
            hashMap.put("version", "android_3.9.101");
            String authToken = authToken();
            if (authToken != null) hashMap.put("auth_token", authToken);
            return okResultStatus(http("POST", "https://api.meetscom.com/api/chat/messages", (Map<String, String>) null, hashMap));
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
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("phone_number", phoneNumber);
        fields.put("code", code);
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
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("email", email);
        fields.put("token", token);
        return okResult(request("POST", "/api/check_email_token", (Map<String, String>) null, fields));
    }

    private String bulkDeleteChats(String chatIdsCsv) {
        if (chatIdsCsv == null || chatIdsCsv.length() == 0) {
            return jsonErr("chat_ids不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        String[] ids = chatIdsCsv.split(",");
        int idx = 0;
        for (int i = 0; i < ids.length; i++) {
            String id = ids[i].trim();
            if (id.length() > 0) {
                // 一意なインデックス付きキーにしないと、Map<String,String>では同一キー"chat_ids[]"が上書きされ最後の1件しか送られない
                fields.put("chat_ids[" + idx + "]", id);
                idx++;
            }
        }
        if (idx == 0) {
            return jsonErr("有効なchat_idがありません");
        }
        return okResult(request("DELETE", "/api/chat/chats_bulk_delete", (Map<String, String>) null, fields));
    }

    private String checkRecordingDisabledUsers(String userIdsCsv) {
        if (userIdsCsv == null || userIdsCsv.length() == 0) {
            return jsonErr("user_ids不明");
        }
        return okResult(request("GET", "/api/recording_disabled_users/check", q1("user_ids", userIdsCsv), (Map<String, String>) null));
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

    private String agreeTalkRecording(String roomId) {
        HashMap<String, String> fields = new HashMap<String, String>();
        if (roomId != null && roomId.length() > 0) {
            fields.put("room_id", roomId);
        }
        return okResult(request("POST", "/api/talk_recording_agreements", (Map<String, String>) null, fields));
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
                out.put("channel", firstStr(data, "channel", "skyway_channel", "channel_name"));
                out.put("token", firstStr(data, "token", "skyway_token", "call_token"));
                out.put("target_id", data.opt("id") != null ? data.opt("id") : receiverId);
                out.put("raw", data);
            }
            return out.toString();
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
            JSONObject out = new JSONObject();
            out.put("ok", true);
            out.put("channel", firstStr(data, "channel", "skyway_channel", "channel_name"));
            out.put("target_id", firstStr(data, "target_id", "receiver_id", "id"));
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
        Resp resp = request("GET", "/api/master/system_params", (Map<String, String>) null, (Map<String, String>) null);
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
        Resp resp = request("GET", "/api/communities/" + communityId + "/join-requests", (Map<String, String>) null, (Map<String, String>) null);
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
        return okResult(request("POST", "/api/communities/" + communityId + "/join-requests/approve", (Map<String, String>) null, q1("user_id", userId)));
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
        return okResult(request("POST", "/api/communities/" + communityId + "/join-requests/deny", (Map<String, String>) null, q1("user_id", userId)));
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
        return okResult(request("POST", "/api/communities/" + communityId + "/report", (Map<String, String>) null, fields));
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
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("badge_id", badgeId == null ? "" : badgeId);
        return okResult(request("PUT", "/api/users/" + userId() + "/display-badge", (Map<String, String>) null, fields));
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
        return okResult(httpApi2("PUT", "/api/cheering_talk/receiver_users/" + receiverId + "/update_status", (Map<String, String>) null, fields));
    }

    private String getCheeringRequestReceives() {
        Resp resp = httpApi2("GET", "/api/cheering_talk/request_receives", (Map<String, String>) null, (Map<String, String>) null);
        dbgLog(nowStr() + "  [CHEER] request_receives HTTP " + resp.status + " " + truncate(redactLog(resp.body != null ? resp.body.toString() : "(null)"), 400));
        return cheeringDataResult(resp, "requests");
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
        return okList(request("GET", "/api/v2/daily_point_histories", q1("page", page == null || page.length() == 0 ? "1" : page), (Map<String, String>) null), "histories", "daily_point_histories", "histories", "point_histories");
    }
    private String getItemHistories(String page) {
        return okList(request("GET", "/api/item_histories", q1("page", page == null || page.length() == 0 ? "1" : page), (Map<String, String>) null), "items", "item_histories", "items");
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
        HashMap<String, String> f = new HashMap<String, String>();
        f.put("comment", comment == null ? "" : comment);
        return okResult(httpApi2("POST", "/api/communities/" + communityId + "/talk_rooms/" + roomId + "/comments", (Map<String, String>) null, f));
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
        return okList(request("GET", "/api/v2/dive/target_friends", (Map<String, String>) null, (Map<String, String>) null), "friends", "target_friends", "friends", "users");
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
        return okResult(request("POST", "/api/campaigns/" + campaignId + "/user_campaign", (Map<String, String>) null, new HashMap()));
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

    private String trackEnquete(String enqueteId, String event) {
        if (enqueteId == null || enqueteId.length() == 0) {
            return jsonErr("enquete_id不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("enquete_id", enqueteId);
        if (event != null && event.length() > 0) {
            fields.put("event", event);
        }
        return okResult(request("POST", "/api/enquete_tracking", (Map<String, String>) null, fields));
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

    private String getCommunityBookmarks(String page) {
        // 正しくは count パラメータが必須(無しだと 400 code4200)。レスポンスは data.bookmarks。
        Resp resp = request("GET", "/api/communities/bookmarks", q1("count", "20"), (Map<String, String>) null);
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
        Resp resp = request("GET", "/api/decoration_items", (Map<String, String>) null, (Map<String, String>) null);
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
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("decoration_item_id", itemId);
        return okResult(request("POST", "/api/decoration_items/purchase", (Map<String, String>) null, fields));
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
        Resp resp = request("GET", "/api/subscription_histories", (Map<String, String>) null, (Map<String, String>) null);
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
        return okResult(request("GET", "/api/estimate_point_exchange", q1("points", points), (Map<String, String>) null));
    }

    private String executePointExchange(String points) {
        if (points == null || points.length() == 0) {
            return jsonErr("points不明");
        }
        HashMap<String, String> fields = new HashMap<String, String>();
        fields.put("points", points);
        return okResult(request("POST", "/api/point_exchange", (Map<String, String>) null, fields));
    }

    private String getRoomSettings() {
        Resp resp = request("GET", "/api/room_settings", (Map<String, String>) null, (Map<String, String>) null);
        try {
            if (resp.status != 200 || resp.body == null) {
                return jsonStatus(resp);
            }
            return new JSONObject().put("ok", true).put("settings", resp.body).toString();
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

    private boolean likePrefersServer1 = false;
    private String toggleLike(String str, boolean z) {
        // いいねは feed_posts/{id}/like のみ(タイムライン投稿も共通)。2xxなら成功扱い(vsnsで誤判定して
        // フロントが取り消すのを防ぐ)。失敗時は api2→api1 も試す。診断用にHTTP状況を記録。
        Resp r;
        if (likePrefersServer1) {
            // 前回 api2 が 404 で旧サーバーが成功した → 以後は旧サーバーを先に(無駄打ち防止)
            r = (z ? http("DELETE", "https://api.meetscom.com/api/feed_posts/" + str + "/like", likeFields(), (Map<String, String>) null) : http("POST", "https://api.meetscom.com/api/feed_posts/" + str + "/like", (Map<String, String>) null, likeFields()));
            if (!(r.status >= 200 && r.status < 300)) { likePrefersServer1 = false; r = newTimelineApi(z ? "DELETE" : "POST", "/api/feed_posts/" + str + "/like"); }
        } else {
            r = newTimelineApi(z ? "DELETE" : "POST", "/api/feed_posts/" + str + "/like");
            if (!(r.status >= 200 && r.status < 300)) {
                Resp r2 = (z ? http("DELETE", "https://api.meetscom.com/api/feed_posts/" + str + "/like", likeFields(), (Map<String, String>) null) : http("POST", "https://api.meetscom.com/api/feed_posts/" + str + "/like", (Map<String, String>) null, likeFields()));
                if (r2.status >= 200 && r2.status < 300) { r = r2; likePrefersServer1 = true; }
            }
        }
        dbgLog(nowStr() + "  [LIKE] " + (z ? "un" : "") + "like post=" + str + " HTTP " + r.status + " vsns=" + r.vsns);
        boolean ok = r.status >= 200 && r.status < 300;
        try {
            JSONObject out = new JSONObject().put("ok", ok).put("status", r.status);
            if (r.sessionExpired) out.put("session_expired", true);
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
        hashMap.put("version", APP_VERSION);
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        return okResult(request("POST", "/api/relation/block/deletes", (Map<String, String>) null, hashMap));
    }

    private String unfollowUser(String str) {
        HashMap hashMap = new HashMap();
        hashMap.put("target_id", str);
        hashMap.put("version", "android_3.9.101");
        String authToken = authToken();
        if (authToken != null) {
            hashMap.put("auth_token", authToken);
        }
        return okResult(http("DELETE", "https://api.meetscom.com/api/relation/new_follow/following", hashMap, (Map<String, String>) null));
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
                    } else if ("background".equals(str2)) {
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
                } catch (Exception ig) { out.put("raw", truncate(resp, 200)); }
            }
            return out.toString();
        } catch (Exception e) {
            return errJson(e);
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ig) {}
        }
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
            JSONObject d = r.body.optJSONObject("data");
            JSONObject u = d != null ? d.optJSONObject("user_info") : null;
            if (u == null && d != null) u = d.optJSONObject("userInfo");
            if (u == null) u = r.body.optJSONObject("user_info");
            if (u == null) u = d != null ? d : r.body;
            // ---- 判定(JS 側 koeSpamScore と同じ規則) ----
            double sc = 0; JSONArray reasons = new JSONArray(); JSONObject ev = new JSONObject();
            String icon = u.optString("profile_picture_file_path", "");
            String fn = icon; int sl = fn.lastIndexOf('/'); if (sl >= 0) fn = fn.substring(sl + 1); int qm = fn.indexOf('?'); if (qm >= 0) fn = fn.substring(0, qm);
            ev.put("icon_file", fn);
            if (fn.matches("^[A-Za-z0-9]{16}\\.(png|jpe?g|webp)$")) { sc += 3; reasons.put("量産型アイコン名"); }
            String nm = u.optString("name", ""); ev.put("name", nm);
            if (nm.matches("^[^\\s]{1,20}\\d{3}$") && !nm.matches("^\\d+$")) { sc += 2; reasons.put("名前が単語+3桁数字"); }
            int fol = u.optInt("follower_count", -1), fee = u.optInt("followee_count", -1), fr = u.optInt("friend_count", -1); long liked = u.optLong("liked_count", -1);
            ev.put("follower_count", fol).put("followee_count", fee).put("friend_count", fr).put("liked_count", liked);
            if (fol == 0 && fee == 0) { sc += 1; reasons.put("フォロー0/フォロワー0"); }
            if (fr == 0 && liked == 0) sc += 0.5;
            String cm = u.isNull("comment") ? "" : u.optString("comment", "");
            if (cm.trim().length() == 0) { sc += 0.5; reasons.put("自己紹介なし"); }
            ev.put("comment_empty", cm.trim().length() == 0);
            if (u.has("is_sms_authenticated") && !truthy(u.opt("is_sms_authenticated"))) { sc += 0.5; ev.put("sms_authenticated", false); }
            JSONObject st = u.optJSONObject("settings");
            if (st != null && truthy(st.opt("random_match_enabled"))) { sc += 0.5; ev.put("random_match_enabled", true); }
            String level = sc >= 5 ? "high" : sc >= 3 ? "mid" : "";
            ev.put("score", sc).put("level", level).put("checked_at", nowStr()).put("checked_by", "KoeTomo+ " + "1.00");
            if (level.length() == 0) {
                return new JSONObject().put("ok", false).put("error", "not_spam_like").put("message", "この相手は業者判定の条件を満たしていません(スコア " + sc + ")。通常の通報をご利用ください").toString();
            }
            StringBuilder detail = new StringBuilder("[KoeTomo+ 業者自動判定 score=" + sc + "] ");
            for (int i = 0; i < reasons.length(); i++) { if (i > 0) detail.append("・"); detail.append(reasons.optString(i)); }
            JSONObject body = new JSONObject();
            body.put("target_uid", String.valueOf(t));
            body.put("reason_code", "bot");
            body.put("detail", detail.toString());
            body.put("evidence", ev.toString()); // アプリが API から取得した生の判定材料(ユーザー入力なし)
            body.put("reporter_uid", String.valueOf(me));
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
                    return createRoom(jSONArray.optString(0), jSONArray.optBoolean(1, true), jSONArray.optBoolean(2, true));
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
                    return createPostWithVoice("/api/feed_posts", jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optString(3), "0");
                }
                if (str.equals("create_feed_post")) {
                    return createPost("/api/timeline_posts", jSONArray.optString(0), "0");
                }
                if (str.equals("create_feed_post_with_image")) {
                    return createPostWithImage("/api/timeline_posts", jSONArray.optString(0), "0", jSONArray.optString(2));
                }
                if (str.equals("create_feed_post_with_voice")) {
                    return createPostWithVoice("/api/timeline_posts", jSONArray.optString(0), jSONArray.optString(1), jSONArray.optString(2), jSONArray.optString(3), "0");
                }
                if (str.equals("upload_account_image")) {
                    return uploadAccountImage(jSONArray.optString(0), jSONArray.optString(1, "profile"));
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
                if (str.equals("dive_request_disconnect")) {
                    return diveRequestDisconnect(jSONArray.optString(0), jSONArray.optString(1, ""));
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
                    return getCommunityMembers(jSONArray.optString(0));
                }
                if (str.equals("get_community_posts")) {
                    return getCommunityPosts(jSONArray.optString(0));
                }
                if (str.equals("get_community_rules")) {
                    return getCommunityRules(jSONArray.optString(0));
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
                if (str.equals("get_my_qr_code")) {
                    return getMyQrCode();
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
                    return getCommunityBookmarks(jSONArray.optString(0, "1"));
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
