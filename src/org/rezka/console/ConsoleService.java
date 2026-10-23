package org.rezka.console;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;

import java.util.HashMap;
import java.util.Map;

/**
 * Плавающая консоль поверх всех приложений (в стиле StandOut, без зависимостей).
 * Живой процесс /system/bin/sh (или su), ввод команд, drag за заголовок,
 * ресайз за правый нижний угол, свернуть/развернуть, закрыть.
 *
 * Движение/размер применяются через updateViewLayout (стандарт флоатеров 4.x),
 * с троттлингом ~30мс на жёсткие поверхности. remove+add НЕ используем в цикле —
 * пересоздание окна на каждом шаге давало мигание и зависание UI.
 */
public class ConsoleService extends Service {

    static {
        System.loadLibrary("rezka_pty");
    }

    // Настоящий терминал (PTY) через нативную lib: пароли ssh/su, vi, top — всё работает.
    private native int ptyStart(String cmd);                 // открыть pty + fork + exec, вернуть master fd
    private native int ptyRead(int fd, byte[] buf, int len);
    private native int ptyWrite(int fd, byte[] buf, int off, int len);
    private native void ptyClose(int fd);
    private native void ptySetSize(int fd, int rows, int cols);
    private native int ptyLastError();                       // -(errno) последнего ptyStart

    public static volatile boolean running = false;

    private static final int ORANGE = Color.rgb(255, 140, 0);
    private static final int MAX_OUT = 8000; // символов в выводе, дальше — хвост
    private static final int KEEP_OUT = 6000;

    private WindowManager wm;
    private Handler ui = new Handler(Looper.getMainLooper());

    private View win;
    private WindowManager.LayoutParams p;

    private TextView out;
    private ScrollView sv;
    private EditText inEt;
    private TextView suBtn, minBtn, clsBtn, resizeBtn, title, resizeH;

    private int ptyFd = -1;   // PTY (обычный sh)
    private Process sh;       // старый путь только для su (Runtime.exec): mgyun-суперюзеру
    private OutputStream shIn;// PTY-форк мешает — он не поднимает диалог запроса прав
    private boolean suMode = false;
    private boolean expanded = true;

    private int wDef, hDef, wMin, hMin, hHead, screenW, screenH, visW, visH;

