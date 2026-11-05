/*
 * This file is part of LibreTorrent.
 *
 * LibreTorrent is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LibreTorrent is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LibreTorrent.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.proninyaroslav.libretorrent.core.model.remote;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.nanohttpd.NanoHTTPD;
import org.proninyaroslav.libretorrent.R;
import org.proninyaroslav.libretorrent.core.exception.TorrentAlreadyExistsException;
import org.proninyaroslav.libretorrent.core.model.AddTorrentParams;
import org.proninyaroslav.libretorrent.core.model.TorrentEngine;
import org.proninyaroslav.libretorrent.core.model.data.MagnetInfo;
import org.proninyaroslav.libretorrent.core.model.data.Priority;
import org.proninyaroslav.libretorrent.core.model.data.TorrentInfo;
import org.proninyaroslav.libretorrent.core.model.data.metainfo.TorrentMetaInfo;
import org.proninyaroslav.libretorrent.core.system.FileSystemFacade;
import org.proninyaroslav.libretorrent.core.system.SystemFacadeHelper;
import org.proninyaroslav.libretorrent.core.utils.Utils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * Small HTTP server that lets a phone (or any device on the same network)
 * control the torrent engine from a web browser: list torrents, add magnet
 * links or .torrent files, pause, resume and delete.
 *
 * Access is protected by HTTP Basic authentication (any user name, the password
 * set in the settings). State-changing requests must also carry the
 * "X-Requested-With: LibreTorrentRemote" header, which a third-party web page
 * cannot add without a CORS preflight this server never answers.
 */

public class RemoteControlServer extends NanoHTTPD {
    private static final String TAG = RemoteControlServer.class.getSimpleName();

    public static final int DEFAULT_PORT = 8810;
    private static final String MIME_JSON = "application/json; charset=utf-8";
    private static final String MIME_HTML_UTF8 = "text/html; charset=utf-8";
    private static final String CSRF_HEADER = "x-requested-with";
    private static final String CSRF_VALUE = "LibreTorrentRemote";
    private static final String REALM = "LibreTorrent";
    private static final long MAX_TORRENT_FILE_SIZE = 10L * 1024 * 1024;

    private final Context appContext;
    private final String password;
    private TorrentEngine engine;
    private FileSystemFacade fs;

    public RemoteControlServer(@NonNull Context appContext, int port, @NonNull String password) {
        /* Listen on all interfaces so devices on the local network can reach it */
        super(null, port);
        this.appContext = appContext;
        this.password = password;
    }

    @Override
    public void start() throws IOException {
        Log.i(TAG, "Start " + TAG + " on port " + getListeningPort());
        engine = TorrentEngine.getInstance(appContext);
        fs = SystemFacadeHelper.getFileSystemFacade(appContext);
        super.start(SOCKET_READ_TIMEOUT, true);
    }

    @Override
    public void stop() {
        super.stop();
        Log.i(TAG, "Stop " + TAG);
    }

    /*
     * Returns the addresses (e.g. http://192.168.1.20:8810) at which the server
     * can be reached from the local network.
     */

