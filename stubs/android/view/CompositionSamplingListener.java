package android.view;

import android.graphics.Rect;

import java.util.concurrent.Executor;

/** 仅编译期桩：运行时由 framework 的同名隐藏类提供（不打进 dex）。 */
public abstract class CompositionSamplingListener {
    public CompositionSamplingListener(Executor executor) {
        throw new RuntimeException("Stub!");
    }

    public abstract void onSampleCollected(float medianLuma);

    public static void register(CompositionSamplingListener listener, int displayId,
                                SurfaceControl stopLayer, Rect samplingArea) {
        throw new RuntimeException("Stub!");
    }

    public static void unregister(CompositionSamplingListener listener) {
        throw new RuntimeException("Stub!");
    }

    public void destroy() {
        throw new RuntimeException("Stub!");
    }
}
