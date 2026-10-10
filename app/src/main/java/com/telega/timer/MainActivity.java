package com.telega.timer;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Timer — единственный экран приложения.
 *
 * Сценарий:
 *   1. При открытии по центру висит приглушённый таймер 00:00:00.
 *   2. Внизу кнопка «Дата смерти».
 *   3. По нажатию барабаны раскручиваются и останавливаются по одной цифре.
 *   4. Когда все встали — снизу набирается красно-тёмная жидкость волнами,
 *      и цифры сквозь неё становятся красными.
 *   5. Кнопка плавно превращается в «Ты умрёшь через», и таймер начинает
 *      тикать назад — каждую секунду.
 */
public class MainActivity extends Activity {

    /** Сколько живёт барабан, пока не остановится (мс). */
    private static final long SPIN_MS = 2600L;
    /** Задержка между остановками соседних барабанов (мс). */
    private static final long SPIN_STAGGER_MS = 180L;
    /** Длительность набора жидкости (мс). */
    private static final long WATER_MS = 2400L;
    /** Длительность превращения кнопки (мс). */
    private static final long MORPH_MS = 900L;

    /** Цвет кнопки после превращения. */
    private static final int BUTTON_DEAD_COLOR = 0xFF7E0D0D;

    private DeathClockView clock;
    private MorphButton actionButton;
    private TextView spanLabel;
    private TextView dateLabel;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long deathAtMs = 0L;
    private boolean busy = false;
    private boolean counting = false;

    /** Секундный отсчёт: значение каждый раз считается от абсолютного времени,
     *  поэтому тик не накапливает ошибку. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!counting) {
                return;
            }
            long now = System.currentTimeMillis();
            long left = deathAtMs - now;
            if (left <= 0L) {
                counting = false;
                clock.tickTo("00:00:00");
                spanLabel.setText(R.string.time_is_up);
                return;
            }
            clock.tickTo(formatRemaining(left));
            spanLabel.setText(humanSpan(now, deathAtMs));
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildLayout());

        clock.setValueInstant("00:00:00");
        actionButton.setLabel(getString(R.string.death_date_button));
        actionButton.setTapListener(button -> onDeathDateRequested());
    }

    private LinearLayout buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFF0B0B0F);
        int pad = dp(20);
        root.setPadding(pad, dp(28), pad, dp(24));

        TextView title = new TextView(this);
        title.setText(R.string.screen_title);
        title.setTextColor(0xFF3C3C46);
        title.setTextSize(12f);
        title.setLetterSpacing(0.34f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clock = new DeathClockView(this);
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clockParams.topMargin = dp(4);
        root.addView(clock, clockParams);

        spanLabel = new TextView(this);
        spanLabel.setTextColor(0xFFB03030);
        spanLabel.setTextSize(15f);
        spanLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams spanParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        spanParams.topMargin = dp(10);
        root.addView(spanLabel, spanParams);

        dateLabel = new TextView(this);
        dateLabel.setTextColor(0xFF5A5A64);
        dateLabel.setTextSize(13f);
        dateLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = dp(4);
        root.addView(dateLabel, dateParams);

        actionButton = new MorphButton(this);
        actionButton.setAccentColor(0xFF1C1C22);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        buttonParams.topMargin = dp(26);
        root.addView(actionButton, buttonParams);

        return root;
    }

    /** Нажатие «Дата смерти»: раскрутка барабанов -> жидкость -> превращение кнопки -> отсчёт. */
    private void onDeathDateRequested() {
        if (busy || counting) {
            return;
        }
        busy = true;
        actionButton.setEnabledState(false);
        spanLabel.setText("");
        dateLabel.setText("");

        long now = System.currentTimeMillis();
        deathAtMs = now + randomLifetimeMs();
        final String target = formatRemaining(deathAtMs - now);

        clock.spinTo(target, SPIN_MS, SPIN_STAGGER_MS, () ->
                clock.startWater(WATER_MS, () -> {
                    actionButton.morphTo(getString(R.string.you_will_die_in),
                            MORPH_MS, BUTTON_DEAD_COLOR);
                    SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
                    dateLabel.setText(getString(R.string.death_date_label) + ": "
                            + fmt.format(new Date(deathAtMs)));
                    spanLabel.setText(humanSpan(System.currentTimeMillis(), deathAtMs));
                    busy = false;
                    counting = true;
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                }));
    }

    /** Случайный остаток жизни: от 1 года до 60 лет. */
    private long randomLifetimeMs() {
        Random random = new Random();
        double years = 1.0d + random.nextDouble() * 59.0d;
        return (long) (years * 365.2425d * 24d * 3600d * 1000d);
    }

    /** "525960:07:21" — часы не обрезаются, сколько бы их ни было. */
    private static String formatRemaining(long ms) {
        long total = Math.max(0L, ms / 1000L);
        long hours = total / 3600L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
    }

    /** «47 лет 3 месяца 12 дней» — считается календарно, а не делением на 365. */
    private static String humanSpan(long fromMs, long toMs) {
        if (toMs <= fromMs) {
            return "";
        }
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(toMs);
        Calendar probe = Calendar.getInstance();
        probe.setTimeInMillis(fromMs);

        int years = 0;
        int months = 0;
        int days = 0;

        while (true) {
            Calendar next = (Calendar) probe.clone();
            next.add(Calendar.YEAR, 1);
            if (!next.after(target)) {
                probe = next;
                years++;
            } else {
                break;
            }
        }
        while (true) {
            Calendar next = (Calendar) probe.clone();
            next.add(Calendar.MONTH, 1);
            if (!next.after(target)) {
                probe = next;
                months++;
            } else {
                break;
            }
        }
        while (true) {
            Calendar next = (Calendar) probe.clone();
            next.add(Calendar.DAY_OF_MONTH, 1);
            if (!next.after(target)) {
                probe = next;
                days++;
            } else {
                break;
            }
        }

        return years + " " + plural(years, "год", "года", "лет")
                + " " + months + " " + plural(months, "месяц", "месяца", "месяцев")
                + " " + days + " " + plural(days, "день", "дня", "дней");
    }

    private static String plural(int n, String one, String few, String many) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) {
            return one;
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 10 || mod100 >= 20)) {
            return few;
        }
        return many;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        counting = false;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
}
