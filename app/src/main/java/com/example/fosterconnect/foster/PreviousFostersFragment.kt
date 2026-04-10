package com.example.fosterconnect.foster

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.databinding.FragmentPreviousFostersBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class PreviousFostersFragment : Fragment() {

    private var _binding: FragmentPreviousFostersBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPreviousFostersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerPreviousFosters.layoutManager = LinearLayoutManager(requireContext())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    KittenRepository.kittensFlow,
                    KittenRepository.facetsFlow,
                    KittenRepository.scoresFlow
                ) { kittens, facets, scores -> Triple(kittens, facets, scores) }
                    .collect { (kittens, facets, scores) ->
                        val adopted = kittens.filter { it.isAdopted }
                        if (adopted.isEmpty()) {
                            binding.scrollCharts.visibility = View.GONE
                            binding.textEmptyPrevious.visibility = View.VISIBLE
                        } else {
                            binding.scrollCharts.visibility = View.VISIBLE
                            binding.textEmptyPrevious.visibility = View.GONE
                            renderCharts(adopted, facets, scores)
                            renderList(adopted)
                        }
                    }
            }
        }
    }

    private fun renderCharts(
        adopted: List<Kitten>,
        facets: List<RankFacet>,
        scores: Map<String, Map<String, Int>>
    ) {
        // Summary stats
        binding.statTotalValue.text = adopted.size.toString()

        val durations = adopted.map { it.daysFostered() }
        val avgDays = if (durations.isNotEmpty()) durations.average().toInt() else 0
        binding.statAvgDaysValue.text = avgDays.toString()

        val gains = adopted.mapNotNull { it.weightGainGrams() }
        val avgGain = if (gains.isNotEmpty()) gains.average().toInt() else 0
        binding.statAvgGainValue.text = "${avgGain}g"

        // Foster duration chart
        binding.chartDuration.setData(
            adopted
                .sortedByDescending { it.daysFostered() }
                .map { kitten ->
                    val days = kitten.daysFostered()
                    HorizontalBarChartView.Bar(
                        label = kitten.name,
                        value = days.toFloat(),
                        valueLabel = "$days d"
                    )
                }
        )

        // Weight gain chart
        val gainBars = adopted.mapNotNull { kitten ->
            kitten.weightGainGrams()?.let { gain ->
                HorizontalBarChartView.Bar(
                    label = kitten.name,
                    value = gain.toFloat(),
                    valueLabel = "${gain}g"
                )
            }
        }.sortedByDescending { it.value }
        binding.chartWeightGain.setData(gainBars)

        // Facet averages (only over adopted kittens that have scores)
        val facetBars = facets.mapNotNull { facet ->
            val values = adopted.mapNotNull { scores[it.id]?.get(facet.id) }
            if (values.isEmpty()) return@mapNotNull null
            val avg = values.average().toFloat()
            HorizontalBarChartView.Bar(
                label = facet.displayName,
                value = avg,
                valueLabel = "%.1f".format(avg)
            )
        }
        binding.chartFacetAvg.setData(facetBars, maxValueOverride = 5f)

        // Breed counts
        val breedCounts = adopted
            .groupingBy { it.breed.display }
            .eachCount()
            .toList()
            .sortedByDescending { it.second }
        binding.chartBreeds.setData(
            breedCounts.map { (label, count) ->
                HorizontalBarChartView.Bar(
                    label = label,
                    value = count.toFloat(),
                    valueLabel = count.toString()
                )
            }
        )
    }

    private fun renderList(adopted: List<Kitten>) {
        binding.recyclerPreviousFosters.adapter = KittenAdapter(adopted) { kitten ->
            findNavController().navigate(
                R.id.action_PreviousFosters_to_KittenRanking,
                bundleOf("kittenId" to kitten.id)
            )
        }
    }

    private fun Kitten.daysFostered(): Int {
        val end = adoptionDateMillis ?: System.currentTimeMillis()
        val diff = end - intakeDateMillis
        return (diff / (24L * 60 * 60 * 1000)).toInt().coerceAtLeast(0)
    }

    private fun Kitten.weightGainGrams(): Int? {
        if (weightEntries.size < 2) return null
        val first = weightEntries.first().weightGrams
        val last = weightEntries.last().weightGrams
        return (last - first).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
