package com.ds2api.android;

import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 进程日志的内存环形缓冲，供 Service 写入、Activity 订阅显示。 */
public final class LogStore {

    public interface Listener {
        void onAppended();
    }

    private static final int MAX_CHARS = 512 * 1024; // 约 50 万字符，超出裁剪旧内容
    private static final LogStore INSTANCE = new LogStore();

    public static LogStore get() {
        return INSTANCE;
    }

    private final StringBuilder buffer = new StringBuilder();
    private final List<Listener> listeners = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private LogStore() {
    }

    public synchronized void log(String tag, String msg) {
        buffer.append('[').append(fmt.format(new Date())).append("] [").append(tag).append("] ")
                .append(msg).append('\n');
        if (buffer.length() > MAX_CHARS) {
            buffer.delete(0, buffer.length() - MAX_CHARS * 3 / 4);
            buffer.insert(0, "...(已裁剪早期日志)...\n");
        }
        notifyListeners();
    }

    /** 追加原始输出行（来自子进程 stdout/stderr，已自带时间戳）。 */
    public synchronized void raw(String line) {
        buffer.append(line).append('\n');
        if (buffer.length() > MAX_CHARS) {
            buffer.delete(0, buffer.length() - MAX_CHARS * 3 / 4);
        }
        notifyListeners();
    }

    public synchronized String snapshot() {
        return buffer.toString();
    }

    public synchronized void clear() {
        buffer.setLength(0);
        notifyListeners();
    }

    public synchronized void addListener(Listener l) {
        if (!listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public synchronized void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyListeners() {
        final List<Listener> copy;
        synchronized (this) {
            copy = new ArrayList<>(listeners);
        }
        main.post(() -> {
            for (Listener l : copy) {
                l.onAppended();
            }
        });
    }
}