    @Override public void onCreate() {
        running = true;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
        visW = screenW;
        visH = screenH;
        float d = getResources().getDisplayMetrics().density;
        wDef = (int) (300 * d);
        hDef = (int) (380 * d);
        wMin = (int) (170 * d);
        hMin = (int) (110 * d);
        hHead = (int) (32 * d);

        buildWindow();
        p = new WindowManager.LayoutParams(
                wDef, hDef,
                WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        p.x = (int) (10 * d);
        p.y = (int) (60 * d);
        wm.addView(win, p);
        // узнаём фактическую видимую область окна (без статус/нав-баров)
        ui.post(new Runnable() {
            @Override public void run() {
                refreshVisible();
                clampPos();
                applyWindow();
            }
        });
        appendOut("Rezka Console — терминал поверх всего");
        // стартуем обычным юзером: суперюзер mgyun на планшете сломан
        // (su-запросы висят 20с и получают reject), су — по кнопке $/#
        suMode = false;
        suBtn.setText("$");
        title.setText("REZKA CONSOLE");
        inEt.setHint("~$");
        startShell("/system/bin/sh");
    }

    @Override public void onDestroy() {
        // сначала прячем окно — мгновенно, чтобы ни от чего не зависеть
        if (win != null) {
            try { wm.removeView(win); } catch (Exception ignored) { }
            win = null;
        }
        running = false;
        super.onDestroy();
        // pty и су-процесс закрываем в фоне
        final int fd = ptyFd;
        ptyFd = -1;
        if (fd >= 0) {
            new Thread(new Runnable() {
                @Override public void run() {
                    ptyClose(fd);
                }
            }).start();
        }
        final Process pr = sh;
        sh = null;
        shIn = null;
        if (pr != null) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { pr.destroy(); } catch (Exception ignored) { }
                }
            }).start();
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    /** Применить текущие параметры окна. Основной путь — updateViewLayout. */
    private void applyWindow() {
        try {
            wm.updateViewLayout(win, p);
        } catch (Exception e) {
            // страховка: окно могло отвалиться — пересоздаём заново
            try {
                wm.removeView(win);
            } catch (Exception ignored) { }
            try {
                wm.addView(win, p);
            } catch (Exception ignored) { }
        }
    }

    /** Окно получает фокус ввода: снимаем FLAG_NOT_FOCUSABLE. */
    private void activateInput() {
        p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        applyWindow();
    }

    /** Не даём окну вылезти за видимую область: и размер, и позицию втягиваем обратно. */
    private void clampPos() {
        int maxW = Math.max(0, visW - 8);
        int maxH = Math.max(0, visH - 8);
        if (p.width > maxW) p.width = maxW;
        if (p.height > maxH) p.height = maxH;
        int maxX = Math.max(0, visW - p.width - 8);
        int maxY = Math.max(0, visH - p.height - 8);
        if (p.x > maxX) p.x = maxX;
        if (p.x < 0) p.x = 0;
        if (p.y > maxY) p.y = maxY;
        if (p.y < 0) p.y = 0;
    }

    /** Фактическая видимая область окна (за вычетом статус/нав-баров). */
    private void refreshVisible() {
        try {
            android.graphics.Rect r = new android.graphics.Rect();
            win.getWindowVisibleDisplayFrame(r);
            if (r.width() > 0 && r.height() > 0) {
                visW = r.width();
                visH = r.height();
            }
        } catch (Exception ignored) { }
    }

    // ---------- окно ----------

    private void buildWindow() {
        float d = getResources().getDisplayMetrics().density;
        int pad4 = (int) (6 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xE6000000);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(0xFF2A2A2A);

        title = new TextView(this);
        title.setText("REZKA CONSOLE");
        title.setTextColor(ORANGE);
        title.setTextSize(12);
        title.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        title.setPadding(pad4, 0, pad4, 0);
        title.setGravity(Gravity.CENTER_VERTICAL);

        suBtn = smallBtn("$", 0xFFCCCCCC);
        minBtn = smallBtn("—", ORANGE);
        clsBtn = smallBtn("×", 0xFFE06050);
        resizeBtn = smallBtn("R", 0xFF88AAEE);

        head.addView(resizeBtn, smallLp());
        head.addView(title, new LinearLayout.LayoutParams(0, hHead, 1));
        head.addView(suBtn, smallLp());
        head.addView(minBtn, smallLp());
        head.addView(clsBtn, smallLp());

        out = new TextView(this);
        out.setTextColor(0xFFE2E2E2);
        out.setTextSize(11);
        out.setTypeface(Typeface.MONOSPACE);
        out.setGravity(Gravity.TOP | Gravity.START);
        out.setPadding(pad4, pad4, pad4, pad4);

        inEt = new EditText(this);
        inEt.setTypeface(Typeface.MONOSPACE);
        inEt.setTextColor(Color.WHITE);
        inEt.setHintTextColor(0xFF777777);
        inEt.setHint("~$");
        inEt.setSingleLine(true);
        inEt.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        inEt.setImeOptions(EditorInfo.IME_ACTION_SEND);
        inEt.setPadding(pad4, 0, pad4, 0);
        inEt.setBackgroundColor(0xFF1A1A1A);

        // ресайз-уголок
        resizeH = new TextView(this);
        resizeH.setText("◢");
        resizeH.setTextSize(13);
        resizeH.setTextColor(0xFF888888);
        resizeH.setGravity(Gravity.BOTTOM | Gravity.RIGHT);
        resizeH.setPadding(0, 0, pad4, 0);

        root.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, hHead));
        // вывод — в ScrollView, чтобы можно было листать пальцем (голый TextView не скроллится)
        sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(out, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(inEt, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (34 * d)));
        root.addView(resizeH, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (18 * d)));
        win = root;

        // drag за заголовок (кнопки перехватывают свои тачи сами)
        head.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy, ox, oy;
            long last;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = (int) ev.getRawX();
                        sy = (int) ev.getRawY();
                        ox = p.x;
                        oy = p.y;
                        last = 0;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        p.x = ox + (int) ev.getRawX() - sx;
                        p.y = oy + (int) ev.getRawY() - sy;
                        long now = SystemClock.uptimeMillis();
                        if (now - last > 30) {
                            last = now;
                            applyWindow();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        applyWindow(); // фиксируем финальную позицию
                        return true;
                }
                return true;
            }
        });

        // ресайз кнопкой слева в заголовке: зажал — и размер едет за пальцем
        resizeBtn.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy, ow, oh;
            long last;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!expanded) { // свёрнуто — сначала разворачиваем
                            expanded = true;
                            minBtn.setText("—");
                            out.setVisibility(View.VISIBLE);
                            inEt.setVisibility(View.VISIBLE);
                            resizeH.setVisibility(View.VISIBLE);
                            p.height = hMin;
                        }
                        sx = (int) ev.getRawX();
                        sy = (int) ev.getRawY();
                        ow = p.width;
                        oh = p.height;
                        last = 0;
                        v.setBackgroundColor(0xFF333333); // подсветка: кнопка зажата
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        // дед-зона 20px: случайный дрейф пальца не должен растягивать окно
                        int dxR = (int) ev.getRawX() - sx;
                        int dyR = (int) ev.getRawY() - sy;
                        if (Math.abs(dxR) + Math.abs(dyR) < 20) return true;
                        p.width = Math.max(wMin, Math.min(visW - 10, ow + dxR));
                        p.height = Math.max(hMin, Math.min(visH - 10, oh + dyR));
                        clampPos(); // низ не должен уехать за экран
                        long now = SystemClock.uptimeMillis();
                        if (now - last > 30) {
                            last = now;
                            applyWindow();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        applyWindow(); // фиксируем размер
                        v.setBackgroundColor(0x00000000);
                        return true;
                }
                return true;
            }
        });

        // ресайз за правый нижний угол
        resizeH.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy, ow, oh;
            long last;
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (!expanded) return false;
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = (int) ev.getRawX();
                        sy = (int) ev.getRawY();
                        ow = p.width;
                        oh = p.height;
                        last = 0;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        // дед-зона 20px от старта
                        int dxU = (int) ev.getRawX() - sx;
                        int dyU = (int) ev.getRawY() - sy;
                        if (Math.abs(dxU) + Math.abs(dyU) < 20) return true;
                        p.width = Math.max(wMin, Math.min(visW - 10, ow + dxU));
                        p.height = Math.max(hMin, Math.min(visH - 10, oh + dyU));
                        clampPos(); // низ не должен уехать за экран
                        long now = SystemClock.uptimeMillis();
                        if (now - last > 30) {
                            last = now;
                            applyWindow();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        applyWindow();
                        return true;
                }
                return true;
            }
        });

        // любой тап по телу окна — активируем окно для ввода (снимаем NOT_FOCUSABLE).
        // Заголовок перехватывает драг сам, кнопки едят свои тачи — сюда приходит
        // только «мёртвая» зона (вывод) и промахи мимо контролов.
        win.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) activateInput();
                return false;
            }
        });

        // фокус строки ввода: клавиатура просит снять FLAG_NOT_FOCUSABLE.
        // На 4.x тап по EditText сам по себе фокус не даёт — берём инициативу в руки.
        inEt.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    inEt.setFocusable(true);
                    inEt.setFocusableInTouchMode(true);
                    inEt.requestFocus();
                    activateInput();
                    // клавиатура на 4.x приходит не сразу — показываем после снятия флага
                    inEt.postDelayed(new Runnable() {
                        @Override public void run() {
                            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                            if (imm != null) imm.showSoftInput(inEt, 0);
                        }
                    }, 200);
                }
                return false; // пусть EditText дальше сам ест тачи (курсор, выделение)
            }
        });

        inEt.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                p.flags = hasFocus ? (p.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                                   : (p.flags | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                applyWindow();
            }
        });

        inEt.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND
                        || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    sendCmd(inEt.getText().toString());
                    return true;
                }
                return false;
            }
        });

        // Расширенные клавиатуры шлют спецклавиши KeyEvent'ами (как аппаратные):
        // перехватываем и уводим в терминал. Стрелки -> ESC-последовательности,
        // Ctrl+буква -> управляющий код 0x01..0x1A (Ctrl+C=0x03, Ctrl+Z=0x1A...),
        // Tab/Del/Home/End/PgUp/PgDn/Esc — свои коды. Клавиатуру при этом не прячем.
        inEt.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View v, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (event.isCtrlPressed()) {
                    if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) {
                        sendBytes("" + (char) (keyCode - KeyEvent.KEYCODE_A + 1));
                        return true;
                    }
                    // с Ctrl что-то ещё — съедаем, чтобы EditText не делал copy/paste
                    return true;
                }
                String s = null;
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_UP:     s = "\u001b[A"; break;
                    case KeyEvent.KEYCODE_DPAD_DOWN:   s = "\u001b[B"; break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:  s = "\u001b[C"; break;
                    case KeyEvent.KEYCODE_DPAD_LEFT:   s = "\u001b[D"; break;
                    case KeyEvent.KEYCODE_TAB: {
                        // дополнение: шлём набранный текст вместе с TAB (без Enter —
                        // чтобы bash показал кандидатов, а не выполнил команду)
                        String t = inEt.getText().toString();
                        if (t.length() > 0) {
                            sendBytes(t + "\t");
                            inEt.setText("");
                        } else {
                            sendBytes("\t");
                        }
                        return true;
                    }
                    // KEYCODE_DEL/FORWARD_DEL намеренно НЕ перехватываем:
                    // Backspace должен править текст в строке ввода (локально),
                    // а не слать 0x7F в PTY — ввод в терминал уходит только по Enter.
                    case KeyEvent.KEYCODE_MOVE_HOME:   s = "\u001b[H"; break;
                    case KeyEvent.KEYCODE_MOVE_END:    s = "\u001b[F"; break;
                    case KeyEvent.KEYCODE_PAGE_UP:     s = "\u001b[5~"; break;
                    case KeyEvent.KEYCODE_PAGE_DOWN:   s = "\u001b[6~"; break;
                    case KeyEvent.KEYCODE_ESCAPE:      s = "\u001b"; break;
                    case KeyEvent.KEYCODE_ENTER:
                        sendCmd(inEt.getText().toString());
                        return true;
                }
                if (s != null) {
                    sendBytes(s);
                    return true;
                }
                return false;
            }
        });

        suBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                suMode = !suMode;
                suBtn.setText(suMode ? "#" : "$");
                title.setText(suMode ? "REZKA CONSOLE #" : "REZKA CONSOLE");
                inEt.setHint(suMode ? "~#" : "~$");
                startShell(suMode ? suPath() : "/system/bin/sh");
                appendOut(suMode ? "жми в строку и пиши команду (уже от рута)" : "щелк по строке — клавиатура и команды");
            }
        });

        minBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                expanded = !expanded;
                minBtn.setText(expanded ? "—" : "+");
                out.setVisibility(expanded ? View.VISIBLE : View.GONE);
                inEt.setVisibility(expanded ? View.VISIBLE : View.GONE);
                resizeH.setVisibility(expanded ? View.VISIBLE : View.GONE);
                p.height = expanded ? (p.height > hHead ? p.height : hDef) : hHead;
                refreshVisible(); // видимая область могла измениться
                clampPos(); // развернули — окно целиком в видимой области
                applyWindow();
                inEt.clearFocus();
                inEt.setHint(suMode ? "~#" : "~$");
                hideKbd();
            }
        });

        clsBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopSelf();
            }
        });
    }

    private TextView smallBtn(String t, int color) {
        TextView b = new TextView(this);
        b.setText(t);
        b.setTextSize(13);
        b.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(color);
        return b;
    }

    private LinearLayout.LayoutParams smallLp() {
        return new LinearLayout.LayoutParams(hHead, hHead);
    }

    // ---------- ANSI-фильтр ----------

    /** Убирает ANSI/VT100-управляющие последовательности из вывода:
     *  CSI (ESC [ ... ), OSC (ESC ] ... BEL или ESC \), смену кодировки (ESC ( ),
     *  одиночные ESC-коды. Состояние хранится между чанками — последовательность
     *  может быть разорвана чтениями. Текст (в т.ч. кириллица) и \n \r \t — наружу. */
    private static class EscFilter {
        private static final int T = 0, E = 1, CS = 2, OS = 3, CH = 4, OE = 5;
        private int st = T;
        /** параметры текущей CSI-последовательности (для распознавания очистки экрана) */
        private final StringBuilder csi = new StringBuilder();
        /** выставлен, если в выводе была полная очистка экрана (CSI 2J / 3J) */
        boolean wantClear = false;

        void reset() { st = T; csi.setLength(0); wantClear = false; }

        String clean(String in) {
            StringBuilder b = new StringBuilder(in.length());
            for (int i = 0; i < in.length(); i++) {
                char c = in.charAt(i);
                switch (st) {
                    case T:
                        if (c == 0x1B) { st = E; }
                        else if (c == '\n' || c == '\r' || c == '\t') b.append(c);
                        else if (c >= 0x20 && c < 0x7F || c >= 0xA0) b.append(c);
                        // прочие C0-управляющие и DEL — мусор, выкидываем
                        break;
                    case E:
                        if (c == '[') st = CS;
                        else if (c == ']') st = OS;
                        else if (c == '(' || c == ')') st = CH;
                        else st = T; // одиночные ESC-коды (7,8,M,=,>,c...)
                        break;
                    case CS:
                        if (c >= 0x40 && c <= 0x7E) {
                            // final byte: ED (J) чистит экран. clear шлёт 2J, а скрипты
                            // часто пишут голый ESC[J (=0J, «от курсора до конца») —
                            // для нашей ленты без курсора это тоже очистка всего.
                            if (c == 'J') {
                                String p = csi.toString();
                                if (p.length() == 0 || p.equals("0") || p.equals("2") || p.equals("3")) {
                                    wantClear = true;
                                }
                            }
                            csi.setLength(0);
                            st = T;
                        } else if (c >= 0x20 && c <= 0x3F) {
                            csi.append(c); // параметры/разделители CSI
                        }
                        break;
                    case OS:
                        if (c == 0x07) st = T;      // BEL завершает OSC
                        else if (c == 0x1B) st = OE;
                        break;
                    case OE:
                        st = (c == '\\') ? T : OS;  // ESC \ завершает OSC
                        break;
                    case CH:
                        st = T;                     // один символ после ESC ( / ESC )
                        break;
                }
            }
            return b.toString();
        }
    }

    private final EscFilter esc = new EscFilter();

    // ---------- вывод ----------

    private void appendOut(final String s) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (out == null) return;
                CharSequence cur = out.getText();
                if (cur.length() > MAX_OUT) {
                    out.setText(cur.subSequence(cur.length() - KEEP_OUT, cur.length()));
                }
                out.append(s + "\n");
                // автоскролл вниз, только если юзер и так внизу (не мешаем читать)
                if (sv != null) {
                    View child = sv.getChildAt(0);
                    if (child != null) {
                        int diff = child.getBottom() - (sv.getScrollY() + sv.getHeight());
                        if (diff < 160) sv.fullScroll(View.FOCUS_DOWN);
                    }
                }
            }
        });
    }

    /** Отправить сырые байты в терминал (спецклавиши, Ctrl-коды, ESC-последовательности). */
    private void sendBytes(String s) {
        if (ptyFd >= 0) {
            byte[] b;
            try {
                b = s.getBytes("UTF-8");
            } catch (Exception e) {
                return;
            }
            int r = ptyWrite(ptyFd, b, 0, b.length);
            if (r < 0) appendOut("[!] pty: err=" + r);
        } else if (shIn != null) {
            try {
                shIn.write(s.getBytes("UTF-8"));
                shIn.flush();
            } catch (Exception e) {
                appendOut("[!] " + e);
            }
        }
    }

    private void sendCmd(String c) {
        c = c.trim();
        if (c.length() == 0) return;
        if (ptyFd >= 0) {
            // PTY: эхо и приглашение делает сам sh
            byte[] b;
            try {
                b = (c + "\n").getBytes("UTF-8");
            } catch (Exception e) {
                appendOut("[!] " + e);
                return;
            }
            int r = ptyWrite(ptyFd, b, 0, b.length);
            if (r < 0) appendOut("[!] pty: err=" + r);
        } else if (shIn != null) {
            // su (Runtime.exec): терминала нет — эхо рисуем сами
            appendOut((suMode ? "# " : "$ ") + c);
            try {
                shIn.write((c + "\n").getBytes("UTF-8"));
                shIn.flush();
            } catch (Exception e) {
                appendOut("[!] " + e);
            }
        } else {
            appendOut("[!] шелл не запущен");
            return;
        }
        inEt.setText("");
        inEt.clearFocus();
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        applyWindow();
        hideKbd();
    }

    private void hideKbd() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && inEt != null) imm.hideSoftInputFromWindow(inEt.getWindowToken(), 0);
    }

    // ---------- шелл ----------

    private String suPath() {
        return new File("/system/xbin/su").exists() ? "/system/xbin/su" : "/system/bin/su";
    }

    private static String ptyErrText(int e) {
        switch (e) {
            case 1: return "EPERM";
            case 2: return "ENOENT";
            case 5: return "EIO";
            case 12: return "ENOMEM";
            case 13: return "EACCES";
            case 16: return "EBUSY";
            case 19: return "ENODEV";
            case 22: return "EINVAL";
            case 25: return "ENOTTY";
            case 30: return "EROFS";
            default: return "?";
        }
    }

    private void startShell(final String cmd) {
        esc.reset();
        // закрываем старый терминал
        final int oldFd = ptyFd;
        ptyFd = -1;
        if (oldFd >= 0) {
            new Thread(new Runnable() {
                @Override public void run() { ptyClose(oldFd); }
            }).start();
        }
        final Process oldSh = sh;
        sh = null;
        shIn = null;
        if (oldSh != null) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try { oldSh.destroy(); } catch (Exception ignored) { }
                }
            }).start();
        }

        if (cmd.endsWith("su")) {
            // su — как в первых версиях: Runtime.exec. Этот путь поднимает
            // диалог суперюзера (mgyun/irootsu); PTY-форк этого не умел —
            // су висел 20с и получал reject без запроса прав.
            // env чистим от USER: прошивка кладёт USER=u0_XX, и mksh рисует
            // его в промпте вместо настоящего юзера (врал даже у root).
            try {
                Process p = Runtime.getRuntime().exec(cmd, cleanEnv());
                sh = p;
                shIn = p.getOutputStream();
                final BufferedReader bo = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                final BufferedReader be = new BufferedReader(new InputStreamReader(p.getErrorStream(), "UTF-8"));
                Thread to = new Thread(new Runnable() { @Override public void run() { pump(bo); } });
                Thread te = new Thread(new Runnable() { @Override public void run() { pump(be); } });
                to.setDaemon(true);
                te.setDaemon(true);
                to.start();
                te.start();
                appendOut("ждём запрос прав суперюзера...");
            } catch (Exception e) {
                appendOut("[!] нет такого шелла: " + cmd + " — " + e);
            }
            return;
        }

        // обычный sh — настоящий терминал (PTY): пароли ssh, vi, top
        int fd = -1;
        try {
            fd = ptyStart(cmd);
        } catch (Throwable t) {
            appendOut("[!] натив: " + t);
            return;
        }
        if (fd < 0) {
            int e = ptyLastError();
            appendOut("[!] не удалось поднять PTY для: " + cmd
                    + "  (errno=" + e + " " + ptyErrText(e) + ")");
            return;
        }
        final int thisFd = fd;
        ptyFd = thisFd;
        ptySetSize(thisFd, 24, 80);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                ptyPump(thisFd);
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void pump(BufferedReader br) {
        try {
            String l;
            while ((l = br.readLine()) != null) {
                if (esc.wantClear) {
                    // clear в су-режиме: стираем вывод (appendClear постом — раньше appendOut)
                    appendClear();
                }
                appendOut(esc.clean(l));
                esc.wantClear = false;
            }
        } catch (Exception ignored) { }
    }

    private void appendClear() {
        ui.post(new Runnable() {
            @Override public void run() {
                if (out != null) out.setText("");
            }
        });
    }

    /** env для су: как у нас, но без USER — mksh тогда сам вычислит имя из id (честный промпт). */
    private static String[] cleanEnv() {
        Map<String, String> env = new HashMap<String, String>(System.getenv());
        env.remove("USER");
        String[] out = new String[env.size()];
        int i = 0;
        for (Map.Entry<String, String> e : env.entrySet())
            out[i++] = e.getKey() + "=" + e.getValue();
        return out;
    }

    private void ptyPump(int fd) {
        byte[] buf = new byte[4096];
        while (true) {
            int n = ptyRead(fd, buf, buf.length);
            if (n <= 0) break;
            final byte[] chunk = new byte[n];
            System.arraycopy(buf, 0, chunk, 0, n);
            appendRaw(chunk);
        }
    }

    /** Потоковый вывод с PTY (без принудительных переводов строки). */
    private void appendRaw(final byte[] chunk) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (out == null) return;
                String s;
                try {
                    s = new String(chunk, "UTF-8");
                } catch (Exception e) {
                    s = "?";
                }
                s = esc.clean(s);
                if (esc.wantClear) {
                    esc.wantClear = false;
                    if (out != null) out.setText("");
                }
                CharSequence cur = out.getText();
                if (cur.length() > MAX_OUT) {
                    out.setText(cur.subSequence(cur.length() - KEEP_OUT, cur.length()));
                }
                out.append(s);
                if (sv != null) {
                    View child = sv.getChildAt(0);
                    if (child != null) {
                        int diff = child.getBottom() - (sv.getScrollY() + sv.getHeight());
                        if (diff < 160) sv.fullScroll(View.FOCUS_DOWN);
                    }
                }
            }
        });
    }
}