package com.telega.timer;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/** Premium dark timer screen. Rendering stays native and dependency-free. */
public class MainActivity extends Activity {
    private static final long SPIN_MS = 2400L, SPIN_STAGGER_MS = 150L;
    private static final long WATER_MS = 2600L, MORPH_MS = 900L;
    private static final int BUTTON_DEAD_COLOR = 0xFF6E0A0A;

    private DeathClockView clock;
    private MorphButton actionButton;
    private TextView secondsLabel, dateLabel, statusLabel;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long deathAtMs;
    private boolean busy, counting;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!counting) return;
            long now = System.currentTimeMillis(), left = deathAtMs - now;
            if (left <= 0L) {
                counting = false;
                secondsLabel.setText(R.string.time_is_up);
                statusLabel.setText("CYCLE COMPLETE");
                return;
            }
            int[] parts = splitSpan(now, deathAtMs);
            clock.tickTo(parts);
            clock.setLabels(spanLabels(parts));
            secondsLabel.setText(clockPart(left));
            handler.postDelayed(this, 1000L);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF09090D);
        getWindow().setNavigationBarColor(0xFF09090D);
        getWindow().getDecorView().setSystemUiVisibility(0);
        setContentView(buildLayout());
        int[] zero = {0, 0, 0};
        clock.setGroups(zero, zeroLabels());
        actionButton.setLabel(getString(R.string.death_date_button));
        actionButton.setTapListener(button -> onDeathDateRequested());
        entrance(findViewById(android.R.id.content));
    }

    private LinearLayout buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(22), dp(26), dp(22), dp(22));
        GradientDrawable backdrop = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF151017, 0xFF0B0B10, 0xFF08090D});
        root.setBackground(backdrop);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER);
        TextView eyebrow = label("T I M E   /   I S   F I N I T E", 10, 0xFFB84B50);
        eyebrow.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(eyebrow, matchWrap());
        TextView title = label(getString(R.string.screen_title), 25, 0xFFF5F0F2);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLetterSpacing(-0.025f);
        LinearLayout.LayoutParams titleLp = matchWrap();
        titleLp.topMargin = dp(9);
        top.addView(title, titleLp);
        TextView subtitle = label("A MOMENT, MEASURED IN SILENCE", 9, 0xFF77717D);
        subtitle.setLetterSpacing(0.17f);
        LinearLayout.LayoutParams subLp = matchWrap();
        subLp.topMargin = dp(7);
        top.addView(subtitle, subLp);
        root.addView(top, matchWrap());

        View divider = new View(this);
        GradientDrawable line = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x001E1C25, 0xFF6C2933, 0x001E1C25});
        divider.setBackground(line);
        LinearLayout.LayoutParams lineLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lineLp.topMargin = dp(23);
        root.addView(divider, lineLp);

        clock = new DeathClockView(this);
        LinearLayout.LayoutParams clockParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clockParams.topMargin = dp(10);
        root.addView(clock, clockParams);

        LinearLayout readout = new LinearLayout(this);
        readout.setOrientation(LinearLayout.VERTICAL);
        readout.setGravity(Gravity.CENTER);
        GradientDrawable readoutBg = new GradientDrawable();
        readoutBg.setColor(0x66301A22);
        readoutBg.setCornerRadius(dp(18));
        readoutBg.setStroke(dp(1), 0x553D2935);
        readout.setBackground(readoutBg);
        readout.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView timerCaption = label("LIVE COUNTDOWN", 9, 0xFF8D7785);
        timerCaption.setLetterSpacing(0.2f);
        readout.addView(timerCaption, matchWrap());
        secondsLabel = label("", 23, 0xFFFF7378);
        secondsLabel.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        secondsLabel.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams secParams = matchWrap();
        secParams.topMargin = dp(6);
        readout.addView(secondsLabel, secParams);
        dateLabel = label("", 11, 0xFF8B818D);
        LinearLayout.LayoutParams dateParams = matchWrap();
        dateParams.topMargin = dp(5);
        readout.addView(dateLabel, dateParams);
        LinearLayout.LayoutParams readoutLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        readoutLp.topMargin = dp(16);
        root.addView(readout, readoutLp);

        statusLabel = label("READY WHEN YOU ARE", 9, 0xFF6E6672);
        statusLabel.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams statusLp = matchWrap();
        statusLp.topMargin = dp(16);
        root.addView(statusLabel, statusLp);

        actionButton = new MorphButton(this);
        actionButton.setAccentColor(0xFF5C1822);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        buttonParams.topMargin = dp(14);
        root.addView(actionButton, buttonParams);

        TextView footer = label("DESIGNED FOR THE PRESENT MOMENT", 8, 0xFF4D4651);
        footer.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams footerLp = matchWrap();
        footerLp.topMargin = dp(13);
        root.addView(footer, footerLp);
        return root;
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void entrance(View view) {
        view.setAlpha(0f);
        view.setTranslationY(dp(12));
        view.animate().alpha(1f).translationY(0f).setDuration(650)
                .setInterpolator(new DecelerateInterpolator(1.7f)).start();
    }

    private void onDeathDateRequested() {
        if (busy || counting) return;
        busy = true;
        statusLabel.setText("SEQUENCE INITIALIZING");
        actionButton.setEnabledState(false);
        secondsLabel.setText("");
        dateLabel.setText("");
        long now = System.currentTimeMillis();
        deathAtMs = now + randomLifetimeMs();
        final int[] parts = splitSpan(now, deathAtMs);
        final String[] labels = spanLabels(parts);
        clock.spinTo(parts, labels, SPIN_MS, SPIN_STAGGER_MS, () ->
            clock.startWater(WATER_MS, () -> {
                actionButton.morphTo(getString(R.string.you_will_die_in), MORPH_MS, BUTTON_DEAD_COLOR);
                SimpleDateFormat fmt = new SimpleDateFormat("dd.MM.yyyy", Locale.getDefault());
                dateLabel.setText(getString(R.string.death_date_label) + ": " + fmt.format(new Date(deathAtMs)));
                statusLabel.setText("COUNTDOWN IN PROGRESS");
                busy = false;
                counting = true;
                handler.removeCallbacks(tick);
                handler.post(tick);
            }));
    }

    private static final Random RNG = new Random();
    private long randomLifetimeMs() {
        double years = 1.0d + RNG.nextDouble() * 59.0d;
        return (long) (years * 365.2425d * 24d * 3600d * 1000d);
    }

    private static int[] splitSpan(long fromMs, long toMs) {
        if (toMs <= fromMs) return new int[]{0, 0, 0};
        Calendar target = Calendar.getInstance(), probe = Calendar.getInstance();
        target.setTimeInMillis(toMs); probe.setTimeInMillis(fromMs);
        int years = 0, months = 0, days = 0;
        while (true) {
            Calendar next = (Calendar) probe.clone(); next.add(Calendar.YEAR, 1);
            if (next.after(target)) break; probe = next; years++;
        }
        while (true) {
            Calendar next = (Calendar) probe.clone(); next.add(Calendar.MONTH, 1);
            if (next.after(target)) break; probe = next; months++;
        }
        while (true) {
            Calendar next = (Calendar) probe.clone(); next.add(Calendar.DAY_OF_MONTH, 1);
            if (next.after(target)) break; probe = next; days++;
        }
        return new int[]{years, months, days};
    }

    private static String[] spanLabels(int[] p) {
        return new String[]{plural(p[0], "год", "года", "лет"),
                plural(p[1], "месяц", "месяца", "месяцев"),
                plural(p[2], "день", "дня", "дней")};
    }
    private static String[] zeroLabels() { return spanLabels(new int[]{0, 0, 0}); }
    private static String plural(int n, String one, String few, String many) {
        int a = Math.abs(n) % 10, b = Math.abs(n) % 100;
        if (a == 1 && b != 11) return one;
        if (a >= 2 && a <= 4 && (b < 10 || b >= 20)) return few;
        return many;
    }
    private static String clockPart(long ms) {
        long t = Math.max(0L, ms / 1000L);
        return String.format(Locale.US, "%02d:%02d:%02d",
                (t / 3600L) % 24L, (t % 3600L) / 60L, t % 60L);
    }
    private int dp(float value) {
        return (int)(value * getResources().getDisplayMetrics().density + 0.5f);
    }
    @Override protected void onDestroy() {
        counting = false;
        handler.removeCallbacks(tick);
        super.onDestroy();
    }
}
