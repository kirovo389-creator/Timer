package com.telega.timer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Кнопка, целиком нарисованная на Canvas. Никаких AndroidX/Material —
 * только стандартный android.* SDK.
 *
 * Умеет:
 *  - мгновенно менять подпись ({@link #setLabel(CharSequence)});
 *  - плавно «перетекать» из старого текста в новый ({@link #morphTo(CharSequence, long)});
 *  - плавно менять цвет фона вместе с текстом
 *    ({@link #morphTo(CharSequence, long, int)});
 *  - мягко сжиматься при нажатии.
 */
public class MorphButton extends View {

    /** Слушатель нажатия. */
    public interface TapListener {
        void onTap(MorphButton button);
    }

    /** Тёмно-серый фон по умолчанию (#1C1C22). */
    private static final int DEFAULT_ACCENT = 0xFF1C1C22;
    /** Цвет текста (#F2F2F5). */
    private static final int TEXT_COLOR = 0xFFF2F2F5;

    /** Масштаб кнопки в нажатом состоянии. */
    private static final float PRESS_SCALE = 0.97f;
    /** Масштаб, из которого приходит новый текст и в который уходит старый. */
    private static final float MORPH_EDGE_SCALE = 0.86f;
    /** Насколько «подсветить» фон при нажатии (0..1). */
    private static final float PRESS_BRIGHTEN = 0.14f;
    /** Прозрачность выключенной кнопки. */
    private static final float DISABLED_ALPHA = 0.45f;

    private static final float PREFERRED_TEXT_SP_DP = 22f;
    private static final float MIN_TEXT_SP_DP = 11f;
    private static final float SIDE_PADDING_DP = 20f;
    private static final float DEFAULT_WIDTH_DP = 200f;
    private static final float DEFAULT_HEIGHT_DP = 56f;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaintIncoming = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final ArgbEvaluator argbEvaluator = new ArgbEvaluator();

    /** Что нарисовано сейчас (в покое). */
    private CharSequence currentText = "";
    /** Что должно быть нарисовано после завершения текущего морфа. */
    private CharSequence pendingText = "";
    /** Текст, уходящий в текущем морфе. */
    private CharSequence outgoingText = "";

    private int accentColor = DEFAULT_ACCENT;
    /** Цвет, от которого считаем текущий кадр. */
    private int currentColor = DEFAULT_ACCENT;
    /** Целевой цвет (для морфа и для {@link #setAccentColor(int)}). */
    private int pendingColor = DEFAULT_ACCENT;

    private float morphFraction = 1f;
    private boolean morphing = false;

    private float pressFraction = 0f;

    private ValueAnimator morphAnimator;
    private ValueAnimator pressAnimator;

    private TapListener tapListener;

    private float density = 1f;
    private float preferredTextSizePx;
    private float minTextSizePx;
    private float sidePaddingPx;

    public MorphButton(Context context) {
        super(context);
        init(context);
    }

    public MorphButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        density = context.getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }
        preferredTextSizePx = PREFERRED_TEXT_SP_DP * density;
        minTextSizePx = MIN_TEXT_SP_DP * density;
        sidePaddingPx = SIDE_PADDING_DP * density;

        configureTextPaint(textPaint);
        configureTextPaint(textPaintIncoming);

        bgPaint.setStyle(Paint.Style.FILL);

        setClickable(true);
        setFocusable(true);
    }

    private void configureTextPaint(Paint paint) {
        paint.setColor(TEXT_COLOR);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(preferredTextSizePx);
        paint.setStyle(Paint.Style.FILL);
    }

    // ------------------------------------------------------------------
    // Публичный API
    // ------------------------------------------------------------------

    /** Мгновенно установить текст (без анимации). */
    public void setLabel(CharSequence text) {
        cancelMorph();
        morphing = false;
        morphFraction = 1f;
        currentText = text == null ? "" : text;
        pendingText = currentText;
        outgoingText = currentText;
        invalidate();
    }

    /**
     * Плавно заменить текст за durationMs, не меняя цвет фона.
     * Делегирует в {@link #morphTo(CharSequence, long, int)} с текущим целевым цветом.
     */
    public void morphTo(CharSequence text, long durationMs) {
        morphTo(text, durationMs, pendingColor);
    }

    /**
     * Плавно заменить текст и цвет фона за durationMs.
     * Старый текст: альфа 1 -> 0, масштаб 1 -> 0.86.
     * Новый текст:  альфа 0 -> 1, масштаб 0.86 -> 1.
     * Цвет фона интерполируется через {@link ArgbEvaluator}.
     */
    public void morphTo(CharSequence text, long durationMs, int targetColor) {
        CharSequence next = text == null ? "" : text;

        // Если предыдущий морф ещё идёт — фиксируем его конечное состояние
        // и стартуем новый уже от него (без рывка и без «застревания»).
        cancelMorph();
        if (morphing) {
            currentText = pendingText;
            currentColor = pendingColor;
            morphing = false;
            morphFraction = 1f;
        }

        final CharSequence fromText = currentText;
        final int fromColor = currentColor;

        pendingText = next;
        pendingColor = targetColor;
        outgoingText = fromText;

        boolean textUnchanged = fromText.toString().contentEquals(next);
        if (durationMs <= 0L || (textUnchanged && fromColor == targetColor)) {
            currentText = next;
            currentColor = targetColor;
            pendingText = next;
            morphFraction = 1f;
            morphing = false;
            invalidate();
            return;
        }

        morphFraction = 0f;
        morphing = true;

        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        morphAnimator = animator;
        animator.setDuration(durationMs);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                Object value = animation.getAnimatedValue();
                morphFraction = value instanceof Float ? (Float) value : 1f;
                invalidate();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation != morphAnimator) {
                    return;
                }
                morphAnimator = null;
                currentText = pendingText;
                currentColor = pendingColor;
                outgoingText = currentText;
                morphFraction = 1f;
                morphing = false;
                invalidate();
            }
        });
        animator.start();
        invalidate();
    }

    /** Слушатель нажатия (вызывается на ACTION_UP внутри границ, если кнопка включена). */
    public void setTapListener(TapListener listener) {
        this.tapListener = listener;
    }

    /**
     * Включить/выключить кнопку. Не переопределяет View.setEnabled —
     * просто вызывает super и перерисовывает себя.
     */
    public void setEnabledState(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            cancelPress();
            pressFraction = 0f;
        }
        invalidate();
    }

    /** Установить цвет фона кнопки мгновенно, без анимации. */
    public void setAccentColor(int color) {
        accentColor = color;
        pendingColor = color;
        if (!morphing) {
            currentColor = color;
        }
        invalidate();
    }

    // ------------------------------------------------------------------
    // Измерение и отрисовка
    // ------------------------------------------------------------------

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = (int) (DEFAULT_WIDTH_DP * density + 0.5f);
        int desiredHeight = (int) (DEFAULT_HEIGHT_DP * density + 0.5f);
        setMeasuredDimension(
                resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        final float cx = w / 2f;
        final float cy = h / 2f;
        final float pressScale = 1f - (1f - PRESS_SCALE) * clamp(pressFraction, 0f, 1f);
        final float alphaBase = isEnabled() ? 1f : DISABLED_ALPHA;

        canvas.save();
        canvas.scale(pressScale, pressScale, cx, cy);

        // --- фон ---
        float colorFraction = morphing ? clamp(morphFraction, 0f, 1f) : 1f;
        int bg = blendColors(currentColor, pendingColor, colorFraction);
        bg = brighten(bg, PRESS_BRIGHTEN * clamp(pressFraction, 0f, 1f));
        bg = scaleAlpha(bg, alphaBase);
        bgPaint.setColor(bg);

        float radius = h / 2f;
        rect.set(0f, 0f, w, h);
        canvas.drawRoundRect(rect, radius, radius, bgPaint);

        // --- текст ---
        float maxTextWidth = w - sidePaddingPx * 2f;
        if (maxTextWidth < 1f) {
            maxTextWidth = w;
        }

        if (morphing) {
            float f = clamp(morphFraction, 0f, 1f);
            // Уходящий текст: альфа 1 -> 0, масштаб 1 -> 0.86.
            drawCenteredText(canvas, outgoingText, textPaint, cx, cy, maxTextWidth,
                    (1f - f) * alphaBase, 1f - 0.14f * f);
            // Приходящий текст: альфа 0 -> 1, масштаб 0.86 -> 1.
            drawCenteredText(canvas, pendingText, textPaintIncoming, cx, cy, maxTextWidth,
                    f * alphaBase, MORPH_EDGE_SCALE + 0.14f * f);
        } else {
            drawCenteredText(canvas, currentText, textPaint, cx, cy, maxTextWidth, alphaBase, 1f);
        }

        canvas.restore();
    }

    private void drawCenteredText(Canvas canvas, CharSequence text, Paint paint,
                                  float cx, float cy, float maxWidth,
                                  float alpha, float scale) {
        if (text == null || text.length() == 0 || alpha <= 0.01f) {
            return;
        }
        paint.setTextSize(fitTextSize(paint, text, maxWidth));
        paint.setAlpha((int) (255f * clamp(alpha, 0f, 1f)));

        canvas.save();
        canvas.scale(scale, scale, cx, cy);
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(text, 0, text.length(), cx, baseline, paint);
        canvas.restore();
    }

    /** Подбирает размер шрифта так, чтобы строка помещалась по ширине. */
    private float fitTextSize(Paint paint, CharSequence text, float maxWidth) {
        float size = preferredTextSizePx;
        if (maxWidth <= 0f) {
            return size;
        }
        paint.setTextSize(size);
        float width = paint.measureText(text, 0, text.length());

        int guard = 0;
        while (width > maxWidth && size > minTextSizePx && guard < 12) {
            float next = size * (maxWidth / width) * 0.98f;
            size = Math.max(minTextSizePx, next);
            paint.setTextSize(size);
            width = paint.measureText(text, 0, text.length());
            guard++;
        }
        return size;
    }

    // ------------------------------------------------------------------
    // Ввод
    // ------------------------------------------------------------------

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                animatePress(1f);
                return true;

            case MotionEvent.ACTION_MOVE: {
                boolean inside = isInside(event.getX(), event.getY());
                float target = inside ? 1f : 0f;
                if (Math.abs(pressFraction - target) > 0.01f) {
                    animatePress(target);
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                boolean inside = isInside(event.getX(), event.getY());
                animatePress(0f);
                if (inside) {
                    performClick();
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                animatePress(0f);
                return true;

            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        if (isEnabled() && tapListener != null) {
            tapListener.onTap(this);
        }
        return true;
    }

    private boolean isInside(float x, float y) {
        return x >= 0f && x <= getWidth() && y >= 0f && y <= getHeight();
    }

    private void animatePress(float target) {
        float from = clamp(pressFraction, 0f, 1f);
        float to = clamp(target, 0f, 1f);
        cancelPress();
        if (Math.abs(from - to) < 0.001f) {
            pressFraction = to;
            invalidate();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        pressAnimator = animator;
        animator.setDuration(to > from ? 90L : 150L);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                Object value = animation.getAnimatedValue();
                pressFraction = value instanceof Float ? (Float) value : 0f;
                invalidate();
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animation == pressAnimator) {
                    pressAnimator = null;
                }
            }
        });
        animator.start();
    }

    // ------------------------------------------------------------------
    // Жизненный цикл и утилиты
    // ------------------------------------------------------------------

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        cancelMorph();
        cancelPress();
        morphFraction = 1f;
        morphing = false;
        pressFraction = 0f;
    }

    private void cancelMorph() {
        if (morphAnimator != null) {
            ValueAnimator animator = morphAnimator;
            morphAnimator = null;
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
        }
    }

    private void cancelPress() {
        if (pressAnimator != null) {
            ValueAnimator animator = pressAnimator;
            pressAnimator = null;
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
        }
    }

    private int blendColors(int from, int to, float fraction) {
        if (fraction <= 0f) {
            return from;
        }
        if (fraction >= 1f) {
            return to;
        }
        Object blended = argbEvaluator.evaluate(fraction, Integer.valueOf(from), Integer.valueOf(to));
        return blended instanceof Integer ? (Integer) blended : to;
    }

    /** Осветляет цвет, подтягивая каждую компоненту к 255. */
    private static int brighten(int color, float amount) {
        float a = clamp(amount, 0f, 1f);
        if (a <= 0f) {
            return color;
        }
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        r = r + Math.round((255 - r) * a);
        g = g + Math.round((255 - g) * a);
        b = b + Math.round((255 - b) * a);
        return Color.argb(Color.alpha(color), clamp255(r), clamp255(g), clamp255(b));
    }

    /** Умножает альфу цвета на коэффициент 0..1. */
    private static int scaleAlpha(int color, float factor) {
        float f = clamp(factor, 0f, 1f);
        int alpha = Math.round(Color.alpha(color) * f);
        return Color.argb(clamp255(alpha), Color.red(color), Color.green(color), Color.blue(color));
    }

    private static int clamp255(int value) {
        if (value < 0) {
            return 0;
        }
        return value > 255 ? 255 : value;
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }
}
