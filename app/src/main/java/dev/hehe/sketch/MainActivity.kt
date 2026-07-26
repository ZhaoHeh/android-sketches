package dev.hehe.sketch

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.Space
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import dev.hehe.sketch.core.SketchEntry
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
        val surfaceColor = ContextCompat.getColor(this, R.color.color_surface)

        entries.forEachIndexed { index, entry ->
            val card = inflater.inflate(
                R.layout.item_sketch_entry,
                entryContainer,
                false
            ) as MaterialCardView

            bindEntryCard(card, entry, inflater, surfaceColor)
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

    private fun bindEntryCard(
        card: MaterialCardView,
        entry: SketchEntry,
        inflater: LayoutInflater,
        surfaceColor: Int
    ) {
        val accentColor = resolveAccentColor(entry)
        card.findViewById<TextView>(R.id.entryTitle).text = entry.title
        card.findViewById<View>(R.id.entryAccent).backgroundTintList =
            ColorStateList.valueOf(accentColor)

        val tags = entry.tags.take(MAX_VISIBLE_TAGS)
        val tagGroup = card.findViewById<ChipGroup>(R.id.entryTags)
        tagGroup.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
        val tagBackground = ColorUtils.blendARGB(surfaceColor, accentColor, TAG_BACKGROUND_BLEND)

        tags.forEach { tag ->
            val chip = inflater.inflate(R.layout.item_sketch_tag, tagGroup, false) as Chip
            chip.text = tag
            chip.setTextColor(accentColor)
            chip.chipBackgroundColor = ColorStateList.valueOf(tagBackground)
            tagGroup.addView(chip)
        }
    }

    private fun resolveAccentColor(entry: SketchEntry): Int {
        val colorRes = entry.accentColorRes ?: fallbackAccentColorRes(entry.activityClassName)
        return ContextCompat.getColor(this, colorRes)
    }

    private fun fallbackAccentColorRes(activityClassName: String): Int {
        val index = Math.floorMod(activityClassName.hashCode(), FALLBACK_ACCENT_COLORS.size)
        return FALLBACK_ACCENT_COLORS[index]
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
        const val MAX_VISIBLE_TAGS = 3
        const val TAG_BACKGROUND_BLEND = 0.14f

        val FALLBACK_ACCENT_COLORS = intArrayOf(
            R.color.color_entry_fallback_teal,
            R.color.color_entry_fallback_blue,
            R.color.color_entry_fallback_green,
            R.color.color_entry_fallback_amber,
            R.color.color_entry_fallback_rose
        )
    }
}
