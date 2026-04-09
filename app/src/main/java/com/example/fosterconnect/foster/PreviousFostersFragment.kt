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
                KittenRepository.kittensFlow.collect { kittens ->
                    val adoptedKittens = kittens.filter { it.isAdopted }
                    if (adoptedKittens.isEmpty()) {
                        binding.textEmptyPrevious.visibility = View.VISIBLE
                        binding.recyclerPreviousFosters.visibility = View.GONE
                    } else {
                        binding.textEmptyPrevious.visibility = View.GONE
                        binding.recyclerPreviousFosters.visibility = View.VISIBLE
                        binding.recyclerPreviousFosters.adapter = KittenAdapter(adoptedKittens) { kitten ->
                            findNavController().navigate(
                                R.id.action_PreviousFosters_to_KittenDetail,
                                bundleOf("kittenId" to kitten.id)
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
}
