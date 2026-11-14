package com.studioasmr.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object Ui {
    val BG = Color.parseColor("#0F1720")
    val CARD = Color.parseColor("#18232F")
    val ACCENT = Color.parseColor("#3DDC97")
    val TEXT = Color.parseColor("#EAF1F7")
    val SUB = Color.parseColor("#8FA3B5")
    val DANGER = Color.parseColor("#FF6B6B")
    val BTN2 = Color.parseColor("#26384A")
    val ON_ACCENT = Color.parseColor("#06251A")

    fun dp(ctx: Context, v: Int): Int {
        return (v * ctx.resources.displayMetrics.density).toInt()
    }

    fun rounded(color: Int, radiusPx: Float): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = radiusPx
        return d
    }

    fun text(
        ctx: Context,
        s: String,
        sizeSp: Float = 15f,
        color: Int = TEXT,
        bold: Boolean = false
    ): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = sizeSp
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    fun button(ctx: Context, s: String, primary: Boolean, onClick: () -> Unit): Button {
        val b = Button(ctx)
        b.text = s
        b.setAllCaps(false)
        b.textSize = 15f
        b.stateListAnimator = null
        b.setTextColor(if (primary) ON_ACCENT else TEXT)
        b.background = rounded(if (primary) ACCENT else BTN2, dp(ctx, 12).toFloat())
        b.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12))
        b.setOnClickListener { onClick() }
        return b
    }

    fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 16))
        c.background = rounded(CARD, dp(ctx, 16).toFloat())
        return c
    }

    fun params(ctx: Context, topDp: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        p.topMargin = dp(ctx, topDp)
        return p
    }

    fun add(parent: LinearLayout, child: View, topDp: Int) {
        parent.addView(child, params(parent.context, topDp))
    }
}
