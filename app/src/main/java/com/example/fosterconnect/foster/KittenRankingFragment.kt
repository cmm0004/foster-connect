package com.example.fosterconnect.foster

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.databinding.FragmentKittenRankingBinding
import com.example.fosterconnect.databinding.ItemRankFacetBinding
import kotlinx.coroutines.launch

class KittenRankingFragment : Fragment() {

    private var _binding: FragmentKittenRankingBinding? = null
    private val binding get() = _binding!!

    private lateinit var animalId: String
    private var fosterCaseId: String? = null
    private val workingScores = mutableMapOf<String, Int>()

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

        val fosterCase = fosterCaseId?.let { KittenRepository.getFosterCase(it) }
        val completedFoster = fosterCaseId?.let { KittenRepository.getCompletedFoster(it) }
        binding.textKittenName.text = fosterCase?.name ?: completedFoster?.name ?: "Animal"

        fosterCaseId?.let { workingScores.putAll(KittenRepository.getScoresForCase(it)) }

        binding.recyclerFacets.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                KittenRepository.facetsFlow.collect { facets ->
                    val averages = KittenRepository.facetAveragesFlow.value
                    val allScores = KittenRepository.scoresFlow.value
                    val superlatives = computeSuperlatives(facets, allScores, fosterCaseId)
                    binding.recyclerFacets.adapter = FacetAdapter(
                        facets = facets,
                        initialScores = workingScores,
                        averages = averages,
                        superlatives = superlatives,
                        onScoreChanged = { facetId, score -> workingScores[facetId] = score }
                    )
                }
            }
        }

        binding.buttonSave.setOnClickListener {
            KittenRepository.saveRankScores(animalId, fosterCaseId, workingScores.toMap())
            Toast.makeText(requireContext(), "Rankings saved", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun computeSuperlatives(
        facets: List<RankFacet>,
        allScores: Map<String, Map<String, Int>>,
        currentFosterCaseId: String?
    ): Map<String, String> {
        val currentCaseId = currentFosterCaseId ?: return emptyMap()
        val superlativeLabels = mapOf(
            "prey_drive" to "Mightiest Hunter!",
            "cleanliness" to "Cleanest Kitty!",
            "noisiness" to "Most Talkative!",
            "cuddliness" to "Cuddliest!",
            "playfulness" to "Most Playful!"
        )
        val result = mutableMapOf<String, String>()
        for (facet in facets) {
            val perCase = allScores.mapNotNull { (caseId, scores) ->
                scores[facet.id]?.let { caseId to it }
            }
            if (perCase.size < 2) continue
            val max = perCase.maxOf { it.second }
            val currentScore = allScores[currentCaseId]?.get(facet.id) ?: continue
            if (currentScore == max && max > 0) {
                result[facet.id] = superlativeLabels[facet.id] ?: "Top in ${facet.displayName}!"
            }
        }
        return result
    }

    private class FacetAdapter(
        private val facets: List<RankFacet>,
        private val initialScores: Map<String, Int>,
        private val averages: Map<String, Double>,
        private val superlatives: Map<String, String>,
        private val onScoreChanged: (String, Int) -> Unit
    ) : RecyclerView.Adapter<FacetAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemRankFacetBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemRankFacetBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val facet = facets[position]
            with(holder.binding) {
                textFacetName.text = facet.displayName
                if (!facet.description.isNullOrBlank()) {
                    textFacetDescription.visibility = View.VISIBLE
                    textFacetDescription.text = facet.description
                } else {
                    textFacetDescription.visibility = View.GONE
                }

                val avg = averages[facet.id]
                if (avg != null) {
                    textAverage.visibility = View.VISIBLE
                    textAverage.text = "Average across all animals: %.1f".format(avg)
                } else {
                    textAverage.visibility = View.GONE
                }

                ratingBar.onRatingBarChangeListener = null
                ratingBar.rating = (initialScores[facet.id] ?: 0).toFloat()
                ratingBar.setOnRatingBarChangeListener { _, rating, _ ->
                    onScoreChanged(facet.id, rating.toInt())
                }

                val badgeText = superlatives[facet.id]
                if (badgeText != null) {
                    textBadge.visibility = View.VISIBLE
                    textBadge.text = badgeText
                } else {
                    textBadge.visibility = View.GONE
                }
            }
        }

        override fun getItemCount() = facets.size
    }
}
