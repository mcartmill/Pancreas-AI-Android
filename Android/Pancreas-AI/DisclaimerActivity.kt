package com.pancreas.ai

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * DisclaimerActivity
 *
 * Shown exactly once — on first launch after install or after a data clear.
 * The user must scroll through the disclaimer and tick a checkbox before
 * the "I Understand" button becomes enabled.  Acceptance is persisted via
 * CredentialsManager so subsequent launches go straight to MainActivity.
 */
class DisclaimerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If already accepted (any launch after the first), go straight to
        // MainActivity. Only show the full disclaimer when:
        //   a) it has never been accepted yet (first install / data clear), OR
        //   b) the user opened it deliberately via the menu (EXTRA_FORCE_SHOW).
        val forceShow = intent.getBooleanExtra(EXTRA_FORCE_SHOW, false)
        if (!forceShow && CredentialsManager.hasAcceptedDisclaimer(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // ── Root layout ─────────────────────────────────────────────────────
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#070F1C"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ── Top bar ─────────────────────────────────────────────────────────
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A1628"))
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }
        val appName = TextView(this).apply {
            text = "🩸  PancreasAI"
            textSize = 22f
            setTextColor(Color.parseColor("#00BCD4"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val subtitle = TextView(this).apply {
            text = "Important Information — Please Read"
            textSize = 13f
            setTextColor(Color.parseColor("#546E7A"))
            setPadding(0, 4, 0, 0)
        }
        topBar.addView(appName)
        topBar.addView(subtitle)

        // ── Divider ─────────────────────────────────────────────────────────
        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#00BCD4"))
            alpha = 0.3f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            )
        }

        // ── Scrollable content ──────────────────────────────────────────────
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        // Section helper lambdas
        fun sectionHeader(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(Color.parseColor("#00BCD4"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(20), 0, dp(8))
        }

        fun sectionBody(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Color.parseColor("#B0C4D0"))
            lineHeight = (textSize * 1.6f).toInt()
            setPadding(0, 0, 0, dp(8))
        }

        fun warningBox(text: String): LinearLayout {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1A0A0A"))
                setPadding(dp(16), dp(14), dp(16), dp(14))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, dp(8), 0, dp(8))
                layoutParams = lp
            }
            // Red left accent
            val accent = View(this).apply {
                setBackgroundColor(Color.parseColor("#FF5252"))
                layoutParams = LinearLayout.LayoutParams(dp(4),
                    LinearLayout.LayoutParams.MATCH_PARENT)
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val msg = TextView(this).apply {
                this.text = text
                textSize = 13f
                setTextColor(Color.parseColor("#FFCDD2"))
                setPadding(dp(12), 0, 0, 0)
                lineHeight = (textSize * 1.6f).toInt()
            }
            row.addView(accent)
            row.addView(msg)
            box.addView(row)
            return box
        }

        // ── Section 1: Not a Medical Device ─────────────────────────────────
        contentLayout.addView(sectionHeader("⚠️  Not a Medical Device"))
        contentLayout.addView(warningBox(
            "PancreasAI is a personal data companion app. It is NOT a certified " +
            "medical device, and has NOT been evaluated or approved by the FDA, CE, " +
            "or any other regulatory authority."
        ))
        contentLayout.addView(sectionBody(
            "This app displays glucose data retrieved from the Dexcom Share API. " +
            "It does not replace your Dexcom receiver, the Dexcom app, or any " +
            "other clinically approved diabetes management system."
        ))

        // ── Section 2: No Medical Advice ────────────────────────────────────
        contentLayout.addView(sectionHeader("🩺  No Medical Advice"))
        contentLayout.addView(sectionBody(
            "Nothing in PancreasAI — including glucose readings, charts, " +
            "statistics, pattern analysis, predictive alerts, estimated ISF/ICR, " +
            "or AI-generated insights — constitutes medical advice."
        ))
        contentLayout.addView(warningBox(
            "Do NOT adjust your insulin doses, basal rates, or treatment plan " +
            "based solely on information shown in this app. Always consult your " +
            "endocrinologist, diabetes care team, or a qualified healthcare provider."
        ))

        // ── Section 3: Data Accuracy ─────────────────────────────────────────
        contentLayout.addView(sectionHeader("📡  Data Accuracy"))
        contentLayout.addView(sectionBody(
            "Glucose readings displayed in this app are fetched from the Dexcom " +
            "Share API and may be delayed, incomplete, or unavailable due to network " +
            "conditions, sensor errors, or API outages. Always verify critical " +
            "readings with a fingerstick blood glucose meter when making treatment decisions."
        ))
        contentLayout.addView(sectionBody(
            "Predictive alerts are estimates based on recent rate-of-change. " +
            "They are not guaranteed to be accurate and may be delayed or absent. " +
            "Do not rely on them as your sole hypoglycaemia or hyperglycaemia warning."
        ))

        // ── Section 4: AI Insights ────────────────────────────────────────────
        contentLayout.addView(sectionHeader("🤖  AI-Generated Insights"))
        contentLayout.addView(sectionBody(
            "The optional AI Insights feature sends anonymised statistical summaries " +
            "to Anthropic's API. Suggestions generated by AI are for informational " +
            "purposes only and are not a substitute for personalised clinical guidance. " +
            "Use of this feature requires your own Anthropic API key."
        ))

        // ── Section 5: Emergency ─────────────────────────────────────────────
        contentLayout.addView(sectionHeader("🚨  In an Emergency"))
        contentLayout.addView(warningBox(
            "If you are experiencing a severe hypoglycaemic or hyperglycaemic " +
            "episode, stop using this app and seek immediate medical attention. " +
            "Call emergency services or go to your nearest emergency department."
        ))

        // ── Section 6: Privacy ───────────────────────────────────────────────
        contentLayout.addView(sectionHeader("🔒  Your Privacy"))
        contentLayout.addView(sectionBody(
            "All health data — glucose readings, insulin logs, and meal logs — is " +
            "stored exclusively on your device, encrypted with AES-256-GCM via the " +
            "Android hardware Keystore. We do not operate servers that collect or " +
            "store your personal health data. No data is sold or shared with " +
            "third-party advertisers."
        ))

        // Privacy policy link
        val privacyText = "Read our full Privacy Policy at pancreas-ai.com/privacy.html"
        val spannable = SpannableString(privacyText)
        val linkStart = privacyText.indexOf("pancreas-ai.com")
        val linkEnd   = privacyText.length
        spannable.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://pancreas-ai.com/privacy.html")))
            }
        }, linkStart, linkEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(
            ForegroundColorSpan(Color.parseColor("#00BCD4")),
            linkStart, linkEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        val privacyView = TextView(this).apply {
            text = spannable
            textSize = 13f
            setTextColor(Color.parseColor("#B0C4D0"))
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            setPadding(0, dp(4), 0, dp(16))
        }
        contentLayout.addView(privacyView)

        // ── Section 7: Liability ─────────────────────────────────────────────
        contentLayout.addView(sectionHeader("📋  Limitation of Liability"))
        contentLayout.addView(sectionBody(
            "To the fullest extent permitted by law, the developers of PancreasAI " +
            "accept no liability for any harm, injury, loss, or damage arising from " +
            "use of this application. By continuing, you acknowledge that you use " +
            "this app entirely at your own risk."
        ))

        // Version note
        contentLayout.addView(TextView(this).apply {
            text = "PancreasAI v1.0.6  ·  For personal use on Android"
            textSize = 11f
            setTextColor(Color.parseColor("#374955"))
            setPadding(0, dp(16), 0, dp(8))
        })

        scrollView.addView(contentLayout)

        // ── Bottom bar: checkbox + button ───────────────────────────────────
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0A1628"))
            setPadding(dp(24), dp(16), dp(24), dp(24))
        }

        val divider2 = View(this).apply {
            setBackgroundColor(Color.parseColor("#00BCD4"))
            alpha = 0.2f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
            ).also { it.setMargins(0, 0, 0, dp(16)) }
        }

        val checkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
        }

        val checkBox = CheckBox(this).apply {
            setButtonTintList(android.content.res.ColorStateList.valueOf(
                Color.parseColor("#00BCD4")
            ))
        }

        val checkLabel = TextView(this).apply {
            text = "I have read and understood this disclaimer. " +
                   "I will not use PancreasAI as a substitute for professional medical advice."
            textSize = 12f
            setTextColor(Color.parseColor("#90A4AE"))
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        checkRow.addView(checkBox)
        checkRow.addView(checkLabel)

        val acceptBtn = Button(this).apply {
            text = "I Understand — Continue to App"
            textSize = 14f
            setTextColor(Color.parseColor("#070F1C"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setBackgroundColor(Color.parseColor("#546E7A"))  // disabled colour
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Enable button only when checkbox is ticked
        checkBox.setOnCheckedChangeListener { _, checked ->
            acceptBtn.isEnabled = checked
            acceptBtn.setBackgroundColor(
                if (checked) Color.parseColor("#00BCD4")
                else Color.parseColor("#546E7A")
            )
        }

        // On accept: persist and launch MainActivity
        acceptBtn.setOnClickListener {
            CredentialsManager.setDisclaimerAccepted(this)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        bottomBar.addView(divider2)
        bottomBar.addView(checkRow)
        bottomBar.addView(acceptBtn)

        // ── Assemble root ────────────────────────────────────────────────────
        root.addView(topBar)
        root.addView(divider)
        root.addView(scrollView)
        root.addView(bottomBar)

        setContentView(root)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        /** Pass this extra as true when launching from the menu so the
         *  screen shows even if the user has already accepted. */
        const val EXTRA_FORCE_SHOW = "force_show_disclaimer"
    }
}
