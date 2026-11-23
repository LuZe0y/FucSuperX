package io.luze0y.islandglass;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;

import java.lang.reflect.Field;

/**
 * 替换 ContainerAnimatorCommon.mPaint：原子岛处于玻璃材质状态（mBlurVisible=true）时，
 * 把原版的黑色底色 / 黑色渐变改成全透明，让下层系统材质透出来；否则保持原样。
 */
final class GlassPaint extends Paint {
    /** 胶囊缩成摄像头处的小圆点时为 true：保持原版黑色（与挖孔融为一体），不走玻璃。 */
    static volatile boolean dot;

    private final Object animator;
    private final Field blurVisible;

    GlassPaint(Paint src, Object animator, Field blurVisible) {
        super(src);
        this.animator = animator;
        this.blurVisible = blurVisible;
    }

    private boolean active() {
        if (animator == null || blurVisible == null) return false; // 父类构造期间
        if (dot) return false;
        try {
            return blurVisible.getBoolean(animator);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void setColor(int color) {
        super.setColor(active() ? Color.TRANSPARENT : color);
    }

    @Override
    public Shader setShader(Shader shader) {
        if (active()) {
            super.setColor(Color.TRANSPARENT);
            return super.setShader(null);
        }
        return super.setShader(shader);
    }
}
