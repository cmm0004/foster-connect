package com.example.fosterconnect.history

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.example.fosterconnect.R
import com.example.fosterconnect.data.KittenRepository
import com.example.fosterconnect.databinding.FragmentMessageCenterBinding
import com.example.fosterconnect.databinding.ItemMessageBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

class MessageCenterFragment : Fragment() {

    private var _binding: FragmentMessageCenterBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMessageCenterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                KittenRepository.messagesFlow.collect { messages ->
                    if (messages.isEmpty()) {
                        binding.textEmptyMessages.visibility = View.VISIBLE
                        binding.recyclerMessages.visibility = View.GONE
                    } else {
                        binding.textEmptyMessages.visibility = View.GONE
                        binding.recyclerMessages.visibility = View.VISIBLE
                        binding.recyclerMessages.adapter = MessageAdapter(messages) { message ->
                            KittenRepository.markMessageRead(message.id)
                            if (message.kittenId != null) {
                                val bundle = Bundle().apply {
                                    putString("kittenId", message.kittenId)
                                }
                                findNavController().navigate(R.id.action_MessageCenter_to_KittenDetail, bundle)
                            }
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

    private class MessageAdapter(
        private val messages: List<Message>,
        private val onClick: (Message) -> Unit
    ) : RecyclerView.Adapter<MessageAdapter.ViewHolder>() {

        private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        class ViewHolder(val binding: ItemMessageBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val message = messages[position]
            holder.binding.textMessageTitle.text = message.title
            holder.binding.textMessageDate.text = dateFormat.format(Date(message.timestamp))
            holder.binding.textMessageContent.text = message.content

            holder.binding.root.setOnClickListener { onClick(message) }

            // Highlight unread messages
            if (message.isRead) {
                holder.binding.root.alpha = 0.7f
            } else {
                holder.binding.root.alpha = 1.0f
            }
        }

        override fun getItemCount() = messages.size
    }
}
