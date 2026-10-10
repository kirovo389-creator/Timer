package com.telega.timer;

import android.app.Activity;
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
 *   1. При открытии по центру приглушённые нули: «0 лет : 0 месяцев : 0 дней».
 *   2. Внизу кнопка «Дата смерти».
 *   3. По нажатию барабаны раскручиваются и останавливаются по одной цифре.
 *   4. Когда все встали — внутри контуров цифр снизу вверх набирается тёмная
 *      кровь (снаружи букв ничего не закрашивается).
 *   5. Кнопка превращается в «Ты умрёшь через», включается секундный отсчёт.
 */
public class MainActivity extends Activity {

    /** Сколько крутится барабан до остановки (мс). */
    private static final long SPIN_MS = 2400L;
    /** Задержка между остановками соседних барабанов (мс). */
    private static final long SPIN_STAGGER_MS = 150L;
    /** Длительность подъёма крови (мс). */
    private static final long WATER_MS = 2600L;
    /** Длительность превращения кнопки (мс). */
    private static final long MORPH_MS = 900L;

    /** Цвет кнопки после превращения — тёмная кровь. */
    private static final int BUTTON_DEAD_COLOR = 0xFF6E0A0A;

    private DeathClockView clock;
    private MorphButton actionButton;
    private TextView secondsLabel;
    private TextView dateLabel;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private long deathAtMs = 0L;
    private boolean busy = false;
    private boolean counting = false;

    /** Секундный отсчёт: значение считается от абсолютного времени,
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
                secondsLabel.setText(R.string.time_is_up);
                return;
            }
            int[] parts = splitSpan(now, deathAtMs);
            clock.tickTo(parts);
            clock.setLabels(spanLabels(parts));
            secondsLabel.setText(clockPart(left));
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(buildLayout());

        int[] zero = { 0, 0, 0 };
        clock.setGroups(zero, zeroLabels());
        actionButton.setLabel(getString(R.string.death_date_button));
        actionButton.setTapListener(button -> onDeathDateRequested());
    }

    private LinearLayout buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(0xFF0B0B0F);
        root.setPadding(dp(18), dp(30), dp(18), dp(24));

        TextView title = new TextView(this);
        title.setText(R.string.screen_title);
        title.setTextColor(0xFF33333C);
        title.setTextSize(11f);
        title.setLetterSpacing(0.42f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        clock = new DeathClockView(this);
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clockParams.topMargin = dp(6);
        root.addView(clock, clockParams);

        secondsLabel = new TextView(this);
        secondsLabel.setTextColor(0xFF8A1414);
        secondsLabel.setTextSize(15f);
        secondsLabel.setLetterSpacing(0.08f);
        secondsLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams secParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        secParams.topMargin = dp(14);
        root.addView(secondsLabel, secParams);

        dateLabel = new TextView(this);
        dateLabel.setTextColor(0xFF4E4E58);
        dateLabel.setTextSize(12f);
        dateLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dateParams.topMargin = dp(6);
        root.addView(dateLabel, dateParams);

        actionButton = new MorphButton(this);
        actionButton.setAccentColor(0xFF1C1C22);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(62));
        buttonParams.topMargin = dp(28);
        root.addView(actionButton, buttonParams);

        return root;
    }

    /** Нажатие «Дата смерти»: раскрутка -> кровь -> превращение кнопки -> отсчёт. */
    private void onDeathDateRequested() {
        if (busy || counting) {
            return;
        }
        busy = true;
        actionButton.setEnabledState(false);
        secondsLabel.setText("");
        dateLabel.setText("");

        long now = System.currentTimeMillis();
        deathAtMs = now + randomLifetimeMs();
        final int[] parts = splitSpan(now, deathAtMs);
        final String[] labels = spanLabels(parts);

        clock.spinTo(parts, labels, SPIN_MS, SPIN_STAGGER_MS, () ->
                clock.startWater(WATER_MS, () -> {
                    actionButton.morphTo(getString(R.string.you_will_die_in),
                            MORPH_MS, BUTTON_DEAD_COLOR);
                    SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
                    dateLabel.setText(getString(R.string.death_date_label) + ": "
                            + fmt.format(new Date(deathAtMs)));
                    busy = false;
                    counting = true;
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                }));
    }

    /** Один генератор на всё приложение: иначе два нажатия в одну миллисекунду
     *  дали бы одинаковый «остаток жизни». */
    private static final Random RNG = new Random();

    /** Случайный остаток жизни: от 1 года до 60 лет. */
    private long randomLifetimeMs() {
        double years = 1.0d + RNG.nextDouble() * 59.0d;
        return (long) (years * 365.2425d * 24d * 3600d * 1000d);
    }

    /** Разбивает остаток на {годы, месяцы, дни} календарно, а не делением на 365. */
    private static int[] splitSpan(long fromMs, long toMs) {
        if (toMs <= fromMs) {
            return new int[] { 0, 0, 0 };
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
        return new int[] { years, months, days };
    }

    /** Подписи с правильными склонениями под текущие значения. */
    private static String[] spanLabels(int[] parts) {
        return new String[] {
                plural(parts[0], "год", "года", "лет"),
                plural(parts[1], "месяц", "месяца", "месяцев"),
                plural(parts[2], "день", "дня", "дней")
        };
    }

    private static String[] zeroLabels() {
        return spanLabels(new int[] { 0, 0, 0 });
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

    /** Живая строка секунд: "04:15:33". */
    private static String clockPart(long ms) {
        long total = Math.max(0L, ms / 1000L);
        long hours = (total / 3600L) % 24L;
        long minutes = (total % 3600L) / 60L;
        long seconds = total % 60L;
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds);
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
