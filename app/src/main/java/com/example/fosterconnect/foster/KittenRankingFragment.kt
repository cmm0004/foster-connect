package com.example.fosterconnect.foster

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.data.db.AssignedTraitEntity
import com.example.fosterconnect.databinding.FragmentKittenRankingBinding
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class KittenRankingFragment : Fragment() {

    private var _binding: FragmentKittenRankingBinding? = null
    private val binding get() = _binding!!

    private lateinit var animalId: String
    private lateinit var fosterCaseId: String

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKittenRankingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        animalId = requireArguments().getString("animalId")
            ?: error("animalId argument required")
        fosterCaseId = requireArguments().getString("fosterCaseId")
            ?: error("fosterCaseId argument required")

        val fosterCase = KittenRepository.getFosterCase(fosterCaseId)
        val completedFoster = KittenRepository.getCompletedFoster(fosterCaseId)
        binding.textKittenName.text = fosterCase?.name ?: completedFoster?.name ?: "Animal"

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                KittenRepository.observeTraitsForCase(animalId, fosterCaseId).collect { traits ->
                    rebuildChips(traits)
                }
            }
        }

        binding.buttonAddTrait.setOnClickListener { showAddTraitDialog() }
        binding.buttonViewGallery.setOnClickListener {
            findNavController().navigate(
                R.id.action_KittenRanking_to_Gallery,
                bundleOf("fosterCaseId" to fosterCaseId)
            )
        }
    }

    private fun rebuildChips(traits: List<AssignedTraitEntity>) {
        binding.chipGroupAssigned.removeAllViews()

        if (traits.isEmpty()) {
            binding.textScoreSummary.visibility = View.GONE
            return
        }

        binding.textScoreSummary.visibility = View.GONE

        for (trait in traits) {
            val chip = Chip(requireContext()).apply {
                text = trait.traitName
                chipBackgroundColor = ColorStateList.valueOf(valenceColor(trait.valence))
                setTextColor(valenceTextColor(trait.valence))
                isCloseIconVisible = true
                closeIconTint = ColorStateList.valueOf(valenceTextColor(trait.valence))
                setOnCloseIconClickListener {
                    KittenRepository.removeTrait(animalId, fosterCaseId, trait.traitName)
                }
            }
            binding.chipGroupAssigned.addView(chip)
        }
    }

    private fun showAddTraitDialog() {
        val catalog = KittenRepository.getTraitCatalog()
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        val scroll = android.widget.ScrollView(ctx)
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (16 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val categories = listOf("physical", "behavioral", "quirks", "vibe")
        val categoryLabels = mapOf(
            "physical" to "Physical",
            "behavioral" to "Behavioral",
            "quirks" to "Quirks",
            "vibe" to "Vibe"
        )

        for (category in categories) {
            val traits = catalog.traitsByCategory[category] ?: continue

            val header = TextView(ctx).apply {
                text = categoryLabels[category] ?: category
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                val topPad = if (category == categories.first()) 0 else (16 * density).toInt()
                setPadding(0, topPad, 0, (8 * density).toInt())
            }
            container.addView(header)

            val chipGroup = ChipGroup(ctx).apply {
                chipSpacingHorizontal = (6 * density).toInt()
                chipSpacingVertical = (6 * density).toInt()
            }

            for (trait in traits) {
                val chip = Chip(ctx).apply {
                    text = trait.trait
                    isCheckable = false
                    chipBackgroundColor = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, valenceColorRes(trait.valence))
                    )
                    setTextColor(ContextCompat.getColor(ctx, valenceTextColorRes(trait.valence)))
                    setOnClickListener {
                        KittenRepository.addTrait(animalId, fosterCaseId, trait)
                    }
                }
                chipGroup.addView(chip)
            }
            container.addView(chipGroup)
        }

        scroll.addView(container)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Add Trait")
            .setView(scroll)
            .setNegativeButton("Done", null)
            .show()
    }

    private fun valenceColorRes(valence: String): Int = when (valence) {
        "positive" -> R.color.trait_positive
        "negative" -> R.color.trait_negative
        else -> R.color.trait_neutral
    }

    private fun valenceTextColorRes(valence: String): Int = when (valence) {
        "positive" -> R.color.trait_positive_text
        "negative" -> R.color.trait_negative_text
        else -> R.color.trait_neutral_text
    }

    private fun valenceColor(valence: String): Int =
        ContextCompat.getColor(requireContext(), valenceColorRes(valence))

    private fun valenceTextColor(valence: String): Int =
        ContextCompat.getColor(requireContext(), valenceTextColorRes(valence))

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun tierFromScore(totalScore: Int): String = when {
            totalScore >= 350 -> "S+"
            totalScore >= 300 -> "S"
            totalScore >= 250 -> "S-"
            totalScore >= 225 -> "A+"
            totalScore >= 200 -> "A"
            totalScore >= 175 -> "A-"
            totalScore >= 150 -> "B+"
            totalScore >= 100 -> "B"
            totalScore >= 75 -> "B-"
            totalScore >= 50 -> "C+"
            totalScore >= 0 -> "C"
            totalScore >= -50 -> "C-"
            totalScore >= -75 -> "D+"
            totalScore >= -100 -> "D"
            totalScore >= -150 -> "D-"
            else -> "F"
        }
    }
}
