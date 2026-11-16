package com.grillme.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;

/**
 * 弹窗前的截图。
 *
 * 安卓不允许后台静默截屏，唯一合规的路子是 MediaProjection：由用户在系统弹窗里亲手点一次
 * 「开始」，之后才能自己抓帧。所以这里做成"一次授权、长期使用"：
 * 保持一个低分辨率 VirtualDisplay，只保留最新一帧（不逐帧转 Bitmap，省电），
 * 到该弹窗时才把那一帧转成 JPEG 存下来。
 *
 * 注意 Android 14 起一个 MediaProjection 只允许建一个 VirtualDisplay，所以不能"每次截屏
 * 新建一个"，只能常驻这一个。进程被杀或用户点了系统状态栏的"停止"之后，需要重新授权。
 */
public final class Shots {

    private static Shots inst;

    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private HandlerThread thread;
    private Handler handler;
    private Image kept;
    private Bitmap last;
    private int width;
    private boolean stopping;
    private boolean paused;

    public static synchronized Shots get() {
        if (inst == null) {
            inst = new Shots();
        }
        return inst;
    }

    public boolean ready() {
        return projection != null && display != null;
    }

    public synchronized boolean paused() {
        return paused;
    }

    /**
     * 暂停合成。
     *
     * 投屏管道是常驻的，但只要 ImageReader 那边一直在收帧，屏幕每变一下系统就要
     * 多合成一份副本——实测在带动画的 App 里 SurfaceFlinger 能吃到 80% CPU。
     * 把 VirtualDisplay 的 surface 摘掉，合成端就彻底停了，等快弹窗时再接回来。
     */
    public synchronized void pause() {
        if (display == null || paused) {
            return;
        }
        try {
            display.setSurface(null);
            paused = true;
        } catch (Exception ignored) {
        }
    }

    public synchronized void resume() {
        if (display == null || !paused || reader == null) {
            return;
        }
        try {
            display.setSurface(reader.getSurface());
            paused = false;
        } catch (Exception ignored) {
        }
    }

    public int width() {
        return width;
    }

