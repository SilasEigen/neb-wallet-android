package org.neuralenginebyte.wallet;

import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

final class KeyDialog {
    private KeyDialog() { }

    /** Shows the secret key behind asterisks; the eye reveals it. */
    static void show(MainActivity a, String key) {
        TextView value = a.text("", 16, 120, false);
        boolean[] shown = {false};
        Runnable draw = () -> {
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < key.length(); i += 16) {
                String part = key.substring(i, Math.min(key.length(), i + 16));
                s.append(shown[0] ? part : part.replaceAll(".", "*")).append(i + 16 < key.length() ? "\n" : "");
            }
            value.setText(s);
        };
        draw.run();
        ImageButton eye = new ImageButton(a);
        eye.setImageResource(android.R.drawable.ic_menu_view);
        eye.setBackgroundColor(0);
        eye.setContentDescription("Show or hide the key");
        eye.setOnClickListener(v -> {
            shown[0] = !shown[0];
            draw.run();
        });
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(a.px(24), a.px(12), a.px(12), 0);
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(eye);
        TextView note = new TextView(a);
        note.setText("Write this key down and keep it off the phone. Anyone who has it can spend the coins.\n\n"
                + "If the app is removed, its data is cleared or the phone is lost, the coins can only be recovered "
                + "with this key: Import a key in this app, or neb-wallet import KEY on a computer.");
        note.setPadding(a.px(24), a.px(14), a.px(24), 0);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(row);
        box.addView(note);
        a.secure(new AlertDialog.Builder(a).setTitle("Secret key").setView(box).setCancelable(false)
                .setPositiveButton("Done", null)
                .setNeutralButton("Copy and close", (d, w) -> a.copy("NEB key", key))
                .create());
    }
}
