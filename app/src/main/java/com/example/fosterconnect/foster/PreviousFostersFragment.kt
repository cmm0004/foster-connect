package com.example.fosterconnect.foster

import android.graphics.Color
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
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.databinding.FragmentPreviousFostersBinding
import com.example.fosterconnect.databinding.ItemPreviousFosterBinding
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

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    KittenRepository.completedFostersFlow,
                    KittenRepository.scoresFlow
                ) { completed, scores -> completed to scores }
                    .collect { (completed, scores) ->
                        if (completed.isEmpty()) {
                            binding.recyclerPreviousFosters.visibility = View.GONE
                            binding.textEmptyPrevious.visibility = View.VISIBLE
                        } else {
                            binding.recyclerPreviousFosters.visibility = View.VISIBLE
                            binding.textEmptyPrevious.visibility = View.GONE
                            val sorted = completed.sortedByDescending { it.outDateMillis }
                            val items = sorted.map { foster ->
                                TierRow(foster, Tier.fromScores(scores[foster.fosterCaseId]))
                            }
                            binding.recyclerPreviousFosters.adapter = PreviousFosterAdapter(items) { foster ->
                                findNavController().navigate(
                                    R.id.action_PreviousFosters_to_KittenRanking,
                                    bundleOf(
                                        "animalId" to foster.animalId,
                                        "fosterCaseId" to foster.fosterCaseId
                                    )
                                )
                            }
                        }
                    }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class TierRow(val foster: CompletedFoster, val tier: Tier)

    private enum class Tier(
        val label: String,
        val backgroundRes: Int,
        val textColor: Int
    ) {
        S("S", R.drawable.tier_s_bg, Color.BLACK),
        A("A", R.drawable.tier_a_bg, Color.BLACK),
        B("B", R.drawable.tier_b_bg, Color.WHITE),
        C("C", R.drawable.tier_c_bg, Color.WHITE),
        D("D", R.drawable.tier_d_bg, Color.WHITE),
        F("F", R.drawable.tier_f_bg, Color.WHITE),
        NONE("—", R.drawable.tier_none_bg, Color.WHITE);

        companion object {
            fun fromScores(scores: Map<String, Int>?): Tier {
                if (scores.isNullOrEmpty()) return NONE
                val avg = scores.values.average()
                return when {
                    avg >= 4.5 -> S
                    avg >= 3.8 -> A
                    avg >= 3.0 -> B
                    avg >= 2.2 -> C
                    avg >= 1.5 -> D
                    else -> F
                }
            }
        }
    }

    private class PreviousFosterAdapter(
        private val rows: List<TierRow>,
        private val onClick: (CompletedFoster) -> Unit
    ) : RecyclerView.Adapter<PreviousFosterAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemPreviousFosterBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemPreviousFosterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (foster, tier) = rows[position]
            holder.binding.tierCard.setBackgroundResource(tier.backgroundRes)
            holder.binding.textName.text = foster.name
            holder.binding.textName.setTextColor(tier.textColor)
            holder.binding.textOutDate.text = "Adopted ${dateFormat.format(Date(foster.outDateMillis))}"
            holder.binding.textOutDate.setTextColor(tier.textColor)
            holder.binding.textTier.text = tier.label
            holder.binding.textTier.setTextColor(tier.textColor)
            holder.binding.tierCard.setOnClickListener { onClick(foster) }
        }

        override fun getItemCount() = rows.size

        companion object {
            private val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)
        }
    }
}