    /** 拿到用户授权后建立投屏。必须在带 mediaProjection 类型的前台服务里调用。 */
    public synchronized boolean start(Context c, int code, Intent data) {
        stop();
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    c.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null || data == null) {
                return false;
            }
            projection = mpm.getMediaProjection(code, data);
            if (projection == null) {
                return false;
            }
            thread = new HandlerThread("grill-shot");
            thread.start();
            handler = new Handler(thread.getLooper());
            // Android 14 要求先注册回调再建 VirtualDisplay
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stop();
                }
            }, handler);

            DisplayMetrics dm = new DisplayMetrics();
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            wm.getDefaultDisplay().getRealMetrics(dm);
            int sw = Math.max(1, dm.widthPixels);
            int sh = Math.max(1, dm.heightPixels);
            int target = Cfg.get(c).shotWidth();
            width = Math.min(sw, target);
            int height = Math.max(1, (int) ((long) sh * width / sw));

            // 3 帧而不是 2 帧：我们常驻持有最新一帧（kept），只留 1 个空位的话，
            // 只要监听线程稍微晚一拍，BufferQueue 就没有可用的 buffer，投屏的生产端
            // （SurfaceFlinger 那条合成线程）会被反压卡住——表现出来就是整机卡顿，
            // 连带我们自己的 Handler 也跟着停。多留一个缓冲，给系统一个喘息位。
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    Image img = null;
                    try {
                        img = r.acquireLatestImage();
                    } catch (Exception ignored) {
                    }
                    if (img == null) {
                        return;
                    }
                    // 锁只包住"换个引用"这一步。以前整段 grab() 都是 synchronized，
                    // 主线程在转 Bitmap 的时候监听线程一直等锁，帧就越堆越多。
                    Image old;
                    synchronized (Shots.this) {
                        old = kept;
                        kept = img;
                    }
                    if (old != null) {
                        try {
                            old.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, handler);

            display = projection.createVirtualDisplay("grill", width, height, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
            return display != null;
        } catch (Exception e) {
            stop();
            return false;
        }
    }

    /**
     * 取一张图。调用方负责 recycle。
     *
     * 投屏只在画面变化时出帧：你要是盯着静态页面看十分钟，队列里一个帧都没有。
     * 所以这里保留"最近成功转换的一帧"，没有新帧时用它兜底，免得弹窗时抓空。
     */
    public Bitmap grab() {
        Image img;
        synchronized (this) {
            img = kept;
            kept = null;
            if (img == null) {
                return last == null ? null : last.copy(Bitmap.Config.ARGB_8888, false);
            }
        }
        try {
            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int w = img.getWidth();
            int h = img.getHeight();
            int bmpW = pixelStride > 0 ? rowStride / pixelStride : w;
            Bitmap raw = Bitmap.createBitmap(bmpW, h, Bitmap.Config.ARGB_8888);
            buf.rewind();
            raw.copyPixelsFromBuffer(buf);
            if (bmpW != w) {
                Bitmap cut = Bitmap.createBitmap(raw, 0, 0, w, h);
                raw.recycle();
                raw = cut;
            }
            synchronized (this) {
                if (last != null && last != raw) {
                    last.recycle();
                }
                last = raw;
                return raw.copy(Bitmap.Config.ARGB_8888, false);
            }
        } catch (Exception e) {
            return null;
        } finally {
            try {
                img.close();
            } catch (Exception ignored) {
            }
        }
    }

    public synchronized void stop() {
        if (stopping) {
            return;
        }
        stopping = true;
        try {
            if (kept != null) {
                kept.close();
            }
        } catch (Exception ignored) {
        }
        kept = null;
        try {
            if (last != null) {
                last.recycle();
            }
        } catch (Exception ignored) {
        }
        last = null;
        try {
            if (display != null) {
                display.release();
            }
        } catch (Exception ignored) {
        }
        display = null;
        try {
            if (reader != null) {
                reader.close();
            }
        } catch (Exception ignored) {
        }
        reader = null;
        try {
            if (projection != null) {
                projection.stop();
            }
        } catch (Exception ignored) {
        }
        projection = null;
        try {
            if (thread != null) {
                thread.quitSafely();
            }
        } catch (Exception ignored) {
        }
        thread = null;
        handler = null;
        stopping = false;
        paused = false;
    }

    // ------------------------------------------------------------ 存盘与读取

    /**
     * 申请投屏授权。API 34 起可以直接告诉系统"我要整屏"，
     * 免得 ROM 默认按"单个应用"来录，抓到的图只有某一个 App。
     */
    public static void requestPermission(android.app.Activity a, int reqCode) {
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                return;
            }
            Intent i;
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                i = mpm.createScreenCaptureIntent(
                        android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay());
            } else {
                i = mpm.createScreenCaptureIntent();
            }
            a.startActivityForResult(i, reqCode);
        } catch (Exception e) {
            Ui.toast(a, String.valueOf(e.getMessage()));
        }
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "shots");
        if (!d.exists()) {
            d.mkdirs();
        }
        return d;
    }

    public static File fileOf(Context c, String name) {
        if (name == null || name.length() == 0) {
            return null;
        }
        File f = new File(dir(c), name);
        return f.exists() ? f : null;
    }

    /** 存成 JPEG，返回文件名（不含目录）。失败返回空串。 */
    public static String save(Context c, Bitmap bmp, String id, int width, int quality) {
        if (bmp == null) {
            return "";
        }
        Bitmap out = bmp;
        try {
            if (bmp.getWidth() > width) {
                int h = Math.max(1, bmp.getHeight() * width / bmp.getWidth());
                out = Bitmap.createScaledBitmap(bmp, width, h, true);
            }
            File f = new File(dir(c), id + ".jpg");
            FileOutputStream fos = new FileOutputStream(f);
            out.compress(Bitmap.CompressFormat.JPEG, quality, fos);
            fos.flush();
            fos.close();
            return f.getName();
        } catch (Exception e) {
            return "";
        } finally {
            if (out != bmp) {
                out.recycle();
            }
            bmp.recycle();
        }
    }

    public static Bitmap decode(File f, int targetW) {
        if (f == null || !f.exists()) {
            return null;
        }
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
            int sample = 1;
            while (probe.outWidth / (sample * 2) >= targetW) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Exception e) {
            return null;
        }
    }

    /** 按保留天数清理旧图。days<=0 表示不自动删。 */
    public static int cleanup(Context c, int days) {
        if (days <= 0) {
            return 0;
        }
        int n = 0;
        try {
            File[] files = dir(c).listFiles();
            if (files == null) {
                return 0;
            }
            long edge = System.currentTimeMillis() - days * 86400000L;
            for (File f : files) {
                if (f.isFile() && f.lastModified() < edge && f.delete()) {
                    n++;
                }
            }
        } catch (Exception ignored) {
        }
        return n;
    }
}
