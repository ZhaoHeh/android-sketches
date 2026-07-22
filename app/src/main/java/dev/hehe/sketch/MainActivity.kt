package dev.hehe.sketch

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import dev.hehe.sketch.core.SketchRegistry

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val entryContainer = findViewById<GridLayout>(R.id.entryContainer)
        val emptyView = findViewById<TextView>(R.id.emptyView)
        val entrySectionTitle = findViewById<TextView>(R.id.entrySectionTitle)
        val entries = SketchRegistry.discover(this)

        if (entries.isEmpty()) {
            emptyView.text = getString(R.string.sketch_home_empty)
            emptyView.visibility = View.VISIBLE
            entrySectionTitle.visibility = View.GONE
            return
        }

        emptyView.visibility = View.GONE
        entrySectionTitle.visibility = View.VISIBLE
        val inflater = LayoutInflater.from(this)
        val gridGap = resources.getDimensionPixelSize(R.dimen.sketch_entry_grid_gap)
        val cardHeight = resources.getDimensionPixelSize(R.dimen.sketch_entry_card_height)

        entries.forEachIndexed { index, entry ->
            val card = inflater.inflate(
                R.layout.item_sketch_entry,
                entryContainer,
                false
            ) as MaterialCardView

            card.findViewById<TextView>(R.id.entryTitle).text = entry.title
            card.layoutParams = createGridLayoutParams(index, gridGap, cardHeight)

            card.setOnClickListener {
                SketchRegistry.open(this, entry)
            }

            entryContainer.addView(card)
        }

        if (entries.size % GRID_COLUMN_COUNT != 0) {
            val spacerIndex = entries.size
            entryContainer.addView(
                Space(this).apply {
                    visibility = View.INVISIBLE
                    layoutParams = createGridLayoutParams(spacerIndex, gridGap, cardHeight)
                }
            )
        }
    }

    private fun createGridLayoutParams(index: Int, gap: Int, height: Int) =
        GridLayout.LayoutParams(
            GridLayout.spec(index / GRID_COLUMN_COUNT),
            GridLayout.spec(index % GRID_COLUMN_COUNT, 1f)
        ).apply {
            width = 0
            this.height = height
            val halfGap = gap / 2
            if (index % GRID_COLUMN_COUNT == 0) {
                setMargins(0, 0, halfGap, gap)
            } else {
                setMargins(halfGap, 0, 0, gap)
            }
        }

    private companion object {
        const val GRID_COLUMN_COUNT = 2
    }
}
