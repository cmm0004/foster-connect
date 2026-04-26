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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

        val headerDateFormat = SimpleDateFormat("MM / dd / yy", Locale.US)
        binding.textDate.text = headerDateFormat.format(Date())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    KittenRepository.completedFostersFlow,
                    KittenRepository.caseTraitScoresFlow
                ) { completed, scores -> completed to scores }
                .collect { (completed, scores) ->
                    if (completed.isEmpty()) {
                        binding.recyclerPreviousFosters.visibility = View.GONE
                        binding.textEmptyPrevious.visibility = View.VISIBLE
                        binding.textPreviousCount.text =
                            getString(R.string.previous_count_format, 0)
                    } else {
                        binding.recyclerPreviousFosters.visibility = View.VISIBLE
                        binding.textEmptyPrevious.visibility = View.GONE
                        binding.textPreviousCount.text =
                            getString(R.string.previous_count_format, completed.size)
                        val sorted = completed.sortedByDescending { it.outDateMillis }
                        binding.recyclerPreviousFosters.adapter = PreviousFosterAdapter(
                            fosters = sorted,
                            scoresByCase = scores,
                            onRankClick = { foster ->
                                findNavController().navigate(
                                    R.id.action_PreviousFosters_to_KittenRanking,
                                    bundleOf(
                                        "animalId" to foster.animalId,
                                        "fosterCaseId" to foster.fosterCaseId
                                    )
                                )
                            },
                            onClick = { foster ->
                                findNavController().navigate(
                                    R.id.action_PreviousFosters_to_KittenDetail,
                                    bundleOf("fosterCaseId" to foster.fosterCaseId)
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
