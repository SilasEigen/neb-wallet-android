package org.neuralenginebyte.wallet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;

/** The address as a QR code, dark on white so that every scanner reads it, with the NEB mark in the middle. */
final class QrView extends View {
    private final boolean[][] modules;
    private final Paint paint = new Paint();
    private final Drawable mark;

    QrView(Context context, String content, Drawable mark) {
        super(context);
        this.mark = mark;
        modules = Qr.encode(content);
        GradientDrawable white = new GradientDrawable();
        white.setColor(0xFFFFFFFF);
        white.setCornerRadius(18 * context.getResources().getDisplayMetrics().density);
        setBackground(white);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float quiet = getWidth() * 0.07f, cell = (getWidth() - 2 * quiet) / Qr.SIZE;
        paint.setColor(0xFF000000);
        for (int y = 0; y < Qr.SIZE; y++) {
            for (int x = 0; x < Qr.SIZE; x++) {
                if (modules[y][x]) {
                    canvas.drawRect(quiet + x * cell, quiet + y * cell, quiet + (x + 1) * cell + 0.5f,
                            quiet + (y + 1) * cell + 0.5f, paint);
                }
            }
        }
        // The mark hides 9 of the 41 modules across; the error correction of the code restores them.
        float half = 4.5f * cell, c = getWidth() / 2f;
        paint.setColor(MainActivity.color(233));
        canvas.drawRoundRect(c - half, c - half, c + half, c + half, cell, cell, paint);
        float w = half * 1.5f, h = w * mark.getIntrinsicHeight() / mark.getIntrinsicWidth();
        mark.setBounds((int) (c - w / 2), (int) (c - h / 2), (int) (c + w / 2), (int) (c + h / 2));
        mark.draw(canvas);
    }
}
