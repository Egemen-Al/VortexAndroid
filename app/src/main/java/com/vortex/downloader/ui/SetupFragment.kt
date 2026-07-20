package com.vortex.downloader.ui

import android.os.Bundle
import android.view.*
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.vortex.downloader.R
import com.vortex.downloader.databinding.FragmentSetupBinding

class SetupFragment : Fragment() {

    private var _binding: FragmentSetupBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.setupLabel.observe(viewLifecycleOwner) {
            binding.tvSetupLabel.text = it
        }
        viewModel.setupProgress.observe(viewLifecycleOwner) { progress ->
            // setProgressCompat(.., true) barın dolumunu sert bir sıçrama yerine
            // akıcı bir animasyonla gösterir.
            binding.progressSetup.setProgressCompat((progress * 100).toInt(), true)
            binding.tvSetupPct.text = "${(progress * 100).toInt()}%"
        }
        viewModel.setupState.observe(viewLifecycleOwner) { state ->
            when (state) {
                MainViewModel.SetupState.READY -> {
                    bounceLogoThenNavigate()
                }
                MainViewModel.SetupState.ERROR -> {
                    binding.tvSetupLabel.text = "Hata oluştu. İnternet bağlantınızı kontrol edin."
                    binding.btnRetry.visibility = View.VISIBLE
                }
                else -> {
                    binding.btnRetry.visibility = View.GONE
                }
            }
        }
        binding.btnRetry.setOnClickListener {
            binding.btnRetry.visibility = View.GONE
            viewModel.checkAndSetup()
        }
    }

    /** Kurulum bitince küçük bir "hoplama" ile bitirir, sonra ana ekrana geçer. */
    private fun bounceLogoThenNavigate() {
        if (_binding == null) return
        binding.ivLogo.animate()
            .scaleX(1.25f).scaleY(1.25f)
            .setDuration(180)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                if (_binding == null) return@withEndAction
                binding.ivLogo.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(150)
                    .withEndAction {
                        if (isAdded) findNavController().navigate(R.id.action_setup_to_home)
                    }
                    .start()
            }
            .start()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
