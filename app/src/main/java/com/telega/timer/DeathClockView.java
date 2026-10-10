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
import android.view.animation.AnimationUtils;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;

import java.util.ArrayList;
import java.util.List;

/**
 * «Часы смерти».
 *
 * <p>Ровно ОДИН видимый ряд цифр: окно барабана — одна высота цифры, перед
 * отрисовкой canvas обрезается полосой {@code clipRect(0, rowTop, w, rowBottom)},
 * поэтому соседние цифры сверху и снизу не видны (эффекта глубины барабана нет).
 * Под каждой группой цифр — подпись, она рисуется ПОСЛЕ {@code restore()} и
 * полосой не обрезается.</p>
 *
 * <p>Кровь видна ТОЛЬКО внутри контуров цифр, двоеточий и подписей: два
 * последовательных {@code clipPath} дают пересечение {@code glyphPath ∩ waterPath}.
 * Снаружи букв не появляется ни прямоугольника, ни свечения, ни обводки — фон
 * остаётся {@code #0B0B0F}.</p>
 *
 * <p>Только стандартный Android SDK (без AndroidX). Все Paint/Path/Matrix/Rect —
 * поля класса; в {@link #onDraw(Canvas)} ничего не аллоцируется. Аниматоры
 * отменяются в {@link #onDetachedFromWindow()}.</p>
 */
public class DeathClockView extends View {

    private static final String DIGITS = "0123456789";
    private static final String SEPARATOR = ":";

    // ------------------------------------------------------------------
    //  Цвета
    // ------------------------------------------------------------------
    private static final int COLOR_BG = 0xFF0B0B0F;            // фон
    private static final int COLOR_DIGIT_DIM = 0xFF8A8A93;     // цифры до крови
    private static final int COLOR_LABEL_DIM = 0xFF5A5A64;     // подписи до крови
    private static final int COLOR_LIVE = 0xFFE01F1F;          // цифры под кровью — насыщенный тёмно-красный
    private static final int COLOR_BLOOD_TOP = 0xFFA31212;     // кровь у поверхности волны
    private static final int COLOR_BLOOD_MID = 0xFF2A0303;     // середина
    private static final int COLOR_BLOOD_BOTTOM = 0xFF150101;  // самый низ
    private static final int COLOR_WAVE_EDGE = 0xFFD14A3A;     // кромка волны

    private static final int[] BLOOD_COLORS = { COLOR_BLOOD_TOP, COLOR_BLOOD_MID, COLOR_BLOOD_BOTTOM };
    private static final float[] BLOOD_STOPS = { 0f, 0.42f, 1f };

    // ------------------------------------------------------------------
    //  Константы оформления
    // ------------------------------------------------------------------
    private static final int DIGIT_SLOTS_PER_GROUP = 2;        // каждая группа резервирует 2 позиции
    private static final float SEP_WIDTH_RATIO = 0.55f;        // ширина двоеточия в долях позиции цифры
    private static final float DIGIT_BOX_PAD = 1.08f;          // полоса чуть выше чернил цифры
    private static final float BAND_SLACK_PX = 1f;             // крошечный запас полосы, px
    private static final float MAX_DIGIT_OFFSET = 0.965f;      // дальше цифра в полосу не попадает
    private static final float LABEL_SIZE_RATIO = 0.30f;       // подпись ≈ 0.30 от размера цифры
    private static final float LABEL_GAP_RATIO = 0.10f;        // отступ подписи от полосы
    private static final int BLOOD_ALPHA = 152;                // кровь полупрозрачна — «живой» красный просвечивает

    private static final float WAVE_AMP_1 = 0.018f;            // A1 ≈ height * 0.018
    private static final float WAVE_AMP_2 = 0.010f;            // A2 ≈ height * 0.010
    private static final float WAVE_LEN_1 = 0.55f;             // k1 ≈ 2π / (width * 0.55)
    private static final float WAVE_LEN_2 = 0.28f;             // k2 ≈ 2π / (width * 0.28)
    private static final float WAVE_PULSE_DEPTH = 0.18f;       // амплитуда «дышит»: 1 + 0.18 * sin(t * 0.9)
    private static final float WAVE_PULSE_SPEED = 0.9f;
    private static final float GLOW_ALPHA_MIN = 0.10f;         // пульс свечения по контурам
    private static final float GLOW_ALPHA_MAX = 0.28f;
    private static final float GLOW_SPEED = 2.0f;
    private static final float GLOW_RISE_FROM = 0.55f;         // свечение проявляется, когда кровь дошла до цифр
    private static final int WAVE_STEPS = 96;
    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final long WAVE_CYCLE_MS = 2600L;           // цикл кратен 2π — петля бесшовная
    private static final long TICK_MS = 240L;                  // короткий сдвиг изменившейся цифры
    private static final long SETTLE_MS = 120L;                // «доезд» барабана
    private static final float SETTLE_BACK = 0.12f;            // на сколько не доехать до цели перед доездом
    private static final float WATER_EPS = 0.0005f;
    private static final float PROBE_SIZE = 100f;              // пробный размер для замеров шрифта

