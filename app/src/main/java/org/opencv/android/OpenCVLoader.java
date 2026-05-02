package org.opencv.android;

import android.content.Context;
import android.util.Log;

/**
 * 极简OpenCV初始化 — 直接加载so
 */
public class OpenCVLoader {
    private static final String TAG = "OpenCVLoader";
    private static boolean sLoaded = false;

    public static boolean initDebug() {
        return initLocal();
    }

    public static boolean initLocal() {
        if (sLoaded) return true;
        try {
            System.loadLibrary("opencv_java4");
            sLoaded = true;
            Log.i(TAG, "OpenCV loaded successfully");
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load OpenCV: " + e.getMessage());
            return false;
        }
    }

    public static boolean initAsync(String version, Context context, LoaderCallbackInterface callback) {
        boolean ok = initLocal();
        if (callback != null) {
            if (ok) callback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
            else callback.onManagerConnected(LoaderCallbackInterface.INIT_FAILED);
        }
        return ok;
    }

    public interface LoaderCallbackInterface {
        int SUCCESS = 0;
        int INIT_FAILED = 255;
        void onManagerConnected(int status);
    }
}
