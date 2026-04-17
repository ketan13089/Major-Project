package com.ketan.slam

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Encapsulates the two LLM assistant FABs ("Ask" + "Guide me to"), the
 * loading spinner, and the transient reply card. Wires voice input to the
 * right LLM flow and hands results back to the caller.
 *
 *   val ui = LlmAssistantUi(activity, root) { flow, transcript ->
 *       // dispatch to LlmAssistant on a background thread
 *   }
 *
 * Reply/TTS is driven separately by the caller (ArActivity already has
 * NavigationGuide for TTS).
 */
class LlmAssistantUi(
    private val activity: Activity,
    private val root: FrameLayout,
    /** Called on the UI thread when the user finishes speaking. */
    private val onUserSpoke: (flow: LlmTaskKind, transcript: String) -> Unit
) {
    private val dp = activity.resources.displayMetrics.density
    private val voice = LlmVoiceInput(activity)

    private val askButton: TextView
    private val guideButton: TextView
    private val loading: ProgressBar
    private val replyCard: TextView

    // Which flow is currently being requested via voice
    private var pendingFlow: LlmTaskKind? = null

    init {
        val fabSize = (56 * dp).toInt()

        askButton = makeFab("💬", 0xFF10B981.toInt(),
            "Ask about your surroundings. Tap and speak a question.") {
            trigger(LlmTaskKind.QUERY)
        }
        guideButton = makeFab("🧭", 0xFFEA580C.toInt(),
            "Guide me to a destination. Tap and say where you want to go.") {
            trigger(LlmTaskKind.NAVIGATE)
        }

        loading = ProgressBar(activity).apply {
            isIndeterminate = true
            visibility = View.GONE
        }

        replyCard = TextView(activity).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = GradientDrawable().apply {
                setColor(0xEE1F2937.toInt())
                cornerRadius = 18f * dp
            }
            setPadding((18 * dp).toInt(), (14 * dp).toInt(), (18 * dp).toInt(), (14 * dp).toInt())
            visibility = View.GONE
            elevation = 10f * dp
        }

        // Layout — stack both FABs along the bottom-start edge, above the
        // existing mic button which lives at bottom-end.
        root.addView(askButton, FrameLayout.LayoutParams(
            fabSize, fabSize, Gravity.BOTTOM or Gravity.START).apply {
            setMargins((20 * dp).toInt(), 0, 0, (20 * dp).toInt())
        })
        root.addView(guideButton, FrameLayout.LayoutParams(
            fabSize, fabSize, Gravity.BOTTOM or Gravity.START).apply {
            setMargins((20 * dp).toInt(), 0, 0, ((20 + 68) * dp).toInt())
        })

        root.addView(loading, FrameLayout.LayoutParams(
            (56 * dp).toInt(), (56 * dp).toInt(), Gravity.CENTER))

        root.addView(replyCard, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            setMargins((16 * dp).toInt(), (120 * dp).toInt(), (16 * dp).toInt(), 0)
        })
    }

    // ── Public control ───────────────────────────────────────────────────────

    fun showLoading(show: Boolean) {
        activity.runOnUiThread { loading.visibility = if (show) View.VISIBLE else View.GONE }
    }

    fun showReply(text: String, autoHideMs: Long = 8_000L) {
        activity.runOnUiThread {
            replyCard.text = text
            replyCard.visibility = View.VISIBLE
            replyCard.removeCallbacks(hideReply)
            replyCard.postDelayed(hideReply, autoHideMs)
        }
    }

    fun toast(msg: String) {
        activity.runOnUiThread { Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show() }
    }

    fun destroy() { voice.destroy() }

    // ── Internals ────────────────────────────────────────────────────────────

    private val hideReply = Runnable { replyCard.visibility = View.GONE }

    private fun trigger(flow: LlmTaskKind) {
        if (!LlmAssistantConfig.enabled) {
            toast("LLM assistant not configured. Add llm.assistant.api.key to local.properties.")
            return
        }
        pendingFlow = flow
        val prompt = when (flow) {
            LlmTaskKind.QUERY    -> "Listening — ask a question about your surroundings…"
            LlmTaskKind.NAVIGATE -> "Listening — where would you like to go?"
            else                 -> "Listening…"
        }
        toast(prompt)
        voice.start(
            onTranscript = { text ->
                val f = pendingFlow ?: return@start
                pendingFlow = null
                activity.runOnUiThread { onUserSpoke(f, text) }
            },
            onError = { msg ->
                pendingFlow = null
                toast(msg)
            },
            onListening = {}
        )
    }

    private fun makeFab(
        label: String, colorArgb: Int, description: String, onTap: () -> Unit
    ): TextView {
        return TextView(activity).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            elevation = 12f * dp
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorArgb)
            }
            contentDescription = description
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { onTap() }
        }
    }
}