    // ------------------------------------------------------------------
    //  Paint'ы (создаются один раз, в onDraw не аллоцируем)
    // ------------------------------------------------------------------
    private final Paint digitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // цифры и двоеточия (и замеры, и getTextPath)
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // подписи
    private final Paint livePaint = new Paint(Paint.ANTI_ALIAS_FLAG);    // заливка «живым» красным внутри букв
    private final Paint bloodPaint = new Paint(Paint.ANTI_ALIAS_FLAG);   // градиент крови
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);    // кромка волны
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);    // пульсирующее свечение контуров

    // ------------------------------------------------------------------
    //  Переиспользуемые Path / Matrix / Rect / Shader
    // ------------------------------------------------------------------
    private final Path glyphPath = new Path();      // контуры ВСЕХ видимых символов (цифры + двоеточия + подписи)
    private final Path waterPath = new Path();      // область жидкости: от волны до низа экрана
    private final Path wavePath = new Path();       // открытая линия волны (для кромки)
    private final Path digitAccum = new Path();     // накопитель контуров цифр (обрезается полосой)
    private final Path bandRectPath = new Path();   // прямоугольник полосы цифр
    private final Path[] digitGlyphs = createDigitPaths();  // контуры цифр 0..9 в начале координат
    private final Matrix bloodMatrix = new Matrix();        // локальная матрица градиента крови
    private final Rect textBounds = new Rect();             // замеры чернил шрифта
    private Shader bloodShader;

    private final Typeface monoBold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);

    // ------------------------------------------------------------------
    //  Интерполяторы
    // ------------------------------------------------------------------
    private final Interpolator spinInterpolator = new DecelerateInterpolator(2.2f);
    private final Interpolator settleInterpolator = new OvershootInterpolator(1.1f);
    private final Interpolator tickInterpolator = new DecelerateInterpolator(1.6f);
    private final Interpolator waterInterpolator = new AccelerateDecelerateInterpolator();
    private final Interpolator waveInterpolator = new LinearInterpolator();

    // ------------------------------------------------------------------
    //  Группы (число + подпись)
    // ------------------------------------------------------------------
    private final List<Group> groups = new ArrayList<Group>();

    // ------------------------------------------------------------------
    //  Геометрия
    // ------------------------------------------------------------------
    private float textSize = 48f;
    private float digitAdvance = 32f;
    private float digitHeight = 64f;
    private float baselineOffset = 0f;     // сдвиг базовой линии, чтобы чернила цифры легли по центру полосы
    private float slotWidth = 32f;
    private float sepAdvance = 18f;
    private float rowTop = 0f;
    private float rowBottom = 0f;
    private float rowCenterY = 0f;
    private float labelTextSize = 16f;
    private float labelHeight = 20f;
    private float labelAscent = -16f;
    private float labelGap = 6f;
    private float labelBaseline = 0f;

    // ------------------------------------------------------------------
    //  Жидкость
    // ------------------------------------------------------------------
    private float waterLevel = 0f;   // 0..1
    private float phase1 = 0f;
    private float phase2 = 0f;
    private float waveTimeSec = 0f;  // время работы волны в секундах (для пульса амплитуды и свечения)
    private long waveStartMs = 0L;

    // ------------------------------------------------------------------
    //  Аниматоры и поколения (защита от «поздних» колбэков)
    // ------------------------------------------------------------------
    private final List<ValueAnimator> reelAnimators = new ArrayList<ValueAnimator>();
    private ValueAnimator waterAnimator;
    private ValueAnimator waveAnimator;
    private int reelGeneration = 0;
    private int waterGeneration = 0;
    private Runnable pendingSpinDone;
    private Runnable pendingWaterDone;

    public DeathClockView(Context context) {
        this(context, null);
    }

    public DeathClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        // раскладка считается в onSizeChanged (в конструкторе ширина ещё нулевая)
        initPaints();
    }

    // ==================================================================
    //  Публичный API
    // ==================================================================

    /**
     * Мгновенно показать значения и подписи (без анимации).
     *
     * @param values числа групп, например {12, 7, 23}
     * @param labels подписи групп, например {"лет", "месяцев", "дней"}
     */
    public void setGroups(final int[] values, final String[] labels) {
        if (!isOnUiThread()) {
            post(() -> setGroups(values, labels));
            return;
        }
        cancelReelAnimators(true);
        applyGroups(values, labels, true);
    }

    /**
     * Раскрутить барабаны под новые значения и остановить их по очереди.
     * Все барабаны стартуют одновременно, останавливаются через
     * {@code setStartDelay(общийИндекс * staggerMs)} (индекс сквозной слева направо,
     * независимо от группы), после чего каждый коротко «щёлкает» на место.
     *
     * @param values    новые значения групп
     * @param labels    новые подписи групп
     * @param spinMs    длительность вращения одного барабана
     * @param staggerMs задержка между остановками соседних барабанов
     * @param onDone    ровно один раз, на UI-потоке, когда ВСЕ барабаны встали
     */
    public void spinTo(final int[] values, final String[] labels, final long spinMs,
                       final long staggerMs, final Runnable onDone) {
        if (!isOnUiThread()) {
            post(() -> spinTo(values, labels, spinMs, staggerMs, onDone));
            return;
        }
        cancelReelAnimators(true);
        applyGroups(values, labels, false);
        startSpin(spinMs, staggerMs, onDone);
    }

    /**
     * Обновить числа без вращения: изменившиеся цифры уезжают коротким сдвигом
     * (~240 мс). Подписи не меняются.
     *
     * @param values новые значения групп
     */
    public void tickTo(final int[] values) {
        if (!isOnUiThread()) {
            post(() -> tickTo(values));
            return;
        }
        cancelReelAnimators(true);
        applyTick(values);
    }

    /**
     * Обновить только подписи (склонения: год/года/лет). Без анимации.
     *
     * @param labels новые подписи групп
     */
    public void setLabels(final String[] labels) {
        if (!isOnUiThread()) {
            post(() -> setLabels(labels));
            return;
        }
        if (labels == null) {
            return;
        }
        for (int i = 0; i < groups.size() && i < labels.length; i++) {
            Group group = groups.get(i);
            group.label = (labels[i] == null) ? "" : labels[i];
        }
        recomputeLayout();
        invalidate();
    }

    /**
     * Залить тёмной кровью ВНУТРИ ЦИФР и подписей, снизу вверх.
     *
     * @param durationMs длительность подъёма
     * @param onDone     вызов на UI-потоке по завершении подъёма (ровно один раз)
     */
    public void startWater(final long durationMs, final Runnable onDone) {
        if (!isOnUiThread()) {
            post(() -> startWater(durationMs, onDone));
            return;
        }
        cancelWaterAnimator();
        startWaveAnimator();
        final int generation = waterGeneration;

        waterLevel = 0f;
        pendingWaterDone = onDone;
        invalidate();

        waterAnimator = ValueAnimator.ofFloat(0f, 1f);
        waterAnimator.setDuration(Math.max(1L, durationMs));
        // подъём начинается мягко, идёт быстро и мягко останавливается
        waterAnimator.setInterpolator(waterInterpolator);
        waterAnimator.addUpdateListener(animation -> {
            if (generation != waterGeneration) {
                return;
            }
            waterLevel = (Float) animation.getAnimatedValue();
            invalidate();
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
                Runnable done = pendingWaterDone;
                pendingWaterDone = null;
                postOnUi(done);
            }
        });
        waterAnimator.start();
    }

    /** Полный сброс анимаций: барабаны встают на текущие числа, крови нет, волна погашена. */
    public void reset() {
        if (!isOnUiThread()) {
            post(() -> reset());
            return;
        }
        cancelReelAnimators(false);
        cancelWaterAnimator();
        stopWaveAnimator();

        waterLevel = 0f;
        phase1 = 0f;
        phase2 = 0f;
        waveTimeSec = 0f;

        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            for (int j = 0; j < group.reels.length; j++) {
                Reel reel = group.reels[j];
                reel.pos = (reel.digit >= 0) ? reel.digit : 0f;
            }
        }
        invalidate();
    }

    // ==================================================================
    //  Жизненный цикл View
    // ==================================================================

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // градиент крови: единичный отрезок 0..1, реальный размах задаёт локальная матрица
        bloodShader = new LinearGradient(0f, 0f, 0f, 1f, BLOOD_COLORS, BLOOD_STOPS, Shader.TileMode.CLAMP);
        bloodPaint.setShader(bloodShader);
        recomputeLayout();
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

    // ==================================================================
    //  Отрисовка
    // ==================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 1) фон
        canvas.drawColor(COLOR_BG);

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        // 2) цифры ТУСКЛЫМ цветом — строго внутри полосы высотой в одну цифру
        int save = canvas.save();
        canvas.clipRect(0f, rowTop - BAND_SLACK_PX, w, rowBottom + BAND_SLACK_PX);
        drawDigits(canvas);
        canvas.restoreToCount(save);

        // 3) подписи ТУСКЛЫМ цветом — под полосой, клипом не обрезаются
        drawLabels(canvas);

        // 4) контуры всех букв и область жидкости
        if (waterLevel > WATER_EPS) {
            buildGlyphPath();
            buildWaterPath();
            if (!glyphPath.isEmpty() && !waterPath.isEmpty()) {
                save = canvas.save();
                canvas.clipPath(glyphPath);   // пересечение №1 — только внутри букв
                canvas.clipPath(waterPath);   // пересечение №2 — только ниже уровня крови
                // 5) цифры под кровью — насыщенный тёмно-красный, затем сама кровь
                canvas.drawRect(0f, 0f, w, h, livePaint);
                updateBloodMatrix();
                canvas.drawRect(0f, 0f, w, h, bloodPaint);
                // 6) светлая кромка волны — тоже только внутри букв
                canvas.drawPath(wavePath, edgePaint);
                // мягкий пульс свечения по тем же контурам (наружу не выходит — режет клип)
                final int glowAlpha = computeGlowAlpha();
                if (glowAlpha > 0) {
                    glowPaint.setAlpha(glowAlpha);
                    canvas.drawPath(glyphPath, glowPaint);
                }
                canvas.restoreToCount(save);
            }
        }
    }

    /** Цифры барабанов и двоеточия тусклым цветом (вызывать внутри клипа по полосе). */
    private void drawDigits(Canvas canvas) {
        digitPaint.setColor(COLOR_DIGIT_DIM);
        for (int i = 0; i < groups.size(); i++) {
            Group group = groups.get(i);
            for (int j = 0; j < group.reels.length; j++) {
                final Reel reel = group.reels[j];
                if (reel.digit < 0) {
                    continue;   // ведущий ноль не рисуем — вместо него пустое место
                }
                final float pos = reel.pos;
                final int base = (int) Math.floor(pos);
                for (int k = -1; k <= 1; k++) {
                    final float offset = (base + k) - pos;
                    if (offset < -MAX_DIGIT_OFFSET || offset > MAX_DIGIT_OFFSET) {
                        continue;   // эта цифра в полосу не попала
                    }
                    final int digit = (((base + k) % 10) + 10) % 10;
                    final float y = rowCenterY + offset * digitHeight + baselineOffset;
                    canvas.drawText(DIGITS, digit, digit + 1, reel.penX, y, digitPaint);
                }
            }
            if (group.hasSep) {
                canvas.drawText(SEPARATOR, group.sepPenX, rowCenterY + baselineOffset, digitPaint);
            }
        }
    }

    /** Подписи под полосой (вне клипа — не обрезаются). */
    private void drawLabels(Canvas canvas) {
        labelPaint.setColor(COLOR_LABEL_DIM);
        for (int i = 0; i < groups.size(); i++) {
            final Group group = groups.get(i);
            if (group.label.isEmpty()) {
                continue;
            }
            canvas.drawText(group.label, group.labelPenX, labelBaseline, labelPaint);
        }
    }

    /**
     * Собирает {@link #glyphPath} — контуры ВСЕХ видимых символов:
     * цифр (только попавших в полосу), двоеточий и подписей.
     */
    private void buildGlyphPath() {
        glyphPath.reset();
        digitAccum.reset();

        boolean rolling = false;
        for (int i = 0; i < groups.size(); i++) {
            final Group group = groups.get(i);
            for (int j = 0; j < group.reels.length; j++) {
                final Reel reel = group.reels[j];
                if (reel.digit < 0) {
                    continue;
                }
                final float pos = reel.pos;
                final int base = (int) Math.floor(pos);
                if (Math.abs(pos - Math.round(pos)) > 0.0005f) {
                    rolling = true;   // барабан между цифрами — нужен срез по полосе
                }
                for (int k = -1; k <= 1; k++) {
                    final float offset = (base + k) - pos;
                    if (offset < -MAX_DIGIT_OFFSET || offset > MAX_DIGIT_OFFSET) {
                        continue;
                    }
                    final int digit = (((base + k) % 10) + 10) % 10;
                    final float y = rowCenterY + offset * digitHeight + baselineOffset;
                    digitAccum.addPath(digitGlyphs[digit], reel.penX, y);
                }
            }
            if (group.hasSep) {
                digitAccum.addPath(group.sepGlyph, group.sepPenX, rowCenterY + baselineOffset);
            }
        }

        // пока барабан крутится, соседняя цифра может торчать из полосы — срезаем её,
        // чтобы кровь не проступала вне видимого ряда
        if (rolling) {
            digitAccum.op(bandRectPath, Path.Op.INTERSECT);
        }
        glyphPath.addPath(digitAccum);

        for (int i = 0; i < groups.size(); i++) {
            final Group group = groups.get(i);
            if (!group.labelGlyph.isEmpty()) {
                glyphPath.addPath(group.labelGlyph);
            }
        }
    }

    /** Контур жидкости (замкнутый) и открытая линия волны (для кромки). */
    private void buildWaterPath() {
        waterPath.reset();
        wavePath.reset();

        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0 || waterLevel <= WATER_EPS) {
            return;
        }

        final float topY = waterTopY();
        // амплитуда «дышит» во времени
        final float pulse = 1f + WAVE_PULSE_DEPTH * (float) Math.sin(waveTimeSec * WAVE_PULSE_SPEED);
        final float amp1 = h * WAVE_AMP_1 * pulse;
        final float amp2 = h * WAVE_AMP_2 * pulse;
        final float k1 = TWO_PI / (w * WAVE_LEN_1);
        final float k2 = TWO_PI / (w * WAVE_LEN_2);

        for (int i = 0; i <= WAVE_STEPS; i++) {
            final float x = w * (i / (float) WAVE_STEPS);
            final float y = topY
                    + amp1 * (float) Math.sin(k1 * x + phase1)
                    + amp2 * (float) Math.sin(k2 * x + phase2);
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

    /** Локальная матрица градиента: 0 → поверхность волны, 1 → низ экрана. */
    private void updateBloodMatrix() {
        if (bloodShader == null) {
            return;
        }
        final float top = waterTopY();
        final float span = Math.max(1f, getHeight() - top);
        bloodMatrix.setScale(1f, span);
        bloodMatrix.postTranslate(0f, top);
        bloodShader.setLocalMatrix(bloodMatrix);
    }

    /** Альфа пульсирующего свечения по контурам: 0.10..0.28, проявляется по мере подъёма крови. */
    private int computeGlowAlpha() {
        final float wave = 0.5f + 0.5f * (float) Math.sin(waveTimeSec * GLOW_SPEED);
        final float alpha = GLOW_ALPHA_MIN + (GLOW_ALPHA_MAX - GLOW_ALPHA_MIN) * wave;
        float rise = (waterLevel - GLOW_RISE_FROM) / (1f - GLOW_RISE_FROM);
        if (rise <= 0f) {
            return 0;
        }
        if (rise > 1f) {
            rise = 1f;
        }
        return (int) (255f * alpha * rise);
    }

    private float waterTopY() {
        return getHeight() * (1f - waterLevel);
    }

    // ==================================================================
    //  Значения групп
    // ==================================================================

    /** Пересобирает группы под новые значения и подписи; instant — поставить позиции сразу. */
    private void applyGroups(int[] values, String[] labels, boolean instant) {
        final int count = (values == null) ? 0 : values.length;
        final List<Group> previous = new ArrayList<Group>(groups);
        groups.clear();
        for (int i = 0; i < count; i++) {
            // группы переиспользуются по индексу — позиции барабанов не прыгают
            final Group group = (i < previous.size()) ? previous.get(i) : new Group();
            groups.add(group);
            group.label = (labels != null && i < labels.length && labels[i] != null) ? labels[i] : "";
            setGroupTargets(group, values[i], instant);
        }
        recomputeLayout();
        invalidate();
    }

    /** Цели барабанов одной группы: старший разряд пуст, пока число < 10. */
    private void setGroupTargets(Group group, int value, boolean instant) {
        group.value = clampValue(value);
        final int tens = group.value / 10;
        final int ones = group.value % 10;
        applyReelTarget(group.reels[0], (tens > 0) ? tens : -1, instant);
        applyReelTarget(group.reels[1], ones, instant);
    }

    private void applyReelTarget(Reel reel, int digit, boolean instant) {
        reel.digit = digit;
        if (digit < 0) {
            reel.pos = 0f;   // пустая позиция ничего не показывает
            return;
        }
        if (instant) {
            reel.pos = digit;
        }
    }

    /** Обновление чисел без вращения: изменившиеся цифры уезжают коротким сдвигом. */
    private void applyTick(int[] values) {
        if (values == null) {
            invalidate();
            return;
        }
        final int generation = reelGeneration;
        final int count = Math.min(values.length, groups.size());
        for (int i = 0; i < count; i++) {
            final Group group = groups.get(i);
            group.value = clampValue(values[i]);
            final int[] targets = new int[] { (group.value >= 10) ? group.value / 10 : -1, group.value % 10 };
            for (int j = 0; j < group.reels.length; j++) {
                final Reel reel = group.reels[j];
                final int oldDigit = reel.digit;
                final int newDigit = targets[j];
                reel.digit = newDigit;
                if (newDigit < 0) {
                    reel.pos = 0f;   // ведущий ноль не рисуем — ширина не прыгает
                    continue;
                }
                final float from;
                final float to;
                if (oldDigit < 0) {
                    from = newDigit - 1f;   // цифра вкатывается в пустую позицию
                    to = newDigit;
                } else if (oldDigit == newDigit) {
                    final int base = Math.round(reel.pos);
                    if (Math.abs(reel.pos - base) <= 0.0005f) {
                        continue;           // уже на месте
                    }
                    from = reel.pos;        // доводим «застрявший» барабан
                    to = base;
                } else {
                    final int base = Math.round(reel.pos);
                    final int shown = ((base % 10) + 10) % 10;
                    int delta = newDigit - shown;   // кратчайший путь по барабану
                    if (delta > 5) {
                        delta -= 10;
                    } else if (delta < -5) {
                        delta += 10;
                    }
                    from = reel.pos;
                    to = base + delta;
                }
                animateReel(reel, generation, from, to);
            }
        }
        invalidate();
    }

    // ==================================================================
    //  Вращение барабанов
    // ==================================================================

    private void startSpin(long spinMs, long staggerMs, Runnable onDone) {
        final int generation = reelGeneration;
        final long duration = Math.max(1L, spinMs);
        final long stagger = Math.max(0L, staggerMs);
        final int[] remaining = new int[] { 0 };
        final List<ValueAnimator> created = new ArrayList<ValueAnimator>();

        // сквозной индекс барабана слева направо, независимо от группы
        int globalIndex = 0;
        for (int i = 0; i < groups.size(); i++) {
            final Group group = groups.get(i);
            for (int j = 0; j < group.reels.length; j++) {
                final Reel reel = group.reels[j];
                final int index = globalIndex++;
                if (reel.digit < 0) {
                    continue;   // пустая позиция не крутится
                }

                final float start = reel.pos;
                final int turns = 7 + (index % 4);   // 7..10 полных оборотов
                final int base = (int) Math.floor(start);
                final int shown = ((base % 10) + 10) % 10;
                float end = base + turns * 10f + (((reel.digit - shown) % 10 + 10) % 10);
                while (end - start < turns * 10f) {
                    end += 10f;
                }
                final float target = end;
                final float mainEnd = end - SETTLE_BACK;   // не доезжаем чуть-чуть — догоним «щёлчком»

                ValueAnimator main = ValueAnimator.ofFloat(start, mainEnd);
                main.setDuration(duration);
                main.setStartDelay(stagger * index);
                main.setInterpolator(spinInterpolator);
                main.addUpdateListener(animation -> {
                    if (generation != reelGeneration) {
                        return;
                    }
                    reel.pos = (Float) animation.getAnimatedValue();
                    invalidate();
                });
                main.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (generation != reelGeneration) {
                            return;
                        }
                        // короткий «доезд» с лёгким перелётом — цифра щёлкает на место
                        ValueAnimator settle = ValueAnimator.ofFloat(mainEnd, target);
                        settle.setDuration(SETTLE_MS);
                        settle.setInterpolator(settleInterpolator);
                        settle.addUpdateListener(inner -> {
                            if (generation != reelGeneration) {
                                return;
                            }
                            reel.pos = (Float) inner.getAnimatedValue();
                            invalidate();
                        });
                        settle.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator inner) {
                                if (generation != reelGeneration) {
                                    return;
                                }
                                reel.pos = norm10(target);
                                invalidate();
                                remaining[0]--;
                                if (remaining[0] <= 0) {
                                    Runnable done = pendingSpinDone;
                                    pendingSpinDone = null;
                                    postOnUi(done);
                                }
                            }
                        });
                        reelAnimators.add(settle);
                        settle.start();
                    }
                });
                reelAnimators.add(main);
                created.add(main);
            }
        }

        if (created.isEmpty()) {
            pendingSpinDone = null;
            if (onDone != null) {
                post(onDone);   // крутить нечего — сообщаем асинхронно, ровно один раз
            }
            return;
        }

        remaining[0] = created.size();
        pendingSpinDone = onDone;
        for (int i = 0; i < created.size(); i++) {
            created.get(i).start();
        }
    }

    /** Короткий сдвиг одного барабана (тик). */
    private void animateReel(final Reel reel, final int generation, float from, float to) {
        final float end = to;
        ValueAnimator animator = ValueAnimator.ofFloat(from, to);
        animator.setDuration(TICK_MS);
        animator.setInterpolator(tickInterpolator);
        animator.addUpdateListener(animation -> {
            if (generation != reelGeneration) {
                return;
            }
            reel.pos = (Float) animation.getAnimatedValue();
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (generation != reelGeneration) {
                    return;
                }
                reel.pos = norm10(end);
                invalidate();
            }
        });
        reelAnimators.add(animator);
        animator.start();
    }

    // ==================================================================
    //  Волна
    // ==================================================================

    private void startWaveAnimator() {
        if (waveAnimator == null) {
            waveAnimator = ValueAnimator.ofFloat(0f, 1f);
            waveAnimator.setDuration(WAVE_CYCLE_MS);
            waveAnimator.setRepeatCount(ValueAnimator.INFINITE);
            waveAnimator.setRepeatMode(ValueAnimator.RESTART);
            waveAnimator.setInterpolator(waveInterpolator);
            waveAnimator.addUpdateListener(animation -> {
                // фазы кратны 2π — петля бесшовная, волны бегут в разные стороны
                final float fraction = (Float) animation.getAnimatedValue();
                phase1 = fraction * TWO_PI;
                phase2 = -fraction * TWO_PI * 2f;
                waveTimeSec = (AnimationUtils.currentAnimationTimeMillis() - waveStartMs) * 0.001f;
                invalidate();
            });
        }
        if (!waveAnimator.isRunning()) {
            waveStartMs = AnimationUtils.currentAnimationTimeMillis();
            waveAnimator.start();
        }
    }

    private void stopWaveAnimator() {
        if (waveAnimator != null) {
            waveAnimator.removeAllListeners();
            waveAnimator.removeAllUpdateListeners();
            waveAnimator.cancel();
            waveAnimator = null;   // следующая волна создаётся заново — с update-листенером
        }
    }

    // ==================================================================
    //  Геометрия и кэш контуров
    // ==================================================================

    /** Пересчитывает размеры шрифта, полосу цифр, позиции групп и кэш контуров. */
    private void recomputeLayout() {
        final int w = getWidth();
        final int h = getHeight();
        final float contentW = w - getPaddingLeft() - getPaddingRight();
        final float contentH = h - getPaddingTop() - getPaddingBottom();
        if (w <= 0 || h <= 0 || contentW <= 1f || contentH <= 1f) {
            return;
        }

        // ширина: (позиции цифр) + (разделители)
        final int count = Math.max(1, groups.size());
        final float units = count * DIGIT_SLOTS_PER_GROUP + (count - 1) * SEP_WIDTH_RATIO;
        slotWidth = contentW / units;

        // пробный замер моношрифта: сколько пикселей занимает одна позиция цифры
        digitPaint.setTextSize(PROBE_SIZE);
        final float probeAdvance = Math.max(1f, digitPaint.measureText("0"));

        // размер по ширине и, при необходимости, уменьшение по высоте
        applyTextSize(PROBE_SIZE * slotWidth / probeAdvance);
        final float need = digitHeight + 2f * labelGap + labelHeight;
        if (need > contentH) {
            applyTextSize(textSize * contentH / need);
        }
        final float minSize = dp(8f);
        if (textSize < minSize) {
            applyTextSize(minSize);
        }
        sepAdvance = Math.max(1f, digitPaint.measureText(SEPARATOR));

        // вертикаль: полоса цифр центрируется в области ВЫШЕ подписей
        rowTop = getPaddingTop() + (contentH - labelHeight) * 0.5f - digitHeight * 0.5f;
        rowBottom = rowTop + digitHeight;
        rowCenterY = rowTop + digitHeight * 0.5f;
        labelBaseline = rowBottom + labelGap - labelAscent;

        // горизонталь: группы слева направо, двоеточие между ними
        final float sepWidth = slotWidth * SEP_WIDTH_RATIO;
        float cursor = getPaddingLeft();
        for (int i = 0; i < groups.size(); i++) {
            final Group group = groups.get(i);
            group.boxLeft = cursor;
            for (int j = 0; j < group.reels.length; j++) {
                group.reels[j].penX = cursor + (slotWidth - digitAdvance) * 0.5f;
                cursor += slotWidth;
            }
            group.centerX = group.boxLeft + group.reels.length * slotWidth * 0.5f;
            group.hasSep = (i < groups.size() - 1);
            group.sepPenX = cursor + (sepWidth - sepAdvance) * 0.5f;
            if (group.hasSep) {
                cursor += sepWidth;
            }
            group.labelWidth = group.label.isEmpty() ? 0f : labelPaint.measureText(group.label);
            group.labelPenX = group.centerX - group.labelWidth * 0.5f;
        }

        rebuildGlyphCaches();
    }

    /** Применяет размер цифры ко всем производным метрикам. */
    private void applyTextSize(float size) {
        textSize = size;
        digitPaint.setTextSize(size);
        digitPaint.getTextBounds(DIGITS, 0, DIGITS.length(), textBounds);
        digitAdvance = Math.max(1f, digitPaint.measureText("0"));
        digitHeight = Math.max(1f, textBounds.height()) * DIGIT_BOX_PAD;
        // базовая линия так, чтобы чернила цифры легли ровно по центру полосы
        baselineOffset = -(textBounds.top + textBounds.bottom) * 0.5f;

        labelTextSize = size * LABEL_SIZE_RATIO;
        labelPaint.setTextSize(labelTextSize);
        final Paint.FontMetrics labelMetrics = labelPaint.getFontMetrics();
        labelHeight = labelMetrics.descent - labelMetrics.ascent;
        labelAscent = labelMetrics.ascent;
        labelGap = Math.max(dp(2f), digitHeight * LABEL_GAP_RATIO);
    }

    /** Кэш контуров: цифры и двоеточия — в начале координат, подписи — сразу на местах. */
    private void rebuildGlyphCaches() {
        for (int d = 0; d < digitGlyphs.length; d++) {
            digitGlyphs[d].reset();
            digitPaint.getTextPath(DIGITS, d, d + 1, 0f, 0f, digitGlyphs[d]);
        }
        for (int i = 0; i < groups.size(); i++) {
            final Group group = groups.get(i);
            group.sepGlyph.reset();
            digitPaint.getTextPath(SEPARATOR, 0, SEPARATOR.length(), 0f, 0f, group.sepGlyph);

            group.labelGlyph.reset();
            if (!group.label.isEmpty()) {
                labelPaint.getTextPath(group.label, 0, group.label.length(),
                        group.labelPenX, labelBaseline, group.labelGlyph);
            }
        }
        bandRectPath.reset();
        bandRectPath.addRect(0f, rowTop, getWidth(), rowBottom, Path.Direction.CW);
    }

    private static Path[] createDigitPaths() {
        final Path[] paths = new Path[10];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = new Path();
        }
        return paths;
    }

    // ==================================================================
    //  Мелочи
    // ==================================================================

    private void initPaints() {
        digitPaint.setTypeface(monoBold);
        digitPaint.setTextAlign(Paint.Align.LEFT);
        digitPaint.setStyle(Paint.Style.FILL);
        digitPaint.setColor(COLOR_DIGIT_DIM);

        labelPaint.setTypeface(monoBold);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        labelPaint.setStyle(Paint.Style.FILL);
        labelPaint.setColor(COLOR_LABEL_DIM);

        livePaint.setStyle(Paint.Style.FILL);
        livePaint.setColor(COLOR_LIVE);

        bloodPaint.setStyle(Paint.Style.FILL);
        bloodPaint.setAlpha(BLOOD_ALPHA);

        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeCap(Paint.Cap.ROUND);
        edgePaint.setStrokeJoin(Paint.Join.ROUND);
        edgePaint.setColor(COLOR_WAVE_EDGE);
        edgePaint.setStrokeWidth(dp(2f));

        // свечение имитируем обводкой (setShadowLayer не используем — тормозит)
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setColor(COLOR_LIVE);
        glowPaint.setStrokeWidth(dp(1.5f));
    }

    /**
     * Отменяет все аниматоры барабанов.
     *
     * @param flushSpinDone если true — незавершённый spinTo получит свой onDone
     *                      (ровно один раз), чтобы вызывающий код не «завис» на ожидании
     */
    private void cancelReelAnimators(boolean flushSpinDone) {
        reelGeneration++;
        for (int i = 0; i < reelAnimators.size(); i++) {
            final ValueAnimator animator = reelAnimators.get(i);
            animator.removeAllListeners();          // слушатели снимаем ДО cancel: колбэки не нужны
            animator.removeAllUpdateListeners();
            animator.cancel();
        }
        reelAnimators.clear();

        final Runnable done = pendingSpinDone;
        pendingSpinDone = null;
        if (flushSpinDone && done != null) {
            post(done);   // всегда через post: колбэк не должен выполняться из середины вызова
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
        pendingWaterDone = null;
    }

    private int clampValue(int value) {
        if (value < 0) {
            return 0;
        }
        return (value > 99) ? 99 : value;
    }

    private float norm10(float value) {
        final float m = value % 10f;
        return (m < 0f) ? m + 10f : m;
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

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    /** Один барабан: цифра и непрерывная позиция ленты. */
    private static final class Reel {
        int digit = -1;    // -1 — пустая позиция (ведущий ноль не рисуется)
        float pos = 0f;    // видимая цифра = ((round(pos) % 10) + 10) % 10
        float penX = 0f;   // левый край пера (drawText и getTextPath совпадают)
    }

    /** Группа: число из двух позиций, подпись под ним и всё, что нужно для отрисовки. */
    private static final class Group {
        int value = 0;
        String label = "";
        final Reel[] reels = new Reel[] { new Reel(), new Reel() };
        final Path sepGlyph = new Path();
        final Path labelGlyph = new Path();
        float boxLeft = 0f;
        float centerX = 0f;
        float labelPenX = 0f;
        float labelWidth = 0f;
        float sepPenX = 0f;
        boolean hasSep = false;
    }
}
