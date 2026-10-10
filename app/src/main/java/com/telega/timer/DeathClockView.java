package com.telega.timer;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * «Часы смерти»: ряд барабанов-цифр как в слот-машине плюс набирающаяся
 * красно-тёмная жидкость с двумя бегущими волнами на поверхности.
 *
 * <p>Порядок отрисовки: фон → все цифры в тусклом цвете → (внутри клипа по
 * контуру жидкости) те же цифры ярко-красным → сама жидкость с градиентом и
 * светлой кромкой волны. Жидкость «окрашивает» цифры по мере подъёма.</p>
 *
 * <p>Только стандартный Android SDK, без AndroidX. Все аниматоры живут в полях
 * и отменяются в {@link #onDetachedFromWindow()}.</p>
 */
public class DeathClockView extends View {

    private static final String DIGITS = "0123456789";

    // ---- цвета ----
    private static final int COLOR_BG = 0xFF0B0B0F;
    private static final int COLOR_DIM = 0xFF8A8A93;
    private static final int COLOR_BRIGHT = 0xFFFF2A2A;
    private static final int COLOR_WATER_TOP = 0xFFC41111;
    private static final int COLOR_WATER_BOTTOM = 0xFF1A0303;
    private static final int COLOR_WAVE_EDGE = 0xFFFF5A4A;

    // ---- константы оформления ----
    private static final int WATER_ALPHA = 172;          // 150..190 — цифры просвечивают
    private static final int WAVE_EDGE_ALPHA = 170;
    private static final int GLOW_TEXT_ALPHA = 110;
    private static final int GLOW_DOT_ALPHA = 80;
    private static final float DIGIT_HEIGHT_RATIO = 1.45f;
    private static final float MAX_VISIBLE_OFFSET = 1.9f; // сколько цифр видно над/под окном
    private static final int WAVE_STEPS = 120;
    private static final float WAVE_AMP_1 = 0.018f;       // A1 ≈ height*0.018
    private static final float WAVE_AMP_2 = 0.010f;       // A2 ≈ height*0.010
    private static final float WAVE_LEN_1 = 0.55f;        // k1 ≈ 2π/(width*0.55)
    private static final float WAVE_LEN_2 = 0.28f;        // k2 ≈ 2π/(width*0.28)
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final long WAVE_CYCLE_MS = 2400L;
    private static final long TICK_MS = 220L;
    private static final float WATER_EPS = 0.0005f;

    // ---- paints (создаются один раз, в onDraw не аллоцируем) ----
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint brightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint waterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // ---- переиспользуемые объекты графики ----
    private final Path waterPath = new Path();
    private final Path wavePath = new Path();
    private final Rect glyphBounds = new Rect();
    private final Matrix gradientMatrix = new Matrix();
    private Shader waterShader;

    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    private final Interpolator spinInterpolator = new DecelerateInterpolator();
    private final Interpolator tickInterpolator = new DecelerateInterpolator();
    private final Interpolator waterInterpolator = new AccelerateDecelerateInterpolator();
    private final Interpolator waveInterpolator = new LinearInterpolator();

    // ---- барабаны ----
    private final List<Reel> reels = new ArrayList<Reel>();

    // ---- геометрия ----
    private float textSize = 48f;
    private float digitHeight = 64f;
    private float baselineOffset = 0f;
    private float slotWidth = 32f;
    private float dotRadius = 3f;
    private float centerY = 0f;

    // ---- жидкость ----
    private float waterLevel = 0f;
    private float phase1 = 0f;
    private float phase2 = 0f;

    // ---- аниматоры ----
    private final List<ValueAnimator> reelAnimators = new ArrayList<ValueAnimator>();
    private ValueAnimator waterAnimator;
    private ValueAnimator waveAnimator;
    private int reelGeneration = 0;
    private int waterGeneration = 0;
    private Runnable pendingSpinDone;

    public DeathClockView(Context context) {
        this(context, null);
    }

    public DeathClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPaints();
        setValueInstant("00:00:00");
    }

    // ======================================================================
    //  Публичный API
    // ======================================================================

    /** Мгновенно показать значение без анимации. Формат: "00:00:00" */
    public void setValueInstant(String text) {
        if (!isOnUiThread()) {
            final String value = text;
            post(new Runnable() {
                @Override
                public void run() {
                    setValueInstant(value);
                }
            });
            return;
        }
        cancelReelAnimators(false);
        rebuildReels(text, true);
        invalidate();
    }

    /**
     * Запустить барабаны: перестроить набор под targetText, раскрутить и остановить по очереди.
     *
     * @param targetText например "411234:07:21" или "00:00:00"
     * @param spinMs     длительность вращения одного барабана (например 2600)
     * @param staggerMs  задержка между остановками соседних барабанов (например 180)
     * @param onDone     вызов на UI-потоке, когда ВСЕ барабаны остановились
     */
    public void spinTo(final String targetText, final long spinMs, final long staggerMs, final Runnable onDone) {
        if (!isOnUiThread()) {
            post(new Runnable() {
                @Override
                public void run() {
                    spinTo(targetText, spinMs, staggerMs, onDone);
                }
            });
            return;
        }

        cancelReelAnimators(true);
        rebuildReels(targetText, false);
        final int generation = reelGeneration;
        pendingSpinDone = onDone;

        final List<Reel> spinning = new ArrayList<Reel>();
        for (int i = 0; i < reels.size(); i++) {
            Reel reel = reels.get(i);
            if (reel.digit >= 0) {
                spinning.add(reel);
            }
        }
        if (spinning.isEmpty()) {
            pendingSpinDone = null;
            postOnUi(onDone);
            invalidate();
            return;
        }

        final int[] remaining = new int[] { spinning.size() };
        final long duration = Math.max(1L, spinMs);
        final long stagger = Math.max(0L, staggerMs);

        for (int i = 0; i < spinning.size(); i++) {
            final Reel reel = spinning.get(i);
            final float start = reel.pos;
            final int turns = 7 + (i % 4);                     // 7..10 полных оборотов
            float end = (float) (Math.floor(start / 10.0) * 10.0) + turns * 10f + reel.digit;
            while (end - start < turns * 10f) {
                end += 10f;
            }
            final float endPos = end;

            ValueAnimator animator = ValueAnimator.ofFloat(start, endPos);
            animator.setDuration(duration);
            animator.setStartDelay(stagger * i);
            animator.setInterpolator(spinInterpolator);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    if (generation != reelGeneration) {
                        return;
                    }
                    reel.pos = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (generation != reelGeneration) {
                        return;
                    }
                    reel.pos = endPos;
                    invalidate();
                    remaining[0]--;
                    if (remaining[0] <= 0) {
                        Runnable done = pendingSpinDone;
                        pendingSpinDone = null;
                        postOnUi(done);
                    }
                }
            });
            reelAnimators.add(animator);
            animator.start();
        }
    }

    /**
     * Обновить значение без вращения (тикающий отсчёт раз в секунду).
     * Изменившиеся цифры анимируются коротким сдвигом ~220 мс.
     */
    public void tickTo(final String text) {
        if (!isOnUiThread()) {
            final String value = text;
            post(new Runnable() {
                @Override
                public void run() {
                    tickTo(value);
                }
            });
            return;
        }

        cancelReelAnimators(true);
        rebuildReels(text, false);
        final int generation = reelGeneration;

        for (int i = 0; i < reels.size(); i++) {
            final Reel reel = reels.get(i);
            if (reel.digit < 0) {
                continue;
            }
            final float base = Math.round(reel.pos);
            final int shown = (((int) base % 10) + 10) % 10;
            if (shown == reel.digit) {
                if (reel.pos != base) {
                    reel.pos = base;
                    invalidate();
                }
                continue;
            }
            int delta = reel.digit - shown;          // кратчайший путь по барабану
            if (delta > 5) {
                delta -= 10;
            } else if (delta < -5) {
                delta += 10;
            }
            final float start = reel.pos;
            final float end = base + delta;

            ValueAnimator animator = ValueAnimator.ofFloat(start, end);
            animator.setDuration(TICK_MS);
            animator.setInterpolator(tickInterpolator);
            animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    if (generation != reelGeneration) {
                        return;
                    }
                    reel.pos = (Float) animation.getAnimatedValue();
                    invalidate();
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (generation != reelGeneration) {
                        return;
                    }
                    reel.pos = end;
                    invalidate();
                }
            });
            reelAnimators.add(animator);
            animator.start();
        }
    }

    /**
     * Запустить заливку жидкостью снизу вверх.
     *
     * @param durationMs длительность заливки (например 2400)
     * @param onDone     вызов на UI-потоке по завершении
     */
    public void startWater(final long durationMs, final Runnable onDone) {
        if (!isOnUiThread()) {
            post(new Runnable() {
                @Override
                public void run() {
                    startWater(durationMs, onDone);
                }
            });
            return;
        }

        cancelWaterAnimator();
        startWaveAnimator();
        final int generation = waterGeneration;

        waterLevel = 0f;
        invalidate();

        waterAnimator = ValueAnimator.ofFloat(0f, 1f);
        waterAnimator.setDuration(Math.max(1L, durationMs));
        waterAnimator.setInterpolator(waterInterpolator);
        waterAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (generation != waterGeneration) {
                    return;
                }
                waterLevel = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        waterAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean finished = false;

            @Override
            public void onAnimationEnd(Animator animation) {
                if (finished || generation != waterGeneration) {
                    return;
                }
                finished = true;
                waterLevel = 1f;
                invalidate();
                postOnUi(onDone);
            }
        });
        waterAnimator.start();
    }

    /** Полный сброс: цифры тусклые, жидкости нет, всё в начальном состоянии. */
    public void reset() {
        if (!isOnUiThread()) {
            post(new Runnable() {
                @Override
                public void run() {
                    reset();
                }
            });
            return;
        }

        cancelReelAnimators(false);
        cancelWaterAnimator();
        stopWaveAnimator();

        waterLevel = 0f;
        phase1 = 0f;
        phase2 = 0f;

        for (int i = 0; i < reels.size(); i++) {
            Reel reel = reels.get(i);
            if (reel.digit >= 0) {
                reel.pos = reel.digit;
            }
        }
        invalidate();
    }

    // ======================================================================
    //  Жизненный цикл View
    // ======================================================================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerY = getPaddingTop() + (h - getPaddingTop() - getPaddingBottom()) * 0.5f;
        waterShader = new LinearGradient(
                0f, 0f, 0f, Math.max(1f, h),
                COLOR_WATER_TOP, COLOR_WATER_BOTTOM,
                Shader.TileMode.CLAMP);
        waterPaint.setShader(waterShader);
        recomputeTextSize();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (waterLevel > WATER_EPS) {
            startWaveAnimator();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelReelAnimators(false);
        cancelWaterAnimator();
        stopWaveAnimator();
        super.onDetachedFromWindow();
    }

    // ======================================================================
    //  Отрисовка
    // ======================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(COLOR_BG);

        // 1) все символы в тусклом цвете
        drawSymbols(canvas, dimPaint, null, COLOR_DIM, 255);

        if (waterLevel > WATER_EPS) {
            buildWaterPaths();
            if (!waterPath.isEmpty()) {
                // 2) те же цифры ярко-красным со «свечением», но только под жидкостью
                int save = canvas.save();
                canvas.clipPath(waterPath);
                drawSymbols(canvas, brightPaint, glowPaint, COLOR_BRIGHT, 255);
                canvas.restoreToCount(save);

                // 3) сама жидкость: градиент + светлая кромка волны
                if (waterShader != null) {
                    gradientMatrix.setTranslate(0f, waterTopY());
                    waterShader.setLocalMatrix(gradientMatrix);
                }
                canvas.drawPath(waterPath, waterPaint);
                canvas.drawPath(wavePath, edgePaint);
            }
        }
    }

    /**
     * Рисует все символы одним проходом (цифры — барабанами, разделители — точками).
     *
     * @param glow если не null — второй проход «свечением» (stroke) перед заливкой
     */
    private void drawSymbols(Canvas canvas, Paint textPaint, Paint glow, int dotColor, int dotAlpha) {
        dotPaint.setColor(dotColor);
        final int count = reels.size();
        for (int i = 0; i < count; i++) {
            Reel reel = reels.get(i);
            float cx = getPaddingLeft() + slotWidth * (i + 0.5f);
            if (reel.digit >= 0) {
                drawReel(canvas, reel, cx, textPaint, glow);
            } else {
                drawSeparator(canvas, reel.ch, cx, dotAlpha, glow != null);
            }
        }
    }

    /** Один барабан: лента цифр 0..9 в окне высотой в одну цифру. */
    private void drawReel(Canvas canvas, Reel reel, float cx, Paint textPaint, Paint glow) {
        final float pos = reel.pos;
        final int base = (int) Math.floor(pos);
        for (int k = -2; k <= 3; k++) {
            final int index = base + k;
            float offset = index - pos;
            float distance = offset < 0f ? -offset : offset;
            if (distance >= MAX_VISIBLE_OFFSET) {
                continue;
            }
            // затухание к краям окна — эффект глубины барабана
            float fade = 1f - distance / MAX_VISIBLE_OFFSET;
            fade = fade * fade * (3f - 2f * fade);
            int alpha = (int) (fade * 255f);
            if (alpha <= 2) {
                continue;
            }
            int digit = ((index % 10) + 10) % 10;
            float y = centerY + offset * digitHeight + baselineOffset;

            textPaint.setAlpha(alpha);
            if (glow != null) {
                // имитация свечения без setShadowLayer: обводка с малой альфой
                glow.setAlpha((int) (fade * GLOW_TEXT_ALPHA));
                canvas.drawText(DIGITS, digit, digit + 1, cx, y, glow);
            }
            canvas.drawText(DIGITS, digit, digit + 1, cx, y, textPaint);
        }
    }

    /** Разделитель: ':' — две точки, '.' — одна, ' ' — пусто, прочее — короткая черта. */
    private void drawSeparator(Canvas canvas, char ch, float cx, int alpha, boolean glow) {
        dotPaint.setAlpha(alpha);
        if (ch == ' ') {
            return;
        }
        if (ch == ':') {
            float gap = digitHeight * 0.30f;
            if (glow) {
                dotPaint.setAlpha(GLOW_DOT_ALPHA);
                canvas.drawCircle(cx, centerY - gap, dotRadius * 1.9f, dotPaint);
                canvas.drawCircle(cx, centerY + gap, dotRadius * 1.9f, dotPaint);
                dotPaint.setAlpha(alpha);
            }
            canvas.drawCircle(cx, centerY - gap, dotRadius, dotPaint);
            canvas.drawCircle(cx, centerY + gap, dotRadius, dotPaint);
        } else if (ch == '.') {
            canvas.drawCircle(cx, centerY + digitHeight * 0.30f, dotRadius, dotPaint);
        } else {
            float half = slotWidth * 0.22f;
            float thickness = Math.max(dp(1.5f), digitHeight * 0.06f);
            canvas.drawRect(cx - half, centerY - thickness * 0.5f, cx + half, centerY + thickness * 0.5f, dotPaint);
        }
    }

    // ======================================================================
    //  Жидкость
    // ======================================================================

    private float waterTopY() {
        return getHeight() * (1f - waterLevel);
    }

    /** Контур жидкости (замкнутый, для клипа и заливки) и открытая линия волны (для кромки). */
    private void buildWaterPaths() {
        waterPath.reset();
        wavePath.reset();

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0 || waterLevel <= WATER_EPS) {
            return;
        }

        final float topY = waterTopY();
        final float a1 = h * WAVE_AMP_1;
        final float a2 = h * WAVE_AMP_2;
        final float k1 = TWO_PI / (w * WAVE_LEN_1);
        final float k2 = TWO_PI / (w * WAVE_LEN_2);

        for (int i = 0; i <= WAVE_STEPS; i++) {
            float x = w * (i / (float) WAVE_STEPS);
            float y = topY
                    + a1 * (float) Math.sin(k1 * x + phase1)
                    + a2 * (float) Math.sin(k2 * x - phase2);
            if (i == 0) {
                wavePath.moveTo(x, y);
                waterPath.moveTo(x, y);
            } else {
                wavePath.lineTo(x, y);
                waterPath.lineTo(x, y);
            }
        }
        waterPath.lineTo(w, h);
        waterPath.lineTo(0f, h);
        waterPath.close();
    }

    private void startWaveAnimator() {
        if (waveAnimator == null) {
            waveAnimator = ValueAnimator.ofFloat(0f, 1f);
            waveAnimator.setDuration(WAVE_CYCLE_MS);
            waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
            waveAnimator.setRepeatMode(ValueAnimator.RESTART);
            waveAnimator.setInterpolator(waveInterpolator);
            waveAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    // фазы кратны полному циклу -> петля бесшовная, волны бегут в разные стороны
                    float fraction = (Float) animation.getAnimatedValue();
                    phase1 = fraction * TWO_PI;
                    phase2 = fraction * TWO_PI * 2f;
                    invalidate();
                }
            });
        }
        if (!waveAnimator.isRunning()) {
            waveAnimator.start();
        }
    }

    private void stopWaveAnimator() {
        if (waveAnimator != null) {
            waveAnimator.removeAllListeners();
            waveAnimator.removeAllUpdateListeners();
            waveAnimator.cancel();
        }
    }

    // ======================================================================
    //  Набор барабанов и метрики шрифта
    // ======================================================================

    /**
     * Перестраивает набор барабанов под текст. Существующие барабаны переиспользуются
     * по индексу (позиция сохраняется), поэтому количество символов может меняться —
     * «часы растягиваются».
     */
    private void rebuildReels(String text, boolean instant) {
        final String value = (text == null) ? "" : text;
        final List<Reel> previous = new ArrayList<Reel>(reels);
        reels.clear();
        for (int i = 0; i < value.length(); i++) {
            Reel reel = (i < previous.size()) ? previous.get(i) : new Reel();
            reel.set(value.charAt(i));
            if (instant && reel.digit >= 0) {
                reel.pos = reel.digit;
            }
            reels.add(reel);
        }
        recomputeTextSize();
        invalidate();
    }

    /** Ширина символа ≈ contentWidth / кол-во символов, высота цифры ≈ ширина * 1.45. */
    private void recomputeTextSize() {
        final int symbols = Math.max(1, reels.size());
        final float contentWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        if (contentWidth <= 0f) {
            return;
        }
        final float targetCharWidth = contentWidth / symbols;
        final float targetDigitHeight = targetCharWidth * DIGIT_HEIGHT_RATIO;

        final float probeSize = 100f;
        dimPaint.setTextSize(probeSize);
        float probeAdvance = Math.max(1f, dimPaint.measureText("0"));
        dimPaint.getTextBounds("0", 0, 1, glyphBounds);
        float probeInkHeight = Math.max(1f, glyphBounds.height());

        float sizeForWidth = probeSize * targetCharWidth / probeAdvance;
        float sizeForHeight = probeSize * targetDigitHeight / probeInkHeight;
        textSize = Math.max(dp(6f), Math.min(sizeForWidth, sizeForHeight));

        dimPaint.setTextSize(textSize);
        brightPaint.setTextSize(textSize);
        glowPaint.setTextSize(textSize);
        glowPaint.setStrokeWidth(Math.max(dp(1.2f), textSize * 0.055f));

        dimPaint.getTextBounds("0", 0, 1, glyphBounds);
        digitHeight = Math.max(dp(8f), glyphBounds.height() * 1.06f);
        Paint.FontMetrics metrics = dimPaint.getFontMetrics();
        baselineOffset = -(metrics.ascent + metrics.descent) * 0.5f;
        slotWidth = targetCharWidth;
        dotRadius = Math.max(dp(1.5f), targetCharWidth * 0.09f);
    }

    // ======================================================================
    //  Аниматоры: отмена и утилиты
    // ======================================================================

    /**
     * Отменяет все аниматоры барабанов (вращение и тики).
     *
     * @param flushSpinDone если true — незавершённый spinTo получит свой onDone
     *                      (ровно один раз), чтобы вызывающий код не «завис» на ожидании
     */
    private void cancelReelAnimators(boolean flushSpinDone) {
        reelGeneration++;
        for (int i = 0; i < reelAnimators.size(); i++) {
            ValueAnimator animator = reelAnimators.get(i);
            animator.removeAllListeners();
            animator.removeAllUpdateListeners();
            animator.cancel();
        }
        reelAnimators.clear();

        Runnable done = pendingSpinDone;
        pendingSpinDone = null;
        if (flushSpinDone) {
            postOnUi(done);
        }
    }

    private void cancelWaterAnimator() {
        waterGeneration++;
        if (waterAnimator != null) {
            waterAnimator.removeAllListeners();
            waterAnimator.removeAllUpdateListeners();
            waterAnimator.cancel();
            waterAnimator = null;
        }
    }

    private boolean isOnUiThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private void postOnUi(Runnable action) {
        if (action == null) {
            return;
        }
        if (isOnUiThread()) {
            action.run();
        } else {
            post(action);
        }
    }

    private void initPaints() {
        dimPaint.setTypeface(monoBold);
        dimPaint.setTextAlign(Paint.Align.CENTER);
        dimPaint.setStyle(Paint.Style.FILL);
        dimPaint.setColor(COLOR_DIM);

        brightPaint.setTypeface(monoBold);
        brightPaint.setTextAlign(Paint.Align.CENTER);
        brightPaint.setStyle(Paint.Style.FILL);
        brightPaint.setColor(COLOR_BRIGHT);

        glowPaint.setTypeface(monoBold);
        glowPaint.setTextAlign(Paint.Align.CENTER);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setColor(COLOR_BRIGHT);

        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(COLOR_DIM);

        waterPaint.setStyle(Paint.Style.FILL);
        waterPaint.setAlpha(WATER_ALPHA);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        edgePaint.setStrokeJoin(Paint.Join.ROUND);
        edgePaint.setColor(COLOR_WAVE_EDGE);
        edgePaint.setStrokeWidth(dp(2f));
        edgePaint.setAlpha(WAVE_EDGE_ALPHA);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /** Один барабан: символ и непрерывная позиция ленты. */
    private static final class Reel {
        char ch = '0';
        int digit = 0;   // -1 для разделителя
        float pos = 0f;  // цифра в окне = ((round(pos) % 10) + 10) % 10

        void set(char c) {
            ch = c;
            digit = (c >= '0' && c <= '9') ? (c - '0') : -1;
        }
    }
}