    @NonNull
    public static List<String> getLocalUrls(int port) {
        ArrayList<String> urls = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback())
                    continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress())
                        urls.add("http://" + addr.getHostAddress() + ":" + port);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to list network interfaces: " + Log.getStackTraceString(e));
        }

        return urls;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if (!isAuthorized(session)) {
                Response res = newFixedLengthResponse(Response.Status.UNAUTHORIZED,
                        MIME_PLAINTEXT, "Authentication required");
                res.addHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
                return res;
            }

            String uri = session.getUri();
            Method method = session.getMethod();

            if (Method.GET.equals(method)) {
                if (uri.equals("/") || uri.equals("/index.html"))
                    return serveIndex();
                if (uri.equals("/api/torrents"))
                    return serveTorrentList();
                return error(Response.Status.NOT_FOUND, "Not found");
            }

            if (Method.POST.equals(method)) {
                if (!CSRF_VALUE.equals(session.getHeaders().get(CSRF_HEADER)))
                    return error(Response.Status.FORBIDDEN, "Missing request header");

                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                Map<String, String> params = session.getParms();

                switch (uri) {
                    case "/api/add":
                        return handleAdd(params, files);
                    case "/api/action":
                        return handleAction(params);
                    case "/api/all":
                        return handleAll(params);
                    default:
                        return error(Response.Status.NOT_FOUND, "Not found");
                }
            }

            return error(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed");

        } catch (Throwable e) {
            Log.e(TAG, Log.getStackTraceString(e));
            return error(Response.Status.INTERNAL_ERROR, e.getMessage() == null ? "Error" : e.getMessage());
        }
    }

    private boolean isAuthorized(IHTTPSession session) {
        if (TextUtils.isEmpty(password))
            return false;

        String header = session.getHeaders().get("authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6))
            return false;

        String decoded;
        try {
            decoded = new String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        int colon = decoded.indexOf(':');
        if (colon < 0)
            return false;
        String given = decoded.substring(colon + 1);

        /* Constant-time comparison */
        return MessageDigest.isEqual(
                given.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
    }

    private Response serveIndex() throws IOException {
        try (InputStream is = appContext.getResources().openRawResource(R.raw.remote_index)) {
            String html = new String(readAll(is, Long.MAX_VALUE), StandardCharsets.UTF_8);
            Response res = newFixedLengthResponse(Response.Status.OK, MIME_HTML_UTF8, html);
            res.addHeader("Cache-Control", "no-store");
            return res;
        }
    }

    private Response serveTorrentList() throws JSONException {
        JSONArray list = new JSONArray();
        for (TorrentInfo info : engine.makeInfoListSync()) {
            if (info == null)
                continue;
            JSONObject o = new JSONObject();
            o.put("id", info.torrentId);
            o.put("name", info.name);
            o.put("state", info.stateCode == null ? "UNKNOWN" : info.stateCode.name());
            o.put("progress", info.progress);
            o.put("size", info.totalBytes);
            o.put("received", info.receivedBytes);
            o.put("uploaded", info.uploadedBytes);
            o.put("down", info.downloadSpeed);
            o.put("up", info.uploadSpeed);
            o.put("eta", info.ETA >= TorrentInfo.MAX_ETA ? -1 : info.ETA);
            o.put("peers", info.peers);
            o.put("totalPeers", info.totalPeers);
            o.put("added", info.dateAdded);
            if (!TextUtils.isEmpty(info.error))
                o.put("error", info.error);
            list.put(o);
        }

        JSONObject root = new JSONObject();
        root.put("torrents", list);
        root.put("running", engine.isRunning());
        long free = getFreeSpace();
        if (free >= 0)
            root.put("freeSpace", free);

        return json(root);
    }

    private long getFreeSpace() {
        try {
            Uri path = Utils.getTorrentDownloadPath(appContext);
            if (path != null)
                return fs.getDirAvailableBytes(path);
        } catch (Exception e) {
            /* Ignore */
        }
        return -1;
    }

    private Response handleAdd(Map<String, String> params, Map<String, String> files)
            throws JSONException {
        if (!engine.isRunning())
            return error(Response.Status.CONFLICT, "Torrent engine is not running");

        Uri downloadPath = Utils.getTorrentDownloadPath(appContext);
        if (downloadPath == null)
            return error(Response.Status.INTERNAL_ERROR, "No download folder");

        ArrayList<String> added = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();

        /* Magnet links, one per line */
        String magnets = params.get("magnet");
        if (!TextUtils.isEmpty(magnets)) {
            for (String line : magnets.split("\\s+")) {
                String link = line.trim();
                if (link.isEmpty())
                    continue;
                try {
                    added.add(addMagnet(link, downloadPath));
                } catch (Exception e) {
                    errors.add(describeError(link, e));
                }
            }
        }

        /* Uploaded .torrent files: "torrent", then "torrent2", "torrent3"... */
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String key = entry.getKey();
            String tmpPath = entry.getValue();
            if (key == null || !key.startsWith("torrent") || TextUtils.isEmpty(tmpPath))
                continue;
            try {
                added.add(addTorrentFile(new File(tmpPath), downloadPath));
            } catch (Exception e) {
                errors.add(describeError(key.equals("torrent") ? ".torrent" : key, e));
            }
        }

        if (added.isEmpty() && errors.isEmpty())
            return error(Response.Status.BAD_REQUEST, "Nothing to add");

        JSONObject root = new JSONObject();
        root.put("added", new JSONArray(added));
        root.put("errors", new JSONArray(errors));
        return json(added.isEmpty() ? Response.Status.BAD_REQUEST : Response.Status.OK, root);
    }

    private String addMagnet(String link, Uri downloadPath) throws Exception {
        if (!link.startsWith(Utils.MAGNET_PREFIX))
            throw new IllegalArgumentException("Not a magnet link");

        MagnetInfo info = engine.parseMagnet(link);
        String hash = info.getSha1hash();
        String name = TextUtils.isEmpty(info.getName()) ? hash : info.getName();

        AddTorrentParams p = new AddTorrentParams(
                link,
                true,
                hash,
                name,
                null,
                downloadPath,
                false,
                false,
                new ArrayList<>(),
                false
        );
        engine.addTorrentSync(p, false);

        return name;
    }

    private String addTorrentFile(File uploaded, Uri downloadPath) throws Exception {
        if (uploaded.length() > MAX_TORRENT_FILE_SIZE)
            throw new IllegalArgumentException("File too large");

        byte[] data;
        try (FileInputStream is = new FileInputStream(uploaded)) {
            data = readAll(is, MAX_TORRENT_FILE_SIZE);
        }
        TorrentMetaInfo info = new TorrentMetaInfo(data);

        long free = fs.getDirAvailableBytes(downloadPath);
        if (free >= 0 && free < info.torrentSize)
            throw new IOException("Not enough free space");

        /* The upload is deleted after the request, so keep our own copy */
        File tmp = fs.makeTempFile(".torrent");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(data);
        }

        Priority[] priorities = new Priority[info.fileList.size()];
        Arrays.fill(priorities, Priority.DEFAULT);

        AddTorrentParams p = new AddTorrentParams(
                Uri.fromFile(tmp).toString(),
                false,
                info.sha1Hash,
                info.torrentName,
                priorities,
                downloadPath,
                false,
                false,
                new ArrayList<>(),
                false
        );
        engine.addTorrentSync(p, true);

        return info.torrentName;
    }

    private String describeError(String what, Exception e) {
        if (e instanceof TorrentAlreadyExistsException)
            return what + ": already added";
        String msg = e.getMessage();
        return what + ": " + (TextUtils.isEmpty(msg) ? e.getClass().getSimpleName() : msg);
    }

    private Response handleAction(Map<String, String> params) throws JSONException {
        String id = params.get("id");
        String action = params.get("action");
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(action))
            return error(Response.Status.BAD_REQUEST, "Missing id or action");

        if (engine.makeInfoSync(id) == null)
            return error(Response.Status.NOT_FOUND, "Unknown torrent");

        switch (action) {
            case "toggle":
                engine.pauseResumeTorrent(id);
                break;
            case "delete":
                engine.deleteTorrents(Collections.singletonList(id), false);
                break;
            case "deleteFiles":
                engine.deleteTorrents(Collections.singletonList(id), true);
                break;
            case "recheck":
                engine.forceRecheckTorrents(Collections.singletonList(id));
                break;
            default:
                return error(Response.Status.BAD_REQUEST, "Unknown action");
        }

        return ok();
    }

    private Response handleAll(Map<String, String> params) throws JSONException {
        String action = params.get("action");
        if ("pause".equals(action))
            engine.pauseAll();
        else if ("resume".equals(action))
            engine.resumeAll();
        else
            return error(Response.Status.BAD_REQUEST, "Unknown action");

        return ok();
    }

    private static byte[] readAll(InputStream is, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = is.read(buf)) != -1) {
            total += n;
            if (total > limit)
                throw new IOException("File too large");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private Response ok() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ok", true);
        return json(o);
    }

    private Response json(JSONObject o) {
        return json(Response.Status.OK, o);
    }

    private Response json(Response.Status status, JSONObject o) {
        Response res = newFixedLengthResponse(status, MIME_JSON, o.toString());
        res.addHeader("Cache-Control", "no-store");
        return res;
    }

    private Response error(Response.Status status, @Nullable String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", message == null ? status.getDescription() : message);
        } catch (JSONException e) {
            /* Ignore */
        }
        return json(status, o);
    }
}
