package dev.hehe.sketch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import dev.hehe.sketch.core.SketchRegistry

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val entryContainer = findViewById<LinearLayout>(R.id.entryContainer)
        val emptyView = findViewById<TextView>(R.id.emptyView)
        val entrySectionTitle = findViewById<TextView>(R.id.entrySectionTitle)
        val homeCount = findViewById<TextView>(R.id.homeCount)
        val entries = SketchRegistry.discover(this)
        homeCount.text = getString(R.string.sketch_home_count, entries.size)

        if (entries.isEmpty()) {
            emptyView.text = getString(R.string.sketch_home_empty)
            emptyView.visibility = View.VISIBLE
            entrySectionTitle.visibility = View.GONE
            return
        }

        emptyView.visibility = View.GONE
        entrySectionTitle.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        entries.forEachIndexed { index, entry ->
            val card = inflater.inflate(
                R.layout.item_sketch_entry,
                entryContainer,
                false
            ) as MaterialCardView

            card.findViewById<TextView>(R.id.entryTitle).text = entry.title
            card.findViewById<TextView>(R.id.entryModule).text = entry.moduleName
            card.findViewById<TextView>(R.id.entryIndex).text = (index + 1).toString().padStart(2, '0')
            card.findViewById<TextView>(R.id.entrySummary).text =
                entry.summary ?: getString(R.string.sketch_entry_summary_fallback, entry.moduleName)

            card.setOnClickListener {
                SketchRegistry.open(this, entry)
            }

            entryContainer.addView(card)
        }
    }
}
