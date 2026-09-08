package com.akun.koetomo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * 一覧・通話画面のアイコン用サムネイルをネイティブ側で作って WebView に渡す。
 *
 * 背景
 *   koetomo のアイコン画像は原寸(数百px〜)で配信される。WebView は表示サイズに関係なく
 *   原寸でデコードし GPU に載せるため、一覧を開くたびに CPU/GPU と メモリを大きく使い、
 *   発熱の原因になる。公式アプリは Glide で表示サイズに縮小してから描画している。
 *
 * 仕組み
 *   JS 側が画像 URL に <code>koe_w=&lt;px&gt;</code> を付ける。
 *   {@link MainActivity} の shouldInterceptRequest がそれを検出してここに回す。
 *   1. ディスクキャッシュにあればそれを返す
 *   2. 無ければ原画像を取得し、inSampleSize で粗く縮小 → 目標幅に縮小 → WebP で保存
 *   3. 失敗時は null を返し、WebView が通常どおり原画像を読む
 *
 * キャッシュは cacheDir/thumbs に置き、上限を超えたら古いものから削除する。
 */
public final class ImageThumbCache {

    private static final String QUERY_KEY = "koe_w";
    private static final int MIN_WIDTH = 24;
    private static final int MAX_WIDTH = 1080;
    private static final int WEBP_QUALITY = 82;
    private static final long MAX_CACHE_BYTES = 24L * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int TRIM_EVERY = 40;

    private final File dir;
    private int writesSinceTrim = 0;

    public ImageThumbCache(Context context) {
        this.dir = new File(context.getCacheDir(), "thumbs");
        if (!this.dir.exists()) this.dir.mkdirs();
    }

    /** この URL がサムネイル対象か(koe_w= を持つ http(s) 画像か)。 */
    public static boolean handles(String url) {
        return url != null
                && (url.startsWith("https://") || url.startsWith("http://"))
                && url.contains(QUERY_KEY + "=");
    }

    /**
     * サムネイルを返す。作れなければ null(呼び出し側はそのまま通常読み込みに任せる)。
     * WebView の IO スレッドから呼ばれる前提(ネットワーク・ディスク I/O を行う)。
     */
    public WebResourceResponse serve(String url) {
        try {
            int width = parseWidth(url);
            if (width <= 0) return null;
            String sourceUrl = stripQueryKey(url);
            File cached = new File(dir, sha1(sourceUrl + "@" + width) + ".webp");

            byte[] bytes = cached.exists() ? readFile(cached) : null;
            if (bytes == null) {
                bytes = buildThumbnail(sourceUrl, width);
                if (bytes == null) return null;
                writeFile(cached, bytes);
                maybeTrim();
            } else {
                cached.setLastModified(System.currentTimeMillis()); // LRU 用
            }
            WebResourceResponse res = new WebResourceResponse("image/webp", null, new ByteArrayInputStream(bytes));
            java.util.Map<String, String> headers = new java.util.HashMap<String, String>();
            headers.put("Cache-Control", "max-age=86400");
            headers.put("Access-Control-Allow-Origin", "*");
            res.setResponseHeaders(headers);
            return res;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 生成 -------------------------------------------------------------

    private byte[] buildThumbnail(String sourceUrl, int width) throws Exception {
        byte[] original = download(sourceUrl);
        if (original == null || original.length == 0) return null;

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(original, 0, original.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sampleSizeFor(bounds.outWidth, width);
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeByteArray(original, 0, original.length, opts);
        if (decoded == null) return null;

        Bitmap scaled = decoded;
        if (decoded.getWidth() > width) {
            int height = Math.max(1, Math.round(decoded.getHeight() * (width / (float) decoded.getWidth())));
            scaled = Bitmap.createScaledBitmap(decoded, width, height, true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean ok = scaled.compress(Bitmap.CompressFormat.WEBP, WEBP_QUALITY, out);
        if (scaled != decoded) scaled.recycle();
        decoded.recycle();
        return ok ? out.toByteArray() : null;
    }

    /** 目標幅の 2 倍以上を保ったまま取れる最大の 2 の冪を inSampleSize にする。 */
    private static int sampleSizeFor(int sourceWidth, int targetWidth) {
        int sample = 1;
        while (sourceWidth / (sample * 2) >= targetWidth * 2) sample *= 2;
        return sample;
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "image/*");
        try {
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            try {
                return readAll(in);
            } finally {
                in.close();
            }
        } finally {
            conn.disconnect();
        }
    }

    // ---- URL --------------------------------------------------------------

    private static int parseWidth(String url) {
        int i = url.indexOf(QUERY_KEY + "=");
        if (i < 0) return 0;
        int start = i + QUERY_KEY.length() + 1;
        int end = start;
        while (end < url.length() && Character.isDigit(url.charAt(end))) end++;
        if (end == start) return 0;
        int w = Integer.parseInt(url.substring(start, end));
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, w));
    }

    /** koe_w=... だけを取り除いた元の URL を返す。 */
    private static String stripQueryKey(String url) {
        int q = url.indexOf('?');
        if (q < 0) return url;
        String base = url.substring(0, q);
        StringBuilder rest = new StringBuilder();
        for (String part : url.substring(q + 1).split("&")) {
            if (part.length() == 0 || part.startsWith(QUERY_KEY + "=")) continue;
            rest.append(rest.length() == 0 ? "?" : "&").append(part);
        }
        return base + rest;
    }

    // ---- ディスク -----------------------------------------------------------

    private synchronized void maybeTrim() {
        if (++writesSinceTrim < TRIM_EVERY) return;
        writesSinceTrim = 0;
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= MAX_CACHE_BYTES) return;
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        for (File f : files) {
            if (total <= MAX_CACHE_BYTES * 3 / 4) break;
            total -= f.length();
            f.delete();
        }
    }

    private static byte[] readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            return readAll(in);
        } finally {
            in.close();
        }
    }

    private static void writeFile(File f, byte[] bytes) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(bytes);
        } finally {
            out.close();
        }
        if (!tmp.renameTo(f)) tmp.delete();
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String sha1(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
